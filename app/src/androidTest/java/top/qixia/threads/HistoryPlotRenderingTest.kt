package top.qixia.threads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.*

class HistoryPlotRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollDoesNotActivateCursorButLongPressStillDoes() {
        val cursor = mutableStateOf<Float?>(null)
        var scrollPosition = { 0 }
        val data = HistoryMetrics.Series("test", 50f, 20f, 80f, 2,
            listOf(HistoryMetrics.Point(0f, 20f), HistoryMetrics.Point(1f, 80f)))
        compose.setContent { QixiaThreadsTheme {
            val scroll = rememberScrollState()
            scrollPosition = { scroll.value }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                HistoryReportPlotCard("测试图表", listOf(HistoryPlotSeries(data, "CPU", "%", Color.Blue)),
                    2000L, "%", description = "测试数据", emptyMessage = "", cursor = cursor)
                Spacer(Modifier.height(1200.dp))
            }
        } }
        val chart = compose.onNode(hasContentDescription("趋势图", substring = true))
        chart.performTouchInput { down(center) }
        compose.runOnIdle { assertNull("普通按下不能触发所有图表游标", cursor.value) }
        chart.performTouchInput { moveBy(Offset(0f, -100f)); up() }
        compose.runOnIdle { assertNull(cursor.value); assertTrue(scrollPosition() > 0) }
        chart.performTouchInput { down(Offset(width * .5f, height * .8f)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertNotNull("长按仍应显示原始样本", cursor.value) }
        chart.performTouchInput { up() }
        compose.runOnIdle { assertNull(cursor.value) }
    }

    @Test fun rasterPreservesMissingIntervalsAndInvalidatesChangedSamples() = runBlocking {
        val cache = HistoryPlotRasterCache()
        val red = Color.Red.toArgb()
        val points = listOf(HistoryCurvePoint(.1f, 20f), HistoryCurvePoint(.2f, 80f),
            HistoryCurvePoint(.8f, 20f, true), HistoryCurvePoint(.9f, 80f))
        val request = HistoryRasterKey(listOf(HistoryRasterCurve(points, red, 1f, false)),
            IntSize(200, 100), 1f, HistoryPlotMath.Axis(0f, 100f), HistoryPlotMath.Axis(0f, 100f),
            emptyList(), Color.Transparent.toArgb(), Color.Blue.toArgb())
        val first = cache.render(request).asAndroidBitmap()
        assertTrue("已有曲线须绘制", android.graphics.Color.alpha(first.getPixel(30, 50)) > 0)
        assertEquals(red and 0xffffff, first.getPixel(30, 50) and 0xffffff)
        assertEquals("缺测区间不得连线", 0, first.getPixel(100, 50))
        val changed = request.copy(curves = listOf(HistoryRasterCurve(
            listOf(HistoryCurvePoint(0f, 50f), HistoryCurvePoint(1f, 50f)), red, 1f, false)))
        val second = cache.render(changed).asAndroidBitmap()
        assertTrue("样本改变后不能复用旧图", android.graphics.Color.alpha(second.getPixel(100, 50)) > 0)
        val hidden = cache.render(changed.copy(curves = emptyList())).asAndroidBitmap()
        assertEquals("取消图例必须隐藏对应曲线", 0, hidden.getPixel(100, 50))
    }

    @Test fun overlappingCoresDoNotRepeatedlyDarkenTheSameStroke() = runBlocking {
        val cache = HistoryPlotRasterCache()
        val points = listOf(HistoryCurvePoint(0f, 50f), HistoryCurvePoint(1f, 50f))
        val curve = HistoryRasterCurve(points, Color.Cyan.toArgb(), 1f, false)
        val axis = HistoryPlotMath.Axis(0f, 100f)
        val single = HistoryRasterKey(listOf(curve), IntSize(200, 100), 2f, axis, axis,
            emptyList(), Color.Transparent.toArgb(), Color.Transparent.toArgb())
        val before = cache.render(single).asAndroidBitmap()
        val overlapping = cache.render(single.copy(curves = listOf(curve, curve, curve))).asAndroidBitmap()
        val singlePixel = before.getPixel(100, 50)
        val overlapPixel = overlapping.getPixel(100, 50)
        assertEquals(singlePixel and 0xffffff, overlapPixel and 0xffffff)
        // 抗锯齿覆盖率取整允许 1 级误差；重复混合会产生明显更大的透明度差异。
        assertTrue("同色重叠不能叠加成更深的色块", kotlin.math.abs(
            android.graphics.Color.alpha(singlePixel) - android.graphics.Color.alpha(overlapPixel)) <= 1)
        assertEquals("曲线下方不应出现大面积填充", 0, overlapping.getPixel(100, 70))
    }
}
