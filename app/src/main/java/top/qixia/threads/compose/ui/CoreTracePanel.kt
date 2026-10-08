package top.qixia.threads.compose.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.CoreTraceGraph
import top.qixia.threads.compose.CoreTimelinePresentation as Timeline
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.qixia.threads.CoreEventKind

/** 图形只预计算一次，指针移动仅做二分查找和状态写入。 */
@Composable
internal fun CoreTracePanel(
    graph: CoreTraceGraph,
    cursor: MutableIntState,
    indices: List<Int>,
    reportStart: Long,
    onLocate: () -> Unit
) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, OceanOutline)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("执行核采样轨迹", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OceanText)
                Spacer(Modifier.weight(1f))
                Text("拖动定位事件", color = OceanPrimary, fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (graph.lanes.isEmpty()) {
                Text("这条记录没有可绘制的核心信息", Modifier.padding(vertical = 24.dp),
                    color = OceanTextSecondary, fontSize = 12.sp)
            } else {
                CoreLanePlot(graph, cursor, indices)
                Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 8.dp, top = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(graph.startMs, graph.startMs + (graph.endMs - graph.startMs) / 2, graph.endMs).forEach {
                        Text(Timeline.relativeTime(it, reportStart), fontSize = 10.sp, color = OceanTextSecondary)
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp, 7.dp).background(OceanPrimary.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                        Text("  自动分配允许范围", fontSize = 10.sp, color = OceanTextSecondary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(OceanPrimary, RoundedCornerShape(50)))
                        Text("  实际执行采样", fontSize = 10.sp, color = OceanTextSecondary)
                    }
                }
            }
            Text("圆点仅代表采样瞬间，不推测两点之间在哪颗核运行。", Modifier.padding(top = 7.dp),
                fontSize = 10.sp, lineHeight = 15.sp, color = OceanTextSecondary)
            if (graph.compressed) {
                Text("图形已压缩，事件逐条保留", Modifier.padding(top = 6.dp), fontSize = 10.sp, color = OceanTextSecondary)
            }
            HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 10.dp), color = OceanDivider)
            CoreTraceInspector(graph, cursor, indices, onLocate)
        }
    }
}

@Composable
private fun CoreLanePlot(graph: CoreTraceGraph, cursor: MutableIntState, indices: List<Int>) {
    val density = LocalDensity.current
    val labelWidth = with(density) { 44.dp.toPx() }
    val endInset = with(density) { 8.dp.toPx() }
    val rowHeight = with(density) { 23.dp.toPx() }
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OceanTextSecondary.toArgb()
            textSize = with(density) { 10.sp.toPx() }
        }
    }
    val lanes = graph.lanes
    val height = (lanes.size * 23 + 12).dp
    Box(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
        Canvas(Modifier.fillMaxWidth().height(height).testTag("core-lane-plot")
            .semantics { contentDescription = "核心时间轴，横向滑动查看事件" }
            .pointerInput(graph, indices, labelWidth, endInset) {
                detectTapGestures { position ->
                    graph.nearestEventIndex((position.x - labelWidth) / (size.width - labelWidth - endInset).coerceAtLeast(1f), indices)
                        ?.let { cursor.intValue = it }
                }
            }
            .pointerInput(graph, indices, labelWidth, endInset) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    graph.nearestEventIndex((change.position.x - labelWidth) / (size.width - labelWidth - endInset).coerceAtLeast(1f), indices)
                        ?.let { cursor.intValue = it }
                }
            }) {
            val plotWidth = (size.width - labelWidth - endInset).coerceAtLeast(1f)
            val top = 6.dp.toPx()
            val bandHeight = 13.dp.toPx()
            val selected = graph.events.getOrNull(cursor.intValue)
            repeat(5) { tick ->
                val x = labelWidth + plotWidth * tick / 4f
                drawLine(OceanDivider, Offset(x, top), Offset(x, size.height - top), 1.dp.toPx())
            }
            lanes.forEachIndexed { index, lane ->
                val y = top + rowHeight * (index + .5f)
                drawContext.canvas.nativeCanvas.drawText("CPU " + lane.cpu, 0f, y - (paint.ascent() + paint.descent()) / 2, paint)
                drawLine(OceanDivider, Offset(labelWidth, y), Offset(size.width - endInset, y), 1.dp.toPx())
                lane.allowedIntervals.forEach { interval ->
                    val left = labelWidth + interval.startFraction * plotWidth
                    val width = ((interval.endFraction - interval.startFraction) * plotWidth).coerceAtLeast(1.dp.toPx())
                    drawRoundRect(OceanPrimary.copy(alpha = .20f), Offset(left, y - bandHeight / 2),
                        Size(width, bandHeight), CornerRadius(3.dp.toPx()))
                }
                lane.samples.forEach { sample ->
                    drawCircle(OceanPrimary.copy(alpha = .66f), 2.5.dp.toPx(),
                        Offset(labelWidth + sample.fraction * plotWidth, y))
                }
                if (selected != null && selected.kind != CoreEventKind.EXIT && lane.cpu == selected.runningCpu) {
                    val point = Offset(labelWidth + graph.fraction(selected.timestampMs) * plotWidth, y)
                    drawCircle(Color.White, 5.dp.toPx(), point)
                    drawCircle(OceanPrimary, 3.5.dp.toPx(), point)
                }
            }
            selected?.let {
                val x = labelWidth + graph.fraction(it.timestampMs) * plotWidth
                drawLine(OceanPrimary.copy(alpha = .8f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawCircle(OceanPrimary, 3.dp.toPx(), Offset(x, 3.dp.toPx()))
            }
        }
    }
}

