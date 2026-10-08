package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryRetention
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.HistorySource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    state: HistoryUiState, contentPadding: PaddingValues, onRefresh: () -> Unit,
    onOpenSession: (HistoryPackageModel, SessionSummary) -> Unit, onBack: () -> Unit,
    onSelectSession: (SessionSummary) -> Unit, onDeletePackage: (HistoryPackageModel) -> Unit,
    onDeleteSession: (Long) -> Unit, onExportPackage: (HistoryPackageModel) -> Unit,
    onExportSession: (Long) -> Unit, onNewCalibration: () -> Unit,
    onDeleteSessions: (Set<Long>) -> Unit,
    autoHistoryEnabled: Boolean = false,
    onSelectWindow: (Int) -> Unit = {},
    onCompare: (String, SessionSummary, SessionSummary) -> Unit = { _, _, _ -> },
    onCloseComparison: () -> Unit = {}
) {
    state.comparison?.let { comparison ->
        HistoryComparisonScreen(comparison, contentPadding, onCloseComparison) {
            onCompare(comparison.packageName, comparison.first, comparison.second)
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceName by rememberSaveable { mutableStateOf(HistorySource.CALIBRATION.name) }
    val source = HistorySource.entries.firstOrNull { it.name == sourceName } ?: HistorySource.CALIBRATION
    var deleteApp by remember { mutableStateOf<HistoryPackageModel?>(null) }
    var deleteRecord by remember { mutableStateOf<Pair<HistoryPackageModel, SessionSummary>?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    // 切换筛选时重置选择范围，隐藏记录不会继续保持选中。
    var selectedIds by rememberSaveable(sourceName, filter, query) { mutableStateOf(emptyList<Long>()) }
    var deleteSelection by remember(sourceName, filter, query) { mutableStateOf<List<Long>?>(null) }
    val listState = rememberLazyListState()
    val sourcePackages = remember(state.packages, source) {
        state.packages.mapNotNull { app ->
            val sessions = app.sessions.filter { it.source == source }
            if (sessions.isEmpty()) null else app.copy(sessions = sessions, sessionCount = sessions.size)
        }
    }
    val selectedApp = sourcePackages.firstOrNull { it.packageName == filter }
    val sourceCount = remember(sourcePackages, source) {
        sourcePackages.sumOf { it.sessions.size }
    }
    val visible = remember(sourcePackages, query, filter, source) {
        sourcePackages.filter { (filter == null || it.packageName == filter) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true)) }
            .flatMap { app -> app.sessions.map { app to it } }
            .sortedByDescending { it.second.endedAtMs }
    }
    val days = remember(visible) { visible.groupBy { historyTime(it.second.epoch, "yyyy-MM-dd") } }
    val selectableIds = remember(visible, state.busySessionIds, state.busyPackages) {
        visible.filterNot { (app, session) -> session.id in state.busySessionIds || app.packageName in state.busyPackages }
            .map { it.second.id }.toSet()
    }
    val selected = remember(selectedIds, selectableIds) { selectedIds.filter { it in selectableIds }.toSet() }
    val sourceCounts = remember(state.packages) {
        HistorySource.entries.associateWith { entry -> state.packages.sumOf { app -> app.sessions.count { it.source == entry } } }
    }
    LaunchedEffect(query, filter, source) { listState.scrollToItem(0) }
    LaunchedEffect(selectableIds) { selectedIds = selectedIds.filter { it in selectableIds } }
    LaunchedEffect(state.detail?.expandedSessionId) {
        state.detail?.let { detail ->
            detail.sessions.firstOrNull { it.id == detail.expandedSessionId }?.let { sourceName = it.source.name }
        }
    }
    LaunchedEffect(state.packages, state.loading) {
        if (!state.loading && filter != null && state.packages.none { it.packageName == filter }) filter = null
    }
    if (state.detail != null) {
        HistoryReportScreen(state.detail, contentPadding, onBack, onSelectSession,
            onExportSession, onDeleteSession, state.busySessionIds, onSelectWindow,
            state.packages.firstOrNull { it.packageName == state.detail.packageName }?.sessions.orEmpty(),
            { first, second -> onCompare(state.detail.packageName, first, second) })
        return
    }
    BackHandler(enabled = selecting) {
        selecting = false
        selectedIds = emptyList()
        deleteSelection = null
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OceanBackground, ClearTechBackgroundEnd)))
        .padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QIXIA / HISTORY", color = PorcelainOnTonal, fontSize = 10.sp, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Medium)
                Text(if (selecting) "选择记录" else "运行档案", color = OceanText, fontSize = 29.sp, lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                Text(if (selecting) "${HistoryPresentation.sourceLabel(source)} · 已选择 ${selected.size} 条"
                    else "回看性能表现，了解每一次运行", color = OceanTextSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (selecting) {
                TextButton(onClick = {
                    selecting = false
                    selectedIds = emptyList()
                    deleteSelection = null
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", fontSize = 14.sp) }
            } else {
                Surface(onClick = { selecting = true }, enabled = selectableIds.isNotEmpty() && !state.loading,
                    shape = RoundedCornerShape(16.dp), color = OceanSurface,
                    border = BorderStroke(1.dp, OceanOutline)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Checklist, "批量管理记录", Modifier.size(22.dp),
                            tint = if (selectableIds.isNotEmpty()) PorcelainOnTonal else OceanTextSecondary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(onClick = onRefresh, enabled = !state.loading,
                    shape = RoundedCornerShape(16.dp), color = OceanSurface,
                    border = BorderStroke(1.dp, OceanOutline)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Refresh, "刷新运行档案", Modifier.size(22.dp), tint = PorcelainOnTonal)
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history-records"), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "source") {
                HistorySourceSelector(source, sourceCounts) { sourceName = it.name }
            }
            item(key = "summary") {
                if (source == HistorySource.CALIBRATION) {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("校准结果，随时回看", color = OceanText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("最多保留 ${HistoryRetention.CALIBRATION_LIMIT} 条 · 已收录 ${sourcePackages.size} 个应用", color = OceanTextSecondary, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        if (!selecting) TextButton(onNewCalibration, shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("开始校准", fontSize = 12.sp)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (autoHistoryEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                            null, Modifier.padding(top = 2.dp).size(16.dp), tint = PorcelainOnTonal)
                        Column {
                            Text(if (autoHistoryEnabled) "前台使用超过 3 分钟才留档" else "自动记录已关闭",
                                color = OceanText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(if (autoHistoryEnabled) "最多保留 ${HistoryRetention.AUTOMATIC_LIMIT} 条 · 已收录 ${sourcePackages.size} 个应用"
                                else "可在设置中开启，已有记录仍保留，最多 ${HistoryRetention.AUTOMATIC_LIMIT} 条。", color = OceanTextSecondary,
                                fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
            item(key = "search") { SearchField(query, { query = it }, "搜索应用名称或包名") }
            item(key = "apps") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filter == null, { filter = null }, label = { Text("全部应用", fontSize = 12.sp) }, shape = RoundedCornerShape(12.dp)) }
                    items(state.packages.filter { app -> sourcePackages.any { it.packageName == app.packageName } || app.packageName == filter }, key = { it.packageName }) { app ->
                        FilterChip(filter == app.packageName, { filter = app.packageName },
                            label = { Text(app.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
                            leadingIcon = { AppDrawableIcon(app.icon, "", Modifier.size(20.dp)) }, shape = RoundedCornerShape(12.dp))
                    }
                }
            }
            if (selectedApp != null && !selecting) item(key = "app-actions") {
                Surface(color = OceanSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, OceanOutline)) {
                    Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前分类 · ${selectedApp.sessions.size} 次", Modifier.weight(1f), color = OceanTextSecondary, fontSize = 11.sp)
                        TextButton({ onExportPackage(selectedApp) }, enabled = visible.isNotEmpty()) { Text("导出全部", fontSize = 12.sp) }
                        IconButton({ deleteApp = selectedApp }, enabled = selectedApp.packageName !in state.busyPackages) {
                            Icon(Icons.Outlined.DeleteOutline, "删除此应用当前分类的历史", tint = OceanTextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (state.loading && state.packages.isEmpty()) item { LoadingState("正在读取历史记录") }
            else if (visible.isEmpty()) item {
                val searching = query.isNotBlank() || filter != null
                HistorySurface { EmptyState(if (searching) "没有匹配的记录" else "还没有${HistoryPresentation.sourceLabel(source)}",
                    when {
                        searching -> "试试其他名称，或切换到全部应用。"
                        source == HistorySource.CALIBRATION -> "从应用页启动一次校准，完成后在这里查看报告与线程曲线。"
                        !autoHistoryEnabled -> "在设置中开启“记录自动分配历史”，再使用已开启自动分配的应用。记录默认关闭。"
                        else -> "使用已开启自动分配的应用，前台使用超过 3 分钟，结束后便会在这里生成完整报告。"
                    }) }
            }
            days.forEach { (date, records) ->
                item(key = "date-$date") {
                    Row(Modifier.fillMaxWidth().padding(start = 3.dp, top = 9.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(historyTime(records.first().second.epoch, "MM.dd"), color = OceanText,
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(historyTime(records.first().second.epoch, "EEEE"), color = OceanTextSecondary, fontSize = 10.sp)
                            Text(historyTime(records.first().second.epoch, "yyyy"), color = OceanTextSecondary, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        Text("${records.size} 次运行", color = OceanTextSecondary, fontSize = 11.sp)
                    }
                }
                items(records, key = { it.second.id }) { (app, session) ->
                    HistoryRecordCard(app, session, session.id in state.busySessionIds || app.packageName in state.busyPackages,
                        {
                            if (selecting) {
                                selectedIds = if (session.id in selected) selectedIds - session.id else selectedIds + session.id
                            } else onOpenSession(app, session)
                        }, { onExportSession(session.id) }, { deleteRecord = app to session },
                        selecting = selecting, selected = session.id in selected)
                }
            }
            if (visible.isNotEmpty()) item {
                Text("${visible.size} / $sourceCount 次记录 · 数据仅保存在本机", color = OceanTextSecondary, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
        if (selecting) HistorySelectionBar(
            selectedCount = selected.size,
            allSelected = selectableIds.isNotEmpty() && selected.size == selectableIds.size,
            enabled = selectableIds.isNotEmpty() && !state.loading,
            onSelectAll = {
                selectedIds = if (selected.size == selectableIds.size) emptyList() else selectableIds.toList()
            },
            onDelete = { deleteSelection = selected.toList() }
        )
    }
    deleteApp?.let { app ->
        val category = app.sessions.firstOrNull()?.source?.let(HistoryPresentation::sourceLabel).orEmpty()
        HistoryDeleteDialog("删除这些$category？", "将删除 ${app.label} 的 ${app.sessions.size} 次${category}及相关曲线。其他分类和已保存的核心规则不受影响。",
            { deleteApp = null }, { deleteApp = null; onDeletePackage(app) })
    }
    deleteRecord?.let { (app, session) ->
        HistoryDeleteDialog("删除这次记录？", "${app.label} · ${historyTime(session.epoch)}\n本次负载记录和曲线会被删除，已保存的核心规则不受影响。",
            { deleteRecord = null }, { deleteRecord = null; onDeleteSession(session.id) })
    }
    deleteSelection?.let { confirmedIds ->
        // 确认操作绑定原快照，刷新或导入不得增加删除目标。
        val targets = confirmedIds.filter { it in selectableIds }.toSet()
        val changed = targets.size != confirmedIds.size
        AlertDialog(onDismissRequest = { deleteSelection = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = OceanError) },
            title = { Text(if (targets.isEmpty()) "所选记录已更新" else "删除 ${targets.size} 条记录？") },
            text = {
                Text(if (targets.isEmpty()) "所选记录已不存在或正在处理，无需重复删除。"
                    else buildString {
                        if (changed) append("部分记录已不存在或正在处理，将仅删除剩余 ${targets.size} 条。\n\n")
                        append("将删除所选${HistoryPresentation.sourceLabel(source)}及相关曲线，无法撤销。已保存的核心规则不受影响。")
                    })
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteSelection = null
                    selectedIds = emptyList()
                    selecting = false
                    if (targets.isNotEmpty()) onDeleteSessions(targets)
                }, enabled = targets.isNotEmpty() && !state.loading) {
                    Text("删除 ${targets.size} 条", color = if (targets.isNotEmpty() && !state.loading) OceanError else OceanTextSecondary)
                }
            },
            dismissButton = { TextButton({ deleteSelection = null }) { Text(if (targets.isEmpty()) "关闭" else "取消") } }
        )
    }
}

@Composable
private fun HistorySelectionBar(selectedCount: Int, allSelected: Boolean, enabled: Boolean,
    onSelectAll: () -> Unit, onDelete: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        color = OceanSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, OceanOutline)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onSelectAll, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(13.dp)) {
                Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (allSelected) "取消全选" else "全选当前结果", fontSize = 12.sp)
            }
            Button(onDelete, enabled = enabled && selectedCount > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PorcelainAction)) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("删除所选 · $selectedCount", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HistorySourceSelector(selected: HistorySource, counts: Map<HistorySource, Int>, onSelected: (HistorySource) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HistorySource.entries.forEach { source ->
            val active = source == selected
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                color = if (active) PorcelainHeader.copy(alpha = .62f) else OceanSurface,
                border = BorderStroke(1.dp, if (active) PorcelainOnTonal.copy(alpha = .38f) else OceanOutline)) {
                Column(Modifier.fillMaxWidth().testTag("history-source-${source.name}")
                    .selectable(active, role = Role.Tab, onClick = { onSelected(source) })
                    .heightIn(min = 76.dp).padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(HistoryPresentation.sourceLabel(source), Modifier.weight(1f),
                            color = if (active) PorcelainOnTonal else OceanText,
                            fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (active) Icon(Icons.Outlined.Check, null, Modifier.padding(start = 3.dp).size(16.dp), tint = PorcelainOnTonal)
                    }
                    Text("${counts[source] ?: 0} 次记录", color = OceanTextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(app: HistoryPackageModel, session: SessionSummary, busy: Boolean,
    onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
    selecting: Boolean = false, selected: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(selecting) { menu = false }
    val fps = app.sessionFps[session.id]
    Surface(onClick = onOpen, enabled = !busy,
        modifier = Modifier.testTag("history-record-${session.id}").semantics {
            if (selecting) {
                role = Role.Checkbox
                toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
            }
        }, shape = RoundedCornerShape(25.dp), color = OceanSurface,
        border = BorderStroke(if (selecting && selected) 1.5.dp else 1.dp,
            if (selecting && selected) PorcelainOnTonal else OceanOutline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppDrawableIcon(app.icon, "${app.label} 图标", Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = OceanText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(historyTime(session.epoch, "HH:mm:ss"), fontSize = 11.sp, color = OceanTextSecondary)
                }
                Column(Modifier.padding(start = 10.dp).widthIn(max = 86.dp), horizontalAlignment = Alignment.End) {
                    Text(historyDuration(session.durationMs), color = OceanText, fontSize = 14.sp,
                        lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("采集时长", color = OceanTextSecondary, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
            Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = OceanSurfaceHigh.copy(alpha = .72f),
                shape = RoundedCornerShape(17.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("平均帧率", color = OceanTextSecondary, fontSize = 10.sp)
                        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
                            Text(fps?.average?.let(::historyNumber) ?: "—", color = if (fps == null) OceanTextSecondary else PorcelainOnTonal,
                                fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                            Text(if (fps == null) "未采集" else "FPS", color = OceanTextSecondary, fontSize = 10.sp,
                                modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
                        }
                    }
                    VerticalDivider(Modifier.height(36.dp), color = OceanOutline)
                    Column(Modifier.weight(.8f).padding(start = 17.dp)) {
                        Text("线程记录", color = OceanTextSecondary, fontSize = 10.sp)
                        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Bottom) {
                            Text("${session.threadCount}", color = OceanText, fontSize = 23.sp, lineHeight = 34.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text("条", color = OceanTextSecondary, fontSize = 10.sp,
                                modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(HistoryPresentation.sourceLabel(session.source), color = OceanTextSecondary, fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
                if (selecting) {
                    Text(if (busy) "正在处理" else if (selected) "已选择" else "选择此记录",
                        color = if (selected) PorcelainOnTonal else OceanTextSecondary, fontSize = 11.sp)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = selected, onCheckedChange = null, enabled = !busy,
                            colors = CheckboxDefaults.colors(checkedColor = PorcelainAction))
                    }
                } else {
                    Text("查看报告", color = PorcelainOnTonal, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 2.dp).size(16.dp), tint = PorcelainOnTonal)
                    Box(Modifier.padding(start = 8.dp)) {
                        IconButton({ menu = true }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreHoriz, "记录操作", tint = OceanTextSecondary, modifier = Modifier.size(21.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("导出本次记录") }, onClick = { menu = false; onExport() },
                                leadingIcon = { Icon(Icons.Outlined.Download, null) })
                            DropdownMenuItem(text = { Text("删除本次记录", color = OceanError) }, onClick = { menu = false; onDelete() },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = OceanError) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoryMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier) {
        Text(label, color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = if (accent) PorcelainAction else OceanText, fontSize = 20.sp, lineHeight = 27.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (unit.isNotEmpty()) Text(unit, color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun HistorySurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = OceanSurface, shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, OceanOutline.copy(alpha = .8f)), content = content)
}

@Composable
internal fun HistoryDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onDelete) { Text("删除", color = OceanError) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

internal fun historyTime(epoch: Long, pattern: String = "yyyy-MM-dd HH:mm") =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epoch * 1000L))
internal fun historyNumber(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
internal fun historyDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "${seconds / 3600}h ${seconds % 3600 / 60}m"
        else if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}
