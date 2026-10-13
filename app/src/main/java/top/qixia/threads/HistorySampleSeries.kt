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
