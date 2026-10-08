package top.qixia.threads.compose.ui

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.*
import top.qixia.threads.compose.CoreTableIndex
import top.qixia.threads.compose.CoreTimelinePresentation as Timeline
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ThreadColumnWidth = 108.dp

/** 固定线程名称列，并共用水平偏移，确保所有列对齐。 */
@Composable
internal fun CoreThreadTable(
    threads: List<CoreTableIndex.Thread>,
    time: MutableLongState,
    cpuIds: List<Int>,
    modifier: Modifier = Modifier,
    onThread: (CoreTableIndex.Thread) -> Unit
) {
    val horizontal = rememberScrollState()
    // 普通手机可在固定名称列旁容纳 8～10 个核心；更宽的记录拓扑
    // 仍支持滚动，标签取自记录，不使用写死的八核模板。
    val coreWidth = maxOf(184, cpuIds.size * 19 + 14).dp
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, OceanOutline)) {
        Column {
            Row(Modifier.fillMaxWidth().background(OceanSurfaceHigh), verticalAlignment = Alignment.CenterVertically) {
                Text("线程", Modifier.width(ThreadColumnWidth).padding(start = 13.dp, top = 16.dp, bottom = 16.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OceanText)
                Box(Modifier.width(1.dp).height(44.dp).background(OceanOutline))
                Row(Modifier.weight(1f).horizontalScroll(horizontal).testTag("core-table-columns"),
                    verticalAlignment = Alignment.CenterVertically) {
                    TableCell("核心分布", "允许范围 / 最近执行采样", coreWidth, header = true)
                    TableCell("范围来源", "最近记录", 90.dp, header = true)
                    TableCell("平均负载", "记录时均值", 84.dp, header = true)
                    TableCell("最近事件", "月日 时分秒", 140.dp, header = true)
                }
            }
            HorizontalDivider(color = OceanOutline)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("core-data-table")) {
                itemsIndexed(threads, key = { _, thread -> thread.identity.stableKey }, contentType = { _, _ -> "thread" }) { position, thread ->
                    CoreSnapshotRow(thread, time, cpuIds, coreWidth, horizontal, position % 2 == 1) { onThread(thread) }
                }
            }
        }
    }
}

