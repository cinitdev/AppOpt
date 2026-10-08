package top.qixia.threads

import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** 仅保存已完成的 FPS 记录，不新增采样器、守护查询或唤醒。 */
object HistoryFpsStore {
    data class Point(val progress: Float, val fps: Float, val breakBefore: Boolean = false, val timestampMs: Long? = null)
    data class Report(val average: Float, val minimum: Float, val maximum: Float,
        val samples: Int, val points: List<Point>, val low5: Float? = null, val jitter: Float? = null)

    /** Scene 报告使用最低 5% 的每秒 FPS 样本均值，而非百分位数。 */
    internal fun summary(values: List<Float>, points: List<Point>): Report {
        require(values.isNotEmpty() && values.all { it.isFinite() && it in 0f..1000f })
        val average = values.map(Float::toDouble).average()
        val variance = values.sumOf { (it - average) * (it - average) } / values.size
        val low = if (values.size > 100) values.sorted().take((values.size * .05f).roundToInt().coerceAtLeast(1))
            .map(Float::toDouble).average().toFloat() else null
        return Report(average.toFloat(), values.min(), values.max(), values.size, points, low,
            if (average > 0) (kotlin.math.sqrt(variance) / average * 100).toFloat() else null)
    }

    private const val MAX_FILE_BYTES = 8 * 1024 * 1024L
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024L
    private fun directory(filesDir: File) = File(filesDir, "history_fps")
    private fun prefix(pkg: String) = MessageDigest.getInstance("SHA-256")
        .digest(pkg.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun summaryName(pkg: String, epoch: Long, duration: Long) = "${prefix(pkg)}_${epoch}_${duration}.summary"

    /** 在首页加载路径之外准备精确的校准区间统计。 */
    internal fun cachedSummary(filesDir: File, pkg: String, epoch: Long, duration: Long): Report? = runCatching {
        val folder = directory(filesDir)
        val metadata = File(folder, summaryName(pkg, epoch, duration))
        require(epoch > 0 && duration > 0 && metadata.isFile && metadata.length() <= 4096)
        DataInputStream(metadata.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x51465732 && input.readUTF() == pkg)
            require(input.readLong() == epoch && input.readLong() == duration)
            val sourceName = input.readUTF()
            require(sourceName.startsWith(prefix(pkg) + "_") && sourceName.endsWith(".csv") &&
                File(sourceName).name == sourceName && '/' !in sourceName && '\\' !in sourceName)
            val source = File(folder, sourceName)
            val length = input.readLong()
            val modified = input.readLong()
            require(source.isFile && source.length() == length && source.lastModified() == modified)
            val average = input.readFloat(); val minimum = input.readFloat(); val maximum = input.readFloat()
            val samples = input.readInt()
            require(samples > 0 && listOf(average, minimum, maximum).all { it.isFinite() && it in 0f..1000f } &&
                minimum <= average && average <= maximum)
            val low = input.readFloat().takeIf { it.isFinite() }
            val jitter = input.readFloat().takeIf { it.isFinite() }
            val count = input.readInt().also { require(it in 0..FpsPreview.MAX_POINTS) }
            val until = Math.multiplyExact(epoch, 1000L)
            val since = Math.subtractExact(until, duration)
            var previous = Long.MIN_VALUE
            val points = List(count) {
                val time = input.readLong().also { require(it in since..until && it > previous); previous = it }
                val fps = input.readFloat().also { require(it.isFinite() && it in 0f..1000f) }
                Point(((time - since).toDouble() / duration).toFloat(), fps, input.readBoolean(), time)
            }
            require(input.read() == -1)
            Report(average, minimum, maximum, samples, points, low, jitter)
        }
    }.getOrNull()

    internal fun prepareHomeSummaries(filesDir: File, pkg: String, sessions: List<Pair<Long, Long>>) {
        val missing = sessions.filter { cachedSummary(filesDir, pkg, it.first, it.second) == null }
        if (missing.isNotEmpty()) reports(filesDir, pkg, missing)
    }

