package top.qixia.threads.compose

import top.qixia.threads.db.SessionSummary

enum class HomeRecordMode(val label: String) {
    CALIBRATION("校准"), AUTOMATIC("自动分配"), RULES("规则应用")
}

/** 使用摘要不表示已经采集或导入完整报告。 */
data class HomeRecord(
    val app: HistoryPackageModel,
    val mode: HomeRecordMode,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val fps: HomeFpsSummary? = null,
    val report: SessionSummary? = null
)

data class HomeFpsPoint(val timestampMs: Long, val fps: Float, val breakBefore: Boolean = false)

data class HomeFpsSummary(
    val average: Float,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val peak: Float? = null,
    val points: List<HomeFpsPoint> = emptyList()
)

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val records: List<HomeRecord> = emptyList()
)
