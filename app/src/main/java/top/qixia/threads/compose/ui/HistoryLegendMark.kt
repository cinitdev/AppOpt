package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 颜色表示指标，勾选表示可见性，不使用实心填充方块。 */
@Composable
internal fun HistoryLegendMark(color: Color, selected: Boolean = true) {
    Canvas(Modifier.size(12.dp)) {
        val inset = .6.dp.toPx()
        val topLeft = Offset(inset, inset)
        val markerSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = markerSize,
            cornerRadius = CornerRadius(1.8.dp.toPx()),
            style = Stroke(1.1.dp.toPx()),
        )
        if (selected) {
            val elbow = Offset(size.width * .44f, size.height * .68f)
            val checkWidth = 1.3.dp.toPx()
            drawLine(
                color, Offset(size.width * .25f, size.height * .50f), elbow,
                strokeWidth = checkWidth, cap = StrokeCap.Round,
            )
            drawLine(
                color, elbow, Offset(size.width * .77f, size.height * .31f),
                strokeWidth = checkWidth, cap = StrokeCap.Round,
            )
        }
    }
}
