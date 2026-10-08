package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class HistoryReportParityTest {
    @Test fun fpsSummaryUsesOriginalSamplesAndDoesNotInventShortLow5() {
        val values = List(10) { 30f } + List(190) { 60f }
        val report = HistoryFpsStore.summary(values, emptyList())
        assertEquals(30f, report.low5!!, .001f)
        assertEquals(58.5f, report.average, .001f)
        assertTrue(report.jitter!! > 0)
        assertNull(HistoryFpsStore.summary(List(100) { 60f }, emptyList()).low5)
        assertEquals(0f, HistoryFpsStore.summary(List(101) { 60f }, emptyList()).jitter!!, .001f)
        assertNull(HistoryFpsStore.summary(listOf(0f), emptyList()).jitter)
        assertEquals((10 * 30f + 60f) / 11, HistoryFpsStore.summary(List(10) { 30f } + List(200) { 60f }, emptyList()).low5!!, .001f)
    }
    @Test fun optionalFrameLinesPreservePeaksAndExpire() {
        val report = HistoryFrameReport()
        assertFalse(report.accept("60.0", 1000))
        report.accept("frame 31.2", 1100)
        report.accept("frame 18.0", 2100)
        report.accept("frame NaN", 2200)
        assertEquals(31.2f, report.take(2500)!!, .001f)
        assertNull(report.take(2501))
        report.accept("frame 25", 3000)
        assertNull(report.take(7000))
    }
    @Test fun longContinuousFrequencyDistributionIsBoundedWithoutDroppingSamples() {
        val report = HistoryMetricsStore.summarize((1..10000).map { i ->
            HistoryMetrics.Sample(i * 2000L, false, mapOf("cpu_mhz.0_1" to 1000f + i / 10f))
        }, 0, 20_000_000)
        val bins = report.distributions.getValue("cpu_mhz.0_1")
        assertTrue(bins.size <= 32)
        assertEquals(100f, bins.sumOf { it.percent.toDouble() }.toFloat(), .001f)
        assertEquals(1000.1f, bins.last().minimum, .001f)
        assertEquals(2000f, bins.first().maximum, .001f)
    }
}
