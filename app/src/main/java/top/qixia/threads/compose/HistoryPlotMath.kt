package top.qixia.threads.compose

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 仅处理显示计算，不改动采样值、统计或已保存记录。 */
internal object HistoryPlotMath {
    data class Axis(val minimum: Float, val maximum: Float) {
        fun fraction(value: Float) = ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        fun tick(index: Int) = maximum - (maximum - minimum) * index / 4f
    }

    fun axis(values: List<Float>, unit: String): Axis {
        if (unit == "%") return Axis(0f, 100f)
        val valid = values.filter { it.isFinite() }
        if (valid.isEmpty()) return Axis(0f, when (unit) { "MHz" -> 3000f; "°C" -> 60f; "W" -> 10f; else -> 60f })
        val maximum = valid.max()
        val minimum = valid.min()
        // 温度使用真实范围并包含零下值，其他指标从零开始。
        val padding = if (unit == "°C") maxOf((maximum - minimum) * .12f, 2f) else 0f
        val bottom = if (unit == "°C") minimum - padding else 0f
        val top = maxOf(maximum + padding, bottom + 1f)
        val step = niceStep((top - bottom) / 4f)
        return Axis(floor(bottom / step) * step, ceil(top / step) * step)
    }

    /** 同时显示的 FPS 辅助曲线共用数值刻度，各指标仍保留自身单位。 */
    fun fpsAuxiliaryAxis(values: List<Float>): Axis {
        val valid = values.filter { it.isFinite() }
        val minimum = minOf(0f, valid.minOrNull() ?: 0f)
        val maximum = maxOf(100f, valid.maxOrNull() ?: 100f)
        val step = niceStep((maximum - minimum) / 4f)
        return Axis(floor(minimum / step) * step, ceil(maximum / step) * step)
    }

    private fun niceStep(raw: Float): Float {
        val power = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val normalized = raw / power
        return (listOf(1f, 2f, 2.5f, 5f, 10f).first { it >= normalized }) * power
    }

    /** 不把充电空缺或丢失采样区间插值成看似真实的读数。 */
    fun at(points: List<HistoryCurvePoint>, progress: Float, durationMs: Long = 0): HistoryCurvePoint? {
        if (!progress.isFinite() || progress !in 0f..1f || points.isEmpty()) return null
        // 首次和末次采样通常不恰好落在会话边界；边缘仅允许一个采样间隔，
        // 再加记录器最终释放及写入的宽限时间。
        // 返回附近真实样本，不生成虚构端点，也不延伸到较长的充电空段。
        val edge = when {
            progress < points.first().progress -> points.first()
            progress > points.last().progress -> points.last()
            else -> null
        }
        if (edge != null) return edge.takeIf {
            durationMs > 0 && abs(it.progress - progress).toDouble() * durationMs <= 6000.0
        }
        // 游标移动需查询每条可见曲线，二分查找降低长报告拖动开销。
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].progress < progress) low = middle + 1 else high = middle
        }
        val right = low
        val next = points[right]
        if (next.progress == progress || right == 0) return next
        val previous = points[right - 1]
        if (next.breakBefore) return null
        return if (abs(previous.progress - progress) <= abs(next.progress - progress)) previous else next
    }

    /** 将浮动读数限制在图内，兼容窄屏及左右边缘。 */
    fun tooltipOffset(anchor: Float, extent: Float, tooltip: Float, gap: Float): Float {
        val after = anchor + gap
        val preferred = if (after + tooltip <= extent) after else anchor - gap - tooltip
        return preferred.coerceIn(0f, (extent - tooltip).coerceAtLeast(0f))
    }

    fun cpuLabel(key: String): String {
        val cores = key.substringAfter('.').split('_').mapNotNull(String::toIntOrNull).distinct().sorted()
        if (cores.isEmpty()) return "CPU"
        val contiguous = cores.zipWithNext().all { (a, b) -> b == a + 1 }
        return "CPU " + if (cores.size > 1 && contiguous) "${cores.first()}–${cores.last()}" else cores.joinToString("、")
    }
}
