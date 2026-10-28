package top.qixia.threads.compose

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** 应用 Tab 和刷新率规则共用桌面入口查询，不通过完整应用列表构建目录。 */
@Suppress("DEPRECATION")
internal fun launchableAppCatalog(
    context: Context,
    onStage: (String, Long) -> Unit = { _, _ -> }
): List<AppItemModel> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val started = SystemClock.elapsedRealtime()
    val activities = manager.queryIntentActivities(intent, 0)
    onStage("catalog_query", SystemClock.elapsedRealtime() - started)
    val seen = HashSet<String>()
    var labelsMs = 0L
    val apps = activities.mapNotNull { info ->
        val activity = info.activityInfo ?: return@mapNotNull null
        val pkg = activity.packageName ?: return@mapNotNull null
        if (!activity.enabled || !activity.applicationInfo.enabled ||
            pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
        val time = SystemClock.elapsedRealtime()
        val label = runCatching { info.loadLabel(manager)?.toString() }.getOrNull().orEmpty().ifBlank { pkg }
        labelsMs += SystemClock.elapsedRealtime() - time
        // 仅解析名称；图标继续由可见行异步加载。
        AppItemModel(packageName = pkg, label = label, installed = true, icon = null, state = AppRuleState.CONFIGURED)
    }
    onStage("catalog_labels", labelsMs)
    val metadataStart = SystemClock.elapsedRealtime()
    // 只查询已经由桌面 Intent 返回的包，不枚举整机安装列表。
    val packages = apps.associate { app -> app.packageName to runCatching {
        manager.getPackageInfo(app.packageName, 0)
    }.getOrNull() }
    val sorted = sortAppsByInstallTime(apps) { packages[it]?.firstInstallTime ?: 0L }
        .map { it.copy(iconVersion = packages[it.packageName]?.lastUpdateTime ?: 0L) }
    onStage("catalog_sort", SystemClock.elapsedRealtime() - metadataStart)
    return sorted
}
