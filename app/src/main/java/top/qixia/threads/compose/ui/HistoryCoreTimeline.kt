package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.*
import top.qixia.threads.compose.CoreTableIndex
import top.qixia.threads.compose.CoreTimelinePresentation as Timeline
import top.qixia.threads.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val CoreObservedColor = Color(0xFF536580)
internal fun coreEventColor(event: CoreEvent): Color = coreEventColor(event.kind)
internal fun coreEventColor(kind: CoreEventKind): Color = when (kind) {
    CoreEventKind.ERROR -> OceanError
    CoreEventKind.ASSIGN, CoreEventKind.RELEASE -> OceanPrimary
    else -> CoreObservedColor
}
internal fun coreEventTitle(event: CoreEvent): String = coreEventTitle(event.kind)
internal fun coreEventTitle(kind: CoreEventKind): String = when (kind) {
    CoreEventKind.ASSIGN -> "自动分配"
    CoreEventKind.RELEASE -> "交回系统"
    CoreEventKind.OBSERVE -> "采样观察"
    CoreEventKind.EXTERNAL -> "外部调整"
    CoreEventKind.EXIT -> "线程结束"
    CoreEventKind.ERROR -> "操作未完成"
}
internal fun coreEventSourceLabel(event: CoreEvent): String =
    if (event.legacy && event.kind == CoreEventKind.RELEASE) "旧版未记录" else Timeline.source(event.source)

@Composable
internal fun CoreTimelineEntry(report: CoreTimelineReport?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = PorcelainHeader.copy(alpha = .65f), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, null, Modifier.size(21.dp), tint = OceanPrimary)
            Text("查看调度记录", Modifier.weight(1f).padding(start = 11.dp), color = OceanText,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            val count = remember(report) { report?.events?.count(Timeline::isOperation) ?: 0 }
            if (count > 0) Text(count.toString() + " 次调整", color = OceanTextSecondary, fontSize = 11.sp)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 6.dp).size(20.dp), tint = PorcelainOnTonal)
        }
    }
}

