package top.qixia.threads

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.roundToInt
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData

/** 带版本的 Rust 运行记录文件，解析不会修改规则或启动采集器。 */
internal object AutoHistoryRecord {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    val fileName = Regex("auto_[0-9]+_[0-9]+(?:_[0-9]+)?\\.log(?:\\.gz)?")
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+(?:@[0-9]+)?")
    internal const val MAX_THREADS = 8192
    private const val CURVE_BUCKETS = 60

    data class Entry(val fileName: String, val pkg: String, val session: SessionSummary,
        val fps: HistoryFpsStore.Report?, val captureId: String? = null,
        val foregroundMs: Long? = null, val memberFileNames: List<String> = listOf(fileName),
        val expandedBytes: Long = 0, val identityCount: Int = session.threadCount)
    data class AffinityChange(val timestampMs: Long, val pid: Int, val tid: Int,
        val startTicks: Long, val cpus: String)
    data class Record(val entry: Entry, val threads: List<ThreadData>,
        val metrics: HistoryMetrics.Report?, val fps: HistoryFpsStore.Report?,
        val affinityChanges: List<AffinityChange>, val coreTimeline: CoreTimelineReport)

    /** 独立的负数 ID 空间避免与 SQLite ID 及同秒完成的记录冲突。 */
    fun sessionId(name: String): Long {
        require(fileName.matches(name))
        val bytes = MessageDigest.getInstance("SHA-256").digest(AutoHistoryIo.canonicalName(name).toByteArray(Charsets.UTF_8))
        return -(ByteBuffer.wrap(bytes).long.and(Long.MAX_VALUE).coerceAtLeast(1L))
    }

    private data class Segment(val file: File, val sourceName: String, val metadata: Map<String, String>,
        val device: Map<String, String>, val pkg: String, val start: Long, val end: Long,
        val duration: Long, val captureId: String?, val foregroundMs: Long?, val expandedBytes: Long)

    private fun segment(file: File, sourceName: String): Segment {
        require(fileName.matches(sourceName) && file.isFile)
        val metadata = linkedMapOf<String, String>()
        val device = linkedMapOf<String, String>()
        var first = true
        // 完整且有界地扫描，验证每个串联的 gzip 成员及 CRC，
        // 并读取最终准入及尾部标记，无需将事件全部载入内存。
        val expanded = AutoHistoryIo.scan(file) { line ->
            if (first) { require(line == "QIXIA_AUTO_HISTORY\t1"); first = false }
            else {
                val key = line.substringBefore('\t')
                if (key in METADATA_KEYS) {
                    require(line.length <= 16_384)
                    val fields = line.split('\t', limit = 2)
                    if (fields.size == 2) metadata[key] = fields[1]
                } else if (key == "D") {
                    require(line.length <= 16_384)
                    val fields = line.split('\t', limit = 3)
                    if (fields.size == 3 && fields[1] in DEVICE_KEYS) device[fields[1]] = fields[2].take(160)
                }
            }
        }
        val pkg = metadata.getValue("package").also { require(packageName.matches(it)) }
        val start = metadata.getValue("start_ms").toLong().also { require(it > 0) }
        val end = metadata.getValue("end_ms").toLong().also { require(it >= start) }
        val duration = (metadata["duration_ms"]?.toLongOrNull() ?: (end - start))
            .also { require(it in 1..(24L * 60 * 60 * 1000)) }
        require(metadata["source"] == "auto")
        // 准入条件针对整次前台使用，不针对每个轮转分片。
        // 此策略之前的文件仍可读取，包括较短的记录。
        metadata["minimum_usage_ms"]?.let { minimum ->
            val threshold = minimum.toLong().also { require(it > 0) }
            require(metadata.getValue("foreground_ms").toLong() > threshold)
        }
        val captureId = metadata["capture_id"]?.also {
            require(fileName.matches("$it.log") && metadata.containsKey("minimum_usage_ms"))
        }
        val foreground = metadata["foreground_ms"]?.toLong()?.also { require(it >= 0) }
        return Segment(file, sourceName, metadata, device, pkg, start, end, duration, captureId, foreground, expanded)
    }

    fun read(file: File, sourceName: String = file.name): Record? =
        readSegments(listOf(file to sourceName), sourceName, grouped = false)

    /** 每个物理分片保留独立的校验和事件限制，统计包含所有原始样本。 */
    fun read(files: List<File>, sourceName: String = files.firstOrNull()?.name.orEmpty(), windowed: Boolean = false): Record? =
        readSegments(files.map { it to it.name }, sourceName, grouped = true, windowed = windowed)