@Composable
private fun CoreSnapshotRow(
    thread: CoreTableIndex.Thread, time: MutableLongState, cpuIds: List<Int>, coreWidth: Dp,
    horizontal: ScrollState, alternate: Boolean, onClick: () -> Unit
) {
    // 仅可见行执行 O(log N) 查询，事件文字不在拖动的每一帧重新格式化。
    val snapshot by remember(thread, time) { derivedStateOf { thread.snapshotAt(time.longValue) } }
    val latest = snapshot?.latestEvent
    val range = snapshot?.rangeEvent
    val sample = snapshot?.sampleEvent
    val average = snapshot?.averageEvent
    val exited = latest?.kind == CoreEventKind.EXIT
    val uncertain = latest?.kind == CoreEventKind.ERROR || latest?.source == CoreEventSource.UNKNOWN
    val rangeText = remember(range) { range?.let { Timeline.cpus(it.afterCpus) } ?: "范围未确认" }
    val source = when {
        latest == null -> "尚无记录"
        exited -> "已结束"
        latest.kind == CoreEventKind.ERROR -> "待核对"
        range != null -> coreEventSourceLabel(range)
        else -> "未确认"
    }
    val sourceColor = if (range?.source == CoreEventSource.QIXIA) OceanPrimary else CoreObservedColor
    val averageText = remember(average) {
        average?.averagePercent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
    }
    val rangeTime = remember(range) { range?.let { coreRecordTime(it.timestampMs) } }
    val sampleTime = remember(sample) { sample?.let { coreRecordTime(it.timestampMs) } }
    val averageTime = remember(average) { average?.let { coreRecordTime(it.timestampMs) } }
    val latestTime = remember(latest) { latest?.let { coreRecordTime(it.timestampMs, full = true) } }
    val observedCpu = sample?.runningCpu?.takeUnless { uncertain || exited }
    val sampleLabel = when {
        latest == null -> "尚无记录"
        exited -> "线程已结束"
        uncertain -> "运行核未确认"
        observedCpu != null -> "采样运行 CPU $observedCpu"
        else -> "尚无执行核采样"
    }
    val allowed = remember(range) { range?.afterCpus?.toSet().orEmpty() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (alternate) Color(0xFFFAFBFE) else Color.White)
        .testTag("core-row-" + thread.identity.stableKey), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(ThreadColumnWidth).fillMaxHeight().clickable(onClick = onClick)
            .padding(start = 13.dp, end = 9.dp, top = 15.dp, bottom = 15.dp), verticalArrangement = Arrangement.Center) {
            Text(thread.name, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                color = OceanText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("TID " + thread.identity.tid, Modifier.padding(top = 5.dp), fontSize = 10.sp, color = OceanTextSecondary)
            Text(source, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = sourceColor)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(OceanDivider))
        Row(Modifier.weight(1f).horizontalScroll(horizontal).clickable(onClick = onClick)
            .heightIn(min = 92.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(coreWidth).padding(horizontal = 8.dp, vertical = 12.dp)) {
                CoreCpuCells(cpuIds, allowed, observedCpu)
                Text(sampleLabel, Modifier.padding(top = 7.dp), fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = if (observedCpu != null) OceanPrimary else OceanTextSecondary)
                Text(if (sampleTime != null && !exited) (if (uncertain) "此前采样 · " else "最近记录于 ") + sampleTime else "点线程查看逐次记录",
                    Modifier.padding(top = 3.dp), fontSize = 9.sp, color = OceanTextSecondary)
                Text(if (exited) "允许范围已结束" else rangeText, Modifier.padding(top = 3.dp), fontSize = 9.sp,
                    color = OceanTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TableCell(source, rangeTime, 90.dp, sourceColor)
            TableCell(averageText, averageTime, 84.dp, OceanText)
            TableCell(latest?.let(::coreEventTitle) ?: "尚无记录", latestTime, 140.dp,
                latest?.let(::coreEventColor) ?: OceanTextSecondary)
        }
    }
    HorizontalDivider(color = OceanDivider)
}

/** CPU 数字表示允许核心，下方独立圆点表示带时间戳的运行采样。 */
@Composable
internal fun CoreCpuCells(cpuIds: List<Int>, allowed: Set<Int>, observedCpu: Int?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val paint = remember(density) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = with(density) { 10.sp.toPx() }
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    } }
    val labels = remember(cpuIds) { cpuIds.map(Int::toString) }
    Canvas(modifier.fillMaxWidth().height(29.dp).semantics {
        contentDescription = "允许核心 " + Timeline.cpus(allowed.toList()) +
            (observedCpu?.let { "；最近执行采样 CPU $it" } ?: "；没有已确认的执行采样")
    }) {
        if (cpuIds.isEmpty()) return@Canvas
        val step = size.width / cpuIds.size
        val width = (step - 3.dp.toPx()).coerceAtMost(22.dp.toPx())
        val height = 20.dp.toPx()
        cpuIds.forEachIndexed { index, cpu ->
            val x = step * (index + .5f)
            val origin = Offset(x - width / 2, 0f)
            val isAllowed = cpu in allowed
            drawRoundRect(if (isAllowed) PorcelainHeader else OceanSurfaceHigh.copy(alpha = .55f),
                origin, Size(width, height), CornerRadius(4.dp.toPx()))
            drawRoundRect(if (isAllowed) OceanPrimary.copy(alpha = .55f) else OceanDivider,
                origin, Size(width, height), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            paint.color = (if (isAllowed) PorcelainOnTonal else OceanTextSecondary).toArgb()
            drawContext.canvas.nativeCanvas.drawText(labels[index], x,
                height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
            if (cpu == observedCpu) drawCircle(OceanPrimary, 2.8.dp.toPx(), Offset(x, 26.dp.toPx()))
        }
    }
}

@Composable
private fun TableCell(title: String, subtitle: String?, width: Dp, color: Color = OceanTextSecondary, header: Boolean = false) {
    Column(Modifier.width(width).padding(horizontal = 10.dp, vertical = if (header) 12.dp else 13.dp)) {
        Text(title, color = if (header) OceanText else color, fontSize = if (header) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, maxLines = if (header) 1 else 3, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 9.sp,
            color = OceanTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun coreRecordTime(timestamp: Long, full: Boolean = false, millis: Boolean = false): String =
    SimpleDateFormat((if (full) "MM/dd " else "") + "HH:mm:ss" + if (millis) ".SSS" else "", Locale.getDefault()).format(Date(timestamp))
