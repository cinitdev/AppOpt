package top.qixia.threads

import java.util.Locale

object AutoStartCalibrationDelay {
    const val MAX_DELAY_MS = 60_000L

    fun normalize(delayMs: Long): Long = delayMs.coerceIn(0L, MAX_DELAY_MS)

    fun parse(value: CharSequence?): Long? {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.toLongOrNull()?.takeIf { it in 0L..MAX_DELAY_MS }
    }

    fun label(delayMs: Long): String {
        val normalized = normalize(delayMs)
        if (normalized < 1_000L) return "$normalized 毫秒"
        val seconds = normalized / 1_000.0
        return if (normalized % 1_000L == 0L) {
            "${normalized / 1_000L} 秒"
        } else {
            val value = String.format(Locale.US, "%.3f", seconds)
                .trimEnd('0')
                .trimEnd('.')
            "$value 秒"
        }
    }

    fun summary(delayMs: Long): String {
        val normalized = normalize(delayMs)
        return if (normalized == 0L) {
            "检测到目标应用后立即开始校准"
        } else {
            "检测到目标应用后，将在 ${label(normalized)}后自动开始校准"
        }
    }
}