    /** 列表和导入保留精确统计及有界 FPS 预览，不保留完整调度事件。 */
    fun summary(file: File, sourceName: String = file.name): Entry? =
        readSegments(listOf(file to sourceName), sourceName, grouped = false, summaryOnly = true)?.entry

    fun summary(files: List<File>, sourceName: String): Entry? =
        readSegments(files.map { it to it.name }, sourceName, grouped = true, summaryOnly = true)?.entry

    private fun readSegments(files: List<Pair<File, String>>, sourceName: String, grouped: Boolean,
        summaryOnly: Boolean = false, windowed: Boolean = false): Record? = runCatching {
        require(fileName.matches(sourceName) && files.isNotEmpty() && files.size <= 400)
        require(files.map { AutoHistoryIo.canonicalName(it.second) }.distinct().size == files.size)
        val segments = files.map { (file, name) -> segment(file, name) }.sortedBy { it.start }
        val expanded = segments.sumOf { it.expandedBytes }
        require(summaryOnly || expanded <= 64L * 1024 * 1024)
        val pkg = segments.first().pkg
        val captureId = segments.first().captureId
        require(segments.all { it.pkg == pkg && it.captureId == captureId })
        require(segments.size == 1 || captureId != null)
        val canonical = AutoHistoryIo.canonicalName(sourceName)
        if (grouped && captureId != null) require(canonical == "$captureId.log")
        val start = segments.minOf { it.start }
        val end = segments.maxOf { it.end }
        val foreground = segments.mapNotNull { it.foregroundMs }.maxOrNull()
        // 前台使用时长只用于准入判断；释放宽限期和暂停采样仍占用真实时间，
        // 若用前台时长归一化，会把这些样本压到进度 100% 处。
        val duration = (end - start).coerceAtLeast(1)
        require(duration > 0)
        val device = linkedMapOf<String, String>()
        segments.forEach { device.putAll(it.device) }
        val threadSamples = linkedMapOf<String, ThreadSamples>()
        val identities = AutoHistoryIdentitySet(MAX_THREADS * segments.size)
        val fpsSamples = arrayListOf<Pair<Long, Float>>()
        // 两条路径共用累加器，确保列表缓存与打开后的报告统计逐位一致。
        // 首页预览使用固定内存。
        val fpsSummary = FpsSummary()
        val homePreview = FpsPreview(start, end)
        val metrics = arrayListOf<HistoryMetrics.Sample>()
        val frameSamples = arrayListOf<Pair<Long, Float>>()
        val affinities = arrayListOf<AffinityChange>()
        val timelineParts = arrayListOf<CoreTimelineReport>()
        val eventPool = CoreEventPool()
        var rounds = 0
        var lastThreadTime = Long.MIN_VALUE
        var previousFpsTime = Long.MIN_VALUE
        var previousMetricTime = Long.MIN_VALUE
        segments.forEach { segment ->
            val segmentIdentities = AutoHistoryIdentitySet(MAX_THREADS)
            val timeline = if (summaryOnly) null else
                CoreTimelineParser(sessionId(sourceName), segment.start, segment.end, ::decodeName, eventPool)
            if (segment.metadata["recovered"] == "1") timeline?.markIncomplete()
            AutoHistoryIo.reader(segment.file).useLines { lines -> lines.drop(1).forEach row@ { line ->
                // 避免拆分或载入可能多达数十万条的核心事件。
                // 保留原始文件，打开报告时才解码详情。
                if (summaryOnly && !line.startsWith("T\t") && !line.startsWith("F\t")) return@row
                if (timeline?.accepts(line) == true) {
                    timeline.consume(line)
                    return@row
                }
                require(line.length <= 16_384)
                val fields = line.split('\t')
                when (fields.firstOrNull()) {
                    "T" -> {
                        require(fields.size == 7)
                        val time = fields[1].toLong().also { require(it in segment.start..segment.end) }
                        val pid = fields[2].toInt().also { require(it > 0) }
                        val tid = fields[3].toInt().also { require(it > 0) }
                        val ticks = fields[4].toLong().also { require(it >= 0) }
                        val percent = fields[6].toFloat().also { require(it.isFinite() && it in 0f..105f) }.coerceAtMost(100f)
                        if (time != lastThreadTime) { rounds++; lastThreadTime = time }
                        val firstInSegment = segmentIdentities.add(pid, tid, ticks, percent > 0f)
                        identities.add(pid, tid, ticks, percent > 0f)
                        if (summaryOnly && firstInSegment) decodeName(fields[5])
                        if (!summaryOnly) {
                            val key = "$pid:$tid:$ticks"
                            val samples = threadSamples[key] ?: ThreadSamples(fields[5]).also {
                                require(threadSamples.size < MAX_THREADS)
                                threadSamples[key] = it
                            }
                            val renamed = samples.rename(fields[5])
                            if (firstInSegment || renamed) timeline?.rememberName(ThreadIdentity(pid, tid, ticks), samples.name)
                            samples.add(((time - start).toFloat() / duration).coerceIn(0f, 1f), percent, time)
                        }
                    }
                    "F" -> {
                        require(fields.size in 3..4 && (summaryOnly || fpsSamples.size < 100_000))
                        val time = fields[1].toLong().also { require(it in segment.start..segment.end) }
                        val fps = fields[2].toFloat().also { require(it.isFinite() && it in 0f..1000f) }
                        if (time > previousFpsTime) {
                            fpsSummary.add(fps)
                            homePreview.add(time, fps)
                            if (!summaryOnly) fpsSamples += time to fps
                            previousFpsTime = time
                            fields.getOrNull(3)?.takeUnless { summaryOnly }?.toFloatOrNull()?.takeIf { HistoryMetrics.valid("frame_max_ms", it) }
                                ?.let { frameSamples += time to it }
                        }
                    }
                    "M" -> {
                        require(fields.size >= 3 && metrics.size < 50_000)
                        val time = fields[1].toLong().also { require(it in segment.start..segment.end) }
                        if (time > previousMetricTime) {
                            val values = fields.drop(3).mapNotNull { field ->
                                if ('=' !in field) null else field.substringBefore('=') to field.substringAfter('=')
                            }.toMap() + ("charging" to fields[2])
                            metrics += HistoryMetrics.decode(values, time)
                            previousMetricTime = time
                        }
                    }
                    "A" -> {
                        require(fields.size == 6 && affinities.size < 100_000)
                        require(fields[5].matches(Regex("[0-9,-]{1,256}|restore")))
                        val change = AffinityChange(fields[1].toLong().also { require(it in segment.start..segment.end) }, fields[2].toInt(),
                            fields[3].toInt(), fields[4].toLong(), fields[5])
                        affinities += change
                        timeline?.legacy(change.timestampMs, change.pid, change.tid, change.startTicks, change.cpus)
                    }
                    "D" -> if (fields.size == 3 && fields[1] in DEVICE_KEYS)
                        device[fields[1]] = fields[2].take(160)
                }
            } }
            timeline?.let { timelineParts += it.finish() }
        }
        val activeThreads = threadSamples.values.filter { it.maximum > 0f }
        val threads = (if (summaryOnly) emptyList() else activeThreads.map { it.toThread() })
            .sortedWith(compareByDescending<ThreadData> { it.avg }.thenByDescending { it.max }.thenBy { it.name })
        val statistics = fpsSummary.report()
        val fps = if (summaryOnly) statistics else fpsSamples.takeIf { it.isNotEmpty() }?.let { samples ->
            val points = samples.mapIndexed { index, sample -> HistoryFpsStore.Point(
                ((sample.first - start).toFloat() / duration).coerceIn(0f, 1f), sample.second,
                index > 0 && sample.first - samples[index - 1].first > 2500, sample.first) }
            val reduced = if (points.size <= 240) points else points.chunked((points.size + 59) / 60).flatMap { bucket ->
                var previous = -1f
                listOf(bucket.first(), bucket.minBy { it.fps }, bucket.maxBy { it.fps }, bucket.last())
                    .distinct().sortedBy { it.progress }.map { point ->
                        point.copy(breakBefore = bucket.any { it.breakBefore && it.progress > previous && it.progress <= point.progress })
                            .also { previous = point.progress }
                    }
            }
            statistics!!.copy(points = reduced)
        }
        var report = metrics.takeIf { it.isNotEmpty() }?.let {
            HistoryMetricsStore.summarize(it, start, duration).copy(device = device)
        }
        if (frameSamples.isNotEmpty()) {
            val frameReport = HistoryMetricsStore.summarize(frameSamples.map {
                HistoryMetrics.Sample(it.first, null, mapOf("frame_max_ms" to it.second))
            }, start, duration)
            report = report?.copy(series = report.series + frameReport.series)
                ?: frameReport.copy(device = device)
        }
        val summary = SessionSummary(sessionId(sourceName), end / 1000L,
            segments.map { it.metadata["samples"]?.toIntOrNull()?.coerceAtLeast(0) }.let { counts ->
                if (counts.all { it != null }) counts.sumOf { it!! } else rounds
            }, identities.activeSize,
            HistorySource.AUTO_ALLOCATION, start, duration, end)
        val dropped = timelineParts.fold(0L) { sum, part ->
            if (Long.MAX_VALUE - sum < part.droppedEvents) Long.MAX_VALUE else sum + part.droppedEvents
        }
        val timeline = CoreTimelineReport(sessionId(sourceName), start, end,
            timelineParts.flatMap { it.events }.sortedBy { it.timestampMs },
            timelineParts.map { it.coreEventsVersion }.distinct().singleOrNull(), dropped,
            timelineParts.any { it.incomplete }, timelineParts.any { it.legacyMissingDetails })
        Record(Entry(canonical, pkg, summary, statistics?.copy(points = homePreview.points()), captureId, foreground,
            segments.map { it.sourceName }, expanded, identities.size), threads, report, fps, affinities, timeline)
    }.getOrNull()

