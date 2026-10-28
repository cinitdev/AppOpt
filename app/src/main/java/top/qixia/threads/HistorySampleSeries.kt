package top.qixia.threads

import kotlin.math.abs
import kotlin.math.roundToLong

/** 分析查询保留文件中的采样，绘图仍可独立抽稀；基础类型数组避免长记录装箱膨胀。 */
class HistorySampleSeries private constructor(
    private val durationMs: Long,
    private val offsets: LongArray,
    private val values: FloatArray,
    private val gapMs: Long
) {
    private val plotted by lazy(LazyThreadSafetyMode.PUBLICATION) { historyPlotSamples(durationMs, offsets, values, gapMs) }
    fun plotPoints(): List<HistoryMetrics.Point> = plotted

    /** 图表使用原始样本定位，不能把用于绘图的抽稀点当成采集间隔。 */
    fun pointAt(progress: Float): HistoryMetrics.Point? {
        if (!progress.isFinite() || progress !in 0f..1f || offsets.isEmpty()) return null
        val time = (progress.toDouble() * durationMs).roundToLong()
        val found = offsets.binarySearch(time)
        val next = if (found >= 0) found else -found - 1
        val index = when {
            found >= 0 -> found
            next == 0 -> 0
            next == offsets.size -> offsets.lastIndex
            time - offsets[next - 1] <= offsets[next] - time -> next - 1
            else -> next
        }
        val tolerance = if (time < offsets.first() || time > offsets.last()) 6000L else minOf(1500L, gapMs / 2)
        return values[index].takeIf { it.isFinite() && abs(offsets[index] - time) <= tolerance }?.let {
            HistoryMetrics.Point(offsets[index].toFloat() / durationMs, it)
        }
    }

    fun at(progress: Float): Float? {
        if (!progress.isFinite() || progress !in 0f..1f || offsets.isEmpty()) return null
        val time = (progress.toDouble() * durationMs).roundToLong()
        val found = offsets.binarySearch(time)
        val index = if (found >= 0) found else {
            val next = -found - 1
            if (next in 1 until offsets.size && offsets[next] - offsets[next - 1] > gapMs) return null
            when {
                next == 0 -> 0
                next == offsets.size -> offsets.lastIndex
                time - offsets[next - 1] <= offsets[next] - time -> next - 1
                else -> next
            }
        }
        return values[index].takeIf { it.isFinite() && abs(offsets[index] - time) <= 1500 }
    }

    internal fun points(): List<HistoryMetrics.Point> {
        var previous = -1
        return values.indices.mapNotNull { index ->
            values[index].takeIf { it.isFinite() }?.let { value ->
                HistoryMetrics.Point(offsets[index].toFloat() / durationMs, value,
                    previous >= 0 && (index != previous + 1 || offsets[index] - offsets[previous] > gapMs))
                    .also { previous = index }
            }
        }
    }

    override fun equals(other: Any?): Boolean = this === other || other is HistorySampleSeries &&
        durationMs == other.durationMs && gapMs == other.gapMs &&
        offsets.contentEquals(other.offsets) && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * (31 * (31 * durationMs.hashCode() + gapMs.hashCode()) +
        offsets.contentHashCode()) + values.contentHashCode()

    internal class Builder(private val since: Long, private val durationMs: Long, private val gapMs: Long = 3500) {
        private var offsets = LongArray(16)
        private var values = FloatArray(16)
        private var size = 0
        private var valid = false

        fun add(time: Long, value: Float) {
            val offset = time - since
            if (durationMs <= 0 || offset !in 0..durationMs || (size > 0 && offset <= offsets[size - 1])) return
            if (size == offsets.size) {
                offsets = offsets.copyOf(size * 2)
                values = values.copyOf(size * 2)
            }
            offsets[size] = offset
            values[size] = value.takeIf { it.isFinite() && it >= 0f } ?: Float.NaN
            valid = valid || values[size].isFinite()
            size++
        }

        fun build(): HistorySampleSeries? = if (!valid) null else
            HistorySampleSeries(durationMs, offsets.copyOf(size), values.copyOf(size), gapMs)
    }
}

/** 按时间桶保留首尾与极值；原始查询和绘图抽稀使用不同的数据入口。 */
internal fun historyPlotSamples(durationMs: Long, offsets: LongArray, values: FloatArray,
    gapMs: Long, limit: Int = 1200): List<HistoryMetrics.Point> {
    if (durationMs <= 0 || offsets.isEmpty()) return emptyList()
    data class Mark(val index: Int, val gap: Int)
    val result = ArrayList<HistoryMetrics.Point>(minOf(limit, offsets.size))
    val bucketSize = ((offsets.size + limit / 4 - 1) / (limit / 4)).coerceAtLeast(1)
    var previousValid = -1
    var lastGap = -1
    var previousOutput = -1
    for (start in offsets.indices step bucketSize) {
        var first: Mark? = null; var last: Mark? = null; var low: Mark? = null; var high: Mark? = null
        for (index in start until minOf(start + bucketSize, offsets.size)) {
            if (!values[index].isFinite()) continue
            if (previousValid >= 0 && (index != previousValid + 1 || offsets[index] - offsets[previousValid] > gapMs)) lastGap = index
            previousValid = index
            val mark = Mark(index, lastGap)
            if (first == null) first = mark
            last = mark
            if (low == null || values[index] < values[low.index]) low = mark
            if (high == null || values[index] > values[high.index]) high = mark
        }
        listOfNotNull(first, low, high, last).distinctBy { it.index }.sortedBy { it.index }.forEach { mark ->
            result += HistoryMetrics.Point(offsets[mark.index].toFloat() / durationMs, values[mark.index],
                previousOutput >= 0 && mark.gap > previousOutput)
            previousOutput = mark.index
        }
    }
    return result
}
