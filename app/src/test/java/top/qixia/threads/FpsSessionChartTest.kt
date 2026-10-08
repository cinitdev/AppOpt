package top.qixia.threads

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FpsSessionChartTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pkg = "com.example.chart"

    private fun record(count: Int = 3): FpsSessionRecorder.AverageRecord {
        val recorder = FpsSessionRecorder(temporary.root) { 1_000L }
        recorder.begin(pkg)
        recorder.markRunning(pkg)
        repeat(count) { recorder.record(pkg, if (it == 1) 120f else 60f, 1_000L + it * 1_000L) }
        return recorder.finish(pkg, (count + 1) * 1_000L)!!
    }

    @Test fun readsRealTimestampsAndPeakWithBoundedChartMemory() {
        val average = record(10_000)
        val chart = FpsSessionChart.read(temporary.root, average)!!
        assertTrue(chart.points.size <= 80)
        assertEquals(120f, chart.peak, .001f)
        assertTrue(chart.points.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
        assertEquals(10_000_000L, chart.points.last().timestampMs)
    }

    @Test fun refusesStaleArchiveWhenNewAverageWasPublished() {
        val average = record()
        assertNull(FpsSessionChart.read(temporary.root, average.copy(startedAtMs = 2_000L)))
        assertNull(FpsSessionChart.read(temporary.root, average.copy(sampleCount = 4)))
        assertNull(FpsSessionChart.read(temporary.root, average.copy(averageFps = 50f)))
    }

    @Test fun refusesCorruptAndMissingArchivesWithoutLosingAverage() {
        val average = record()
        val archive = FpsSessionRecorder.lastSampleFile(temporary.root, pkg)
        archive.appendText("4000,NaN\n")
        assertNull(FpsSessionChart.read(temporary.root, average))
        assertTrue(archive.delete())
        assertNull(FpsSessionChart.read(temporary.root, average))
        assertEquals(80f, FpsSessionRecorder.readLastAverage(temporary.root, pkg)!!.averageFps, .001f)
    }

    @Test fun preservesSingleSampleAsAPoint() {
        val chart = FpsSessionChart.read(temporary.root, record(1))!!
        assertEquals(1, chart.points.size)
        assertEquals(60f, chart.points.single().fps, .001f)
        assertEquals(1_000L, chart.points.single().timestampMs)
    }
}
