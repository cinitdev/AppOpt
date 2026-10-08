package top.qixia.threads

/** 在数据边界统一单位，传感器缺失或无效不能表示成零。 */
object HistoryMetrics {
    data class Sample(val timestampMs: Long, val charging: Boolean?, val values: Map<String, Float>)
    data class Point(val progress: Float, val value: Float, val breakBefore: Boolean = false)
    data class Series(val key: String, val average: Float, val minimum: Float, val maximum: Float,
        val samples: Int, val points: List<Point>)
    data class FrequencyBucket(val minimum: Float, val maximum: Float, val percent: Float)
    data class Report(val series: Map<String, Series>, val samples: Int, val chargingSamples: Int,
        val durationMs: Long, val coverage: Float, val device: Map<String, String> = emptyMap(),
        val distributions: Map<String, List<FrequencyBucket>> = emptyMap())

    fun unit(key: String) = when {
        key.startsWith("cpu_mhz.") || key in setOf("gpu_mhz", "ddr_mhz") -> "MHz"
        key.startsWith("cpu_cycles.") || key.startsWith("cpu_cycles_core.") -> "M"
        key == "ddr_mbps" -> "Mbps"
        key == "battery_ma" -> "mA"
        key == "battery_v" -> "V"
        key.startsWith("frame_") -> "ms"
        key.endsWith("_c") -> "°C"
        key == "power_w" -> "W"
        else -> "%"
    }

    fun title(key: String) = when (key) {
        "power_w" -> "放电功率（估算）"
        "battery_c" -> "电池温度"
        "cpu_c" -> "CPU 温度"
        "gpu_c" -> "GPU 温度"
        "gpu_mhz" -> "GPU 频率"
        "cpu_usage" -> "CPU 总使用率"
        "gpu_usage" -> "GPU 使用率"
        "ddr_mhz" -> "DDR 频率"
        "ddr_mbps" -> "DDR 数据率"
        "battery_ma" -> "放电电流"
        "battery_v" -> "电池电压"
        "frame_max_ms" -> "最大帧间隔"
        "battery_pct" -> "剩余电量"
        else -> "CPU ${key.substringAfter('.').replace('_', '、')} " + when {
            key.startsWith("cpu_mhz.") -> "频率"
            key.startsWith("cpu_cycles.") || key.startsWith("cpu_cycles_core.") -> "每秒周期数"
            else -> "使用率"
        }
    }

    fun valid(key: String, value: Float): Boolean = value.isFinite() && when {
        key.startsWith("cpu_mhz.") && key.substringAfter("cpu_mhz.").matches(Regex("[0-9_]{1,80}")) -> value in 0f..10_000f
        (key.startsWith("cpu_cluster.") || key.startsWith("cpu_core.")) && key.substringAfter('.').matches(Regex("[0-9_]{1,80}")) -> value in 0f..100f
        (key.startsWith("cpu_cycles.") || key.startsWith("cpu_cycles_core.")) && key.substringAfter('.').matches(Regex("[0-9_]{1,80}")) -> value in 0f..20_000f
        key in setOf("gpu_mhz", "ddr_mhz") -> value in 0f..10_000f
        key == "ddr_mbps" -> value in 0f..30_000f
        key == "battery_ma" -> value in 0f..30_000f
        key == "battery_v" -> value in 2f..20f
        key == "frame_max_ms" -> value in 0.01f..10_000f
        key in setOf("battery_c", "cpu_c", "gpu_c") -> value in -10f..150f
        key == "power_w" -> value in 0f..100f
        key in setOf("cpu_usage", "gpu_usage", "battery_pct") -> value in 0f..100f
        else -> false
    }

    /** Rust 协议已统一单位，应用只负责校验、保存和绘制。 */
    internal fun decode(raw: Map<String, String>, timestamp: Long): Sample {
        val charging = when (raw["charging"]) { "1" -> true; "0" -> false; else -> null }
        val values = raw.mapNotNull { (key, text) ->
            text.toFloatOrNull()?.takeIf { valid(key, it) }?.let { key to it }
        }.toMap().toMutableMap()
        if (charging != false) { values.remove("power_w"); values.remove("battery_ma") }
        return Sample(timestamp, charging, values)
    }
}