    private data class Point(val progress: Float, val value: Float, val breakBefore: Boolean = false)
    private class Bucket(val first: Point) {
        var last = first
        var minimum = first
        var maximum = first
        val gaps = mutableListOf<Float>().apply { if (first.breakBefore) add(first.progress) }
        fun add(point: Point) {
            last = point
            if (point.value < minimum.value) minimum = point
            if (point.value > maximum.value) maximum = point
            if (point.breakBefore) gaps += point.progress
        }
        fun points() = listOf(first, minimum, maximum, last).distinct().sortedBy { it.progress }
    }
    private class ThreadSamples(private var encodedName: String) {
        var name = decodeName(encodedName)
            private set
        private val buckets = mutableMapOf<Int, Bucket>()
        fun rename(encoded: String): Boolean {
            if (encoded == encodedName) return false
            encodedName = encoded
            val updated = decodeThreadName(encoded)
            if (updated.isBlank() || updated == name) return false
            name = updated
            return true
        }
        private var total = 0.0
        private var count = 0
        private var lastTime = Long.MIN_VALUE
        var maximum = 0f
            private set
        fun add(progress: Float, percent: Float, time: Long) {
            total += percent; count++; maximum = maxOf(maximum, percent)
            val index = (progress * CURVE_BUCKETS).toInt().coerceIn(0, CURVE_BUCKETS - 1)
            val point = Point(progress, percent, lastTime != Long.MIN_VALUE && time - lastTime > 3500)
            lastTime = time
            buckets[index]?.add(point) ?: run { buckets[index] = Bucket(point) }
        }
        fun toThread(): ThreadData {
            var previous = -1f
            val points = buckets.toSortedMap().values.flatMap { bucket -> bucket.points().map { point ->
                point.copy(breakBefore = bucket.gaps.any { it > previous && it <= point.progress })
                    .also { previous = point.progress }
            } }
            return ThreadData(name, (total / count.coerceAtLeast(1)).toFloat(), maximum,
                "@t:" + points.joinToString(",") { "${it.progress}=${it.value}=${if (it.breakBefore) 1 else 0}" }, "")
        }
    }
    private fun decodeName(hex: String): String = decodeThreadName(hex).also { require(it.isNotBlank()) }

