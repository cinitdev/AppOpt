package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.theme.*
import kotlin.math.abs
import kotlin.math.ceil

/** 可见顶点最多几百个；用直线段连接，不虚构平滑的帧率或负载值。 */
@Composable
internal fun HistoryLineChart(points: List<HistoryCurvePoint>, unit: String, minimumScale: Float,
    height: Dp = 150.dp, durationMs: Long? = null, scaleStep: Float = 20f) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("没有曲线样本", color = OceanTextSecondary, fontSize = 11.sp)
        }
        return
    }
    val maximum = remember(points, minimumScale, scaleStep) {
        val actual = maxOf(minimumScale, points.maxOf { it.value }, 1f)
        (ceil(actual / scaleStep) * scaleStep).coerceAtLeast(scaleStep)
    }
    var selected by remember(points) { mutableStateOf<HistoryCurvePoint?>(null) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(30.dp).height(height), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${maximum.toInt()}", color = OceanTextSecondary, fontSize = 9.sp)
                Text("${(maximum / 2).toInt()}", color = OceanTextSecondary, fontSize = 9.sp)
                Text("0", color = OceanTextSecondary, fontSize = 9.sp)
            }
            Canvas(Modifier.weight(1f).height(height).semantics {
                contentDescription = "采样曲线，最低 ${historyNumber(points.minOf { it.value })}$unit，最高 ${historyNumber(points.maxOf { it.value })}$unit，点击查看采样点"
            }.pointerInput(points) {
                detectTapGestures { tap ->
                    val progress = (tap.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    selected = points.minByOrNull { abs(it.progress - progress) }
                }
            }) {
                val inset = 4.dp.toPx()
                fun xy(point: HistoryCurvePoint) = Offset(point.progress * size.width,
                    inset + (size.height - 2 * inset) * (1f - point.value / maximum))
                repeat(5) { index ->
                    val y = inset + (size.height - 2 * inset) * index / 4f
                    drawLine(OceanOutline.copy(alpha = .52f), Offset(0f, y), Offset(size.width, y), .65.dp.toPx())
                }
                val line = Path().apply {
                    val first = xy(points.first()); moveTo(first.x, first.y)
                    for (point in points.drop(1)) {
                        val next = xy(point)
                        if (point.breakBefore) moveTo(next.x, next.y) else lineTo(next.x, next.y)
                    }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xy(points.last()).x, size.height - inset)
                    lineTo(xy(points.first()).x, size.height - inset)
                    close()
                }
                if (points.none { it.breakBefore }) drawPath(fill, Brush.verticalGradient(listOf(OceanPrimary.copy(alpha = .12f), Color.Transparent)))
                drawPath(line, OceanPrimary, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                if (points.size == 1) drawCircle(OceanPrimary, 2.5.dp.toPx(), xy(points.first()))
                selected?.let { point ->
                    val position = xy(point)
                    drawLine(PorcelainOnTonal.copy(alpha = .4f), Offset(position.x, 0f), Offset(position.x, size.height), 1.dp.toPx())
                    drawCircle(Color.White, 4.dp.toPx(), position)
                    drawCircle(OceanPrimary, 2.5.dp.toPx(), position)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, .5f, 1f).forEach { progress ->
                Text(if (durationMs == null) "${(progress * 100).toInt()}%" else historyDuration((durationMs * progress).toLong()),
                    color = OceanTextSecondary, fontSize = 9.sp)
            }
        }
        selected?.let { point ->
            Text("${if (durationMs == null) "采样进度 ${(point.progress * 100).toInt()}%" else historyDuration((durationMs * point.progress).toLong())} · ${historyNumber(point.value)} $unit",
                color = PorcelainOnTonal, fontSize = 11.sp, modifier = Modifier.padding(start = 30.dp, bottom = 4.dp))
        }
    }
}