    @Synchronized
    private fun cacheSummary(filesDir: File, pkg: String, epoch: Long, duration: Long, source: File, report: Report) {
        val destination = File(directory(filesDir), summaryName(pkg, epoch, duration))
        val pending = File(destination.parentFile, "${destination.name}.tmp")
        runCatching {
            DataOutputStream(pending.outputStream().buffered()).use { output ->
                output.writeInt(0x51465732); output.writeUTF(pkg); output.writeLong(epoch); output.writeLong(duration)
                output.writeUTF(source.name); output.writeLong(source.length()); output.writeLong(source.lastModified())
                output.writeFloat(report.average); output.writeFloat(report.minimum); output.writeFloat(report.maximum)
                output.writeInt(report.samples); output.writeFloat(report.low5 ?: Float.NaN); output.writeFloat(report.jitter ?: Float.NaN)
                val preview = FpsPreview(epoch * 1000L - duration, epoch * 1000L)
                report.points.forEach { point -> point.timestampMs?.let { preview.add(it, point.fps, point.breakBefore) } }
                val points = preview.points()
                output.writeInt(points.size)
                points.forEach { point -> output.writeLong(point.timestampMs!!); output.writeFloat(point.fps); output.writeBoolean(point.breakBefore) }
            }
            try {
                java.nio.file.Files.move(pending.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(pending.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        pending.delete()
    }

    /** 完成时调用一次，归档失败不能影响实时 FPS 服务。 */
    @Synchronized
    internal fun archive(filesDir: File, record: FpsSessionRecorder.AverageRecord, source: File,
        retained: Map<String, List<Pair<Long, Long>>>? = null, calibration: Boolean = false) {
        runCatching {
            if (!source.isFile || source.length() > MAX_FILE_BYTES) return
            val folder = directory(filesDir).apply { mkdirs() }
            val key = prefix(record.packageName)
            val file = File(folder, "${key}_${record.startedAtMs}.csv")
            if (file.exists()) return
            val pending = File(folder, "${file.name}.tmp")
            try {
                pending.outputStream().buffered().use { output ->
                    output.write(("# ended_at_ms=${record.endedAtMs}\n" +
                        "# sample_count=${record.sampleCount}\n# average_fps=${record.averageFps}\n" +
                        "# calibration=${if (calibration) 1 else 0}\n").toByteArray())
                    source.inputStream().use { it.copyTo(output) }
                }
                // 旧版摘要与 CSV 分别发布，提交前检查已复制的文件头，
                // 避免并发完成另一轮记录时污染当前归档标识。
                val headers = pending.useLines { it.take(8).toList() }
                check("# package=${record.packageName}" in headers)
                check("# started_at_ms=${record.startedAtMs}" in headers)
                check(pending.renameTo(file))
            } finally { pending.delete() }
            // 调用方没有数据库快照时（例如旧版导入），
            // 无法安全判断哪些已完成曲线不再被引用。
            if (retained != null) prune(filesDir, retained)
        }
    }

    @Synchronized
    internal fun prune(filesDir: File, retained: Map<String, List<Pair<Long, Long>>>) {
            val folder = directory(filesDir)
            val summaries = retained.flatMap { (pkg, windows) -> windows.map { summaryName(pkg, it.first, it.second) } }.toSet()
            folder.listFiles { file -> file.extension == "summary" && file.name !in summaries }.orEmpty().forEach { it.delete() }
            val all = folder.listFiles().orEmpty().filter { it.extension == "csv" }
                .sortedByDescending { it.name.substringAfterLast('_').removeSuffix(".csv").toLongOrNull() ?: 0L }
            // 校准之外也可记录 FPS，一个 CSV 不等于一次历史会话。
            val perApp = mutableMapOf<String, Int>()
            var kept = 0
            var bytes = 0L
            var pendingCalibrations = 0
            for (entry in all) {
                val headers = runCatching { entry.useLines { lines -> lines.take(8)
                    .filter { it.startsWith("# ") && '=' in it }
                    .associate { it.substringAfter("# ").substringBefore('=') to it.substringAfter('=') } } }
                    .getOrDefault(emptyMap())
                val start = headers["started_at_ms"]?.toLongOrNull()
                val end = headers["ended_at_ms"]?.toLongOrNull()
                val referenced = start != null && end != null && headers["package"]?.let { retained[it] }.orEmpty().any { (epoch, duration) ->
                    duration > 0 && start <= epoch * 1000L && end >= epoch * 1000L - duration
                }
                // 刚完成的校准会先归档，之后原生历史才进入 SQLite。
                // 为这些记录提供独立且有界的保留额度，
                // 期间不得被无关的手动 FPS 记录挤出。
                val pending = headers["calibration"] == "1" && pendingCalibrations++ < HistoryRetention.CALIBRATION_LIMIT
                if (referenced || pending) continue
                val app = entry.name.substringBefore('_')
                val count = perApp.getOrDefault(app, 0)
                if (count >= 30 || kept >= 300 || bytes + entry.length() > MAX_TOTAL_BYTES) entry.delete()
                else { perApp[app] = count + 1; kept++; bytes += entry.length() }
            }
    }

    @Synchronized
    internal fun removeSessions(filesDir: File, pkg: String, removed: List<Pair<Long, Long>>, retained: List<Pair<Long, Long>>) {
        removed.filterNot { it in retained }.forEach { (epoch, duration) -> File(directory(filesDir), summaryName(pkg, epoch, duration)).delete() }
        directory(filesDir).listFiles { file -> file.name.startsWith(prefix(pkg) + "_") && file.extension == "csv" }
            .orEmpty().forEach { file -> runCatching {
                val headers = file.useLines { lines -> lines.take(8).filter { it.startsWith("# ") && '=' in it }
                    .associate { it.substringAfter("# ").substringBefore('=') to it.substringAfter('=') } }
                if (headers["package"] != pkg) return@runCatching
                val start = headers["started_at_ms"]?.toLongOrNull() ?: return@runCatching
                val end = headers["ended_at_ms"]?.toLongOrNull() ?: return@runCatching
                fun overlaps(window: Pair<Long, Long>): Boolean = window.second > 0L &&
                    start <= window.first * 1000L && end >= window.first * 1000L - window.second
                if (removed.any(::overlaps) && retained.none(::overlaps)) file.delete()
            } }
    }

    /** 按需导入旧版 .last.csv，每份报告裁切到对应的校准区间。 */
    internal fun reports(filesDir: File, pkg: String, sessions: List<Pair<Long, Long>>): Map<Long, Report> {
        val last = FpsSessionRecorder.readLastAverage(filesDir, pkg)
        if (last != null && sessions.any { (epoch, duration) ->
                duration > 0L && last.startedAtMs <= epoch * 1000L && last.endedAtMs >= epoch * 1000L - duration
            }) archive(filesDir, last, FpsSessionRecorder.lastSampleFile(filesDir, pkg))
        val key = prefix(pkg) + "_"
        val candidates = directory(filesDir).listFiles().orEmpty()
            .filter { it.name.startsWith(key) && it.extension == "csv" }
            .sortedByDescending { it.name }
        val results = mutableMapOf<Long, Report>()
        for (file in candidates) {
            if (results.size == sessions.size) break
            val stamp = file.length() to file.lastModified()
            readReports(file, pkg, sessions.filter { it.first !in results }).forEach { (epoch, report) ->
                results.putIfAbsent(epoch, report)
                if (stamp == (file.length() to file.lastModified())) {
                    sessions.firstOrNull { it.first == epoch }?.let { cacheSummary(filesDir, pkg, epoch, it.second, file, report) }
                }
            }
        }
        return results
    }

    internal fun readReports(file: File, pkg: String, sessions: List<Pair<Long, Long>>): Map<Long, Report> = runCatching {
        if (file.length() > MAX_FILE_BYTES || sessions.isEmpty()) return emptyMap()
        val headers = mutableMapOf<String, String>()
        val samples = ArrayList<Pair<Long, Float>>()
        var lastTime = Long.MIN_VALUE
        file.useLines { lines -> lines.forEach { line ->
            if (line.startsWith("# ") && '=' in line) headers[line.substring(2).substringBefore('=')] = line.substringAfter('=')
            else if (line.isNotBlank() && !line.startsWith('#') && !line.startsWith("timestamp_ms,")) {
                val fields = line.split(',', limit = 2)
                val time = fields[0].toLong()
                val fps = fields[1].toFloat()
                check(time >= lastTime && fps.isFinite() && fps in 0f..1000f && samples.size < 100000)
                lastTime = time
                samples.add(time to fps)
            }
        } }
        val start = headers.getValue("started_at_ms").toLong()
        val end = headers.getValue("ended_at_ms").toLong()
        check(headers["package"] == pkg && samples.isNotEmpty() && end >= start)
        check(samples.size.toLong() == headers.getValue("sample_count").toLong())
        check(samples.first().first >= start && samples.last().first <= end)
        check(abs(samples.sumOf { it.second.toDouble() } / samples.size - headers.getValue("average_fps").toDouble()) < .01)
        sessions.mapNotNull { (epoch, durationMs) ->
            val until = epoch * 1000L
            val since = until - durationMs
            // 原生历史使用完成时的秒级时间戳，FPS 使用毫秒。
            // 仅容忍最终写入或采样周期的延迟，不能借用另一轮记录的平均值。
            if (durationMs <= 0 || start > since + 1500L || end < until - 2500L || end < since || start > until) return@mapNotNull null
            val window = samples.filter { it.first in since..until }
            if (window.isEmpty()) return@mapNotNull null
            val preview = FpsPreview(since, until, maxPoints = 240)
            window.forEach { preview.add(it.first, it.second) }
            epoch to summary(window.map { it.second }, preview.points())
        }.toMap()
    }.getOrDefault(emptyMap())
}
