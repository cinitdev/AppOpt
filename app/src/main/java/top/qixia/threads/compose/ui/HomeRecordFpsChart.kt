package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.HomeFpsSummary
import top.qixia.threads.compose.theme.*

/** 只使用选中报告的有界预览，不虚构 FPS 值。 */
@Composable
internal fun HomeRecordFpsChart(fps: HomeFpsSummary?, startedAtMs: Long, endedAtMs: Long) {
    val points = remember(fps, startedAtMs, endedAtMs) {
        fps?.points.orEmpty().filter { it.fps.isFinite() && it.fps in 0f..1000f &&
            it.timestampMs in startedAtMs..endedAtMs }.take(160).sortedBy { it.timestampMs }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 13.dp).testTag("home_fps_chart")) {
        Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = if (points.isEmpty()) "本次记录暂无帧率曲线" else
                    "帧率趋势，${points.size} 个预览点，平均 ${homeFpsText(fps?.average)} FPS"
            }) {
                repeat(3) { index ->
                    val y = size.height * index / 2
                    drawLine(MintBorder.copy(alpha = .65f), Offset.Zero.copy(y = y), Offset(size.width, y), .5.dp.toPx())
                }
                if (points.isEmpty()) return@Canvas
                val ceiling = (points.maxOf { it.fps } * 1.25f).coerceAtLeast(10f)
                val span = (endedAtMs - startedAtMs).coerceAtLeast(1L)
                val offsets = points.map { point -> Offset(
                    3.dp.toPx() + ((point.timestampMs - startedAtMs).toDouble() / span *
                        (size.width - 6.dp.toPx())).toFloat(),
                    (1f - point.fps / ceiling) * (size.height - 8.dp.toPx()) + 4.dp.toPx()) }
                var start = 0
                fun drawSegment(end: Int) {
                    val line = Path().apply {
                        moveTo(offsets[start].x, offsets[start].y)
                        for (i in start + 1..end) {
                            val a = offsets[i - 1]; val b = offsets[i]; val middle = (a.x + b.x) / 2f
                            cubicTo(middle, a.y, middle, b.y, b.x, b.y)
                        }
                    }
                    if (end > start) {
                        val fill = Path().apply {
                            addPath(line); lineTo(offsets[end].x, size.height)
                            lineTo(offsets[start].x, size.height); close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(OceanPrimary.copy(alpha = .12f), Color.Transparent)))
                        drawPath(line, OceanPrimary, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                    } else drawCircle(OceanPrimary, 1.8.dp.toPx(), offsets[start])
                }
                for (i in 1 until points.size) if (points[i].breakBefore) { drawSegment(i - 1); start = i }
                drawSegment(points.lastIndex)
                drawCircle(PorcelainHeader, 4.5.dp.toPx(), offsets.last())
                drawCircle(OceanPrimary, 2.5.dp.toPx(), offsets.last())
            }
            if (points.isEmpty()) Text("本次记录暂无帧率曲线", color = MintMuted, fontSize = 10.sp)
        }
        val pattern = if (endedAtMs - startedAtMs < 60_000) "HH:mm:ss" else "HH:mm"
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(startedAtMs, startedAtMs + (endedAtMs - startedAtMs) / 2, endedAtMs).forEach {
                Text(homeDate(it, pattern), color = MintMuted, fontSize = 10.sp)
            }
        }
    }
}
