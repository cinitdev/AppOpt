package top.qixia.threads.compose

import top.qixia.threads.HistoryMetrics
import kotlin.math.ceil
import kotlin.math.floor

/** 只根据本次记录的电量样本计算，不读取设备当前电量。 */
internal data class HistoryBatteryDrain(val start: Float, val end: Float, val series: HistoryMetrics.Series) {
    val consumed: Float get() = start - end
    // 保留微小电量下降；电量上升（充电或电量计校正）仍表示为负值。
    val axis: HistoryPlotMath.Axis get() {
        val minimum = floor(minOf(0f, series.minimum))
        return HistoryPlotMath.Axis(minimum, maxOf(ceil(series.maximum), minimum + 1f, 0f))
    }

    companion object {
        fun from(battery: HistoryMetrics.Series?): HistoryBatteryDrain? {
            if (battery == null || battery.points.size < 2) return null
            val points = battery.points
            if (points.any { !it.progress.isFinite() || it.progress !in 0f..1f || !HistoryMetrics.valid("battery_pct", it.value) } ||
                points.zipWithNext().any { (a, b) -> a.progress >= b.progress }) return null
            val start = points.first().value
            val end = points.last().value
            return HistoryBatteryDrain(start, end, HistoryMetrics.Series("battery_used_pct",
                start - battery.average, start - battery.maximum, start - battery.minimum, battery.samples,
                points.map { it.copy(value = start - it.value) }))
        }
    }
}
