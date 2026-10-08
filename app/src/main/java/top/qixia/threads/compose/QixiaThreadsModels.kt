package top.qixia.threads.compose

import android.graphics.drawable.Drawable
import top.qixia.threads.CalibPolicy
import top.qixia.threads.DaemonBridge
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData

enum class MainDestination(val label: String) {
    HOME("首页"),
    APPLICATIONS("应用"),
    HISTORY("历史"),
    LOGS("日志"),
    SETTINGS("设置");

    companion object {
        // 已保存的导航状态可能指向应用更新后移除的页面。
        fun fromSavedName(name: String?): MainDestination = entries.firstOrNull { it.name == name } ?: HOME
    }
}

enum class ApplicationTab(val label: String) {
    LIBRARY("应用列表"),
    CONFIGURED("已配置")
}

enum class RecordsTab(val label: String) {
    HISTORY("历史"),
    LOGS("日志")
}

enum class LogSource(val label: String) {
    DAEMON("守护进程"),
    FOREGROUND("前台助手")
}

enum class LogFilter(val label: String) {
    ALL("全部"),
    ATTENTION("提醒"),
    ERROR("错误")
}

enum class LogLevel(val label: String) {
    INFO("信息"),
    SUCCESS("完成"),
    WARNING("提醒"),
    ERROR("错误")
}

enum class LogCategory(val label: String) {
    ALL("全部事件"), ALLOCATION("核心分配"), RULES("规则执行"),
    CALIBRATION("校准采集"), FPS("帧率监测"), FOREGROUND("前台识别"), SERVICE("服务与系统")
}

enum class AppRuleState {
    CONFIGURED,
    PENDING,
    MISSING
}

enum class AppComponentKind {
    APP, SYSTEM_COMPONENT, MISSING_APP
}

data class AppItemModel(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val icon: Drawable?,
    val configPackages: List<String> = listOf(packageName),
    val ruleCount: Int = 0,
    val cpuSummary: String = "CPU --",
    val state: AppRuleState = AppRuleState.CONFIGURED,
    val automaticAffinityEnabled: Boolean = false,
    val automaticAffinityDetail: String = "等待所选游戏进入前台",
    val unhealthyRuleCount: Int = 0,
    val averageFps: Float? = null,
    val fpsSampleCount: Long = 0L,
    val fpsSessionDurationMs: Long = 0L,
    val fpsSessionEndedAtMs: Long = 0L,
    // 自动模式暂时暂停这些规则时，仍保留手动规则视图。
    val manualRuleState: AppRuleState = state,
    val manualCpuSummary: String = cpuSummary,
    val manualUnhealthyRuleCount: Int = unhealthyRuleCount,
    val iconVersion: Long = 0L,
    val componentKind: AppComponentKind = if (installed) AppComponentKind.APP else AppComponentKind.MISSING_APP
) {
    val stableKey: String = "${state.name}:$packageName"
    val isSystemComponent: Boolean get() = componentKind == AppComponentKind.SYSTEM_COMPONENT
    val available: Boolean get() = installed || isSystemComponent
}

data class DeviceMetricsUiState(
    val cpuPercent: Int? = null,
    val memoryPercent: Int? = null,
    val temperatureCelsius: Float? = null,
    val batteryPercent: Int? = null,
    val activeTargetPackage: String? = null,
    val cpuHistory: List<Float> = emptyList(),
    val memoryHistory: List<Float> = emptyList(),
    val temperatureHistory: List<Float> = emptyList(),
    val batteryHistory: List<Float> = emptyList()
)

data class EnvironmentUiState(
    val automaticAffinitySupported: Boolean = false,
    val loading: Boolean = true,
    val hasRoot: Boolean = false,
    val pendingModuleUpdate: Boolean = false,
    val moduleVersion: DaemonBridge.ModuleVersion? = null,
    val moduleCompatible: Boolean = false,
    val daemonRuntime: DaemonBridge.DaemonRuntime = DaemonBridge.DaemonRuntime(false),
    val foregroundState: DaemonBridge.TaskForegroundState? = null,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val configuredAppCount: Int = 0,
    val ruleCount: Int = 0,
    val statusMessage: String? = null
) {
    val featuresAvailable: Boolean
        get() = hasRoot && !pendingModuleUpdate && moduleCompatible && daemonRuntime.running
}

data class ApplicationsUiState(
    val loading: Boolean = true,
    val catalogLoading: Boolean = false,
    val selectedTab: ApplicationTab = ApplicationTab.LIBRARY,
    val query: String = "",
    val configured: List<AppItemModel> = emptyList(),
    val addable: List<AppItemModel> = emptyList(),
    val hideMissing: Boolean = false,
    val autoStartCalibration: Boolean = false,
    val autoStartDelayMs: Long = 0L,
    val busyPackages: Set<String> = emptySet()
) {
    val visibleItems: List<AppItemModel>
        get() {
            val source = when (selectedTab) {
                ApplicationTab.LIBRARY -> {
                    // 应用归属以已保存配置为准，刷新期间也如此。
                    // 已安装应用模型不包含已添加或待处理状态。
                    val addedPackages = configured.mapTo(HashSet()) { it.packageName }
                    configured.filter { it.state == AppRuleState.PENDING } +
                        addable.filterNot { it.packageName in addedPackages }
                }
                ApplicationTab.CONFIGURED -> configured.filter { it.state != AppRuleState.PENDING }
            }
            val normalized = query.trim().lowercase()
            return source.distinctBy { it.packageName }.filter {
                (!hideMissing || it.available) && (normalized.isEmpty() ||
                    it.label.lowercase().contains(normalized) ||
                    it.packageName.lowercase().contains(normalized))
            }
        }
}

