package top.qixia.threads.compose

import android.app.Application
import android.app.ActivityManager
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.provider.Settings
import java.io.File
import top.qixia.threads.CalibPolicy
import top.qixia.threads.ConfigReader
import top.qixia.threads.DaemonBridge
import top.qixia.threads.DatabaseMigrator
import top.qixia.threads.DiagnosticExporter
import top.qixia.threads.ForegroundDetector
import top.qixia.threads.FloatingBallSessionState
import top.qixia.threads.FpsSessionRecorder
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.RuleSyntax
import top.qixia.threads.AutoHistoryStore
import top.qixia.threads.db.QixiaThreadsDbHelper
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData

class QixiaThreadsRepository(private val application: Application) {
    private val packageManager: PackageManager = application.packageManager
    private val database: QixiaThreadsDbHelper
        get() = QixiaThreadsDbHelper.getInstance(application)
    private var previousCpuTimes: CpuTimes? = null

    data class DashboardSnapshot(
        val environment: EnvironmentUiState,
        val configured: List<AppItemModel>
    )

    data class DeviceMetricsSnapshot(
        val cpuPercent: Int?,
        val memoryPercent: Int?,
        val temperatureCelsius: Float?,
        val batteryPercent: Int?,
        val activeTargetPackage: String?
    )

    private data class CpuTimes(val total: Long, val idle: Long)

    fun loadDashboard(): DashboardSnapshot = loadDashboard { _, _ -> }

    internal fun loadDashboard(onStage: (String, Long) -> Unit): DashboardSnapshot {
        var lastStage = android.os.SystemClock.elapsedRealtime()
        fun stage(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            onStage(name, now - lastStage)
            lastStage = now
        }
        val hasRoot = DaemonBridge.hasRoot()
        stage("root")
        val pendingUpdate = hasRoot && DaemonBridge.hasPendingModuleUpdate()
        stage("pending_update")
        val moduleVersion = if (hasRoot && !pendingUpdate) DaemonBridge.readModuleVersion() else null
        stage("module_version")
        val compatible = moduleVersion?.versionCode?.let {
            it >= DaemonBridge.REQUIRED_MODULE_VERSION_CODE
        } == true
        val daemon = if (hasRoot && compatible && !pendingUpdate) {
            DaemonBridge.readDaemonRuntime()
        } else {
            DaemonBridge.DaemonRuntime(false)
        }
        val usable = hasRoot && compatible && daemon.running && !pendingUpdate
        stage("daemon")
        val rawConfig = if (usable) DaemonBridge.readConfigRawOrNull() else null
        stage("config_read")
        val config = rawConfig?.let {
            ConfigReader.parsePackages(it, RuleConfigLogic.readPresentCpuSet())
        }
        val safeConfig = config ?: ConfigReader.ConfigPackages(emptyList(), emptyList())
        stage("config_parse")
        val automatic = if (usable) DaemonBridge.readAutomaticAffinity() else DaemonBridge.AutomaticAffinityState()
        stage("automatic")
        val health = if (usable) DaemonBridge.readRuleHealthOrNull().orEmpty() else emptyMap()
        stage("health")
        val cpuRanges = rawConfig?.let(::cpuRangesByPackage).orEmpty()
        stage("cpu_ranges")
        val configured = buildConfiguredApps(safeConfig, health, cpuRanges).map { app ->
            app.withAutomaticAffinity(app.packageName in automatic.packages,
                if (automatic.activePackage == app.packageName) automatic.detail else "等待所选游戏进入前台")
        }
        stage("configured")
        val foreground = if (usable) {
            runCatching {
                val current = DaemonBridge.readTaskForegroundState()
                if (current.available) current else {
                    DaemonBridge.ensureTaskForegroundHelper()
                    DaemonBridge.readTaskForegroundState()
                }
            }.getOrNull()
        } else null
        stage("foreground")

        return DashboardSnapshot(
            environment = EnvironmentUiState(
                automaticAffinitySupported = automatic.supported,
                loading = false,
                hasRoot = hasRoot,
                pendingModuleUpdate = pendingUpdate,
                moduleVersion = moduleVersion,
                moduleCompatible = compatible,
                daemonRuntime = daemon,
                foregroundState = foreground,
                overlayGranted = Settings.canDrawOverlays(application),
                usageAccessGranted = ForegroundDetector.hasUsageAccess(application),
                configuredAppCount = configured.size,
                ruleCount = configured.sumOf(AppItemModel::ruleCount),
                statusMessage = if (config == null && usable) "配置读取失败，已保留安全空状态" else null
            ),
            configured = configured
        )
    }

