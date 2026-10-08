package top.qixia.threads.compose

import kotlin.math.abs
import kotlin.math.roundToInt

/** 稳定 ID 确保筛选后曲线、图例、游标和统计颜色保持一致。 */
internal object HistoryChartPalette {
    val fps = 0xFF356FE5.toInt()
    val cpuUsage = 0xFF8A59CA.toInt()
    val gpuUsage = 0xFF078D8D.toInt()
    val temperature = 0xFFC17D19.toInt()
    val battery = 0xFFC43D70.toInt()

    // 颜色互不相同，在报告白色背景上的对比度至少为 4.5:1。
    private val cores = intArrayOf(
        0x2563EB, 0xDC2626, 0x15803D, 0xC026D3,
        0xB45309, 0x0E7490, 0xBE185D, 0x4D7C0F,
        0x4338CA, 0x9A3412, 0x047857, 0x7E22CE,
        0xA16207, 0x0369A1, 0x9F1239, 0x365314,
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

    fun metric(key: String, fallbackIndex: Int = 0): Int = when {
        key.startsWith("cpu_core.") || key.startsWith("cpu_cycles_core.") ->
            core(key.substringAfter('.').toIntOrNull() ?: fallbackIndex)
        key.startsWith("cpu_mhz.") ->
            core(key.substringAfter('.').split('_').mapNotNull(String::toIntOrNull).minOrNull() ?: fallbackIndex)
        else -> when (key) {
            "fps", "frame_max_ms", "gpu_mhz", "power_w" -> fps
            "cpu_usage", "ddr_mhz" -> cpuUsage
            "gpu_usage", "gpu_c", "ddr_mbps", "battery_ma" -> gpuUsage
            "cpu_c" -> temperature
            "battery_pct", "battery_used_pct", "battery_c" -> battery
            else -> core(fallbackIndex)
        }
    }
}
