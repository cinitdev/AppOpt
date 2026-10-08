package top.qixia.threads

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 仅追加的记录文件可应对应用意外中断，不迁移数据库或改动线程历史。 */
object HistoryMetricsStore {
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ROWS = 21_600 // 每两秒一条，可记录 12 小时
    private fun prefix(pkg: String) = MessageDigest.getInstance("SHA-256").digest(pkg.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun directory(filesDir: File) = File(filesDir, "history_metrics")
    private val activeFiles = mutableSetOf<String>()

    internal class Writer(private val filesDir: File, private val pkg: String, startedAt: Long,
        device: Map<String, String> = emptyMap(), captureId: String? = null) : java.io.Closeable {
        private val file: File
        private var rows = 0
        private var lastTimestamp = 0L
        private var closed = false
        init {
            synchronized(HistoryMetricsStore) {
                val dir = directory(filesDir).also { check(it.isDirectory || it.mkdirs()) }
                pruneHistory(filesDir)
                file = File(dir, "${prefix(pkg)}_${startedAt}_${UUID.randomUUID()}.tsv")
                file.writeText("# QixiaThreads metrics v1\n# package=$pkg\n# started=$startedAt\n")
                captureId?.takeIf { CAPTURE_ID.matches(it) }?.let { file.appendText("# capture_id=$it\n") }
                device.filterKeys { it in DEVICE_KEYS }.forEach { (key, value) ->
                    file.appendText("# device.$key=${value.replace('\n', ' ').replace('\r', ' ').take(160)}\n")
                }
                activeFiles += file.absolutePath
            }
        }
        @Synchronized
        fun append(sample: HistoryMetrics.Sample): Boolean {
            if (closed || rows >= MAX_ROWS || file.length() >= MAX_FILE_BYTES || sample.timestampMs <= lastTimestamp) return false
            val line = buildString {
                append(sample.timestampMs).append('\t').append(when (sample.charging) { true -> "1"; false -> "0"; null -> "?" })
                sample.values.forEach { (key, value) -> if (HistoryMetrics.valid(key, value)) append('\t').append(key).append('=').append(value) }
                append('\n')
            }
            file.appendText(line)
            lastTimestamp = sample.timestampMs
            rows++
            return true
        }
        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            synchronized(HistoryMetricsStore) {
                activeFiles -= file.absolutePath
                pruneHistory(filesDir)
            }
        }
    }

    @Synchronized
    internal fun pruneHistory(filesDir: File) {
        val files = directory(filesDir).listFiles { f -> f.name.endsWith(".tsv") && f.absolutePath !in activeFiles }
            ?.sortedByDescending { it.name.substringAfter('_').substringBefore('_').toLongOrNull() ?: 0L }.orEmpty()
        var bytes = 0L
        val perApp = mutableMapOf<String, Int>()
        files.forEachIndexed { index, file ->
            bytes += file.length()
            val app = file.name.substringBefore('_')
            val count = perApp.getOrDefault(app, 0) + 1
            perApp[app] = count
            // 一次校准可能包含多段前台片段，因此独立限制此缓冲区，
            // 并按校准区间删除已完成会话。
            if (index >= 300 || count > 30 || bytes > MAX_TOTAL_BYTES) file.delete()
        }
    }

    @Synchronized
    internal fun removeSessions(filesDir: File, pkg: String, removed: List<Pair<Long, Long>>, retained: List<Pair<Long, Long>>) {
        directory(filesDir).listFiles { file -> file.name.startsWith(prefix(pkg) + "_") && file.extension == "tsv" && file.absolutePath !in activeFiles }
            .orEmpty().forEach { file -> runCatching {
                val samples = read(file, pkg)
                val start = samples.firstOrNull()?.timestampMs ?: return@runCatching
                val end = samples.last().timestampMs
                fun overlaps(window: Pair<Long, Long>): Boolean = window.second > 0L &&
                    start <= window.first * 1000L && end >= window.first * 1000L - window.second
                if (removed.any(::overlaps) && retained.none(::overlaps)) file.delete()
            } }
    }

