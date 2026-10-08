package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ApplicationsScreen(
    state: QixiaThreadsUiState, contentPadding: PaddingValues,
    onRefresh: () -> Unit, onOpenEnvironment: () -> Unit,
    onSelectTab: (ApplicationTab) -> Unit, onSearch: (String) -> Unit,
    onToggleHideMissing: (Boolean) -> Unit, onAdd: (AppItemModel) -> Unit,
    onDelete: (AppItemModel) -> Unit, onLaunch: (AppItemModel) -> Unit,
    onEditRules: (AppItemModel) -> Unit,
    onAutoStartChange: (Boolean, Long?) -> Unit,
    onAutomaticAffinity: (AppItemModel, Boolean) -> Unit,
    onDiagnostics: (AppItemModel) -> Unit = {},
) {
    val apps = state.applications
    val env = state.environment
    var managedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val managed = apps.configured.firstOrNull { it.packageName == managedPackage }
    var deleteTarget by remember { mutableStateOf<AppItemModel?>(null) }
    var showAutoStart by rememberSaveable { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val available = env.featuresAvailable
    val visibleItems = remember(apps.configured, apps.addable, apps.selectedTab, apps.query, apps.hideMissing) {
        apps.visibleItems
    }
    val addedPackages = remember(apps.configured) { apps.configured.mapTo(HashSet()) { it.packageName } }
    val (addedItems, addableItems) = remember(visibleItems, addedPackages) {
        visibleItems.partition { it.packageName in addedPackages }
    }
    val library = apps.selectedTab == ApplicationTab.LIBRARY
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastQuery by rememberSaveable { mutableStateOf(apps.query) }
    var lastTab by rememberSaveable { mutableStateOf(apps.selectedTab.name) }
    LaunchedEffect(apps.query, apps.selectedTab) {
        if (lastQuery != apps.query || lastTab != apps.selectedTab.name) {
            listState.scrollToItem(0)
            lastQuery = apps.query
            lastTab = apps.selectedTab.name
        }
    }
    val showEnvironmentWarning = !available && !env.loading

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OceanBackground, ClearTechBackgroundEnd))).padding(contentPadding)) {
        ScreenHeader("应用", status = if (env.loading) "检测中" else if (available) "守护在线" else "需要处理",
            statusColor = if (available) OceanSuccess else OceanWarning, onMore = onRefresh,
            subtitle = "添加、校准与核心分配")
        Column(Modifier.padding(horizontal = 20.dp)) {
            PorcelainTabs(ApplicationTab.entries.map { it.label }, apps.selectedTab.ordinal,
                { onSelectTab(ApplicationTab.entries[it]) })
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                SearchField(apps.query, onSearch, trailing = {
                    IconButton(onClick = { filterOpen = true }) { Icon(Icons.Outlined.FilterList, "应用筛选", tint = PorcelainOnTonal) }
                })
                DropdownMenu(filterOpen, { filterOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (apps.hideMissing) "显示未安装应用" else "隐藏未安装应用") },
                        onClick = { filterOpen = false; onToggleHideMissing(!apps.hideMissing) },
                        leadingIcon = { Icon(if (apps.hideMissing) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (apps.loading) "正在读取应用" else "${visibleItems.size} 个应用${if (apps.query.isNotBlank()) "符合搜索" else ""}",
                    Modifier.weight(1f), color = OceanTextSecondary, fontSize = 11.sp)
                if (apps.hideMissing) Text("已隐藏未安装", color = OceanTextSecondary, fontSize = 11.sp)
                if ((state.refreshing || (library && apps.catalogLoading)) && !apps.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        if (library) item(key = "auto-start") {
            Surface(Modifier.padding(bottom = 8.dp), color = PorcelainHeader.copy(alpha = .6f), shape = RoundedCornerShape(17.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = PorcelainOnTonal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("启动后自动开始校准", color = OceanText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (apps.autoStartCalibration) {
                            TextButton(onClick = { showAutoStart = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("延迟 ${apps.autoStartDelayMs / 1000} 秒 · 调整", fontSize = 11.sp)
                            }
                        } else {
                            Text("当前由悬浮球手动开始采集", color = OceanTextSecondary, fontSize = 11.sp)
                        }
                    }
                    PorcelainSwitch(apps.autoStartCalibration, {
                        if (it) showAutoStart = true else onAutoStartChange(false, null)
                    })
                }
            }
        }
        if (showEnvironmentWarning) item(key = "environment-warning") {
            Surface(modifier = Modifier.padding(bottom = 12.dp), color = OceanWarning.copy(alpha = .07f), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = OceanWarning)
                    Spacer(Modifier.width(10.dp))
                    Text(env.statusMessage ?: "核心功能尚未就绪，请检查 Root、模块和守护进程。", Modifier.weight(1f), color = OceanWarning, fontSize = 12.sp)
                    TextButton(onClick = onOpenEnvironment) { Text("检查") }
                }
            }
        }
        when {
            apps.loading || (library && apps.catalogLoading && visibleItems.isEmpty()) -> item { LoadingState("正在读取应用") }
            visibleItems.isEmpty() -> item {
                GroupedSurface {
                    Column {
                        EmptyState(
                            if (apps.query.isNotBlank()) "没有匹配的应用" else if (library) "暂无可添加或待校准的应用" else "暂无已配置应用",
                            if (apps.query.isNotBlank()) "试试应用名称或完整包名" else if (library) "已完成配置的应用可在“已配置”中管理" else "添加应用并保存校准建议，或开启自动分配后，会显示在这里"
                        )
                        if (!library && apps.query.isBlank())
                            TextButton(onClick = { onSelectTab(ApplicationTab.LIBRARY) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("前往应用列表") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            else -> {
                if (addedItems.isNotEmpty()) {
                    item(key = "added-heading") {
                        SectionTitle("${if (library) "待校准" else "已配置"} · ${addedItems.size}",
                            action = if (library && addableItems.isNotEmpty()) "添加应用 ↓" else null,
                            onAction = {
                                // 依次显示自动启动、可选的环境提醒、标题和待处理行。
                                val index = 1 + (if (showEnvironmentWarning) 1 else 0) + 1 + addedItems.size
                                scope.launch { listState.scrollToItem(index) }
                            })
                    }
                    items(addedItems, key = { it.packageName }) { app ->
                        val busy = app.packageName in apps.busyPackages
                        ApplicationRow(app, added = true, available && !busy, busy,
                            onManage = { managedPackage = app.packageName },
                            onAction = { if (library && !app.isSystemComponent) onLaunch(app) else managedPackage = app.packageName })
                    }
                }
                if (addableItems.isNotEmpty()) {
                    item(key = "addable-heading") {
                        Column(Modifier.padding(top = if (addedItems.isEmpty()) 0.dp else 8.dp, bottom = 10.dp)) {
                            SectionTitle("可添加 · ${addableItems.size}")
                            Text("添加后在此启动校准，也可进入管理开启自动分配", color = OceanTextSecondary, fontSize = 11.sp)
                        }
                    }
                    items(addableItems, key = { it.packageName }) { app ->
                        val busy = app.packageName in apps.busyPackages
                        ApplicationRow(app, added = false, available && !busy, busy,
                            onManage = {}, onAction = { onAdd(app) })
                    }
                }
            }
        }
        if (library) {
            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    RuntimeStatusPanel(env, onOpenEnvironment)
                }
            }
        }
        }
    }

    if (managed != null) AppManageSheet(
        managed, available && managed.packageName !in apps.busyPackages,
        onDismiss = { managedPackage = null },
        onLaunch = { managedPackage = null; onLaunch(managed) },
        onEditRules = { managedPackage = null; onEditRules(managed) },
        onAutomaticAffinity = { onAutomaticAffinity(managed, it) },
        automaticSupported = env.automaticAffinitySupported,
        onDelete = { managedPackage = null; deleteTarget = managed },
        onDiagnostics = { managedPackage = null; onDiagnostics(managed) }
    )
    deleteTarget?.let { app ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text(if (app.isSystemComponent) "删除组件配置？" else "删除应用配置？") },
            text = { Text("将移除 ${app.label} 的全部绑定规则。历史会话不会删除。") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete(app) }) { Text("删除配置", color = OceanError) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    if (showAutoStart) {
        var seconds by remember { mutableFloatStateOf(apps.autoStartDelayMs / 1000f) }
        AlertDialog(onDismissRequest = { showAutoStart = false }, title = { Text("启用自动记录？") },
            text = {
                Column {
                    Text("启动后会在指定延迟结束时开始校准。加载页可能影响结果，建议先进入稳定场景；也可以关闭自动开始，手动控制采样。")
                    Spacer(Modifier.height(16.dp))
                    Text("启动延迟：${seconds.roundToInt()} 秒", color = PorcelainOnTonal)
                    Slider(seconds, { seconds = it }, valueRange = 0f..60f, steps = 59)
                }
            },
            confirmButton = { TextButton(onClick = { onAutoStartChange(true, seconds.roundToInt() * 1000L); showAutoStart = false }) { Text("确认启用") } },
            dismissButton = { TextButton(onClick = { showAutoStart = false }) { Text("取消") } })
    }
}

@Composable
private fun RuntimeStatusPanel(env: EnvironmentUiState, onOpenEnvironment: () -> Unit) {
    val states = listOf(
        Triple("悬浮窗", Icons.Outlined.Layers, env.overlayGranted),
        Triple("使用情况", Icons.Outlined.Assessment, env.usageAccessGranted),
        Triple("Root", Icons.Outlined.Security, env.hasRoot),
        Triple("守护进程", Icons.Outlined.Shield, env.daemonRuntime.running),
        Triple("前台监听", Icons.Outlined.Visibility, env.foregroundState?.available == true)
    )
    Panel("运行状态", action = if (env.loading) "检测中" else if (states.all { it.third }) "查看详情" else "检查环境",
        onAction = onOpenEnvironment) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            states.forEach { (label, icon, healthy) ->
                val color = if (healthy) OceanSuccess else OceanWarning
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = .06f))
                    .padding(horizontal = 2.dp, vertical = 11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.height(6.dp))
                    Text(label, color = OceanText, fontSize = 10.sp, lineHeight = 14.sp,
                        minLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) 2 else 1,
                        maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(if (env.loading) "检测" else if (healthy) "正常" else "待处理", color = color, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ApplicationRow(app: AppItemModel, added: Boolean, enabled: Boolean, busy: Boolean, onManage: () -> Unit, onAction: () -> Unit) {
    val pending = added && app.state == AppRuleState.PENDING && !app.isSystemComponent
    Surface(modifier = Modifier.padding(bottom = 10.dp), color = OceanSurface, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().then(if (added) Modifier.clickable(onClick = onManage) else Modifier).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            AppPackageIcon(app, "${app.label} 图标")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(app.packageName, color = OceanTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onAction, enabled = if (added && !pending) !busy else enabled && app.available,
                modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (!added) "添加" else if (pending) "启动" else "管理", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                !app.available || app.unhealthyRuleCount > 0 -> OceanWarning
                app.isSystemComponent -> PorcelainOnTonal
                pending || !added -> PorcelainOnTonal
                else -> OceanSuccess
            }
            StatusDot(color)
            Spacer(Modifier.width(6.dp))
            Text(when {
                app.isSystemComponent -> "系统组件 · " + when {
                    !added -> "未添加"
                    app.unhealthyRuleCount > 0 -> "${app.unhealthyRuleCount} 条规则待复检"
                    else -> "${app.ruleCount} 条规则 · ${app.cpuSummary}"
                }
                !app.available -> "未安装 · ${app.ruleCount} 条规则"
                app.unhealthyRuleCount > 0 -> "${app.unhealthyRuleCount} 条规则待复检"
                !added -> "未添加"
                pending -> "待校准 · 点击应用可管理"
                else -> "${app.ruleCount} 条规则 · ${app.cpuSummary}"
            }, color = color, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
            if (!app.isSystemComponent && app.averageFps != null) {
                Spacer(Modifier.width(6.dp))
                Text("上次 ${formatAverageFps(app.averageFps)}", color = OceanSuccess, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(OceanSuccess.copy(alpha = .07f))
                        .padding(horizontal = 7.dp, vertical = 5.dp))
            }
        }
        }
    }
}

internal fun formatAverageFps(value: Float): String = String.format(Locale.US, "%.1f FPS", value)
internal fun formatSessionDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}
