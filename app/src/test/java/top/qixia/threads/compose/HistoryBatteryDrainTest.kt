package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.HistoryMetricsStore

class HistoryBatteryDrainTest {
    private fun report(vararg values: Float): HistoryMetrics.Series = HistoryMetricsStore.summarize(
        values.mapIndexed { i, value -> HistoryMetrics.Sample((i + 1) * 2000L, false, mapOf("battery_pct" to value)) },
        0, values.size * 2000L).series.getValue("battery_pct")

    @Test fun consumptionIsStartMinusEndNotRemainingBatteryOrRelativePercent() {
        val drain = HistoryBatteryDrain.from(report(85f, 84f, 82f))!!
        assertEquals(85f, drain.start, 0f)
        assertEquals(82f, drain.end, 0f)
        assertEquals(3f, drain.consumed, 0f)
        assertEquals(listOf(0f, 1f, 3f), drain.series.points.map { it.value })
        assertEquals(3, drain.series.samples)
        assertEquals(0f, drain.axis.minimum, 0f)
        assertEquals(3f, drain.axis.maximum, 0f)
    }

    @Test fun unchangedBatteryShowsZeroWithFiniteAxis() {
        val drain = HistoryBatteryDrain.from(report(83f, 83f, 83f))!!
        assertEquals(0f, drain.consumed, 0f)
        assertEquals(0f, drain.series.maximum, 0f)
        assertTrue(drain.axis.maximum > drain.axis.minimum)
    }

    @Test fun chargingAndGaugeRecoveryStaySignedInsteadOfInventingConsumption() {
        val drain = HistoryBatteryDrain.from(report(80f, 79f, 84f))!!
        assertEquals(-4f, drain.consumed, 0f)
        assertEquals(listOf(0f, 1f, -4f), drain.series.points.map { it.value })
        assertEquals(-4f, drain.axis.minimum, 0f)
        assertEquals(1f, drain.axis.maximum, 0f)
    }

    @Test fun missingIntervalsStayMissingAndProgressDoesNotStretch() {
        val battery = report(85f, 83f).copy(points = listOf(
            HistoryMetrics.Point(.2f, 85f), HistoryMetrics.Point(.8f, 83f, true)))
        val drain = HistoryBatteryDrain.from(battery)!!
        assertEquals(.2f, drain.series.points.first().progress, 0f)
        assertTrue(drain.series.points.last().breakBefore)
        assertNull(HistoryPlotMath.at(drain.series.points.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }, .5f))
    }

    @Test fun missingSingleInvalidOrUnorderedSamplesCannotClaimAValidTotal() {
        assertNull(HistoryBatteryDrain.from(null))
        assertNull(HistoryBatteryDrain.from(report(85f)))
        for (points in listOf(
            listOf(HistoryMetrics.Point(0f, 85f), HistoryMetrics.Point(1f, Float.NaN)),
            listOf(HistoryMetrics.Point(0f, 85f), HistoryMetrics.Point(1f, 101f)),
            listOf(HistoryMetrics.Point(.5f, 85f), HistoryMetrics.Point(.5f, 82f)),
            listOf(HistoryMetrics.Point(.8f, 85f), HistoryMetrics.Point(.2f, 82f)),
        )) assertNull(HistoryBatteryDrain.from(report(85f, 82f).copy(points = points)))
    }

    @Test fun longRecordKeepsOriginalEndpointsAndFullSampleStatistics() {
        val readings = FloatArray(10000) { 90f - it / 1000 }
        val battery = report(*readings)
        val drain = HistoryBatteryDrain.from(battery)!!
        assertTrue(drain.series.points.size <= 240)
        assertEquals(90f, drain.start, 0f)
        assertEquals(81f, drain.end, 0f)
        assertEquals(10000, drain.series.samples)
        assertEquals(4.5f, drain.series.average, .001f)
        assertEquals(9f, drain.series.points.last().value, 0f)
    }
}
