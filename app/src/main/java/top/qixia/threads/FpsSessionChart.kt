package top.qixia.threads

import java.io.File
import kotlin.math.abs

/** 按需读取已完成归档，最多保留 80 个时间桶，不启动采样器。 */
internal object FpsSessionChart {
    data class Point(val timestampMs: Long, val fps: Float)
    data class Chart(val points: List<Point>, val peak: Float)

    fun read(filesDir: File, record: FpsSessionRecorder.AverageRecord): Chart? = runCatching {
        val file = FpsSessionRecorder.lastSampleFile(filesDir, record.packageName)
        // 异常大的归档或损坏归档不能拖慢首页。
        if (!file.isFile || file.length() > 16 * 1024 * 1024L) return@runCatching null
        val sums = DoubleArray(80)
        val counts = LongArray(80)
        val times = LongArray(80)
        var packageName = ""
        var startedAt = -1L
        var count = 0L
        var sum = 0.0
        var peak = 0f
        var previousAt = record.startedAtMs
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("# package=") -> packageName = line.substringAfter('=').trim()
                    line.startsWith("# started_at_ms=") -> startedAt = line.substringAfter('=').trim().toLongOrNull() ?: -1L
                    line.isBlank() || line.startsWith('#') || line.startsWith("timestamp_ms,") -> Unit
                    else -> {
                        val parts = line.split(',', limit = 2)
                        val time = parts.getOrNull(0)?.toLongOrNull() ?: error("Invalid timestamp")
                        val fps = parts.getOrNull(1)?.toFloatOrNull() ?: error("Invalid FPS")
                        check(fps.isFinite() && fps in 0f..1000f)
                        check(time >= previousAt && time <= record.endedAtMs)
                        previousAt = time
                        val bucket = (((time - record.startedAtMs).toDouble() /
                            record.durationMs.coerceAtLeast(1L)) * 79).toInt().coerceIn(0, 79)
                        sums[bucket] += fps
                        counts[bucket]++
                        times[bucket] = time
                        count++
                        sum += fps
                        peak = maxOf(peak, fps)
                    }
                }
            }
        }
        // 平均值与归档分别发布，不能把旧归档与新摘要配对。
        if (packageName != record.packageName || startedAt != record.startedAtMs ||
            count != record.sampleCount || count == 0L || abs(sum / count - record.averageFps) > .01
        ) return@runCatching null
        Chart(counts.indices.filter { counts[it] > 0L }.map {
            Point(times[it], (sums[it] / counts[it]).toFloat())
        }, peak)
    }.getOrNull()
}
