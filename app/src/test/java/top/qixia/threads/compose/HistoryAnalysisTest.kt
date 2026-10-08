package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.db.SessionSummary

class HistoryAnalysisTest {
    private fun fps(values: List<Float>, samples: Int = values.size) = HistoryFpsStore.Report(
        values.average().toFloat(), values.min(), values.max(), samples,
        values.mapIndexed { index, value -> HistoryFpsStore.Point(index.toFloat() / (values.size - 1), value) })
    @Test fun detectsRelativeDropsWithoutAssumingSixtyFps() {
        val report = fps(List(20) { if (it in 9..11) 80f else 165f })
        val drops = HistoryAnalysis.drops(report, null, 19000)
        assertEquals(1, drops.size)
        assertEquals(165f, drops.single().referenceFps)
        assertTrue(HistoryAnalysis.drops(fps(List(20) { 30f }), null, 19000).isEmpty())
    }
    @Test fun sparseAndBrokenFpsCannotInventDrops() {
        assertTrue(HistoryAnalysis.drops(fps(listOf(60f, 20f)), null, 1000).isEmpty())
        val report = fps(List(20) { if (it == 10) 10f else 60f }).let {
            it.copy(points = it.points.mapIndexed { index, point -> point.copy(breakBefore = index == 10) })
        }
        assertTrue(HistoryAnalysis.drops(report, null, 19000).isEmpty())
    }
    @Test fun comparisonUsesSampleCountsAndDoesNotInventWholeRunBatteryUse() {
        val session = SessionSummary(1, 100, 40, 1)
        val accumulator = RunComparisonAccumulator(session)
        val first = session.copy(recordedDurationMs = 10000, recordedEndedAtMs = 90000)
        val second = session.copy(recordedDurationMs = 10000, recordedEndedAtMs = 100000)
        accumulator.add(first, fps(listOf(60f, 60f), 10), HistoryMetrics.Report(mapOf(
            "battery_pct" to HistoryMetrics.Series("battery_pct", 80f, 80f, 80f, 1, listOf(HistoryMetrics.Point(.5f, 80f)))
        ), 1, 0, 10000, .5f))
        accumulator.add(second, fps(listOf(30f, 30f), 20), null)
        val result = accumulator.finish()
        assertEquals(40f, result.averageFps)
        assertEquals(30L, result.fpsSamples)
        assertFalse(result.completeFpsSamples)
        assertNull(result.batteryUsed)
        assertEquals(.25f, result.metricsCoverage)
    }
    @Test fun nearbyAnalysisRejectsInteriorGapsAndDiscontinuities() {
        val points = listOf(HistoryMetrics.Point(0f, 10f), HistoryMetrics.Point(1f, 90f))
        assertNull(HistoryAnalysis.at(points, .5f, 60000))
        assertEquals(10f, HistoryAnalysis.at(points, .01f, 60000))
        assertNull(HistoryAnalysis.at(points.mapIndexed { i, p -> p.copy(breakBefore = i == 1) }, .99f, 60000))
    }
    @Test fun wholeComparisonIncludesEveryWindowAndFailsOnMissingWindow() {
        val session = SessionSummary(-5, 100, 60, 1)
        val windows = (0..2).map { HistoryReportWindow(it, 70000L + it * 10000, 80000L + it * 10000) }
        val loaded = mutableListOf<Int>()
        fun load(index: Int): QixiaThreadsRepository.HistorySessionDetail {
            loaded += index
            val window = session.copy(recordedDurationMs = 10000, recordedEndedAtMs = windows[index].endMs)
            return QixiaThreadsRepository.HistorySessionDetail(emptyList(),
                mapOf(session.id to fps(List(10) { (index + 1) * 30f })), emptyMap(), null, window, windows)
        }
        val result = HistoryRunComparison.summarize(session, {}, ::load)
        assertEquals(listOf(0, 1, 2), loaded)
        assertEquals(60f, result.averageFps)
        assertEquals(30L, result.fpsSamples)
        assertTrue(result.completeFpsSamples)
        assertThrows(IllegalStateException::class.java) {
            HistoryRunComparison.summarize(session, {}) { index ->
                if (index == 1) error("分片不可读") else load(index)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            HistoryRunComparison.summarize(session.copy(recordedEndedAtMs = 101000), {}, ::load)
        }
    }
}
