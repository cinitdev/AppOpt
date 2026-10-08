package top.qixia.threads.compose.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.*
import java.util.Locale

/** 整份报告共用时间游标；仅横向拖动消耗手势，纵向滚动保留原生行为。 */
@Composable
internal fun HistoryReportPlot(allSeries: List<HistoryPlotSeries>, visible: List<HistoryPlotSeries>, durationMs: Long,
    leftUnit: String, rightUnit: String?, cursor: MutableState<Float?>, emptyMessage: String,
    rightAxis: HistoryPlotMath.Axis? = null, markers: List<Float> = emptyList(), onMarkerSelected: (Float) -> Unit = {}) {
    val selectMarker by rememberUpdatedState(onMarkerSelected)
    val left = remember(allSeries, leftUnit) { HistoryPlotMath.axis(allSeries.filterNot { it.rightAxis }.flatMap { item -> item.points.map { item.plotValue(it.value) } }, leftUnit) }
    val right = rightAxis ?: remember(allSeries, rightUnit) { HistoryPlotMath.axis(allSeries.filter { it.rightAxis }.flatMap { item -> item.points.map { item.plotValue(it.value) } }, rightUnit.orEmpty()) }
    // 确保选中的每个核心都清晰可读，包括较大的系统字号。
    val height = with(LocalDensity.current) {
        if (allSeries.isEmpty()) 94.dp else maxOf(176.dp, 13.sp.toDp() * (visible.size + 1) + 20.dp)
    }
    val description = remember(allSeries) {
        "${allSeries.joinToString { it.label }}趋势图，按住或横向拖动可联动查看同一时刻的数据，松开收起游标"
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(leftUnit, color = OceanTextSecondary, fontSize = 9.sp)
        rightUnit?.let { Text(it, color = OceanTextSecondary, fontSize = 9.sp) }
    }
    Row(Modifier.fillMaxWidth()) {
        PlotAxis(left, Modifier.width(30.dp).height(height), TextAlign.Start)
        Box(Modifier.weight(1f).height(height).semantics {
                contentDescription = description
                val progress = cursor.value
                progressBarRangeInfo = ProgressBarRangeInfo(progress ?: 0f, 0f..1f)
                // Canvas 读数仍向无障碍服务提供全部选中值。
                if (progress != null && visible.isNotEmpty()) text = AnnotatedString(
                    plotTime((durationMs * progress).toLong()) + "，" + visible.joinToString("，") { item ->
                        "${item.label}：" + (HistoryPlotMath.at(item.points, progress, durationMs)?.let { "${historyNumber(it.value)} ${item.unit}" } ?: "未采集")
                    })
                setProgress { if (visible.isEmpty()) false else { cursor.value = it.coerceIn(0f, 1f); true } }
                customActions = listOf(CustomAccessibilityAction("收起游标") { cursor.value = null; true })
            }.pointerInput(visible, cursor, markers) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun select(x: Float) {
                        if (visible.isEmpty()) return
                        // 连续跟随手指，仅在读数时选取已保存样本。
                        cursor.value = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    select(down.position.x)
                    val marker = markers.minByOrNull { kotlin.math.abs(it * size.width - down.position.x) }
                        ?.takeIf { kotlin.math.abs(it * size.width - down.position.x) <= 16.dp.toPx() &&
                            down.position.y >= size.height - 24.dp.toPx() }
                    if (marker != null) selectMarker(marker)
                    var dragged = false
                    try {
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            dragged = true
                            change.consume()
                            select(change.position.x)
                        }
                        if (start != null) horizontalDrag(start.id) { change ->
                            select(change.position.x)
                            change.consume()
                        }
                    } finally { cursor.value = if (dragged) null else marker }
                }
            }, contentAlignment = Alignment.Center) {
            // 使用独立绘制列表，拖动不得重建或重绘静态曲线路径。
            Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                fun xy(point: HistoryCurvePoint, item: HistoryPlotSeries): Offset {
                    val axis = if (item.rightAxis) right else left
                    return Offset(point.progress * size.width, (1f - axis.fraction(item.plotValue(point.value))) * size.height)
                }
                val curves = visible.map { item ->
                    val path = Path()
                    val dots = mutableListOf<Offset>()
                    item.points.forEachIndexed { index, point ->
                        val position = xy(point, item)
                        if (index == 0 || point.breakBefore) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
                        if ((index == 0 || point.breakBefore) && (index == item.points.lastIndex || item.points[index + 1].breakBefore)) dots += position
                    }
                    Triple(item, path, dots)
                }
                // 两侧坐标轴都使用连续采样折线，断线仍由 breakBefore 指定。
                val lineStroke = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
                val grid = OceanOutline.copy(alpha = .55f)
                onDrawBehind {
                    repeat(5) { i ->
                        val y = size.height * i / 4f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), .65.dp.toPx())
                    }
                    repeat(5) { i ->
                        val x = size.width * i / 4f
                        drawLine(grid.copy(alpha = .24f), Offset(x, 0f), Offset(x, size.height), .65.dp.toPx())
                    }
                    curves.forEach { (item, path, dots) ->
                        dots.forEach { drawCircle(item.color, 1.7.dp.toPx(), it) }
                        drawPath(path, item.color, style = lineStroke)
                    }
                    markers.forEach { progress ->
                        val x = progress.coerceIn(0f, 1f) * size.width
                        drawCircle(OceanError, 3.dp.toPx(), Offset(x, size.height - 4.dp.toPx()))
                    }
                }
            })
            // 只在绘制和语义层读取游标状态，不在报告或卡片组合中读取。
            if (visible.isNotEmpty()) Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val renderer = HistoryCursorRenderer(visible, durationMs, left, right, size.width, this)
                onDrawBehind { cursor.value?.let { renderer.draw(this, it) } }
            })
            if (visible.isEmpty()) Surface(color = Color.White.copy(alpha = .94f)) {
                Text(if (allSeries.isEmpty()) emptyMessage else "点选下方图例显示曲线", color = OceanTextSecondary,
                    fontSize = 11.sp, modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center)
            }
        }
        if (rightUnit != null) PlotAxis(right, Modifier.width(30.dp).height(height), TextAlign.End)
        else Spacer(Modifier.width(30.dp))
    }
    Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        (0..4).forEach { i -> Text(plotTime(durationMs * i / 4), color = OceanTextSecondary, fontSize = 9.sp) }
    }
}

@Composable
private fun PlotAxis(axis: HistoryPlotMath.Axis, modifier: Modifier, alignment: TextAlign) {
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        (0..4).forEach { index ->
            Text(plotNumber(axis.tick(index)), color = OceanTextSecondary, fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = alignment)
        }
    }
}

private fun plotNumber(value: Float): String = if (kotlin.math.abs(value - value.toInt()) < .01f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
internal fun plotTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