    fun reports(filesDir: File, pkg: String, sessions: List<Pair<Long, Long>>): Map<Long, HistoryMetrics.Report> {
        if (sessions.isEmpty()) return emptyMap()
        val windows = sessions.filter { it.first > 0 && it.second > 0 }
        val result = mutableMapOf<Long, HistoryMetrics.Report>()
        val groups = directory(filesDir).listFiles { f -> f.name.startsWith(prefix(pkg)) && f.name.endsWith(".tsv") }
            .orEmpty().sortedByDescending { it.lastModified() }.take(30).groupBy { file ->
                val id = runCatching { file.useLines { lines -> lines.take(12)
                    .firstOrNull { it.startsWith("# capture_id=") }?.substringAfter('=') } }.getOrNull()
                // 旧文件没有足够信息证明两次启动属于同一次记录，
                // 应保留供查看，而不推测并合并。
                id?.takeIf { CAPTURE_ID.matches(it) } ?: "legacy:${file.name}"
            }
        for (files in groups.values) {
            val accumulators = windows.associate { (epoch, duration) -> epoch to HistoryMetricsAccumulator(epoch * 1000L - duration, duration) }
            merge(files, pkg) { sample -> accumulators.values.forEach { it.add(sample) } }
            val device = readDevice(files.first())
            for ((epoch, accumulator) in accumulators) {
                if (accumulator.samples > (result[epoch]?.samples ?: 0)) {
                    result[epoch] = accumulator.report(device)
                }
            }
        }
        return result
    }

    private val CAPTURE_ID = Regex("[A-Za-z0-9_-]{1,80}")

    /** 每个分片只保留一个待读样本，在重叠边界按精确时间戳去重。 */
    private fun merge(files: List<File>, pkg: String, consume: (HistoryMetrics.Sample) -> Unit) {
        val readers = files.mapNotNull { file -> runCatching { SampleReader(file, pkg) }.getOrNull() }
        try {
            data class Pending(val order: Int, val reader: SampleReader, val sample: HistoryMetrics.Sample)
            val queue = java.util.PriorityQueue(compareBy<Pending> { it.sample.timestampMs }.thenBy { it.order })
            readers.forEachIndexed { index, reader -> reader.next()?.let { queue += Pending(index, reader, it) } }
            var previous = Long.MIN_VALUE
            while (queue.isNotEmpty()) {
                val entry = queue.remove()
                if (entry.sample.timestampMs != previous) { consume(entry.sample); previous = entry.sample.timestampMs }
                entry.reader.next()?.let { queue += Pending(entry.order, entry.reader, it) }
            }
        } finally { readers.forEach { it.close() } }
    }

