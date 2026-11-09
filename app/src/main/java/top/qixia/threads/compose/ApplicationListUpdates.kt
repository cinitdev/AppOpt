package top.qixia.threads.compose

/** 非 APK 配置项只批量查询一次进程索引，不把所有无安装包的条目都视为系统组件。 */
internal fun resolveConfiguredComponents(
    names: Collection<String>,
    isInstalled: (String) -> Boolean,
    findProcesses: (Collection<String>) -> Set<String>,
    ownerOf: (String) -> String = { it }
): Map<String, AppComponentKind> {
    val groups = names.distinct().groupBy(ownerOf)
    val installed = groups.keys.associateWith(isInstalled)
    val candidates = installed.filterValues { !it }.keys
    // 展示可以按归属合并，但查询保留真实进程名，不能漏掉仅有子进程的原生服务。
    val targets = candidates.flatMap { groups.getValue(it) + it }.toSet()
    val processes = if (targets.isEmpty()) emptySet() else findProcesses(targets)
        .filter { it in targets }.mapTo(HashSet(), ownerOf)
    return installed.mapValues { (name, exists) -> when {
        exists -> AppComponentKind.APP
        name in processes -> AppComponentKind.SYSTEM_COMPONENT
        else -> AppComponentKind.MISSING_APP
    } }
}

/** 每项只解析一次开销较大的 PackageManager 数据，不在比较器内重复查询。 */
internal fun sortAppsByInstallTime(
    apps: List<AppItemModel>, installTime: (String) -> Long
): List<AppItemModel> {
    val times = HashMap<String, Long>()
    apps.forEach { app -> times.getOrPut(app.packageName) { if (app.installed) installTime(app.packageName) else 0L } }
    return apps.sortedWith(compareByDescending<AppItemModel> { times[it.packageName] ?: 0L }
        .thenBy { it.label.lowercase() })
}

internal fun AppItemModel.withAutomaticAffinity(
    enabled: Boolean, detail: String = "等待所选游戏进入前台"
): AppItemModel = copy(
    automaticAffinityEnabled = enabled,
    automaticFallbackEnabled = enabled && automaticFallbackEnabled,
    automaticCustomRules = if (enabled) automaticCustomRules else emptyList(),
    automaticAffinityDetail = detail,
    state = if (enabled) {
        if (available) AppRuleState.CONFIGURED else AppRuleState.MISSING
    } else manualRuleState,
    cpuSummary = if (enabled) "CPU 自动分配" else manualCpuSummary,
    unhealthyRuleCount = if (enabled) 0 else manualUnhealthyRuleCount
)

/** 只在磁盘提交成功后应用变更，保留启动器应用目录以支持删除和撤销。 */
internal fun ApplicationsUiState.applicationAdded(app: AppItemModel): ApplicationsUiState = copy(
    configured = if (configured.any { it.packageName == app.packageName }) configured
        else configured + app.copy(state = AppRuleState.PENDING, manualRuleState = AppRuleState.PENDING,
            cpuSummary = "CPU 自动", manualCpuSummary = "CPU 自动")
)

internal fun ApplicationsUiState.applicationDeleted(pkg: String): ApplicationsUiState = copy(
    configured = configured.filterNot { it.packageName == pkg }
)

internal fun ApplicationsUiState.automaticModeChanged(
    pkg: String, enabled: Boolean, source: AppItemModel? = null
): ApplicationsUiState {
    val configuredApp = configured.firstOrNull { it.packageName == pkg }
    val existing = configuredApp ?: addable.firstOrNull { it.packageName == pkg } ?: source ?: return this

    if (enabled) {
        val updated = existing.withAutomaticAffinity(true, "自动分配已启用，等待所选应用进入前台").copy(
            automaticOnly = configuredApp?.automaticOnly ?: (configuredApp == null)
        )
        return copy(configured = if (configuredApp == null) configured + updated else configured.map {
            if (it.packageName == pkg) updated else it
        })
    }

    if (configuredApp?.automaticOnly == true) {
        val restored = configuredApp.withAutomaticAffinity(false).copy(automaticOnly = false)
        return copy(
            configured = configured.filterNot { it.packageName == pkg },
            addable = if (addable.any { it.packageName == pkg }) addable.map {
                if (it.packageName == pkg) restored else it
            } else addable + restored
        )
    }

    return copy(
        configured = configured.map { if (it.packageName == pkg) it.withAutomaticAffinity(false) else it },
        addable = addable.map { if (it.packageName == pkg) it.withAutomaticAffinity(false) else it }
    )
}
