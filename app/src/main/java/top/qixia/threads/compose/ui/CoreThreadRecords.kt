package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StackedLineChart
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.*
import top.qixia.threads.compose.CoreTableIndex
import top.qixia.threads.compose.CoreRecordPresentation
import top.qixia.threads.compose.CoreRecordRow
import top.qixia.threads.compose.CoreTraceGraph
import top.qixia.threads.compose.CoreTimelinePresentation as Timeline
import top.qixia.threads.compose.theme.*

private enum class CoreEventFilter(val label: String) {
    ALL("全部"), ACTIONS("分配 / 释放"), OBSERVED("采样观察");
    fun accepts(event: CoreEvent) = when (this) {
        ALL -> true
        ACTIONS -> Timeline.isOperation(event) || event.kind == CoreEventKind.ERROR
        OBSERVED -> event.kind == CoreEventKind.OBSERVE || event.kind == CoreEventKind.EXTERNAL
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoreThreadRecords(thread: CoreTableIndex.Thread, report: CoreTimelineReport, time: Long, onDismiss: () -> Unit) {
    val events = thread.events
    val locale = LocalConfiguration.current.locales[0]
    val records by produceState<List<CoreRecordRow>?>(null, thread, locale) {
        value = null
        value = withContext(Dispatchers.Default) { CoreRecordPresentation.prepare(events, locale) }
    }
    var filter by remember(thread) { mutableStateOf(CoreEventFilter.ALL) }
    var showChart by remember(thread) { mutableStateOf(true) }
    val cursor = remember(thread) { mutableIntStateOf(thread.snapshotAt(time)?.eventIndex ?: 0) }
    val indices = remember(events, filter) { events.indices.filter { filter.accepts(events[it]) } }
    val counts = remember(events) { CoreEventFilter.entries.associateWith { choice -> events.count(choice::accepts) } }
    val graph by produceState<CoreTraceGraph?>(null, thread, report) {
        value = null
        value = withContext(Dispatchers.Default) { CoreTraceGraph.build(report.startMs, report.endMs, events) }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(filter, records) {
        if (records == null) return@LaunchedEffect
        if (indices.binarySearch(cursor.intValue) < 0) cursor.intValue = indices.firstOrNull() ?: -1
        val position = indices.binarySearch(cursor.intValue)
        if (showChart) list.scrollToItem(0)
        else if (position >= 0) list.scrollToItem(position + 1)
    }
    // 与规则编辑器一致：移动边距不得改变弹窗大小或快速滑动中的锚点。
    val viewportHeight = LocalConfiguration.current.screenHeightDp.dp * .92f
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OceanBackground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null,
        sheetGesturesEnabled = false, contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.fillMaxWidth().height(viewportHeight).navigationBarsPadding()) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = PorcelainHeader, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Memory, null, Modifier.size(21.dp), tint = OceanPrimary) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(thread.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OceanText,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("TID " + thread.identity.tid + " · PID " + thread.identity.pid + " · " + events.size + " 条记录",
                        fontSize = 11.sp, color = OceanTextSecondary, modifier = Modifier.padding(top = 5.dp))
                }
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "返回调度表格") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("核心轨迹与逐次事件", color = OceanText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton({
                    showChart = !showChart
                    val position = indices.binarySearch(cursor.intValue)
                    scope.launch { list.scrollToItem(if (showChart || position < 0) 0 else position + 1) }
                }) {
                    Icon(Icons.Outlined.StackedLineChart, null, Modifier.size(16.dp))
                    Text(if (showChart) "收起核心图" else "核心图", Modifier.padding(start = 4.dp), fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoreEventFilter.entries.forEach { choice ->
                    Surface(onClick = { filter = choice }, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (filter == choice) PorcelainHeader else Color.Transparent) {
                        Box(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(choice.label + " " + counts[choice], fontSize = 11.sp,
                                color = if (filter == choice) OceanPrimary else OceanTextSecondary)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp).testTag("core-event-table"), state = list,
                contentPadding = PaddingValues(bottom = 20.dp)) {
                item(key = "graph", contentType = "graph") {
                    if (showChart) {
                        val preparedGraph = graph
                        if (preparedGraph == null) LoadingState("正在准备核心轨迹")
                        else CoreTracePanel(preparedGraph, cursor, indices, report.startMs) {
                            showChart = false
                            val position = indices.binarySearch(cursor.intValue)
                            if (position >= 0) scope.launch { list.scrollToItem(position + 1) }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    Row(Modifier.fillMaxWidth().background(PorcelainHeader.copy(alpha = .5f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text("记录时间", Modifier.width(84.dp), fontSize = 10.sp, color = OceanTextSecondary)
                        Text("允许范围 / 执行采样", Modifier.weight(1f), fontSize = 10.sp, color = OceanTextSecondary)
                        Text("事件 / 负载", Modifier.width(72.dp), fontSize = 10.sp, color = OceanTextSecondary)
                    }
                }
                val prepared = records
                if (prepared == null) item(key = "loading", contentType = "loading") { LoadingState("正在准备记录") }
                else if (indices.isEmpty()) item(key = "empty", contentType = "empty") { EmptyState("当前筛选没有记录", "切换到全部查看。") }
                else items(indices, key = { it }, contentType = { "event" }) { eventIndex ->
                    val selected by remember(cursor, eventIndex) { derivedStateOf { cursor.intValue == eventIndex } }
                    CoreEventTableRow(prepared[eventIndex], selected) { cursor.intValue = eventIndex }
                }
            }
            }
        }
    }
}

@Composable
private fun CoreEventTableRow(row: CoreRecordRow, selected: Boolean, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (selected) Color(0xFFEDF3FF) else Color.White)
        .testTag("core-event-" + row.index).clickable(onClick = onSelect).padding(horizontal = 12.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(84.dp)) {
                Text(row.time, fontSize = 10.sp, color = OceanText, fontWeight = FontWeight.Medium)
                Text(row.date, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = OceanTextSecondary)
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(if (row.rangeUnchanged) "范围未变" else row.before,
                    fontSize = 11.sp, color = OceanTextSecondary, lineHeight = 16.sp)
                Text((if (row.rangeUnchanged) "" else "→ ") + row.after,
                    Modifier.padding(top = 4.dp), fontSize = 12.sp,
                    color = if (row.kind == CoreEventKind.ERROR) OceanError else OceanPrimary,
                    fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                Text("范围归属 · " + row.sourceLabel, Modifier.padding(top = 5.dp),
                    fontSize = 10.sp, color = OceanTextSecondary)
                row.runningCpuLabel?.let { Text(it, Modifier.padding(top = 4.dp),
                    fontSize = 10.sp, color = OceanPrimary) }
            }
            Column(Modifier.width(72.dp)) {
                Text(coreEventTitle(row.kind), fontSize = 11.sp, color = coreEventColor(row.kind))
                Text(row.average, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = OceanTextSecondary)
            }
        }
        if (selected) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OceanDivider)
            Text(row.reason, fontSize = 12.sp, lineHeight = 18.sp,
                color = OceanText)
        }
    }
    HorizontalDivider(color = OceanDivider)
}
