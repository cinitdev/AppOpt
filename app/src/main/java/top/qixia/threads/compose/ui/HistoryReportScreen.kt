package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.HistorySource

private enum class ReportArea(val title: String, val detail: String, val icon: ImageVector, val groups: List<MetricsGroup>) {
    OVERVIEW("概览", "流畅度与关键线程", Icons.Outlined.Insights, listOf(MetricsGroup.FRAME_TIME)),
    CORE("调度", "自动分配与采样观察", Icons.Outlined.Route, emptyList()),
    CPU("处理器", "使用率、频率与硬件周期", Icons.Outlined.Memory,
        listOf(MetricsGroup.CPU_USAGE, MetricsGroup.CPU_FREQUENCY, MetricsGroup.CPU_CYCLES)),
    GRAPHICS("图形", "GPU 与内存运行状态", Icons.Outlined.Layers,
        listOf(MetricsGroup.GPU_FREQUENCY, MetricsGroup.DDR)),
    ENERGY("能耗", "功率、电量与温度变化", Icons.Outlined.Bolt,
        listOf(MetricsGroup.POWER, MetricsGroup.TEMPERATURE))
}

@Composable
internal fun HistoryReportScreen(state: HistoryDetailUiState, padding: PaddingValues, onBack: () -> Unit,
    onSelectSession: (SessionSummary) -> Unit, onExport: (Long) -> Unit, onDelete: (Long) -> Unit,
    busy: Set<Long>, onSelectWindow: (Int) -> Unit = {},
    comparisonCandidates: List<SessionSummary> = emptyList(), onCompare: (SessionSummary, SessionSummary) -> Unit = { _, _ -> }) {
    val wholeSession = state.sessions.firstOrNull { it.id == state.expandedSessionId }
    val session = state.reportSession ?: wholeSession
    val hasWindows = state.reportWindows.size > 1
    val automatic = session?.source == HistorySource.AUTO_ALLOCATION
    var threadsPage by rememberSaveable(state.packageName, state.expandedSessionId) { mutableStateOf(false) }
    var selectSession by remember(state.packageName, state.expandedSessionId) { mutableStateOf(false) }
    var delete by remember(state.packageName, state.expandedSessionId) { mutableStateOf(false) }
    var compare by remember(state.packageName, state.expandedSessionId) { mutableStateOf(false) }
    var selectedDrop by remember(state.packageName, state.expandedSessionId, state.reportWindowIndex) { mutableStateOf<FrameDrop?>(null) }
    var areaName by rememberSaveable(state.packageName, state.expandedSessionId) { mutableStateOf(ReportArea.OVERVIEW.name) }
    val area = ReportArea.entries.firstOrNull { it.name == areaName } ?: ReportArea.OVERVIEW
    val cursor = remember(state.packageName, state.expandedSessionId, state.reportWindowIndex, threadsPage, area) { mutableStateOf<Float?>(null) }
    val pages = key(state.packageName, state.expandedSessionId, state.reportWindowIndex) { rememberSaveableStateHolder() }
    val fps = state.sessionFps[session?.id]
    val metrics = state.sessionMetrics[session?.id]
    val drops by produceState(emptyList<FrameDrop>(), fps, metrics, session?.durationMs) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            HistoryAnalysis.drops(fps, metrics, session?.durationMs ?: 0)
        }
    }
    val threads = remember(state.threads) { state.threads.sortedByDescending { it.avg } }
    BackHandler { if (threadsPage) threadsPage = false else onBack() }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OceanBackground, ClearTechBackgroundEnd))).padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (threadsPage) threadsPage = false else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = OceanText)
            }
            Column(Modifier.weight(1f)) {
                Text(if (threadsPage) "线程分析" else "运行报告", color = OceanText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(state.label, color = OceanTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ compare = true }, enabled = wholeSession != null && comparisonCandidates.any { it.id != wholeSession.id }) {
                Icon(Icons.AutoMirrored.Outlined.CompareArrows, "对比另一条记录", tint = PorcelainOnTonal)
            }
            IconButton({ session?.let { onExport(it.id) } }, enabled = session != null) {
                Icon(Icons.Outlined.Download, if (hasWindows) "导出整次记录（全部区间）" else "导出本次记录", tint = PorcelainOnTonal)
            }
            IconButton({ delete = true }, enabled = session != null && session.id !in busy) {
                Icon(Icons.Outlined.DeleteOutline, if (hasWindows) "删除整次记录（全部区间）" else "删除本次记录",
                    tint = OceanTextSecondary, modifier = Modifier.size(21.dp))
            }
        }
        if (state.reportWindows.size > 1 && wholeSession != null) {
            ReportWindowSelector(state.reportWindows, state.reportWindowIndex, wholeSession,
                state.loading, onSelectWindow)
        }
        if (threadsPage && session != null) {
            pages.SaveableStateProvider("threads") {
                HistoryThreadList(threads, state.threadsLoading, state.threadsError, { wholeSession?.let(onSelectSession) })
            }
        } else {
            ReportNavigation(area, automatic) { cursor.value = null; areaName = it.name }
            // 保留各分区的滚动位置、图例选择和指标展开状态，不重新解析数据。
            if (area == ReportArea.CORE && automatic) {
                key(state.reportWindowIndex) {
                    pages.SaveableStateProvider("core-timeline") {
                        HistoryCoreTimeline(state.coreTimeline, state.loading, state.threadsError, { wholeSession?.let(onSelectSession) })
                    }
                }
            } else pages.SaveableStateProvider(area.name) {
                val sectionScroll = rememberLazyListState()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = sectionScroll,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading) item { LoadingState("正在读取报告") }
                    else if (state.threadsError != null && state.reportSession == null) item {
                        HistorySurface {
                            Column(Modifier.padding(18.dp)) {
                                Text(if (hasWindows) "本区间读取失败" else "报告读取失败", color = OceanError,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(state.threadsError, Modifier.padding(top = 8.dp), color = OceanTextSecondary,
                                    fontSize = 12.sp, lineHeight = 18.sp)
                                TextButton({ wholeSession?.let(onSelectSession) }) { Text("重新读取") }
                            }
                        }
                    }
                    else if (session == null) item { EmptyState("没有会话记录", "返回历史列表，刷新后重试。") }
                    else {
                        item(key = "identity") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppDrawableIcon(state.icon, state.label)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(state.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = OceanText,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(historyTime((wholeSession ?: session).epoch, "MM月dd日 HH:mm:ss"), color = OceanTextSecondary, fontSize = 11.sp)
                                    Text("${HistoryPresentation.sourceLabel(session.source)} · ${historyDuration((wholeSession ?: session).durationMs)}",
                                        color = PorcelainOnTonal, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                                Box {
                                    TextButton({ selectSession = true }, enabled = state.sessions.size > 1) {
                                        Text("切换记录", fontSize = 12.sp)
                                    }
                                    DropdownMenu(selectSession, { selectSession = false }) {
                                        state.sessions.forEach { entry ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(historyTime(entry.epoch))
                                                        Text(HistoryPresentation.sourceLabel(entry.source),
                                                            color = OceanTextSecondary, fontSize = 11.sp)
                                                    }
                                                },
                                                leadingIcon = { if (entry.id == session.id) Icon(Icons.Outlined.Check, null) },
                                                onClick = { selectSession = false; onSelectSession(entry) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "section") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text((if (state.reportWindows.size > 1) "本区间 · " else "") + area.detail,
                                    color = OceanTextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.TouchApp, null, Modifier.size(14.dp), tint = OceanTextSecondary)
                                Text("滑动查看", color = OceanTextSecondary, fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        if (area == ReportArea.OVERVIEW) {
                            if (automatic) item(key = "core-entry") {
                                CoreTimelineEntry(state.coreTimeline) { cursor.value = null; areaName = ReportArea.CORE.name }
                            }
                            item(key = "summary") {
                                HistorySessionSummary(fps, metrics, session.durationMs, session.threadCount)
                            }
                            item(key = "fps-${session.id}") {
                                HistoryFpsPlotCard(fps, metrics, session.durationMs, cursor, drops.map { it.progress }) { position ->
                                    selectedDrop = drops.minByOrNull { kotlin.math.abs(it.progress - position) }
                                    cursor.value = selectedDrop?.progress
                                }
                            }
                            item(key = "drop-analysis") {
                                FrameDropAnalysisCard(state, session, drops, selectedDrop,
                                    { selectedDrop = it; cursor.value = it?.progress })
                            }
                        }
                        area.groups.forEach { group ->
                            item(key = "metrics-${session.id}-$group") {
                                HistoryMetricsCard(group, metrics, session.durationMs, cursor)
                            }
                        }
                        if (area == ReportArea.OVERVIEW || area == ReportArea.CPU) {
                            item(key = "threads-entry") {
                                Surface(onClick = { cursor.value = null; threadsPage = true }, shape = RoundedCornerShape(20.dp),
                                    color = PorcelainHeader.copy(alpha = .7f)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Memory, null, tint = PorcelainOnTonal, modifier = Modifier.size(26.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("分析活跃线程", color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                            Text("${session.threadCount} 条记录 · ${if (hasWindows) "本区间曲线" else "完整曲线"}与子线程明细",
                                                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp)
                                        }
                                        Icon(Icons.Outlined.ChevronRight, "查看全部线程曲线", tint = PorcelainOnTonal)
                                    }
                                }
                            }
                            item(key = "ranking") {
                                HistorySurface {
                                    Column(Modifier.padding(16.dp)) {
                                        ReportSectionTitle("主要负载", "平均 CPU %")
                                        when {
                                            state.threadsLoading -> LoadingState("正在读取线程")
                                            state.threadsError != null -> {
                                                Text("读取失败：${state.threadsError}", color = OceanError, fontSize = 12.sp)
                                                TextButton({ wholeSession?.let(onSelectSession) }) { Text("重试") }
                                            }
                                            threads.isEmpty() -> Text("此记录没有负载数据", color = OceanTextSecondary)
                                            else -> threads.take(5).forEachIndexed { index, thread ->
                                                Spacer(Modifier.height(16.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("0${index + 1}", color = PorcelainOnTonal, fontSize = 11.sp,
                                                        modifier = Modifier.width(28.dp))
                                                    Text(thread.name, color = OceanText, fontSize = 12.sp, maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("${historyNumber(thread.avg)}%", color = PorcelainOnTonal,
                                                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                                LinearProgressIndicator(
                                                    progress = { (thread.avg / threads.first().avg.coerceAtLeast(1f)).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 8.dp).height(4.dp),
                                                    color = OceanPrimary.copy(alpha = 1f - index * .13f),
                                                    trackColor = OceanSurfaceHigh,
                                                    drawStopIndicator = {},
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text("进程汇总与线程分开保留，不重复相加为设备总负载。",
                                            color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp)
                                    }
                                }
                            }
                        }
                        item(key = "coverage") {
                            Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                                Text(if (hasWindows) "关于本区间" else "关于这次记录", color = OceanTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(if (metrics == null) "本次未保存设备指标。未取得的功率、温度、CPU / GPU 频率等数据留空，不会补入其他时段的数据。" else
                                    "设备指标与本次记录时段对齐，约覆盖 ${(metrics.coverage * 100).toInt()}% 的时段。缺失或充电期间不补零，曲线保留断点；未取得的指标留空；详情见各图表说明。",
                                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(state.packageName, color = OceanTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (delete && session != null) HistoryDeleteDialog(if (hasWindows) "删除整次记录？" else "删除这次记录？",
        if (hasWindows) "这次记录的全部 ${state.reportWindows.size} 个区间都会被删除，包括所有负载数据与曲线。已保存的核心规则不受影响。"
        else "本次负载数据与曲线会被删除，已保存的核心规则不受影响。",
        { delete = false }, { delete = false; onDelete(session.id) })
    if (compare && wholeSession != null) AlertDialog(onDismissRequest = { compare = false },
        title = { Text("选择对照记录") }, text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                comparisonCandidates.filter { it.id != wholeSession.id }.forEach { candidate -> item(key = candidate.id) {
                    TextButton({ compare = false; onCompare(wholeSession, candidate) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(historyTime(candidate.epoch, "MM-dd HH:mm:ss"))
                            Text("${HistoryPresentation.sourceLabel(candidate.source)} · ${historyDuration(candidate.durationMs)}", fontSize = 12.sp)
                        }
                    }
                } }
            }
        }, confirmButton = { TextButton({ compare = false }) { Text("取消") } })
}

@Composable
private fun ReportWindowSelector(windows: List<HistoryReportWindow>, selected: Int,
    session: SessionSummary, loading: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    val current = windows.getOrNull(selected) ?: return
    fun label(window: HistoryReportWindow): String =
        "${historyDuration((window.startMs - session.startedAtMs).coerceAtLeast(0))} – " +
            historyDuration((window.endMs - session.startedAtMs).coerceAtLeast(0))
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("同次记录 · 区间 ${selected + 1} / ${windows.size}", color = OceanTextSecondary, fontSize = 11.sp)
            Text("以下图表与指标对应本区间", color = OceanTextSecondary, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
        Box {
            TextButton({ expanded = true }, enabled = !loading) {
                Text(label(current), color = PorcelainOnTonal, fontSize = 12.sp)
                Icon(Icons.Outlined.ExpandMore, "选择查看区间", Modifier.padding(start = 4.dp).size(18.dp), tint = PorcelainOnTonal)
            }
            DropdownMenu(expanded, { expanded = false }) {
                windows.forEach { window ->
                    DropdownMenuItem(text = { Text("${window.index + 1}. ${label(window)}") },
                        leadingIcon = { if (window.index == selected) Icon(Icons.Outlined.Check, null) },
                        onClick = { expanded = false; onSelect(window.index) })
                }
            }
        }
    }
}

@Composable
private fun ReportNavigation(selected: ReportArea, automatic: Boolean, onSelect: (ReportArea) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(18.dp))
        .background(PorcelainHeader.copy(alpha = .65f)).padding(5.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReportArea.entries.filter { automatic || it != ReportArea.CORE }.forEach { area ->
            val active = selected == area
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (active) OceanSurface else Color.Transparent)
                .selectable(active, role = Role.Tab, onClick = { onSelect(area) }).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(area.icon, null, Modifier.size(20.dp), tint = if (active) OceanPrimary else OceanTextSecondary)
                Text(area.title, color = if (active) OceanPrimary else OceanTextSecondary, fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
internal fun ReportSectionTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(detail, color = OceanTextSecondary, fontSize = 10.sp)
    }
}
