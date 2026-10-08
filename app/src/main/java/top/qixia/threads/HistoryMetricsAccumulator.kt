package top.qixia.threads

/** 流式归并校准数据，不需要缓存所有分片的全部样本。 */
internal class HistoryMetricsAccumulator(private val since: Long, private val duration: Long) {
    private val series = sortedMapOf<String, Values>()
    var samples = 0
        private set
    private var charging = 0
    private data class Mark(val point: HistoryMetrics.Point, val lastGap: Float)
    private class Bucket(val first: Mark) {
        var last = first
        var low = first
        var high = first
        fun add(point: Mark) {
            last = point
            if (point.point.value < low.point.value) low = point
            if (point.point.value > high.point.value) high = point
        }
    }
    private class Frequency(var low: Float, var high: Float, var count: Int)
    private class Values(val key: String) {
        var count = 0
        var sum = 0.0
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        var lastTime = Long.MIN_VALUE
        var lastGap = -1f
        val buckets = sortedMapOf<Int, Bucket>()
        val frequencies = if (key.startsWith("cpu_mhz.")) mutableListOf<Frequency>() else null
        fun add(time: Long, progress: Float, value: Float) {
            count++; sum += value; low = minOf(low, value); high = maxOf(high, value)
            val point = HistoryMetrics.Point(progress, value, lastTime != Long.MIN_VALUE && time - lastTime > 3500)
            lastTime = time
            if (point.breakBefore) lastGap = progress
            val marked = Mark(point, lastGap)
            buckets.getOrPut((progress * 60).toInt().coerceIn(0, 59)) { Bucket(marked) }.add(marked)
            frequencies?.let { bins ->
                val existing = bins.firstOrNull { value in it.low..it.high }
                if (existing != null) existing.count++
                else {
                    bins += Frequency(value, value, 1)
                    bins.sortBy { it.low }
                    // 精确保留正常的离散运行频点；对输出连续变化频率的驱动，
                    // 使用数量受限的相邻分桶。
                    if (bins.size > 256) {
                        val at = (0 until bins.lastIndex).minBy { bins[it + 1].high - bins[it].low }
                        val right = bins.removeAt(at + 1)
                        bins[at].high = right.high; bins[at].count += right.count
                    }
                }
            }
        }
        fun report(): HistoryMetrics.Series {
            var previous = -1f
            val points = buckets.values.flatMap { bucket ->
                listOf(bucket.first, bucket.low, bucket.high, bucket.last).distinct().sortedBy { it.point.progress }.map { mark ->
                    mark.point.copy(breakBefore = mark.lastGap > previous)
                        .also { previous = mark.point.progress }
                }
            }
            return HistoryMetrics.Series(key, (sum / count).toFloat(), low, high, count, points)
        }
    }

    fun add(sample: HistoryMetrics.Sample) {
        if (sample.timestampMs !in since..(since + duration)) return
        samples++
        if (sample.charging == true) charging++
        val progress = ((sample.timestampMs - since).toFloat() / duration).coerceIn(0f, 1f)
        sample.values.forEach { (key, value) -> series.getOrPut(key) { Values(key) }.add(sample.timestampMs, progress, value) }
    }

    fun report(device: Map<String, String>): HistoryMetrics.Report {
        val distributions = series.values.mapNotNull { values -> values.frequencies?.let { frequencies ->
            values.key to frequencies.asReversed().chunked(((frequencies.size + 31) / 32).coerceAtLeast(1)).map { bin ->
                HistoryMetrics.FrequencyBucket(bin.last().low, bin.first().high, bin.sumOf { it.count } * 100f / values.count)
            }
        } }.toMap()
        return HistoryMetrics.Report(series.mapValues { it.value.report() }, samples, charging, duration,
            (samples * 2000f / duration).coerceIn(0f, 1f), distributions = distributions, device = device)
    }
}
