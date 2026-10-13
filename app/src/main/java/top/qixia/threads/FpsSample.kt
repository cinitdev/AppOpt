package top.qixia.threads

/** NaN 只作为进程内的缺测通知；数值行必须是有限的非负数。 */
internal fun parseFpsChannelSample(line: String): Float? = when (val text = line.trim()) {
    "gap" -> Float.NaN
    else -> text.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
}

/** 静止页面的真实 0 FPS 仍然保留；采集缺失和过期缓存不补成 0。 */
internal fun fpsSampleForHistory(fps: Float, updatedAt: Long, now: Long): Float? =
    fps.takeIf { it.isFinite() && it >= 0f && updatedAt > 0L && now - updatedAt in 0L..3500L }
