package top.qixia.threads.compose

import kotlin.math.abs

/** 仅选择展示内容，不创建历史会话或修改记录设置。 */
internal object HomeRecordSelector {
    const val MIN_DURATION_MS = 180_000L

    /** 最近记录仅收录单次运行超过三分钟的会话，边界与 Rust 摘要保持一致。 */
    fun hasRecordableDuration(start: Long, end: Long): Boolean =
        start >= 0L && end >= start && end - start > MIN_DURATION_MS

    fun select(reports: List<HomeRecord>, usage: List<RecentUsageReader.Entry>,
        configured: Map<String, Boolean>, app: (String) -> HistoryPackageModel): List<HomeRecord> {
        val records = reports.filter { it.report != null && hasRecordableDuration(it.startedAtMs, it.endedAtMs) }.toMutableList()
        for (entry in usage) {
            if (configured[entry.packageName] != true || !hasRecordableDuration(entry.startedAtMs, entry.endedAtMs)) continue
            val matching = if (entry.mode == HomeRecordMode.AUTOMATIC) records.indices.filter { index ->
                val candidate = records[index]
                candidate.app.packageName == entry.packageName && candidate.mode == HomeRecordMode.AUTOMATIC &&
                    candidate.report != null && sameWindow(candidate.startedAtMs, candidate.endedAtMs,
                        entry.startedAtMs, entry.endedAtMs, toleranceMs = 10000L, overlap = .90)
            } else emptyList()
            if (matching.size == 1) {
                val index = matching.single()
                val report = records[index]
                // 优先使用真实报告统计；只有确认属于同次使用的摘要
                // 才能补充缺失 FPS，不得使用无关的新一轮启动数据。
                records[index] = report.copy(fps = report.fps ?: entry.fps)
            } else {
                records += HomeRecord(app(entry.packageName), entry.mode, entry.startedAtMs, entry.endedAtMs, entry.fps)
            }
        }
        return records.sortedWith(compareByDescending<HomeRecord> { it.endedAtMs }
            .thenByDescending { it.report != null }.thenByDescending { it.startedAtMs })
            .distinctBy { it.app.packageName }.take(RecentUsageReader.MAX_PACKAGES)
    }

    /** 校准时间戳取整到秒，起止边界和覆盖范围都必须匹配。 */
    fun matchingCalibrationFps(start: Long, end: Long, fps: HomeFpsSummary?): HomeFpsSummary? =
        fps?.takeIf { it.average.isFinite() && it.average in 0f..1000f &&
            sameWindow(start, end, it.startedAtMs, it.endedAtMs, toleranceMs = 1000L, overlap = .95) }

    private fun sameWindow(firstStart: Long, firstEnd: Long, secondStart: Long, secondEnd: Long,
        toleranceMs: Long, overlap: Double): Boolean {
        if (firstStart <= 0 || secondStart <= 0 || firstEnd <= firstStart || secondEnd <= secondStart) return false
        if (abs(firstStart - secondStart) > toleranceMs || abs(firstEnd - secondEnd) > toleranceMs) return false
        val shared = (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0L).toDouble()
        return shared >= (firstEnd - firstStart) * overlap && shared >= (secondEnd - secondStart) * overlap
    }
}