    private fun decodeThreadName(hex: String): String {
        require(hex.length in 2..512 && hex.length % 2 == 0)
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
    private val DEVICE_KEYS = setOf("platform", "model", "os", "resolution", "refresh")
    private val FOOTER_KEYS = setOf("end_ms", "duration_ms", "samples", "thread_count", "recovered", "foreground_ms")
    private val METADATA_KEYS = FOOTER_KEYS + setOf("package", "start_ms", "source", "minimum_usage_ms", "capture_id")

    /** 精确频数统计避免在长记录摘要中为每个 FPS 样本保留装箱对象。 */
    private class FpsSummary {
        private val counts = java.util.TreeMap<Float, Int>()
        private var samples = 0
        private var mean = 0.0
        private var squares = 0.0
        fun add(value: Float) {
            require(samples < Int.MAX_VALUE && (value in counts || counts.size < 1_000_001))
            counts[value] = (counts[value] ?: 0) + 1
            samples++
            val delta = value - mean
            mean += delta / samples
            squares += delta * (value - mean)
        }
        fun report(): HistoryFpsStore.Report? {
            if (samples == 0) return null
            var remaining = (samples * .05f).roundToInt().coerceAtLeast(1)
            val selected = remaining
            var low = 0.0
            if (samples > 100) for ((value, count) in counts) {
                val used = minOf(remaining, count)
                low += value.toDouble() * used; remaining -= used
                if (remaining == 0) break
            }
            return HistoryFpsStore.Report(mean.toFloat(), counts.firstKey(), counts.lastKey(), samples,
                emptyList(), if (samples > 100) (low / selected).toFloat() else null,
                if (mean > 0) (kotlin.math.sqrt((squares / samples).coerceAtLeast(0.0)) / mean * 100).toFloat() else null)
        }
    }
}
