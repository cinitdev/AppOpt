package top.qixia.threads

import kotlin.math.abs
import kotlin.math.roundToLong

/** 掉帧分析使用原始采样；绘图抽稀不能改变指定时刻的读数。
 * 只保留分析面板需要的指标，以共享时间轴和基础类型数组减少长记录的内存占用。 */
class HistoryMetricSamples private constructor(
    private val durationMs: Long,
    private val offsets: LongArray,
    private val columns: Map<String, FloatArray>
) {
    fun at(progress: Float): Map<String, Float>? {
        if (!progress.isFinite() || progress !in 0f..1f || offsets.isEmpty()) return null
        val time = (progress.toDouble() * durationMs).roundToLong()
        val found = offsets.binarySearch(time)
        val index = if (found >= 0) found else {
            val next = -found - 1
            // 真实采样中断时，不跨越空洞借用前后的读数。
            if (next in 1 until offsets.size && offsets[next] - offsets[next - 1] > 3500) return null
            when {
                next == 0 -> 0
                next == offsets.size -> offsets.lastIndex
                time - offsets[next - 1] <= offsets[next] - time -> next - 1
                else -> next
            }
        }
        if (abs(offsets[index] - time) > 1500) return null
        // 全部指标来自同一次采样；该次缺测的字段不能从其他时刻补齐。
        return columns.mapNotNull { (key, values) -> values[index].takeIf { it.isFinite() }?.let { key to it } }.toMap()
    }

    override fun equals(other: Any?): Boolean = this === other || other is HistoryMetricSamples &&
        durationMs == other.durationMs && offsets.contentEquals(other.offsets) && columns.keys == other.columns.keys &&
        columns.all { (key, values) -> values.contentEquals(other.columns.getValue(key)) }

    override fun hashCode(): Int = 31 * (31 * durationMs.hashCode() + offsets.contentHashCode()) +
        columns.entries.sumOf { (key, values) -> key.hashCode() xor values.contentHashCode() }

    companion object {
        fun includes(key: String): Boolean = when (key) {
            "cpu_usage", "gpu_usage", "gpu_mhz", "power_w" -> true
            else -> key.startsWith("cpu_mhz.")
        }
    }

    internal class Builder(private val since: Long, private val durationMs: Long) {
        private var times = LongArray(256)
        private val values = linkedMapOf<String, FloatArray>()
        private var size = 0

        fun add(sample: HistoryMetrics.Sample) {
            val offset = sample.timestampMs - since
            if (durationMs <= 0 || offset !in 0..durationMs || (size > 0 && offset <= times[size - 1])) return
            if (size == times.size) {
                val capacity = times.size * 2
                times = times.copyOf(capacity)
                values.replaceAll { _, column -> column.copyOf(capacity).also { it.fill(Float.NaN, size) } }
            }
            times[size] = offset
            sample.values.forEach { (key, value) ->
                if (includes(key) && HistoryMetrics.valid(key, value) && (key != "power_w" || sample.charging == false)) {
                    values.getOrPut(key) { FloatArray(times.size) { Float.NaN } }[size] = value
                }
            }
            size++
        }

        fun build(): HistoryMetricSamples? = if (size == 0 || values.isEmpty()) null else
            HistoryMetricSamples(durationMs, times.copyOf(size), values.mapValues { it.value.copyOf(size) })
    }
}