    internal fun read(file: File, pkg: String): List<HistoryMetrics.Sample> = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES + 4096) return emptyList()
        val samples = ArrayList<HistoryMetrics.Sample>()
        SampleReader(file, pkg).use { reader ->
            while (true) {
                val sample = reader.next() ?: break
                samples += sample
            }
        }
        samples
    }.getOrDefault(emptyList())

    private class SampleReader(file: File, pkg: String) : java.io.Closeable {
        private val reader: java.io.BufferedReader
        private var previous = 0L
        private var rows = 0
        init {
            require(file.isFile && file.length() <= MAX_FILE_BYTES + 4096)
            reader = file.bufferedReader()
            try {
                require(reader.readLine() == "# QixiaThreads metrics v1" && reader.readLine() == "# package=$pkg")
                require(reader.readLine()?.startsWith("# started=") == true)
            } catch (error: Exception) { reader.close(); throw error }
        }
        fun next(): HistoryMetrics.Sample? {
            while (rows < MAX_ROWS) {
                val line = reader.readLine() ?: return null
                if (line.startsWith('#')) continue
                rows++
                if (line.length > 4096) continue
                val fields = line.split('\t')
                val timestamp = fields.firstOrNull()?.toLongOrNull() ?: continue
                if (timestamp <= previous || fields.size < 3 || fields[1] !in listOf("0", "1", "?")) continue
                val values = fields.drop(2).mapNotNull { field ->
                    val key = field.substringBefore('=')
                    val value = field.substringAfter('=', "").toFloatOrNull()
                    if (value != null && HistoryMetrics.valid(key, value)) key to value else null
                }.toMap().toMutableMap()
                val charging = when (fields[1]) { "1" -> true; "0" -> false; else -> null }
                if (charging != false) { values.remove("power_w"); values.remove("battery_ma") }
                previous = timestamp
                return HistoryMetrics.Sample(timestamp, charging, values)
            }
            return null
        }
        override fun close() = reader.close()
    }

    internal fun summarize(samples: List<HistoryMetrics.Sample>, since: Long, duration: Long): HistoryMetrics.Report {
        val series = linkedMapOf<String, HistoryMetrics.Series>()
        samples.flatMap { it.values.keys }.distinct().sorted().forEach { key ->
            var last = Long.MIN_VALUE
            val points = samples.mapNotNull { sample -> sample.values[key]?.let { value ->
                val point = HistoryMetrics.Point(((sample.timestampMs - since).toFloat() / duration).coerceIn(0f, 1f), value,
                    last != Long.MIN_VALUE && sample.timestampMs - last > 3500)
                last = sample.timestampMs
                point
            } }
            if (points.isNotEmpty()) series[key] = HistoryMetrics.Series(key, points.map { it.value.toDouble() }.average().toFloat(),
                points.minOf { it.value }, points.maxOf { it.value }, points.size, reduce(points))
        }
        val distributions = series.keys.filter { it.startsWith("cpu_mhz.") }.associateWith { key ->
            val readings = samples.mapNotNull { it.values[key] }
            val frequencies = readings.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
            // 部分驱动报告连续变化的频率，需限制长记录下的 Compose 计算量。
            frequencies.chunked(((frequencies.size + 31) / 32).coerceAtLeast(1)).map { bin ->
                HistoryMetrics.FrequencyBucket(bin.last().key, bin.first().key, bin.sumOf { it.value } * 100f / readings.size)
            }
        }
        return HistoryMetrics.Report(series, samples.size, samples.count { it.charging == true }, duration,
            (samples.size * 2000f / duration).coerceIn(0f, 1f), distributions = distributions)
    }

    private val DEVICE_KEYS = setOf("platform", "model", "os", "resolution", "refresh")
    private fun readDevice(file: File): Map<String, String> = runCatching {
        file.useLines { lines -> lines.take(12).filter { it.startsWith("# device.") && it.length <= 200 }.mapNotNull {
            val key = it.substringAfter("# device.").substringBefore('=')
            if (key in DEVICE_KEYS) key to it.substringAfter('=') else null
        }.toMap() }
    }.getOrDefault(emptyMap())

    /** 保留极值并在缺失样本处断线，让长记录的绘制规模保持有界。 */
    private fun reduce(points: List<HistoryMetrics.Point>): List<HistoryMetrics.Point> {
        if (points.size <= 240) return points
        val result = mutableListOf<HistoryMetrics.Point>()
        points.chunked((points.size + 59) / 60).forEach { bucket ->
            val chosen = listOf(bucket.first(), bucket.minBy { it.value }, bucket.maxBy { it.value }, bucket.last())
                .distinct().sortedBy { it.progress }
            var previous = result.lastOrNull()?.progress ?: -1f
            for (point in chosen) {
                result += point.copy(breakBefore = bucket.any { it.breakBefore && it.progress > previous && it.progress <= point.progress })
                previous = point.progress
            }
        }
        return result
    }
}