/** 游标状态在导航器和可见表格行内读取，不参与整份报告的组合。 */
@Composable
internal fun ColumnScope.HistoryCoreTimeline(report: CoreTimelineReport?, loading: Boolean, error: String?, onRetry: () -> Unit) {
    val prepared by produceState<Pair<CoreTimelineReport, CoreTableIndex>?>(null, report) {
        value = null
        report?.let { current ->
            value = withContext(Dispatchers.Default) { current to CoreTableIndex.build(current) }
        }
    }
    val index = prepared?.takeIf { it.first === report }?.second
    val time = rememberSaveable(report?.sessionId) { mutableLongStateOf(index?.endMs ?: 0L) }
    var selectedId by rememberSaveable(report?.sessionId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(report?.sessionId) { mutableStateOf("") }
    var searchExpanded by rememberSaveable(report?.sessionId) { mutableStateOf(false) }
    var onlyAssigned by rememberSaveable(report?.sessionId) { mutableStateOf(false) }
    var explanation by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        index?.let { if (time.longValue !in it.startMs..it.endMs) time.longValue = it.endMs }
    }
    val threads = remember(index, query, onlyAssigned) {
        index?.threads.orEmpty().filter {
            (!onlyAssigned || it.operations > 0) &&
                (it.name.contains(query, true) || it.identity.tid.toString().contains(query))
        }
    }
    val operationCount = remember(index) { index?.threads?.sumOf { it.operations } ?: 0 }
    val selected = remember(index, selectedId) { index?.threads?.firstOrNull { it.identity.stableKey == selectedId } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("线程核心分布", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OceanText)
                Text((index?.threads?.count { it.identity.tid > 0 } ?: 0).toString() + " 个线程 · " + operationCount + " 次分配 / 释放",
                    fontSize = 11.sp, color = OceanTextSecondary, modifier = Modifier.padding(top = 3.dp))
            }
            IconButton({ searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }) {
                Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                    if (searchExpanded) "关闭搜索" else "搜索线程", Modifier.size(21.dp), tint = OceanTextSecondary)
            }
            IconButton({ explanation = true }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Info, "记录方式与分配策略说明", Modifier.size(20.dp), tint = OceanTextSecondary)
            }
        }
        if (searchExpanded) {
            Spacer(Modifier.height(8.dp))
            SearchField(query, { query = it }, "搜索线程名称或 TID")
        }
    }
    when {
        loading || (report != null && index == null && error == null) -> Box(Modifier.weight(1f).fillMaxWidth()) { LoadingState("正在准备调度数据") }
        error != null -> Column(Modifier.weight(1f).padding(20.dp)) {
            Text("读取失败：" + error, color = OceanError)
            TextButton(onRetry) { Text("重试") }
        }
        index == null || report == null -> Box(Modifier.weight(1f)) {
            EmptyState("本次没有调度记录", "核心变动从更新后的自动分配记录开始保存。")
        }
        else -> {
            Column(Modifier.padding(horizontal = 16.dp)) {
                CoreTimeNavigator(index, time)
                CoreCoverage(report)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(onlyAssigned, { onlyAssigned = !onlyAssigned },
                        label = { Text("有自动分配", fontSize = 11.sp) },
                        leadingIcon = { Icon(if (onlyAssigned) Icons.Outlined.Check else Icons.Outlined.FilterList, null, Modifier.size(15.dp)) })
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Swipe, null, Modifier.size(15.dp), tint = OceanTextSecondary)
                    Text(" 左滑查看来源与负载", fontSize = 10.sp, color = OceanTextSecondary)
                }
                Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(13.dp).background(PorcelainHeader, RoundedCornerShape(3.dp))
                        .border(1.dp, OceanPrimary.copy(alpha = .55f), RoundedCornerShape(3.dp)))
                    Text(" 允许核心", color = OceanTextSecondary, fontSize = 10.sp)
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(6.dp).background(OceanPrimary, RoundedCornerShape(50)))
                    Text(" 最近执行采样", color = OceanTextSecondary, fontSize = 10.sp)
                }
            }
            if (threads.isEmpty()) Box(Modifier.weight(1f)) {
                EmptyState("没有符合条件的线程", "尝试调整搜索或筛选条件。")
            } else CoreThreadTable(threads, time, index.cpuIds, Modifier.weight(1f).padding(horizontal = 16.dp)) {
                selectedId = it.identity.stableKey
            }
            Text("点线程看核心变动 · 允许多核不代表同时在多核运行", Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                fontSize = 10.sp, color = OceanTextSecondary)
        }
    }
    if (selected != null && report != null) {
        CoreThreadRecords(selected, report, time.longValue) { selectedId = null }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("怎样看调度数据") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("拖动上方游标，整张表只显示该时刻或之前已经记录的数据。同一毫秒的多条事件按记录顺序合并展示；点击线程可逐条查看，原始事件全部保留。")
            Text("核心范围是最近记录的允许范围。“自动分配”表示 QixiaThreads 管理该范围；“系统调度”表示最近记录的范围不由 QixiaThreads 管理，可能是初始系统状态或已交回系统。缺失、异常和线程结束不会继续显示为已分配。")
            Text("采样观察只表示读取到的状态，不代表已交回系统；是否由 QixiaThreads 管理请看范围归属。执行核是采样时读到的最近执行核心，不能代表期间每一次迁核；范围未变时也可能观测到不同的执行核。")
            Text("自动分配使用最近约 6 秒的时间加权平均使用率，首次有效样本达到 5% 就参与分配，近期均值低于 5% 时退出。历史保留全部活跃线程以及分配、释放和采样观察，不受 5% 门槛限制，列中附对应记录时间。")
            Text("核心历史观察独立降频：活跃或已接管线程约每 2 秒观察一次，静止且未接管线程约每 5 秒观察一次；首次观察立即建立基线，相同状态不重复保存。分配、释放和接管异常仍逐次记录。")
            Text("自动分配仍以每 500 毫秒评估负载、每 250 毫秒核对接管为目标，实际时机受系统调度影响。历史观察降频不影响接管检查；执行核旁的时间是实际采样时间，不代表游标时刻正在该核运行。")
            Text("外部调整可能来自系统或其他调度工具，不能仅凭观察判断具体来源。")
        } }, confirmButton = { TextButton({ explanation = false }) { Text("知道了") } })
}

@Composable
private fun CoreCoverage(report: CoreTimelineReport) {
    val text = remember(report) {
        buildList {
            if (report.incomplete || report.droppedEvents > 0) add("此段记录不完整" + (if (report.droppedEvents > 0) " · " + report.droppedEvents + " 条未保存" else "") + "。缺失时段不作推测。")
            if (report.legacyMissingDetails) add("旧版没有系统执行观察和完整释放原因。")
            if (report.hasEventsBeforeStart) add("含前段延迟到达的事件，保留原时间。")
        }.joinToString(" ")
    }
    if (text.isNotEmpty()) Text(text, Modifier.padding(top = 8.dp, start = 2.dp),
        fontSize = 10.sp, lineHeight = 15.sp, color = OceanTextSecondary)
}
