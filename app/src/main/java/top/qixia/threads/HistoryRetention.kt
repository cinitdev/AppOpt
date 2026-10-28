package top.qixia.threads

/** 按记录结束时间保留七天，两种来源共用期限，不按条数淘汰。 */
internal object HistoryRetention {
    const val DAYS = 7
    const val MAX_AGE_MS = DAYS * 24 * 60 * 60 * 1000L
    fun cutoff(nowMs: Long) = (nowMs - MAX_AGE_MS).coerceAtLeast(0L)
    fun expired(endedAtMs: Long, nowMs: Long = System.currentTimeMillis()) =
        endedAtMs > 0L && endedAtMs < cutoff(nowMs)

    /** 只处理应用自己的归档；Root 收件目录仍由导入确认和 Rust 保留策略管理。 */
    fun pruneLocal(context: android.content.Context) {
        val now = System.currentTimeMillis()
        val db = top.qixia.threads.db.QixiaThreadsDbHelper.getInstance(context)
        val before = db.getPackagesWithHistory().associate { it.pkg to db.getSessionSummariesByPackage(it.pkg) }
        db.pruneHistory(now)
        before.forEach { (pkg, sessions) ->
            val removed = sessions.filter { expired(it.endedAtMs, now) }.map { it.epoch to it.durationMs }
            if (removed.isNotEmpty()) {
                val retained = sessions.filterNot { expired(it.endedAtMs, now) }.map { it.epoch to it.durationMs }
                HistoryFpsStore.removeSessions(context.filesDir, pkg, removed, retained)
                HistoryMetricsStore.removeSessions(context.filesDir, pkg, removed, retained)
            }
        }
        AutoHistoryStore.pruneHistory(context.filesDir, now) { false }
        HistoryFpsStore.prune(context.filesDir, db.calibrationWindows(), now)
        HistoryMetricsStore.pruneHistory(context.filesDir, now)
    }
}