@Composable
private fun CoreTraceInspector(
    graph: CoreTraceGraph,
    cursor: MutableIntState,
    indices: List<Int>,
    onLocate: () -> Unit
) {
    val event = graph.events.getOrNull(cursor.intValue)
    val index = indices.binarySearch(cursor.intValue)
    if (event == null || index < 0) {
        Text("当前筛选下没有事件", color = OceanTextSecondary, fontSize = 12.sp)
        return
    }
    val time = remember(event.timestampMs) {
        SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(event.timestampMs))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(coreEventTitle(event), color = coreEventColor(event), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(time, color = OceanTextSecondary, fontSize = 11.sp)
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).background(OceanSurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(if (event.kind == CoreEventKind.ERROR) "操作后读回范围" else "事件记录的允许范围", fontSize = 10.sp, color = OceanTextSecondary)
            Text(if (event.kind == CoreEventKind.EXIT) "线程已结束" else Timeline.cpus(event.afterCpus),
                Modifier.padding(top = 5.dp), fontSize = 13.sp, color = OceanPrimary, fontWeight = FontWeight.SemiBold)
            Text(coreEventSourceLabel(event), Modifier.padding(top = 4.dp), fontSize = 10.sp, color = OceanTextSecondary)
        }
        Column(Modifier.weight(1f).background(OceanSurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text("本条事件的执行采样", fontSize = 10.sp, color = OceanTextSecondary)
            Text(if (event.kind == CoreEventKind.EXIT) "已结束" else event.runningCpu?.let { "CPU $it" } ?: "未采集",
                Modifier.padding(top = 5.dp), fontSize = 13.sp, color = OceanPrimary, fontWeight = FontWeight.SemiBold)
            Text(event.averagePercent?.let { "近期均值 " + String.format(Locale.getDefault(), "%.1f%%", it) } ?: "负载未采集",
                Modifier.padding(top = 4.dp), fontSize = 10.sp, color = OceanTextSecondary)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { cursor.intValue = indices[index - 1] }, enabled = index > 0, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一条事件", Modifier.size(18.dp))
        }
        Text((index + 1).toString() + " / " + indices.size, fontSize = 11.sp, color = OceanTextSecondary)
        IconButton(onClick = { cursor.intValue = indices[index + 1] }, enabled = index < indices.lastIndex, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一条事件", Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onLocate, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("定位明细", fontSize = 12.sp)
        }
    }
}
