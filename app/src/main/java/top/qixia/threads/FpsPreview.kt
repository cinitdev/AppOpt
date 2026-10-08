package top.qixia.threads

/** 真实样本的固定内存预览，时间桶保留首值、最小值、最大值和末值；
 * 即使前一个样本被压缩省略，保留的样本仍携带断线标记。 */
internal class FpsPreview(private val startMs: Long, private val endMs: Long, maxPoints: Int = MAX_POINTS) {
    companion object { const val MAX_POINTS = 80 }
    private data class Mark(val point: HistoryFpsStore.Point, val lastGap: Long)
    private class Bucket(val first: Mark) {
        var last = first
        var minimum = first
        var maximum = first
        fun add(mark: Mark) {
            last = mark
            if (mark.point.fps < minimum.point.fps) minimum = mark
            if (mark.point.fps > maximum.point.fps) maximum = mark
        }
    }
    init { require(endMs >= startMs && maxPoints in 4..240 && maxPoints % 4 == 0) }
    private val buckets = arrayOfNulls<Bucket>(maxPoints / 4)
    private var previous = Long.MIN_VALUE
    private var lastGap = Long.MIN_VALUE

    /** 传 null 时按原始样本推断空缺；已压缩输入必须提供原有断线标记。 */
    fun add(timestampMs: Long, fps: Float, breakBefore: Boolean? = null) {
        if (timestampMs !in startMs..endMs || timestampMs <= previous || !fps.isFinite() || fps !in 0f..1000f) return
        if (breakBefore ?: (previous != Long.MIN_VALUE && timestampMs - previous > 2500L)) lastGap = timestampMs
        previous = timestampMs
        val progress = ((timestampMs - startMs).toDouble() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val mark = Mark(HistoryFpsStore.Point(progress.toFloat(), fps, lastGap == timestampMs, timestampMs), lastGap)
        val index = (progress * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
        buckets[index]?.add(mark) ?: run { buckets[index] = Bucket(mark) }
    }

    fun points(): List<HistoryFpsStore.Point> {
        var previousTime = Long.MIN_VALUE
        return buckets.filterNotNull().flatMap { bucket ->
            listOf(bucket.first, bucket.minimum, bucket.maximum, bucket.last).distinctBy { it.point.timestampMs }
                .sortedBy { it.point.timestampMs }.map { mark ->
                    mark.point.copy(breakBefore = mark.lastGap > previousTime).also { previousTime = mark.point.timestampMs!! }
                }
        }
    }
}
