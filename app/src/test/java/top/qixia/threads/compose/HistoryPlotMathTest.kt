package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.Color
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.ui.HistoryPlotSeries

class HistoryPlotMathTest {
    @Test fun cursorUsesRealBoundarySamplesWithoutFillingLongMissingTailsOrInternalGaps() {
        val duration = 48 * 60_000L + 5_000
        val points = listOf(HistoryCurvePoint(1500f / duration, 8f),
            HistoryCurvePoint(.2f, 4f), HistoryCurvePoint(.7f, 5f, breakBefore = true),
            HistoryCurvePoint(1f - 1200f / duration, 3f))
        assertSame(points.first(), HistoryPlotMath.at(points, 0f, duration))
        assertSame(points.last(), HistoryPlotMath.at(points, 1f, duration))
        assertNull(HistoryPlotMath.at(points, .5f, duration))
        assertNull(HistoryPlotMath.at(points.dropLast(1), 1f, duration))
        assertNull(HistoryPlotMath.at(points.drop(1), 0f, duration))
        assertNull(HistoryPlotMath.at(points, -.01f, duration))
        assertNull(HistoryPlotMath.at(points, 1.01f, duration))
    }

    @Test fun recorderReleaseGraceKeepsTheActualLastSampleWithoutFillingMissingTails() {
        val duration = 2_889_118L
        val last = HistoryCurvePoint(1f - 4121f / duration, 4.8f)
        val points = listOf(HistoryCurvePoint(458f / duration, 6f), last)
        assertSame(last, HistoryPlotMath.at(points, 1f, duration))
        assertTrue(last.progress < 1f)
        assertNull(HistoryPlotMath.at(listOf(last.copy(progress = 1f - 20_000f / duration)), 1f, duration))
        assertNull(HistoryPlotMath.at(listOf(points.first(), last.copy(breakBefore = true)), .5f, duration))
    }

    @Test fun currentPlotUsesAmpsButCursorAndStatisticsKeepMilliampsAndGaps() {
        val data = HistoryMetrics.Series("battery_ma", 1750f, 1000f, 2500f, 3,
            listOf(HistoryMetrics.Point(0f, 1000f), HistoryMetrics.Point(.2f, 1750f),
                HistoryMetrics.Point(.8f, 2500f, breakBefore = true)))
        val current = HistoryPlotSeries(data, "放电电流", "mA", Color.Blue, valueScale = .001f)
        val axis = HistoryPlotMath.axis(listOf(4.5f) + current.points.map { current.plotValue(it.value) }, "W / A")
        assertTrue(axis.maximum in 4.5f..10f)
        assertEquals(2.5f, current.plotValue(2500f), .0001f)
        assertEquals(2500f, current.data.maximum, 0f)
        assertEquals("mA", current.unit)
        assertEquals(1750f, HistoryPlotMath.at(current.points, .2f)!!.value, 0f)
        assertNull(HistoryPlotMath.at(current.points, .5f))
        assertTrue(current.points.last().breakBefore)
        val unscaled = HistoryPlotSeries(data, "原始数据", "mA", Color.Blue)
        assertEquals(2500f, unscaled.plotValue(2500f), 0f)
    }

    @Test fun fpsAuxiliariesCanCoexistWithoutClippingOrChangingRawValues() {
        val axis = HistoryPlotMath.fpsAuxiliaryAxis(listOf(39.1f, 100f, 3f, 0f))
        assertEquals(0f, axis.minimum, 0f)
        assertEquals(100f, axis.maximum, 0f)
        assertEquals(.391f, axis.fraction(39.1f), .0001f)
        assertEquals(1f, axis.fraction(100f), 0f)
    }

    @Test fun fpsAuxiliaryScalePreservesChargingSubZeroTemperatureAndHighTemperature() {
        val values = listOf(-3f, -12f, 0f, 100f, 112f)
        val axis = HistoryPlotMath.fpsAuxiliaryAxis(values)
        assertTrue(axis.minimum <= -12f)
        assertTrue(axis.maximum >= 112f)
        assertTrue(axis.fraction(-3f) < axis.fraction(0f))
        values.forEach { assertTrue(it in axis.minimum..axis.maximum) }
    }

    @Test fun fpsAuxiliaryScaleIsValidWithoutMetrics() {
        for (values in listOf(emptyList(), listOf(Float.NaN, Float.POSITIVE_INFINITY))) {
            assertEquals(HistoryPlotMath.Axis(0f, 100f), HistoryPlotMath.fpsAuxiliaryAxis(values))
        }
    }