data class HistoryPackageModel(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val lastTime: Long,
    val sessionCount: Int,
    val averageFps: Float? = null,
    val fpsDurationMs: Long = 0L,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionFps: Map<Long, top.qixia.threads.HistoryFpsStore.Report> = emptyMap()
)

data class HistoryReportWindow(val index: Int, val startMs: Long, val endMs: Long)

data class HistoryDetailUiState(
    val packageName: String,
    val label: String,
    val loading: Boolean = true,
    val sessions: List<SessionSummary> = emptyList(),
    val expandedSessionId: Long? = null,
    val threads: List<ThreadData> = emptyList(),
    val threadsLoading: Boolean = false,
    val threadsError: String? = null,
    val icon: Drawable? = null,
    val sessionFps: Map<Long, top.qixia.threads.HistoryFpsStore.Report> = emptyMap(),
    val sessionMetrics: Map<Long, top.qixia.threads.HistoryMetrics.Report> = emptyMap(),
    val coreTimeline: top.qixia.threads.CoreTimelineReport? = null,
    val reportSession: SessionSummary? = null,
    val reportWindows: List<HistoryReportWindow> = emptyList(),
    val reportWindowIndex: Int = 0
)

data class HistoryUiState(
    val loading: Boolean = true,
    val packages: List<HistoryPackageModel> = emptyList(),
    val detail: HistoryDetailUiState? = null,
    val busySessionIds: Set<Long> = emptySet(),
    val busyPackages: Set<String> = emptySet(),
    val comparison: HistoryComparisonUiState? = null
)

data class LogEntryModel(
    val id: Long,
    val lineNumber: Int,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val copyText: String,
    val repeatCount: Int = 1,
    val timestampMs: Long? = null,
    val firstTimestampMs: Long? = timestampMs,
    val category: LogCategory = LogCategory.SERVICE,
    val title: String = tag,
    val fields: List<Pair<String, String>> = emptyList(),
    val startupId: Long? = null
)

data class LogsUiState(
    val selectedTab: RecordsTab = RecordsTab.HISTORY,
    val loading: Boolean = false,
    val source: LogSource = LogSource.DAEMON,
    val filter: LogFilter = LogFilter.ALL,
    val entries: List<LogEntryModel> = emptyList(),
    val loaded: Boolean = false,
    val category: LogCategory = LogCategory.ALL,
    val query: String = "",
    val updatedAtMs: Long? = null,
    val readError: String? = null
) {
    val warningCount = entries.count { it.level == LogLevel.WARNING }
    val errorCount = entries.count { it.level == LogLevel.ERROR }
    private val searchWords = query.trim().split(Regex("\\s+"))
    val visibleEntries: List<LogEntryModel> = entries.filter { entry ->
        (when (filter) {
            LogFilter.ALL -> true
            LogFilter.ATTENTION -> entry.level == LogLevel.WARNING || entry.level == LogLevel.ERROR
            LogFilter.ERROR -> entry.level == LogLevel.ERROR
        }) && (category == LogCategory.ALL || entry.category == category) &&
            searchWords.all { word ->
                word.isEmpty() || entry.message.contains(word, true) || entry.tag.contains(word, true) || entry.category.label.contains(word, true)
            }
        }
}

data class SettingsUiState(
    val loading: Boolean = true,
    val policy: CalibPolicy = CalibPolicy.DEFAULT,
    val hasRoot: Boolean = false,
    val moduleVersion: DaemonBridge.ModuleVersion? = null,
    val lockedByPendingUpdate: Boolean = false,
    val readSuccess: Boolean = false,
    val cpusetSupported: Boolean = false,
    val presentCpus: Set<Int> = emptySet(),
    val saving: Boolean = false,
    val savedPolicy: CalibPolicy = policy,
    val autoHistoryEnabled: Boolean = false,
    val autoHistorySaving: Boolean = false,
    val autoHistorySupported: Boolean = false
) {
    val editable: Boolean
        get() = hasRoot && readSuccess && !lockedByPendingUpdate
}

data class RuleEditorUiState(
    val app: AppItemModel,
    val originalLines: List<String>,
    val draft: String,
    val allowedCpus: Set<Int>,
    val loading: Boolean = false,
    val error: String? = null,
    val historyCandidates: List<top.qixia.threads.RuleHistoryCandidate> = emptyList(),
    val health: Map<String, DaemonBridge.RuleHealth> = emptyMap()
) {
    val dirty: Boolean get() = draft.trim() != originalLines.joinToString("\n").trim()
}

data class UpdateUiState(
    val checkingModule: Boolean = false,
    val moduleResult: ModuleUpdater.CheckResult? = null,
    val startupPromptPending: Boolean = false
)

data class QixiaThreadsUiState(
    val affinityDiagnostics: top.qixia.threads.AffinityDiagnosticsUiState? = null,
    val home: HomeUiState = HomeUiState(),
    val environment: EnvironmentUiState = EnvironmentUiState(),
    val applications: ApplicationsUiState = ApplicationsUiState(),
    val history: HistoryUiState = HistoryUiState(),
    val logs: LogsUiState = LogsUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val ruleEditor: RuleEditorUiState? = null,
    val updates: UpdateUiState = UpdateUiState(),
    val deviceMetrics: DeviceMetricsUiState = DeviceMetricsUiState(),
    val refreshing: Boolean = false,
    val message: String? = null
)
