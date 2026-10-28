package top.qixia.threads.compose.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.OceanError
import top.qixia.threads.compose.theme.OceanOutline

internal val LocalHistoryPlotCache = staticCompositionLocalOf<HistoryPlotRasterCache?> { null }

internal data class HistoryRasterCurve(val points: List<HistoryCurvePoint>, val color: Int,
    val scale: Float, val rightAxis: Boolean)

internal data class HistoryRasterKey(val curves: List<HistoryRasterCurve>, val size: IntSize,
    val density: Float, val left: HistoryPlotMath.Axis, val right: HistoryPlotMath.Axis,
    val markers: List<Float>, val gridColor: Int, val markerColor: Int)

/** 每份报告最多缓存 20 MiB；离开报告即可回收，不把位图写入历史文件。 */
internal class HistoryPlotRasterCache(maxBytes: Int = 20 * 1024 * 1024) {
    private val images = object : LruCache<HistoryRasterKey, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: HistoryRasterKey, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(key: HistoryRasterKey): ImageBitmap? = images.get(key)

    suspend fun render(key: HistoryRasterKey): ImageBitmap = withContext(Dispatchers.Default) {
        images.get(key) ?: rasterize(key).also { images.put(key, it) }
        // 淘汰时不能手动 recycle：屏幕上的图层仍可能持有该位图，由引用生命周期回收。
    }
}

/** 静态曲线在后台栅格化，滚动时只合成位图，游标仍单独读取原始采样。 */
@Composable
internal fun HistoryStaticPlot(series: List<HistoryPlotSeries>, left: HistoryPlotMath.Axis,
    right: HistoryPlotMath.Axis, markers: List<Float>) {
    val cache = LocalHistoryPlotCache.current ?: remember { HistoryPlotRasterCache() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val request = remember(series, left, right, markers, size, density) {
        HistoryRasterKey(series.map { HistoryRasterCurve(it.points, it.color.toArgb(), it.valueScale, it.rightAxis) },
            size, density, left, right, markers, OceanOutline.copy(alpha = .55f).toArgb(), OceanError.toArgb())
    }
    // 图例、尺寸或数据变更时不沿用上一张图，避免后台任务完成前显示旧曲线。
    val bitmap = key(cache, request) {
        produceState(cache.get(request), request) {
            if (value == null && request.size.width > 0 && request.size.height > 0) value = cache.render(request)
        }.value
    }
    Spacer(Modifier.fillMaxSize().onSizeChanged { size = it }.graphicsLayer().drawBehind {
        bitmap?.let { drawImage(it) }
    })
}

private fun rasterize(key: HistoryRasterKey): ImageBitmap {
    val bitmap = Bitmap.createBitmap(key.size.width, key.size.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val width = key.size.width.toFloat()
    val height = key.size.height.toFloat()
    val density = key.density
    paint.color = key.gridColor
    paint.strokeWidth = .45f * density
    repeat(5) { i ->
        val y = height * i / 4f
        canvas.drawLine(0f, y, width, y, paint)
    }
    paint.alpha = (paint.alpha * .24f).toInt()
    repeat(5) { i ->
        val x = width * i / 4f
        canvas.drawLine(x, 0f, x, height, paint)
    }
    val dense = key.curves.size > 4
    paint.strokeWidth = ((if (dense) .55f else .8f) * density).coerceAtLeast(.75f)
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    // 同组各核心仍保留独立路径，共用一次描边，避免同色交叠反复加深。
    key.curves.groupBy { it.color }.forEach { (color, curves) ->
        val path = Path()
        paint.color = color
        paint.alpha = (paint.alpha * if (dense) .78f else .9f).toInt()
        paint.style = Paint.Style.FILL
        curves.forEach { curve ->
            val axis = if (curve.rightAxis) key.right else key.left
            curve.points.forEachIndexed { index, point ->
                val x = point.progress * width
                val y = (1f - axis.fraction(point.value * curve.scale)) * height
                if (index == 0 || point.breakBefore) path.moveTo(x, y) else path.lineTo(x, y)
                if ((index == 0 || point.breakBefore) && (index == curve.points.lastIndex || curve.points[index + 1].breakBefore)) {
                    canvas.drawCircle(x, y, .95f * density, paint)
                }
            }
        }
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = key.markerColor
    key.markers.forEach { canvas.drawCircle(it.coerceIn(0f, 1f) * width, height - 4f * density, 3f * density, paint) }
    bitmap.prepareToDraw()
    return bitmap.asImageBitmap()
}
