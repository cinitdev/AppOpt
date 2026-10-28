package top.qixia.threads.compose.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
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
        if (allSeries.isEmpty()) 94.dp else maxOf(if (allSeries.size > 4) 236.dp else 200.dp,
            13.sp.toDp() * (visible.size + 1) + 20.dp)
    }
    val description = remember(allSeries, left, right, leftUnit, rightUnit, durationMs) {
        "${allSeries.joinToString { it.label }}趋势图，按住或横向拖动可联动查看同一时刻的数据，松开收起游标。" +
            "时间 0:00 至 ${plotTime(durationMs)}，左轴 ${plotNumber(left.minimum)} 至 ${plotNumber(left.maximum)} $leftUnit" +
            (rightUnit?.let { "，右轴 ${plotNumber(right.minimum)} 至 ${plotNumber(right.maximum)} $it" } ?: "")
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
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
                        "${item.label}：" + (item.at(progress, durationMs)?.let { "${historyNumber(it.value)} ${item.unit}" } ?: "该时刻无样本")
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
                    val marker = markers.minByOrNull { kotlin.math.abs(it * size.width - down.position.x) }
                        ?.takeIf { kotlin.math.abs(it * size.width - down.position.x) <= 16.dp.toPx() &&
                            down.position.y >= size.height - 24.dp.toPx() }
                    var active = false
                    var tapped = false
                    var last = down
                    try {
                        // 等手势方向明确后再刷新游标，纵向滚动不触发全部图表的读数和语义更新。
                        val horizontal = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var decision: Boolean? = null
                            while (decision == null) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) break
                                last = change
                                if (!change.pressed) { tapped = true; break }
                                val delta = change.position - down.position
                                if (maxOf(kotlin.math.abs(delta.x), kotlin.math.abs(delta.y)) > viewConfiguration.touchSlop) {
                                    decision = kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
                                }
                            }
                            decision == true
                        }
                        // 未移动而超时表示长按；横向拖动与长按均保持原来的联动读数。
                        if (horizontal != false && visible.isNotEmpty()) {
                            active = true
                            last.consume()
                            select(last.position.x)
                            horizontalDrag(down.id) { change ->
                                select(change.position.x)
                                change.consume()
                            }
                        } else if (tapped && marker != null) {
                            selectMarker(marker)
                            cursor.value = marker
                        }
                    } finally { if (active) cursor.value = null }
                }
            }, contentAlignment = Alignment.Center) {
            HistoryStaticPlot(visible, left, right, markers)
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
    PlotLabels((0..4).map { plotTime(durationMs * it / 4) },
        Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 5.dp).height(16.dp), horizontal = true)
}

@Composable
private fun PlotAxis(axis: HistoryPlotMath.Axis, modifier: Modifier, alignment: TextAlign) {
    PlotLabels((0..4).map { plotNumber(axis.tick(it)) }, modifier, alignEnd = alignment == TextAlign.End)
}

/** 刻度作为整张图的装饰绘制；无障碍由图表统一提供范围，避免滚动逐个遍历刻度文本。 */
@Composable
private fun PlotLabels(labels: List<String>, modifier: Modifier, horizontal: Boolean = false, alignEnd: Boolean = false) {
    Spacer(modifier.drawWithCache {
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = OceanTextSecondary.toArgb()
            textSize = 9.sp.toPx()
        }
        val widths = labels.map { paint.measureText(it) }
        val textHeight = paint.descent() - paint.ascent()
        val baseline = -paint.ascent()
        onDrawBehind {
            labels.forEachIndexed { index, label ->
                val fraction = index.toFloat() / (labels.size - 1).coerceAtLeast(1)
                val x = if (horizontal) (size.width - widths[index]) * fraction
                    else if (alignEnd) size.width - widths[index] else 0f
                val y = baseline + if (horizontal) 0f else (size.height - textHeight) * fraction
                drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
            }
        }
    })
}

private fun plotNumber(value: Float): String = if (kotlin.math.abs(value - value.toInt()) < .01f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
internal fun plotTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
