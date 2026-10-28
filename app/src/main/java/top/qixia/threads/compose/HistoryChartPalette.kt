package top.qixia.threads.compose

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

/** 稳定 ID 确保筛选后曲线、图例、游标和统计颜色保持一致。 */
internal object HistoryChartPalette {
    val fps = 0xFF1474E4.toInt()
    val cpuUsage = 0xFF87D3FF.toInt()
    val gpuUsage = 0xFF599CE8.toInt()
    val temperature = 0xFFFFBA7A.toInt()
    val battery = 0xFFEB91C1.toInt()

    // 曲线采用浅紫、青、橙等清亮色；小字号文字另用同色相的加深色。
    private val cores = intArrayOf(
        0xB177E3, 0x00D5D9, 0xFC8A1B, 0xEC8DB9,
        0x688DE0, 0x54B8A0, 0xCB9D5A, 0xE47672,
        0x8D89CF, 0x4BA6C4, 0xB29369, 0xCA80AB,
        0x84AA60, 0x729AC5, 0xBA77A0, 0x5DAAA9,
    )

    fun core(cpu: Int): Int {
        val id = cpu.coerceAtLeast(0)
        cores.getOrNull(id)?.let { return it or (0xFF shl 24) }
        // 通过色相、饱和度和亮度扩展颜色，不循环复用少量颜色。
        val hue = (id * 137.50776405 + 23) % 360 / 60
        val saturation = .64 + (id % 5) * .055
        val value = .57 + (id / 5 % 4) * .035
        val chroma = value * saturation
        val x = chroma * (1 - abs(hue % 2 - 1))
        val low = value - chroma
        fun channel(v: Double) = ((v + low) * 255).roundToInt()
        val (r, g, b) = when (hue.toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** 浅色适合曲线，文字仍需在白底上清晰可读；不把未选中图例改成灰色。 */
    fun label(color: Int): Int {
        var r = color ushr 16 and 255
        var g = color ushr 8 and 255
        var b = color and 255
        fun linear(value: Int): Double {
            val v = value / 255.0
            return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        }
        while (1.05 / (.2126 * linear(r) + .7152 * linear(g) + .0722 * linear(b) + .05) < 4.5) {
            r = (r * .95).toInt(); g = (g * .95).toInt(); b = (b * .95).toInt()
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun metric(key: String, fallbackIndex: Int = 0): Int = when {
        key.startsWith("cpu_core.") || key.startsWith("cpu_cycles_core.") ->
            core(key.substringAfter('.').toIntOrNull() ?: fallbackIndex)
        key.startsWith("cpu_mhz.") ->
            core(key.substringAfter('.').split('_').mapNotNull(String::toIntOrNull).minOrNull() ?: fallbackIndex)
        else -> when (key) {
            "fps", "frame_max_ms", "gpu_mhz", "power_w" -> fps
            "cpu_usage", "ddr_mhz" -> cpuUsage
            "gpu_usage", "ddr_mbps", "battery_ma" -> gpuUsage
            "cpu_c" -> 0xFFEF6C73.toInt()
            "gpu_c" -> cores[0] or (0xFF shl 24)
            "battery_c" -> temperature
            "battery_pct", "battery_used_pct" -> battery
            else -> core(fallbackIndex)
        }
    }
}