    /** 启动器应用目录独立于 Root 和配置，仅在应用列表需要时加载。 */
    fun loadInstalledApps(): List<AppItemModel> = loadInstalledApps { _, _ -> }

    @Suppress("DEPRECATION")
    internal fun loadInstalledApps(onStage: (String, Long) -> Unit): List<AppItemModel> {
        val apps = installedLaunchableApps(onStage)
        val started = android.os.SystemClock.elapsedRealtime()
        // 一次 Binder 调用也可避免厂商 PackageManager 实现逐包进行冷查询。
        val packages = packageManager.getInstalledPackages(0).associateBy { it.packageName }
        val sorted = sortAppsByInstallTime(apps) { packages[it]?.firstInstallTime ?: 0L }
            .map { it.copy(iconVersion = packages[it.packageName]?.lastUpdateTime ?: 0L) }
        onStage("catalog_sort", android.os.SystemClock.elapsedRealtime() - started)
        return sorted
    }

    private fun buildConfiguredApps(
        config: ConfigReader.ConfigPackages,
        health: Map<String, DaemonBridge.RuleHealth>,
        cpuRanges: Map<String, Set<Int>>
    ): List<AppItemModel> {
        val components = configuredComponents(config.autoPackages + config.configuredPackages)
        val groups = LinkedHashMap<String, LinkedHashSet<String>>()
        for (pkg in config.configuredPackages) {
            groups.getOrPut(ownerPackage(pkg)) { LinkedHashSet() }.add(pkg)
        }
        val result = ArrayList<AppItemModel>()
        for (pkg in config.autoPackages) {
            if (ownerPackage(pkg) in groups) continue
            result += appModel(
                packageName = ownerPackage(pkg),
                componentKind = components.getValue(ownerPackage(pkg)),
                configPackages = listOf(pkg),
                state = AppRuleState.PENDING,
                cpuSummary = "CPU 自动"
            )
        }
        for ((pkg, owners) in groups) {
            val configPackages = owners.toList()
            val ruleCount = configPackages.sumOf { config.configuredRuleCounts[it] ?: 0 }
                .takeIf { it > 0 } ?: configPackages.size
            val unhealthy = health.values.count {
                ownerPackage(it.owner) == pkg && it.status == DaemonBridge.RuleHealthStatus.MISSED
            }
            result += appModel(
                packageName = pkg,
                componentKind = components.getValue(pkg),
                configPackages = configPackages,
                state = AppRuleState.CONFIGURED,
                ruleCount = ruleCount,
                cpuSummary = cpuRanges[pkg]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "CPU ${RuleConfigLogic.formatCpuRangeList(it)}" }
                    ?: "CPU --",
                unhealthy = unhealthy
            )
        }
        return sortAppsByInstallTime(result, ::installTime)
    }

    private fun appModel(
        packageName: String,
        componentKind: AppComponentKind,
        configPackages: List<String> = listOf(packageName),
        state: AppRuleState = AppRuleState.CONFIGURED,
        ruleCount: Int = 0,
        cpuSummary: String = "CPU --",
        unhealthy: Int = 0
    ): AppItemModel {
        val lookup = lookupPackage(packageName)
        val installed = componentKind == AppComponentKind.APP
        val fpsRecord = if (installed) FpsSessionRecorder.readLastAverage(application.filesDir, packageName) else null
        return AppItemModel(
            packageName = packageName,
            label = if (installed) appLabel(lookup) else packageName,
            installed = installed,
            icon = if (installed) appIcon(lookup) else null,
            configPackages = configPackages,
            ruleCount = ruleCount,
            cpuSummary = cpuSummary,
            state = if (componentKind == AppComponentKind.MISSING_APP && state == AppRuleState.CONFIGURED) AppRuleState.MISSING else state,
            unhealthyRuleCount = unhealthy,
            averageFps = fpsRecord?.averageFps,
            fpsSampleCount = fpsRecord?.sampleCount ?: 0L,
            fpsSessionDurationMs = fpsRecord?.durationMs ?: 0L,
            fpsSessionEndedAtMs = fpsRecord?.endedAtMs ?: 0L,
            componentKind = componentKind
        )
    }

    private fun configuredComponents(names: Collection<String>): Map<String, AppComponentKind> =
        resolveConfiguredComponents(names, { isInstalled(lookupPackage(it)) },
            DaemonBridge::findRunningProcessNames, ::ownerPackage)

    @Suppress("DEPRECATION")
    private fun installedLaunchableApps(onStage: (String, Long) -> Unit): List<AppItemModel> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val started = android.os.SystemClock.elapsedRealtime()
        val activities = packageManager.queryIntentActivities(intent, 0)
        onStage("catalog_query", android.os.SystemClock.elapsedRealtime() - started)
        var labelsMs = 0L
        val apps = activities.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (!seen.add(pkg) || pkg == application.packageName) return@mapNotNull null
            val time = android.os.SystemClock.elapsedRealtime()
            val label = info.loadLabel(packageManager)?.toString().orEmpty().ifBlank { pkg }
            labelsMs += android.os.SystemClock.elapsedRealtime() - time
            AppItemModel(
                packageName = pkg,
                label = label,
                installed = true,
                // 可见行异步解析图标，屏幕外的图标不能阻塞列表。
                icon = null,
                state = AppRuleState.CONFIGURED
            )
        }
        onStage("catalog_labels", labelsMs)
        return apps
    }

    fun addAutoPackage(pkg: String): Boolean = DaemonBridge.addAutoPackage(pkg)

    fun deleteConfigPackages(pkgs: Collection<String>): Boolean {
        // 原生进程名称不是自动分配包名，不应因清理不存在的自动配置而阻止删除规则。
        for (pkg in pkgs.map(::ownerPackage).distinct().filter(DaemonBridge::isValidBasePackage)) {
            if (!DaemonBridge.setAutomaticAffinity(pkg, false)) return false
        }
        return DaemonBridge.deleteConfigPackages(pkgs)
    }

    fun setAutomaticAffinity(pkg: String, enabled: Boolean): Boolean =
        DaemonBridge.setAutomaticAffinity(ownerPackage(pkg), enabled)

    @Suppress("DEPRECATION")
    fun readDeviceMetrics(): DeviceMetricsSnapshot {
        val cpu = readCpuPercent()
        val activityManager = application.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val memoryPercent = if (memory.totalMem > 0L) {
            (((memory.totalMem - memory.availMem).toDouble() / memory.totalMem.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else null

        val batteryIntent = application.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
            (batteryLevel * 100f / batteryScale).toInt().coerceIn(0, 100)
        } else null
        val batteryTemperature = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10f)

        val session = FloatingBallSessionState.activeSession(application)

        return DeviceMetricsSnapshot(
            cpuPercent = cpu,
            memoryPercent = memoryPercent,
            temperatureCelsius = batteryTemperature,
            batteryPercent = batteryPercent,
            activeTargetPackage = session?.targetPkg
        )
    }

    @Synchronized
    private fun readCpuPercent(): Int? {
        val localLine = runCatching {
            File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            }
        }.getOrNull()
        val line = localLine?.takeIf { it.isNotBlank() }
            ?: DaemonBridge.readSystemCpuStatLine()
        val values = line
            .trim()
            .split(Regex("\\s+"))
            .drop(1)
            .mapNotNull(String::toLongOrNull)
        if (values.size < 4) return null
        val current = CpuTimes(
            total = values.sum(),
            idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        )
        val previous = previousCpuTimes.also { previousCpuTimes = current } ?: return null
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L) return null
        return (((totalDelta - idleDelta).coerceAtLeast(0L) * 100.0) / totalDelta)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun cpuRangesByPackage(rawConfig: String): Map<String, Set<Int>> {
        val result = LinkedHashMap<String, MutableSet<Int>>()
        for (rule in RuleSyntax.parse(rawConfig).rules) {
            if (rule.cpus.equals("auto", ignoreCase = true)) continue
            val cpus = RuleConfigLogic.parseCpuRangeList(rule.cpus) ?: continue
            result.getOrPut(ownerPackage(rule.owner)) { linkedSetOf() }.addAll(cpus)
        }
        return result
    }

    fun loadRuleEditor(app: AppItemModel): RuleEditorUiState {
        DatabaseMigrator.migrateIfNeeded(application, app.packageName)
        val lines = DaemonBridge.readPkgRulesOrNull(app.configPackages)
            ?: error("读取现有规则失败，请检查 Root 和模块状态后重试")
        val automatic = DaemonBridge.readAutomaticAffinity()
        val suspended = if (automatic.supported) app.packageName in automatic.packages else app.automaticAffinityEnabled
        val component = configuredComponents(app.configPackages + app.packageName).getValue(app.packageName)
        return RuleEditorUiState(
            app = app.copy(installed = component == AppComponentKind.APP, componentKind = component,
                automaticAffinityEnabled = suspended,
                unhealthyRuleCount = if (suspended) 0 else app.unhealthyRuleCount),
            originalLines = lines,
            draft = lines.joinToString("\n"),
            allowedCpus = DaemonBridge.readConfigAllowedCpus(),
            historyCandidates = top.qixia.threads.RuleHistoryCandidates.build(
                app.packageName, database.getRuleHistoryRecordsByPackage(app.packageName)
            ),
            health = DaemonBridge.readRuleHealthOrNull().orEmpty().filterValues {
                it.owner == app.packageName || it.owner.startsWith("${app.packageName}:")
            }
        )
    }

    fun saveRules(editor: RuleEditorUiState): Result<Unit> = runCatching {
        val validation = DaemonBridge.validateConfigRulesForPackages(
            editor.app.configPackages,
            editor.draft,
            editor.allowedCpus.takeIf { it.isNotEmpty() }
        )
        check(validation.ok) {
            when {
                validation.foreignLines.isNotEmpty() -> "规则包含其他应用：${validation.foreignLines.first()}"
                validation.invalidCoreLines.isNotEmpty() -> "核心范围不可用：${validation.invalidCoreLines.first()}"
                validation.invalidLines.isNotEmpty() -> "规则格式错误：${validation.invalidLines.first()}"
                else -> "至少保留一条有效规则"
            }
        }
        val replacements = buildMap {
            validation.validLines.take(editor.originalLines.size).forEachIndexed { index, line ->
                put(index, line)
            }
        }
        val result = DaemonBridge.replaceConfigRulesPreservingLayout(
            pkgs = editor.app.configPackages,
            expectedOriginalLines = editor.originalLines,
            replacements = replacements,
            addedLines = validation.validLines.drop(editor.originalLines.size),
            allowedCpus = editor.allowedCpus.takeIf { it.isNotEmpty() }
        )
        check(result == DaemonBridge.ConfigReplaceResult.SUCCESS) {
            when (result) {
                DaemonBridge.ConfigReplaceResult.SOURCE_CHANGED -> "配置已在其他位置变化，请重新打开后编辑"
                DaemonBridge.ConfigReplaceResult.INVALID -> "规则校验失败"
                DaemonBridge.ConfigReplaceResult.WRITE_FAILED -> "保存失败，请检查 Root 和模块状态"
                DaemonBridge.ConfigReplaceResult.SUCCESS -> ""
            }
        }
    }

    fun loadHistory(): List<HistoryPackageModel> = readHistory(includeReports = true)

    /** 导入安排在首页数据加载之后，不作为最近记录卡片的加载前提。 */
    fun importHistory() {
        val before = database.getPackagesWithHistory().associate { it.pkg to database.getSessionSummariesByPackage(it.pkg) }
        runCatching {
            for (entry in DaemonBridge.listHistoryEntries()) {
                DatabaseMigrator.migrateIfNeeded(application, entry.pkg)
            }
        }
        runCatching { database.pruneHistory() }
        runCatching { AutoHistoryStore.importCompleted(application) }
        runCatching { top.qixia.threads.HistoryFpsStore.prune(application.filesDir, database.calibrationWindows()) }
        runCatching { top.qixia.threads.HistoryMetricsStore.pruneHistory(application.filesDir) }
        val calibrationByPackage = database.getPackagesWithHistory()
            .associate { it.pkg to database.getSessionSummariesByPackage(it.pkg) }
        before.forEach { (pkg, sessions) ->
            val retained = calibrationByPackage[pkg].orEmpty()
            val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
            removeCalibrationCurves(pkg, sessions.filterNot { it.id in retainedIds }, retained)
        }
        // 在后台导入路径中准备精确区间摘要；首页仅读取这些小缓存，
        // 不扫描校准 CSV 曲线。
        calibrationByPackage.forEach { (pkg, sessions) ->
            runCatching { top.qixia.threads.HistoryFpsStore.prepareHomeSummaries(application.filesDir, pkg,
                sessions.map { it.epoch to it.durationMs }) }
        }
    }

    private fun readHistory(includeReports: Boolean): List<HistoryPackageModel> {
        // 历史页可以刷新归档元数据；首页使用下方独立的
        // 非阻塞缓存读取路径。
        val automatic = if (includeReports) AutoHistoryStore.entries(application.filesDir).groupBy { it.pkg }
            else emptyMap()
        val calibrationByPackage = database.getPackagesWithHistory()
            .associate { it.pkg to database.getSessionSummariesByPackage(it.pkg) }
        val packages = (calibrationByPackage.keys + automatic.keys).distinct()
        return packages.map { pkg ->
            val lookup = lookupPackage(pkg)
            val calibration = calibrationByPackage[pkg].orEmpty()
            val autoEntries = automatic[pkg].orEmpty()
            val sessions = (calibration + autoEntries.map { it.session }).sortedByDescending { it.endedAtMs }
            val lastFps = FpsSessionRecorder.readLastAverage(application.filesDir, pkg)
            val calibrationFps = if (includeReports) top.qixia.threads.HistoryFpsStore.reports(application.filesDir,
                pkg, calibration.map { it.epoch to it.durationMs }) else emptyMap()
            val fpsReports = if (!includeReports) emptyMap() else
                calibration.mapNotNull { session -> calibrationFps[session.epoch]?.let { session.id to it } }.toMap() +
                    autoEntries.mapNotNull { entry -> entry.fps?.let { entry.session.id to it } }.toMap()
            HistoryPackageModel(
                packageName = pkg,
                label = appLabel(lookup),
                icon = appIcon(lookup),
                lastTime = sessions.maxOfOrNull { it.epoch } ?: 0L,
                sessionCount = sessions.size,
                averageFps = lastFps?.averageFps,
                fpsDurationMs = lastFps?.durationMs ?: 0L,
                sessions = sessions,
                sessionFps = fpsReports
            )
        }.sortedByDescending { it.lastTime }
    }

    fun loadHome(configured: List<AppItemModel>): HomeUiState {
        val calibration = database.getPackagesWithHistory().associate { it.pkg to database.getSessionSummariesByPackage(it.pkg) }
        val automatic = AutoHistoryStore.cachedEntriesForHome(application.filesDir).groupBy { it.pkg }
        val configuredByPackage = configured.associateBy { it.packageName }
        val applications = mutableMapOf<String, HistoryPackageModel>()
        fun app(pkg: String): HistoryPackageModel = applications.getOrPut(pkg) {
            val known = configuredByPackage[pkg]
            val lookup = if (known == null) lookupPackage(pkg) else pkg
            val sessions = (calibration[pkg].orEmpty() + automatic[pkg].orEmpty().map { it.session })
                .sortedByDescending { it.endedAtMs }
            HistoryPackageModel(pkg, known?.label ?: appLabel(lookup), known?.icon ?: appIcon(lookup),
                sessions.maxOfOrNull { it.epoch } ?: 0L, sessions.size, sessions = sessions)
        }
        val reports = mutableListOf<HomeRecord>()
        for ((pkg, sessions) in calibration) {
            // 属性文件很小；不要加载或扫描 FPS CSV，也不能仅因包名相同
            // 就沿用上一次启动的平均值。
            val last = FpsSessionRecorder.readLastAverage(application.filesDir, pkg)?.let {
                HomeFpsSummary(it.averageFps, it.startedAtMs, it.endedAtMs)
            }
            for (session in sessions) {
                val end = session.endedAtMs
                val start = end - session.durationMs
                if (!HomeRecordSelector.hasRecordableDuration(start, end)) continue
                val cached = top.qixia.threads.HistoryFpsStore.cachedSummary(application.filesDir, pkg, session.epoch,
                    session.durationMs)?.let { homeFpsPreview(it, start, end) }
                reports += HomeRecord(app(pkg), HomeRecordMode.CALIBRATION, start, end,
                    cached ?: HomeRecordSelector.matchingCalibrationFps(start, end, last), session)
            }
        }
        for ((pkg, entries) in automatic) for (entry in entries) {
            val session = entry.session
            if (!HomeRecordSelector.hasRecordableDuration(session.startedAtMs, session.endedAtMs)) continue
            reports += HomeRecord(app(pkg), HomeRecordMode.AUTOMATIC, session.startedAtMs, session.endedAtMs,
                entry.fps?.let { homeFpsPreview(it, session.startedAtMs, session.endedAtMs) }, session)
        }
        return HomeUiState(loading = false, records = HomeRecordSelector.select(reports,
            RecentUsageReader.read(application.filesDir),
            configured.associate { it.packageName to (it.ruleCount > 0 || it.automaticAffinityEnabled) }, ::app))
    }

    private fun homeFpsPreview(report: top.qixia.threads.HistoryFpsStore.Report, start: Long, end: Long) =
        HomeFpsSummary(report.average, start, end, report.maximum, report.points.mapNotNull { point ->
            point.timestampMs?.takeIf { it in start..end }?.let { HomeFpsPoint(it, point.fps, point.breakBefore) }
        })

    fun loadSessions(pkg: String): List<SessionSummary> =
        (database.getSessionSummariesByPackage(pkg) + AutoHistoryStore.entries(application.filesDir)
            .filter { it.pkg == pkg }.map { it.session }).sortedByDescending { it.endedAtMs }

    fun loadThreads(sessionId: Long): List<ThreadData> =
        if (sessionId < 0L) AutoHistoryStore.record(application.filesDir, sessionId)?.threads.orEmpty()
        else database.getThreadsBySessionId(sessionId)

    fun loadCoreTimeline(sessionId: Long): top.qixia.threads.CoreTimelineReport? =
        if (sessionId < 0L) AutoHistoryStore.record(application.filesDir, sessionId)?.coreTimeline else null

    data class HistorySessionDetail(
        val threads: List<ThreadData>,
        val fps: Map<Long, top.qixia.threads.HistoryFpsStore.Report>,
        val metrics: Map<Long, top.qixia.threads.HistoryMetrics.Report>,
        val coreTimeline: top.qixia.threads.CoreTimelineReport?,
        val session: SessionSummary,
        val windows: List<HistoryReportWindow> = emptyList()
    )

    /** 对选中的不可变记录只解析一次，为全部详情面板提供数据。 */
    fun loadHistorySessionDetail(pkg: String, session: SessionSummary, windowIndex: Int = 0): HistorySessionDetail {
        if (session.source == HistorySource.AUTO_ALLOCATION) {
            return synchronized(AutoHistoryStore) {
                // 两次独立读取之间可能导入刚完成的分片，
                // 需确保区间列表与解析数据来自同一归档快照。
                val windows = AutoHistoryStore.reportWindows(application.filesDir, session.id)
                val record = checkNotNull(AutoHistoryStore.record(application.filesDir, session.id, windowIndex)) { "自动分配记录不可读" }
                check(record.entry.pkg == pkg) { "记录与所选应用不匹配" }
                HistorySessionDetail(record.threads,
                    record.fps?.let { mapOf(session.id to it) }.orEmpty(),
                    record.metrics?.let { mapOf(session.id to it) }.orEmpty(), record.coreTimeline,
                    record.entry.session, windows.map { HistoryReportWindow(it.index, it.startMs, it.endMs) })
            }
        }
        return HistorySessionDetail(loadThreads(session.id), loadHistoryFps(pkg, listOf(session)),
            loadHistoryMetrics(pkg, listOf(session)), null, session)
    }

    fun compareHistorySummary(pkg: String, session: SessionSummary, checkCancelled: () -> Unit): RunComparisonSummary {
        fun read() = HistoryRunComparison.summarize(session, checkCancelled) { index ->
            loadHistorySessionDetail(pkg, session, index)
        }
        // 与导入、删除共用归档锁，保证整次对比的数据来自同一快照。
        return if (session.source == HistorySource.AUTO_ALLOCATION) synchronized(AutoHistoryStore) { read() } else read()
    }

    fun loadHistoryFps(pkg: String, sessions: List<SessionSummary>): Map<Long, top.qixia.threads.HistoryFpsStore.Report> {
        val reports = top.qixia.threads.HistoryFpsStore.reports(application.filesDir, pkg,
            sessions.filter { it.source == HistorySource.CALIBRATION }.map { it.epoch to it.durationMs })
        return sessions.mapNotNull { session ->
            val report = if (session.source == HistorySource.AUTO_ALLOCATION)
                AutoHistoryStore.record(application.filesDir, session.id)?.fps else reports[session.epoch]
            report?.let { session.id to it }
        }.toMap()
    }

    fun loadHistoryMetrics(pkg: String, sessions: List<SessionSummary>): Map<Long, top.qixia.threads.HistoryMetrics.Report> {
        val reports = top.qixia.threads.HistoryMetricsStore.reports(application.filesDir, pkg,
            sessions.filter { it.source == HistorySource.CALIBRATION }.map { it.epoch to it.durationMs })
        return sessions.mapNotNull { session ->
            val report = if (session.source == HistorySource.AUTO_ALLOCATION)
                AutoHistoryStore.record(application.filesDir, session.id)?.metrics else reports[session.epoch]
            report?.let { session.id to it }
        }.toMap()
    }

    fun deleteHistoryPackage(pkg: String, source: HistorySource? = null): Boolean {
        return DatabaseMigrator.withPackageLock(pkg) {
            val calibration = if (source == HistorySource.AUTO_ALLOCATION) emptyList()
                else database.getSessionSummariesByPackage(pkg)
            // 源文件或已认领文件仍存在时，在操作 SQLite 前中止。
            // 导入也使用同一把锁，防止已删除会话再次出现。
            if (source != HistorySource.AUTO_ALLOCATION &&
                !runCatching { DaemonBridge.deleteHistory(pkg) }.getOrDefault(false)) {
                return@withPackageLock false
            }
            if (source != HistorySource.CALIBRATION && !AutoHistoryStore.deletePackage(application.filesDir, pkg))
                return@withPackageLock false
            if (source == HistorySource.AUTO_ALLOCATION) return@withPackageLock true
            if (database.deleteAllSessionsByPackage(pkg) < 0) return@withPackageLock false
            removeCalibrationCurves(pkg, calibration, emptyList())
            true
        }
    }

    data class HistoryDeleteResult(val deletedIds: Set<Long>, val failedIds: Set<Long>)

    fun deleteHistorySessions(sessionIds: Set<Long>): HistoryDeleteResult {
        val requested = sessionIds.filterTo(linkedSetOf()) { it != 0L }
        val deleted = mutableSetOf<Long>()
        val calibration = requested.filter { it > 0L }
        val packages = database.getSessionPackages(calibration)
        // 保留策略或其他已完成操作可能已经移除了某些 ID。
        deleted += calibration.filter { it !in packages }
        packages.entries.groupBy({ it.value }, { it.key }).forEach { (pkg, ids) ->
            runCatching {
                DatabaseMigrator.withPackageLock(pkg) {
                    val before = database.getSessionSummariesByPackage(pkg)
                    database.deleteSessions(ids)
                    val remaining = database.getSessionPackages(ids).keys
                    val removed = ids.filterNot { it in remaining }.toSet()
                    deleted += removed
                    removeCalibrationCurves(pkg, before.filter { it.id in removed },
                        database.getSessionSummariesByPackage(pkg))
                }
            }
        }
        val automatic = requested.filter { it < 0L }
        if (automatic.isNotEmpty()) {
            runCatching { AutoHistoryStore.deleteSessions(application.filesDir, automatic) }
                .getOrNull()?.let { deleted += it }
        }
        return HistoryDeleteResult(deleted, requested - deleted)
    }

    private fun removeCalibrationCurves(pkg: String, removed: List<SessionSummary>, retained: List<SessionSummary>) {
        if (removed.isEmpty()) return
        val windows = removed.map { it.epoch to it.durationMs }
        val remaining = retained.map { it.epoch to it.durationMs }
        runCatching { top.qixia.threads.HistoryFpsStore.removeSessions(application.filesDir, pkg, windows, remaining) }
        runCatching { top.qixia.threads.HistoryMetricsStore.removeSessions(application.filesDir, pkg, windows, remaining) }
    }

    fun loadLogs(source: LogSource): List<LogEntryModel> {
        return LogEventParser.parse(source, DaemonBridge.readEventLogWindow(source == LogSource.FOREGROUND))
    }

    fun loadPolicy(): SettingsUiState {
        val snapshot = DaemonBridge.readSettingsPolicySnapshot()
        val automaticHistory = if (snapshot.hasRoot) DaemonBridge.readAutomaticHistory()
            else DaemonBridge.AutomaticHistoryState()
        val policy = if (snapshot.policyFile.readSuccess && snapshot.policyFile.content.isNotBlank()) {
            CalibPolicy.parse(snapshot.policyFile.content)
        } else {
            CalibPolicy.DEFAULT
        }
        return SettingsUiState(
            loading = false,
            policy = policy,
            hasRoot = snapshot.hasRoot,
            moduleVersion = snapshot.moduleVersion,
            lockedByPendingUpdate = snapshot.policyFile.lockedByPendingUpdate,
            readSuccess = snapshot.policyFile.readSuccess,
            cpusetSupported = snapshot.cpusetSupported,
            presentCpus = snapshot.presentCpus,
            autoHistoryEnabled = automaticHistory.enabled,
            autoHistorySupported = automaticHistory.supported
        )
    }

    fun savePolicy(policy: CalibPolicy, previous: CalibPolicy): String {
        // 文件管理器可能替换 applist.conf，而未修改策略。
        // 保存或重选同一设置时，也必须同步实际规则语法。
        val result = DaemonBridge.applyRuleOutputFormat(policy.ruleOutputFormat, policy.toConfigText())
        check(result.success) { result.detail ?: "规则格式转换失败：${result.status}" }
        if (policy.cpusetName != previous.cpusetName) return when (DaemonBridge.restartRustDaemon()) {
            DaemonBridge.RustDaemonRestartStatus.REQUESTED -> "已保存，Rust 守护将在数秒内安全重启"
            DaemonBridge.RustDaemonRestartStatus.NOT_RUNNING -> "已保存，cpuset 将在下次启动守护时生效"
            DaemonBridge.RustDaemonRestartStatus.FAILED -> "已保存，自动重启失败；请重启设备使 cpuset 生效"
        }
        return "自动校准策略已保存"
    }

    fun exportHistory(pkg: String, label: String, sessionId: Long? = null, source: HistorySource? = null): String {
        fun write(): String {
            val sessions = loadSessions(pkg).filter { (sessionId == null || it.id == sessionId) &&
                (source == null || it.source == source) }
            return HistoryRunExporter.export(application, pkg, label, sessions) { session, window ->
                loadHistorySessionDetail(pkg, session, window)
            }
        }
        // 整次记录摘要与所有导出区间使用同一快照；归档锁可重入，
        // 详情读取和流式导出共用此锁。
        val includesAutomatic = source != HistorySource.CALIBRATION && (sessionId == null || sessionId < 0L)
        return if (includesAutomatic) synchronized(AutoHistoryStore) { write() } else write()
    }
    fun exportLogs(logs: LogsUiState): String {
        check(logs.visibleEntries.isNotEmpty()) { "当前筛选没有可导出的日志" }
        return exportText(application, "QixiaThreads_${logs.source.name}_${logs.filter.name}",
            "QixiaThreads 运行日志\n来源：${logs.source.label}\n级别：${logs.filter.label}\n分类：${logs.category.label}\n搜索：${logs.query}\n事件：${logs.visibleEntries.size}\n以下保留原始输出与重复计数；仅含最近读取窗口。\n\n" +
                logs.visibleEntries.joinToString("\n\n") { it.copyText })
    }

    fun exportDiagnostics(): Result<String> = DiagnosticExporter.export(application)

    fun checkModuleUpdate(): ModuleUpdater.CheckResult = ModuleUpdater.checkForUpdate()

    private fun ownerPackage(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.contains('.')) base else pkg
    }

    private fun lookupPackage(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.isNotBlank() && isInstalled(base)) base else pkg
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installTime(pkg: String): Long = try {
        packageManager.getPackageInfo(lookupPackage(pkg), 0).firstInstallTime
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

}