    @Test fun dualAxesPreserveIndependentUnits() {
        val power = HistoryPlotMath.axis(listOf(3.5f, 6.1f), "W")
        val battery = HistoryPlotMath.axis(listOf(83f, 87f), "%")
        assertTrue(power.maximum < 10f)
        assertEquals(0f, battery.minimum, 0f)
        assertEquals(100f, battery.maximum, 0f)
        assertEquals(.85f, battery.fraction(85f), .001f)
        assertTrue(power.fraction(6f) > .5f)
    }

    @Test fun temperatureScaleKeepsVariationAndSupportsSubZero() {
        val warm = HistoryPlotMath.axis(listOf(32.5f, 32.7f), "°C")
        assertTrue(warm.minimum > 20f)
        assertTrue(warm.maximum > 32.7f)
        assertTrue(warm.minimum < 32.5f)
        val cold = HistoryPlotMath.axis(listOf(-5f, 1f), "°C")
        assertTrue(cold.minimum <= -5f)
        assertTrue(cold.fraction(-5f) >= 0f)
    }

    @Test fun axesAlwaysHaveFiniteRangeForFlatMissingAndMixedValues() {
        for (values in listOf(emptyList(), listOf(0f), listOf(1804.8f), listOf(Float.NaN, Float.POSITIVE_INFINITY), listOf(300f, 3187.2f))) {
            val axis = HistoryPlotMath.axis(values, "MHz")
            assertTrue(axis.minimum.isFinite() && axis.maximum.isFinite() && axis.maximum > axis.minimum)
            values.filter { it.isFinite() }.forEach { assertTrue(it >= axis.minimum && it <= axis.maximum) }
        }
    }

    @Test fun cursorDoesNotFillChargingOrUnrecordedIntervals() {
        val points = listOf(HistoryCurvePoint(.1f, 3f), HistoryCurvePoint(.2f, 4f),
            HistoryCurvePoint(.7f, 5f, breakBefore = true), HistoryCurvePoint(.9f, 6f))
        assertNull(HistoryPlotMath.at(points, .5f))
        assertNull(HistoryPlotMath.at(points, 0f))
        assertNull(HistoryPlotMath.at(points, 1f))
        assertEquals(4f, HistoryPlotMath.at(points, .18f)!!.value, 0f)
        assertEquals(5f, HistoryPlotMath.at(points, .7f)!!.value, 0f)
        assertEquals(6f, HistoryPlotMath.at(points, .88f)!!.value, 0f)
    }

    @Test fun cpuLabelsDoNotInventContiguousCores() {
        assertEquals("CPU 0–3", HistoryPlotMath.cpuLabel("cpu_mhz.0_1_2_3"))
        assertEquals("CPU 6–7", HistoryPlotMath.cpuLabel("cpu_mhz.6_7"))
        assertEquals("CPU 7", HistoryPlotMath.cpuLabel("cpu_mhz.7"))
        assertEquals("CPU 0、2、4", HistoryPlotMath.cpuLabel("cpu_mhz.0_2_4"))
    }

    @Test fun cursorSearchPreservesBoundarySamplesAndRejectsInvalidPositions() {
        val points = (0..10000).map { HistoryCurvePoint(it / 10000f, it.toFloat()) }
        assertEquals(0f, HistoryPlotMath.at(points, 0f)!!.value, 0f)
        assertEquals(10000f, HistoryPlotMath.at(points, 1f)!!.value, 0f)
        assertEquals(8765f, HistoryPlotMath.at(points, .87651f)!!.value, 0f)
        assertNull(HistoryPlotMath.at(points, Float.NaN))
        assertNull(HistoryPlotMath.at(points, Float.POSITIVE_INFINITY))
        assertNull(HistoryPlotMath.at(emptyList(), .5f))
        assertEquals(42f, HistoryPlotMath.at(listOf(HistoryCurvePoint(.5f, 42f)), .5f)!!.value, 0f)
    }

    @Test fun cursorPopupStaysInsideBothEdgesAndNarrowPlots() {
        assertEquals(10f, HistoryPlotMath.tooltipOffset(0f, 300f, 150f, 10f), 0f)
        assertEquals(140f, HistoryPlotMath.tooltipOffset(300f, 300f, 150f, 10f), 0f)
        for (width in listOf(100f, 200f, 300f)) {
            for (anchor in listOf(0f, width / 2, width)) {
                val tip = minOf(158f, width)
                val offset = HistoryPlotMath.tooltipOffset(anchor, width, tip, 10f)
                assertTrue(offset >= 0f && offset + tip <= width)
            }
        }
    }
}
