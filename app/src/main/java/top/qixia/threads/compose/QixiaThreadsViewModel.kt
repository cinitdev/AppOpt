package top.qixia.threads.compose

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.qixia.threads.CalibPolicy
import top.qixia.threads.DaemonBridge

class QixiaThreadsViewModel @JvmOverloads constructor(
    application: Application,
    autoRefreshOnInit: Boolean = true
) : AndroidViewModel(application) {
    private val repository = QixiaThreadsRepository(application)
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        QixiaThreadsUiState(
            applications = ApplicationsUiState(
                hideMissing = preferences.getBoolean(PREF_HIDE_MISSING, false),
                autoStartCalibration = preferences.getBoolean(PREF_AUTO_START, false),
                autoStartDelayMs = preferences.getLong(PREF_AUTO_DELAY, 0L)
            )
        )
    )
    val state: StateFlow<QixiaThreadsUiState> = _state.asStateFlow()

    private var dashboardGeneration = 0L
    private var dashboardJob: Job? = null
    private var dashboardRefreshPending = false
    private var catalogJob: Job? = null
    private var historyLoaded = false
    private var historyImportJob: Job? = null
    private var historyListJob: Job? = null
    private var historyRefreshPending = false
    private var settingsLoaded = false
    private var settingsJob: Job? = null
    private var ruleFormatSyncJob: Job? = null
    private val moduleUpdateChecks = ModuleUpdateCheckController(viewModelScope, repository::checkModuleUpdate)
    private var logGeneration = 0L
    private var logJob: Job? = null
    private val logReadMutex = Mutex()
    private var ruleGeneration = 0L
    private var historyGeneration = 0L
    private var historyDetailGeneration = 0L
    private var metricsJob: Job? = null
    private var homeGeneration = 0L
    private var uiVisible = false
    private var visibleDestination = MainDestination.HOME
    val calibrationReviews = CalibrationReviewController(application, viewModelScope) { refreshDashboard() }

    init {
        viewModelScope.launch {
            moduleUpdateChecks.state.collect { updates -> _state.update { it.copy(updates = updates) } }
        }
        // 隔离的设置回归测试可跳过无关的历史导入。
        if (autoRefreshOnInit) refreshDashboard()
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        updateMetricsMonitoring()
    }

    private fun updateMetricsMonitoring() {
        // 仅可见首页需要实时设备指标。
        val showsMetrics = visibleDestination == MainDestination.HOME
        if (!uiVisible || !showsMetrics) {
            metricsJob?.cancel()
            metricsJob = null
        } else if (metricsJob?.isActive != true) {
            metricsJob = monitorDeviceMetrics()
        }
    }

    private fun monitorDeviceMetrics(): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val recentSummary = java.io.File(getApplication<Application>().filesDir, "capture/recent_usage.tsv")
            var summaryStamp = recentSummary.lastModified() to recentSummary.length()
            while (isActive) {
                runCatching(repository::readDeviceMetrics).onSuccess { snapshot ->
                    _state.update { current ->
                        val previous = current.deviceMetrics
                        current.copy(
                            deviceMetrics = previous.copy(
                                cpuPercent = snapshot.cpuPercent ?: previous.cpuPercent,
                                memoryPercent = snapshot.memoryPercent,
                                temperatureCelsius = snapshot.temperatureCelsius,
                                batteryPercent = snapshot.batteryPercent,
                                activeTargetPackage = snapshot.activeTargetPackage,
                                cpuHistory = appendMetric(previous.cpuHistory, snapshot.cpuPercent?.toFloat()),
                                memoryHistory = appendMetric(previous.memoryHistory, snapshot.memoryPercent?.toFloat()),
                                temperatureHistory = appendMetric(
                                    previous.temperatureHistory,
                                    snapshot.temperatureCelsius
                                ),
                                batteryHistory = appendMetric(
                                    previous.batteryHistory,
                                    snapshot.batteryPercent?.toFloat()
                                )
                            )
                        )
                    }
                }
                // 守护进程可能在 onResume 之后才结束上一轮前台会话。
                // 复用首页可见时的刷新周期，低成本检查文件标记；
                // 不为刷新最近 FPS 摘要而导入历史或新增轮询任务。
                val nextStamp = recentSummary.lastModified() to recentSummary.length()
                if (nextStamp != summaryStamp) {
                    summaryStamp = nextStamp
                    withContext(Dispatchers.Main) { loadHome() }
                }
                delay(2_000L)
            }
        }

    private fun appendMetric(history: List<Float>, value: Float?): List<Float> {
        if (value == null || !value.isFinite()) return history
        return (history + value).takeLast(18)
    }

    fun refreshDashboard() {
        if (dashboardJob?.isActive == true) {
            dashboardRefreshPending = true
            return
        }
        calibrationReviews.refresh()
        loadHome()
        if (visibleDestination == MainDestination.APPLICATIONS) loadApplicationCatalog()
        _state.update {
            it.copy(
                refreshing = true,
                applications = it.applications.copy(loading = it.environment.loading)
            )
        }
        dashboardJob = viewModelScope.launch {
            do {
                dashboardRefreshPending = false
                val generation = dashboardGeneration
                val result = withContext(Dispatchers.IO) { runCatching(repository::loadDashboard) }
                result.fold(
                    onSuccess = { snapshot ->
                        _state.update { current ->
                            // 在修改前或修改期间开始的读取，不得撤销已提交的 UI 状态。
                            val apps = if (generation == dashboardGeneration && current.applications.busyPackages.isEmpty()) {
                                current.applications.copy(loading = false, configured = snapshot.configured)
                            } else current.applications.copy(loading = false)
                            current.copy(
                                refreshing = false,
                                environment = snapshot.environment.copy(configuredAppCount = apps.configured.size,
                                    ruleCount = apps.configured.sumOf(AppItemModel::ruleCount)),
                                applications = apps
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update { current ->
                            current.copy(
                                refreshing = false,
                                environment = current.environment.copy(
                                    loading = false,
                                    statusMessage = error.message ?: "运行环境读取失败"
                                ),
                                applications = current.applications.copy(loading = false),
                                message = "刷新失败：${error.message ?: "请稍后重试"}"
                            )
                        }
                    }
                )
            } while (dashboardRefreshPending)
            // 首次读取首页时，配置快照可能尚未准备好。
            loadHome()
            // 首页已可用后启动独立后台任务，网络请求不占用首页加载流程。
            moduleUpdateChecks.checkOnStartup(_state.value.environment)
            importHistoryInBackground()
            synchronizeRuleFormatInBackground()
        }
    }

    /** 每次刷新或恢复时后台检查一次，首屏不等待规则转换。 */
    private fun synchronizeRuleFormatInBackground() {
        val environment = _state.value.environment
        // 守护进程停止时仍可编辑规则；不能因运行状态不可用
        // 而让复制进来的配置保留错误语法。
        if (!environment.hasRoot || environment.pendingModuleUpdate || !environment.moduleCompatible ||
            ruleFormatSyncJob?.isActive == true) return
        ruleFormatSyncJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(DaemonBridge::synchronizeRuleOutputFormat) }
            val sync = result.getOrNull()
            if (sync?.success == true) {
                if (sync.changed) {
                    settingsLoaded = false
                    if (visibleDestination == MainDestination.SETTINGS) loadSettings(force = true)
                }
            } else {
                _state.update { it.copy(message = sync?.detail ?: result.exceptionOrNull()?.message
                    ?: "规则格式同步失败，请在设置中重试") }
            }
        }
    }

    private fun importHistoryInBackground() {
        if (historyImportJob?.isActive == true) return
        historyImportJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching(repository::importHistory) }
            historyLoaded = false
            loadHome()
            if (visibleDestination == MainDestination.HISTORY) refreshHistoryList(force = true)
        }
    }

    private fun loadApplicationCatalog() {
        if (catalogJob?.isActive == true) return
        _state.update { it.copy(applications = it.applications.copy(catalogLoading = true)) }
        catalogJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(repository::loadInstalledApps) }
            _state.update { current -> current.copy(
                applications = current.applications.copy(catalogLoading = false,
                    addable = result.getOrDefault(current.applications.addable)),
                message = result.exceptionOrNull()?.let { "应用目录读取失败，请刷新重试" } ?: current.message
            ) }
        }
    }

    private fun loadHome() {
        val generation = ++homeGeneration
        val configured = _state.value.applications.configured
        _state.update { it.copy(home = it.home.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.loadHome(configured) }
            withContext(Dispatchers.Main) {
                if (generation != homeGeneration) return@withContext
                _state.update { current ->
                    current.copy(home = result.getOrElse {
                        current.home.copy(loading = false, error = "最近记录读取失败，下拉重试")
                    })
                }
            }
        }
    }

    fun onDestinationShown(destination: MainDestination) {
        visibleDestination = destination
        updateMetricsMonitoring()
        when (destination) {
            MainDestination.HISTORY -> if (!historyLoaded) loadHistory()
            MainDestination.LOGS -> if (!_state.value.logs.loaded || System.currentTimeMillis() - (_state.value.logs.updatedAtMs ?: 0L) >= 5000L) loadLogs(force = true)
            MainDestination.SETTINGS -> {
                if (!settingsLoaded) loadSettings()
            }
            MainDestination.APPLICATIONS -> loadApplicationCatalog()
            MainDestination.HOME -> Unit
        }
    }

    fun selectApplicationTab(tab: ApplicationTab) {
        _state.update { it.copy(applications = it.applications.copy(selectedTab = tab, query = "")) }
    }

    fun setApplicationQuery(query: String) {
        _state.update { it.copy(applications = it.applications.copy(query = query)) }
    }

    fun selectRecordsTab(tab: RecordsTab) {
        _state.update { it.copy(logs = it.logs.copy(selectedTab = tab)) }
        if (tab == RecordsTab.LOGS && !_state.value.logs.loaded) loadLogs()
    }

    fun selectLogSource(source: LogSource) {
        if (_state.value.logs.source == source) return
        _state.update {
            it.copy(logs = LogsUiState(source = source))
        }
        loadLogs(force = true)
    }

    fun selectLogFilter(filter: LogFilter) {
        _state.update { it.copy(logs = it.logs.copy(filter = filter)) }
    }

    fun selectLogCategory(category: LogCategory) {
        _state.update { it.copy(logs = it.logs.copy(category = category)) }
    }

    fun setLogQuery(query: String) {
        _state.update { it.copy(logs = it.logs.copy(query = query.take(256))) }
    }

    fun loadLogs(force: Boolean = false) {
        val current = _state.value.logs
        if (current.loading || current.loaded && !force) return
        val source = current.source
        val generation = ++logGeneration
        logJob?.cancel()
        _state.update { it.copy(logs = it.logs.copy(loading = true, readError = null)) }
        logJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { logReadMutex.withLock { repository.loadLogs(source) } }
            withContext(Dispatchers.Main) {
                if (generation != logGeneration || _state.value.logs.source != source) return@withContext
                _state.update {
                    it.copy(
                        logs = it.logs.copy(
                            loading = false,
                            entries = result.getOrElse { _ -> it.logs.entries },
                            loaded = result.isSuccess || it.logs.loaded,
                            updatedAtMs = if (result.isSuccess) System.currentTimeMillis() else it.logs.updatedAtMs,
                            readError = result.exceptionOrNull()?.message
                        ),
                        message = result.exceptionOrNull()?.let { error ->
                            "${source.label}日志读取失败：${error.message ?: "Root 不可用"}"
                        }
                    )
                }
            }
        }
    }

    fun setHideMissing(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_HIDE_MISSING, enabled).apply()
        _state.update { it.copy(applications = it.applications.copy(hideMissing = enabled)) }
    }

    fun setAutoStartCalibration(enabled: Boolean, delayMs: Long? = null) {
        val normalizedDelay = (delayMs ?: _state.value.applications.autoStartDelayMs).coerceIn(0L, 60_000L)
        preferences.edit()
            .putBoolean(PREF_AUTO_START, enabled)
            .putLong(PREF_AUTO_DELAY, normalizedDelay)
            .apply()
        _state.update {
            it.copy(applications = it.applications.copy(
                autoStartCalibration = enabled,
                autoStartDelayMs = normalizedDelay
            ))
        }
    }

    fun addApplication(app: AppItemModel) = runPackageAction(app.packageName, "正在添加应用",
        onSuccess = { it.applicationAdded(app) }) {
        check(repository.addAutoPackage(app.packageName)) { "写入配置失败，请检查 Root 权限" }
        "已添加 ${app.label}，点击启动即可校准；点击应用可开启自动分配"
    }

    fun deleteApplication(app: AppItemModel) = runPackageAction(app.packageName, "正在删除配置",
        onSuccess = { it.applicationDeleted(app.packageName) }) {
        check(repository.deleteConfigPackages(app.configPackages)) { "删除失败，请检查 Root 权限" }
        "已删除 ${app.label} 的配置"
    }

    fun setAutomaticAffinity(app: AppItemModel, enabled: Boolean) =
        runPackageAction(app.packageName, "正在更新自动核心分配",
            onSuccess = { it.automaticModeChanged(app.packageName, enabled) }) {
            check(repository.setAutomaticAffinity(app.packageName, enabled)) { "保存失败，请确认已安装支持自动分配的新模块" }
            if (enabled) "已开启 ${app.label} 的自动分配，直接进入游戏即可" else "已关闭自动分配，恢复原有规则模式"
        }

    private fun runPackageAction(
        pkg: String,
        busyMessage: String,
        onSuccess: (ApplicationsUiState) -> ApplicationsUiState = { it },
        action: () -> String
    ) {
        if (!_state.value.environment.featuresAvailable) {
            _state.update { it.copy(message = "运行环境尚未就绪，暂不能修改配置") }
            return
        }
        if (pkg in _state.value.applications.busyPackages) return
        _state.update {
            it.copy(
                applications = it.applications.copy(
                    busyPackages = it.applications.busyPackages + pkg
                ),
                message = busyMessage
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching(action)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) dashboardGeneration++
                _state.update {
                    val apps = (if (result.isSuccess) onSuccess(it.applications) else it.applications).copy(
                        busyPackages = it.applications.busyPackages - pkg
                    )
                    it.copy(
                        applications = apps,
                        environment = it.environment.copy(configuredAppCount = apps.configured.size,
                            ruleCount = apps.configured.sumOf(AppItemModel::ruleCount)),
                        message = result.fold({ message -> message }, { error -> error.message ?: "操作失败" })
                    )
                }
                if (result.isSuccess) loadHome()
            }
        }
    }

    fun openRuleEditor(app: AppItemModel) {
        if (_state.value.ruleEditor != null) return
        val generation = ++ruleGeneration
        _state.update {
            it.copy(ruleEditor = RuleEditorUiState(app, emptyList(), "", emptySet(), loading = true))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.loadRuleEditor(app) }
            withContext(Dispatchers.Main) {
                if (generation != ruleGeneration) return@withContext
                _state.update {
                    it.copy(
                        ruleEditor = result.getOrNull(),
                        message = result.exceptionOrNull()?.let { error ->
                            "读取规则失败：${error.message ?: "未知错误"}"
                        }
                    )
                }
            }
        }
    }

    fun updateRuleDraft(value: String) {
        _state.update { current ->
            current.copy(ruleEditor = current.ruleEditor?.copy(draft = value, error = null))
        }
    }

    fun closeRuleEditor() {
        ruleGeneration++
        _state.update { it.copy(ruleEditor = null) }
    }

    fun recheckRules() {
        val editor = _state.value.ruleEditor ?: return
        if (editor.loading || editor.dirty || editor.app.automaticAffinityEnabled) return
        _state.update { it.copy(ruleEditor = editor.copy(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                check(DaemonBridge.requestRuleHealthReset(editor.app.packageName)) { "提交复检请求失败" }
                DaemonBridge.markRuleHealthResetPending(editor.health, editor.app.packageName)
            }
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    if (current.ruleEditor?.app?.packageName != editor.app.packageName) current
                    else current.copy(ruleEditor = editor.copy(health = result.getOrDefault(editor.health), loading = false),
                        message = result.fold({ "已标记待复检，请运行目标应用以重新检查匹配" }, { it.message ?: "复检失败" }))
                }
            }
        }
    }

    fun saveRuleEditor() {
        val editor = _state.value.ruleEditor ?: return
        if (editor.loading) return
        _state.update { it.copy(ruleEditor = editor.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.saveRules(editor)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _state.update { it.copy(ruleEditor = null, message = if (editor.app.automaticAffinityEnabled)
                        "规则已保存，关闭自动分配后恢复使用" else "规则已保存") }
                    refreshDashboard()
                } else {
                    _state.update {
                        it.copy(ruleEditor = editor.copy(
                            loading = false,
                            error = result.exceptionOrNull()?.message ?: "保存失败"
                        ))
                    }
                }
            }
        }
    }

    fun loadHistory(force: Boolean = false) {
        if (force) importHistoryInBackground()
        refreshHistoryList(force)
    }

    private fun refreshHistoryList(force: Boolean = false) {
        if (historyListJob?.isActive == true) {
            if (force) historyRefreshPending = true
            return
        }
        val generation = ++historyGeneration
        _state.update { it.copy(history = it.history.copy(loading = true)) }
        historyListJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching(repository::loadHistory)
            val recording = if (!settingsLoaded) runCatching { DaemonBridge.readAutomaticHistory() }.getOrNull() else null
            withContext(Dispatchers.Main) {
                if (generation != historyGeneration) return@withContext
                historyLoaded = result.isSuccess
                _state.update {
                    it.copy(
                        history = it.history.copy(
                            loading = false,
                            packages = result.getOrDefault(it.history.packages)
                        ),
                        settings = if (recording != null && !settingsLoaded && !it.settings.autoHistorySaving)
                            it.settings.copy(autoHistoryEnabled = recording.enabled, autoHistorySupported = recording.supported)
                            else it.settings,
                        message = result.exceptionOrNull()?.let { error ->
                            "历史记录加载失败：${error.message ?: "数据库不可用"}"
                        }
                    )
                }
                historyListJob = null
                if (historyRefreshPending) {
                    historyRefreshPending = false
                    refreshHistoryList(force = true)
                }
            }
        }
    }

    fun openHistoryPackage(item: HistoryPackageModel) = openHistory(item, null)

    fun openHistoryRecord(item: HistoryPackageModel, session: top.qixia.threads.db.SessionSummary) =
        openHistory(item, session.id)

    private fun openHistory(item: HistoryPackageModel, sessionId: Long?) {
        val source = item.sessions.firstOrNull { it.id == sessionId }?.source
            ?: item.sessions.firstOrNull()?.source
        val generation = ++historyDetailGeneration
        _state.update {
            it.copy(history = it.history.copy(
                detail = HistoryDetailUiState(item.packageName, item.label, loading = true, icon = item.icon)
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                repository.loadSessions(item.packageName).filter { source == null || it.source == source }
            }
            withContext(Dispatchers.Main) {
                if (generation != historyDetailGeneration) return@withContext
                _state.update { current ->
                    val detail = current.history.detail
                    if (detail?.packageName != item.packageName) current else current.copy(
                        history = current.history.copy(
                            detail = detail.copy(
                                loading = false,
                                sessions = result.getOrNull().orEmpty()
                            )
                        ),
                        message = result.exceptionOrNull()?.let { "读取会话失败：${it.message}" }
                    )
                }
                result.getOrNull()?.let { sessions ->
                    (sessions.firstOrNull { it.id == sessionId } ?: sessions.firstOrNull())?.let(::toggleHistorySession)
                }
            }
        }
    }

    private var diagnosticsJob: kotlinx.coroutines.Job? = null
    fun openAffinityDiagnostics(app: AppItemModel) {
        diagnosticsJob?.cancel()
        val initial = top.qixia.threads.AffinityDiagnosticsUiState(app.packageName, app.label)
        _state.update { it.copy(affinityDiagnostics = initial) }
        diagnosticsJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { top.qixia.threads.AffinityDiagnostics.read(app.packageName) }
            if (coroutineContext[kotlinx.coroutines.Job]?.isActive != true) return@launch
            _state.update { current -> if (current.affinityDiagnostics != initial) current else current.copy(
                affinityDiagnostics = initial.copy(loading = false, report = result.getOrNull(), error = result.exceptionOrNull()?.message)) }
        }
    }
    fun closeAffinityDiagnostics() {
        diagnosticsJob?.cancel()
        _state.update { it.copy(affinityDiagnostics = null) }
    }
    fun refreshAffinityDiagnostics() {
        val selected = _state.value.affinityDiagnostics ?: return
        openAffinityDiagnostics(_state.value.applications.configured.firstOrNull { it.packageName == selected.packageName }
            ?: AppItemModel(selected.packageName, selected.label, true, null))
    }

    private var comparisonJob: kotlinx.coroutines.Job? = null

    fun compareHistory(pkg: String, first: top.qixia.threads.db.SessionSummary, second: top.qixia.threads.db.SessionSummary) {
        val app = _state.value.history.packages.firstOrNull { it.packageName == pkg } ?: return
        if (first.id == second.id || listOf(first, second).any { candidate -> app.sessions.none { it.id == candidate.id } }) return
        comparisonJob?.cancel()
        val initial = HistoryComparisonUiState(pkg, app.label, first, second)
        _state.update { it.copy(history = it.history.copy(comparison = initial)) }
        comparisonJob = viewModelScope.launch(Dispatchers.IO) {
            val job = coroutineContext[kotlinx.coroutines.Job]!!
            val result = runCatching { listOf(first, second).map { session ->
                repository.compareHistorySummary(pkg, session) { if (!job.isActive) throw kotlinx.coroutines.CancellationException() }
            } }
            if (!job.isActive) return@launch
            _state.update { current ->
                if (current.history.comparison != initial) current else current.copy(history = current.history.copy(
                    comparison = initial.copy(loading = false, summaries = result.getOrNull().orEmpty(),
                        error = result.exceptionOrNull()?.message)))
            }
        }
    }

    fun closeHistoryComparison() {
        comparisonJob?.cancel()
        _state.update { it.copy(history = it.history.copy(comparison = null)) }
    }

    fun closeHistoryDetail() {
        closeHistoryComparison()
        ++historyDetailGeneration
        _state.update { it.copy(history = it.history.copy(detail = null)) }
    }

    fun toggleHistorySession(session: top.qixia.threads.db.SessionSummary) {
        val detail = _state.value.history.detail ?: return
        if (detail.expandedSessionId == session.id && detail.threadsError == null) return
        loadHistoryReportWindow(session, if (detail.expandedSessionId == session.id) detail.reportWindowIndex else 0)
    }

    fun selectHistoryWindow(index: Int) {
        val detail = _state.value.history.detail ?: return
        if (index !in detail.reportWindows.indices ||
            (index == detail.reportWindowIndex && detail.threadsError == null)) return
        val session = detail.sessions.firstOrNull { it.id == detail.expandedSessionId } ?: return
        loadHistoryReportWindow(session, index)
    }

    private fun loadHistoryReportWindow(session: top.qixia.threads.db.SessionSummary, windowIndex: Int) {
        val detail = _state.value.history.detail ?: return
        val generation = ++historyDetailGeneration
        _state.update { it.copy(history = it.history.copy(
            detail = detail.copy(expandedSessionId = session.id, threads = emptyList(), threadsLoading = true,
                loading = true, threadsError = null, sessionFps = emptyMap(), sessionMetrics = emptyMap(), coreTimeline = null,
                reportSession = null, reportWindowIndex = windowIndex,
                reportWindows = if (detail.expandedSessionId == session.id) detail.reportWindows else emptyList())
        )) }
        viewModelScope.launch(Dispatchers.IO) {
            // 只解析选中的记录；此处加载所有长记录会浪费内存和 CPU，
            // 还会在线程读取前挤出共享解析缓存。
            val result = runCatching {
                repository.loadHistorySessionDetail(detail.packageName, session, windowIndex)
            }
            withContext(Dispatchers.Main) {
                if (generation != historyDetailGeneration) return@withContext
                _state.update { current ->
                    val latest = current.history.detail
                    if (latest?.expandedSessionId != session.id) current else current.copy(
                        history = current.history.copy(
                            detail = latest.copy(threads = result.getOrNull()?.threads.orEmpty(), threadsLoading = false,
                                loading = false, sessionFps = result.getOrNull()?.fps.orEmpty(),
                                sessionMetrics = result.getOrNull()?.metrics.orEmpty(),
                                coreTimeline = result.getOrNull()?.coreTimeline,
                                reportSession = result.getOrNull()?.session,
                                reportWindows = result.getOrNull()?.windows ?: latest.reportWindows,
                                threadsError = result.exceptionOrNull()?.message)
                        ),
                        message = result.exceptionOrNull()?.let { "线程记录读取失败：${it.message}" }
                    )
                }
            }
        }
    }

    fun deleteHistoryPackage(item: HistoryPackageModel) {
        if (item.packageName in _state.value.history.busyPackages) return
        val source = item.sessions.firstOrNull()?.source ?: return
        _state.update { it.copy(history = it.history.copy(busyPackages = it.history.busyPackages + item.packageName)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.deleteHistoryPackage(item.packageName, source) }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(history = it.history.copy(busyPackages = it.history.busyPackages - item.packageName),
                        message = if (result.getOrDefault(false)) "历史记录已删除" else "历史记录删除失败，已保留本地记录")
                }
                loadHistory(force = true)
                loadHome()
            }
        }
    }

    fun deleteHistorySession(sessionId: Long) = deleteHistorySessions(setOf(sessionId))

    fun deleteHistorySessions(sessionIds: Set<Long>) {
        val history = _state.value.history
        val packageById = history.packages.flatMap { app -> app.sessions.map { it.id to app.packageName } }.toMap() +
            history.detail?.let { detail -> detail.sessions.associate { it.id to detail.packageName } }.orEmpty()
        val selected = sessionIds.filterTo(linkedSetOf()) { id ->
            id in packageById && id !in history.busySessionIds && packageById[id] !in history.busyPackages
        }
        if (selected.isEmpty()) return
        val packages = selected.mapNotNullTo(mutableSetOf()) { packageById[it] }
        _state.update { it.copy(history = it.history.copy(
            busySessionIds = it.history.busySessionIds + selected,
            busyPackages = it.history.busyPackages + packages
        )) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.deleteHistorySessions(selected) }
            val deleted = result.getOrNull()?.deletedIds.orEmpty()
            val failed = selected - deleted
            withContext(Dispatchers.Main) {
                if (_state.value.history.detail?.expandedSessionId in deleted) ++historyDetailGeneration
                _state.update { current ->
                    val detail = current.history.detail
                    val remaining = detail?.sessions?.filterNot { it.id in deleted }.orEmpty()
                    current.copy(
                        history = current.history.copy(
                            busySessionIds = current.history.busySessionIds - selected,
                            busyPackages = current.history.busyPackages - packages,
                            detail = if (detail?.expandedSessionId in deleted) null
                                else detail?.copy(sessions = remaining)
                        ),
                        message = when {
                            failed.isEmpty() -> "已删除 ${deleted.size} 条记录"
                            deleted.isNotEmpty() -> "已删除 ${deleted.size} 条，${failed.size} 条未能删除，请重试"
                            else -> "删除失败，记录已保留，请重试"
                        }
                    )
                }
                loadHistory(force = true)
                loadHome()
            }
        }
    }

    fun loadSettings(force: Boolean = false) {
        if (_state.value.settings.saving || _state.value.settings.autoHistorySaving || settingsJob?.isActive == true) return
        if (_state.value.settings.loading && settingsLoaded && !force) return
        _state.update { it.copy(settings = it.settings.copy(loading = true)) }
        settingsJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching(repository::loadPolicy)
            withContext(Dispatchers.Main) {
                settingsLoaded = result.isSuccess
                _state.update {
                    it.copy(
                        settings = result.getOrDefault(it.settings.copy(loading = false)),
                        message = result.exceptionOrNull()?.let { error ->
                            "策略读取失败：${error.message ?: "Root 不可用"}"
                        }
                    )
                }
            }
        }
    }

    fun updatePolicy(transform: (CalibPolicy) -> CalibPolicy) {
        val settings = _state.value.settings
        if (!settings.editable || settings.loading || settings.saving || settings.autoHistorySaving) return
        val policy = transform(settings.policy)
        val validationError = PolicyEditorLogic.validate(policy, settings.presentCpus)
        if (validationError != null) {
            _state.update { it.copy(message = validationError) }
            return
        }
        // 选择相同值或恢复默认时，也要修复外部替换的规则语法。
        _state.update { it.copy(settings = it.settings.copy(policy = policy, saving = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.savePolicy(policy, settings.savedPolicy) }
            withContext(Dispatchers.Main) {
                _state.update {
                    val persisted = if (result.isSuccess) policy else settings.savedPolicy
                    it.copy(
                        settings = it.settings.copy(saving = false, policy = persisted, savedPolicy = persisted),
                        message = result.getOrElse { error -> error.message ?: "保存失败，已恢复原设置，请重试" }
                    )
                }
            }
        }
    }

    fun setAutomaticHistory(enabled: Boolean) {
        val settings = _state.value.settings
        if (!settings.editable || !settings.autoHistorySupported || settings.loading ||
            settings.saving || settings.autoHistorySaving || settings.autoHistoryEnabled == enabled) return
        _state.update { it.copy(settings = it.settings.copy(autoHistorySaving = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { DaemonBridge.setAutomaticHistory(enabled) }
            withContext(Dispatchers.Main) {
                val saved = result.getOrDefault(false)
                _state.update { it.copy(
                    settings = it.settings.copy(autoHistorySaving = false,
                        autoHistoryEnabled = if (saved) enabled else it.settings.autoHistoryEnabled),
                    message = if (!saved) "记录开关保存失败，请检查模块状态后重试"
                        else if (enabled) "已开启：前台使用超过 3 分钟才保存自动分配记录"
                        else "已关闭自动分配记录，已有历史保留"
                ) }
            }
        }
    }

    fun restoreDefaultPolicy() {
        updatePolicy { current ->
            CalibPolicy.parse(current.detectedTopologyBlock)
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.exportDiagnostics()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(message = result.fold(
                        onSuccess = { path -> "诊断包已导出到 $path" },
                        onFailure = { error -> "导出失败：${error.message ?: "无法写入文件"}" }
                    ))
                }
            }
        }
    }

    fun checkModuleUpdate() {
        moduleUpdateChecks.checkManually()
    }

    fun dismissModuleUpdatePrompt() {
        moduleUpdateChecks.dismissPrompt()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private var exporting = false

    private fun runExport(action: () -> String) {
        if (exporting) return
        exporting = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching(action)
            withContext(Dispatchers.Main) {
                exporting = false
                _state.update { it.copy(message = result.fold({ path -> "已导出到 $path" }, { error -> "导出失败：${error.message}" })) }
            }
        }
    }

    fun exportHistoryPackage(item: HistoryPackageModel) = runExport {
        repository.exportHistory(item.packageName, item.label, source = item.sessions.firstOrNull()?.source)
    }

    fun exportHistorySession(id: Long) {
        val app = _state.value.history.packages.firstOrNull { item -> item.sessions.any { it.id == id } }
        val detail = _state.value.history.detail
        val pkg = app?.packageName ?: detail?.packageName ?: return
        val label = app?.label ?: detail?.label ?: pkg
        runExport { repository.exportHistory(pkg, label, id) }
    }

    fun exportVisibleLogs() {
        val snapshot = _state.value.logs
        runExport { repository.exportLogs(snapshot) }
    }

    companion object {
        const val PREFS_NAME = "qixia_prefs"
        const val PREF_HIDE_MISSING = "hide_missing_configured"
        const val PREF_AUTO_START = "auto_start_calibration"
        const val PREF_AUTO_DELAY = "auto_start_calibration_delay_ms"
    }
}
