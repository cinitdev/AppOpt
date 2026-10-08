package top.qixia.threads

/** 通过前缀区分版本的可选扩展，仍兼容旧守护进程的纯数值 FPS。 */
internal class HistoryFrameReport {
    private var maximum: Float? = null
    private var last = 0L
    @Synchronized fun accept(line: String, elapsed: Long): Boolean {
        if (!line.startsWith("frame ")) return false
        val value = line.removePrefix("frame ").toFloatOrNull()
        if (value != null && value.isFinite() && value in 0.01f..5000f) {
            if (elapsed - last > 3500) maximum = null
            maximum = maxOf(maximum ?: value, value)
            last = elapsed
        }
        return true
    }
    @Synchronized fun take(elapsed: Long): Float? {
        val value = maximum
        maximum = null
        return value?.takeIf { elapsed - last in 0..3500 }
    }
}
