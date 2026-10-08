package top.qixia.threads.compose.ui

import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.OceanPrimary

/** 每张可见图只有一个小覆盖层，拖动时不组合、测量或构建路径。 */
internal class HistoryCursorRenderer(
    series: List<HistoryPlotSeries>,
    private val durationMs: Long,
    private val left: HistoryPlotMath.Axis,
    private val right: HistoryPlotMath.Axis,
    width: Float,
    density: Density,
) {
    private val gap = with(density) { 10.dp.toPx() }
    private val padding = with(density) { 8.dp.toPx() }
    private val top = with(density) { 4.dp.toPx() }
    private val insetY = with(density) { 6.dp.toPx() }
    private val lineHeight = with(density) { 13.sp.toPx() }
    private val dotRadius = with(density) { 2.5.dp.toPx() }
    private val markerRadius = with(density) { 3.dp.toPx() }
    private val stroke = with(density) { 1.dp.toPx() }
    private val corner = CornerRadius(padding)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = with(density) { 9.sp.toPx() }
        color = Color.White.toArgb()
    }
    private val baseline = (lineHeight - (paint.descent() - paint.ascent())) / 2 - paint.ascent()
    // 手势期间宽度固定，切换左右侧不会重新排版标签。
    private val tooltipWidth = minOf(width, with(density) { (width / 2 - gap).coerceIn(96.dp.toPx(), 158.dp.toPx()) })
    private val rows = series.map { item ->
        val valueWidth = maxOf(paint.measureText("未采集"),
            paint.measureText("${historyNumber(item.data.minimum)} ${item.unit}"),
            paint.measureText("${historyNumber(item.data.maximum)} ${item.unit}"))
        val labelWidth = (tooltipWidth - padding * 2 - gap * 1.5f - valueWidth).coerceAtLeast(0f)
        Row(item, TextUtils.ellipsize(item.label, paint, labelWidth, TextUtils.TruncateAt.END).toString())
    }
    private val tooltipHeight = insetY * 2 + lineHeight * (rows.size + 1)
    private var headerSecond = -1L
    private var header = ""

    private class Row(val item: HistoryPlotSeries, val label: String) {
        var point: HistoryCurvePoint? = null
        var value = "未采集"
        var valueWidth = 0f
        var initialized = false
    }

    fun draw(scope: DrawScope, progress: Float) = with(scope) {
        val anchor = progress * size.width
        drawLine(OceanPrimary.copy(alpha = .65f), Offset(anchor, 0f), Offset(anchor, size.height), stroke)
        rows.forEach { row ->
            val point = HistoryPlotMath.at(row.item.points, progress, durationMs)
            // 多数指针事件仍指向同一个已保存样本，可复用文字及宽度。
            if (!row.initialized || row.point !== point) {
                row.point = point
                row.value = point?.let { "${historyNumber(it.value)} ${row.item.unit}" } ?: "未采集"
                row.valueWidth = paint.measureText(row.value)
                row.initialized = true
            }
            if (point != null) {
                val axis = if (row.item.rightAxis) right else left
                val position = Offset(point.progress * size.width, (1 - axis.fraction(row.item.plotValue(point.value))) * size.height)
                drawCircle(Color.White, markerRadius, position)
                drawCircle(row.item.color, markerRadius - stroke, position)
            }
        }
        val x = HistoryPlotMath.tooltipOffset(anchor, size.width, tooltipWidth, gap)
        drawRoundRect(Color(0xF223365B), Offset(x, top), Size(tooltipWidth, tooltipHeight), corner)
        val second = (durationMs * progress).toLong() / 1000
        if (second != headerSecond) {
            headerSecond = second
            header = TextUtils.ellipsize("${plotTime(second * 1000)} · 邻近采样", paint,
                (tooltipWidth - padding * 2).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        }
        val canvas = drawContext.canvas.nativeCanvas
        paint.color = Color(0xFFD3E0FF).toArgb()
        canvas.drawText(header, x + padding, top + insetY + baseline, paint)
        paint.color = Color.White.toArgb()
        rows.forEachIndexed { index, row ->
            val y = top + insetY + (index + 1) * lineHeight
            drawCircle(row.item.color, dotRadius, Offset(x + padding + dotRadius, y + lineHeight / 2))
            canvas.drawText(row.label, x + padding + gap, y + baseline, paint)
            canvas.drawText(row.value, x + tooltipWidth - padding - row.valueWidth, y + baseline, paint)
        }
    }
}
