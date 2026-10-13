package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import top.qixia.threads.AutoHistoryRecord
import top.qixia.threads.HistoryMetricsStore
import top.qixia.threads.HistorySampleSeries
import top.qixia.threads.db.ThreadData
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.db.SessionSummary

class HistoryAnalysisTest {
    @get:Rule val temp = TemporaryFolder()
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
        val points = listOf(HistoryCurvePoint(0f, 10f), HistoryCurvePoint(1f, 90f))
        assertNull(HistoryAnalysis.nearby(points, .5f, 60000))
        assertEquals(10f, HistoryAnalysis.nearby(points, .01f, 60000)?.value)
        assertNull(HistoryAnalysis.nearby(points.mapIndexed { i, p -> p.copy(breakBefore = i == 1) }, .99f, 60000))
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

    @Test fun longAutoHistoryAssociatesUnreducedFpsFramesAndThreadLoads() {
        val start = 1_791_000_000_000L
        val duration = 2_040_000L
        val text = buildString {
            append("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\n")
            for (index in 1..1020) {
                val time = start + index * 2000L
                append("T\t$time\t10\t11\t1\t776f726b6572\t${index / 20f}\n")
                append("M\t$time\t0\tcpu_usage=30\n")
                append("F\t$time\t${if (index == 500) 20f else 60f + index / 1000f}\t${if (index == 803) 80f else 16.7f}\n")
            }
            append("end_ms\t${start + duration}\nduration_ms\t$duration\nsamples\t1020\n")
        }
        val plain = File(temp.root, "auto_${start}_12.log").apply { writeText(text) }
        val gzip = File(temp.root, "${plain.name}.gz").apply {
            GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) }
        }
        for (file in listOf(plain, gzip)) {
            val record = AutoHistoryRecord.read(file)!!
            val progress = 1_606_000f / duration
            assertTrue(record.fps!!.points.all { abs(it.progress - progress) * duration > 1500 })
            assertNull(HistoryAnalysis.nearby(HistoryPresentation.curve(record.threads.single().series), progress, duration))
            assertEquals(40.15f, HistoryAnalysis.threadLoad(record.threads.single(), progress, duration))
            val drops = HistoryAnalysis.drops(record.fps, record.metrics, duration)
            val frameDrop = drops.single { it.frameMaxMs == 80f }
            assertEquals(60.803f, frameDrop.fps)
            assertEquals(16.7f, drops.single { it.fps == 20f }.frameMaxMs)
            assertTrue(record.fps.points.size <= 240)
        }
    }

    @Test fun calibrationLinksOriginalFpsAndFrameSamplesInsteadOfPreviewPoints() {
        val start = 1_791_000_000_000L
        val duration = 2_040_000L
        val values = (1..1020).map { 60f + it / 1000f }
        val source = temp.newFile("fps.csv").apply { writeText(buildString {
            append("# package=app.one\n# started_at_ms=$start\n# ended_at_ms=${start + duration}\n")
            append("# sample_count=1020\n# average_fps=${values.map(Float::toDouble).average()}\n")
            values.forEachIndexed { index, value -> append("${start + (index + 1) * 2000L},$value\n") }
        }) }
        val fps = HistoryFpsStore.readReports(source, "app.one", listOf((start + duration) / 1000 to duration)).values.single()
        val metrics = HistoryMetricsStore.summarize((1..1020).map { index ->
            HistoryMetrics.Sample(start + index * 2000L, false, mapOf("frame_max_ms" to if (index == 803) 80f else 16.7f))
        }, start, duration)
        val progress = 1_606_000f / duration
        assertTrue(fps.points.all { abs(it.progress - progress) * duration > 1500 })
        assertEquals(60.803f, HistoryAnalysis.drops(fps, metrics, duration).single().fps)
        // 校准线程曲线有 1200 个已保存点，分析不能再抽稀为 720 个点后查询。
        val thread = ThreadData("worker", 50f, 100f, (0..1199).joinToString(",") { (it / 20f).toString() }, "")
        val selected = 602f / 1199
        assertNull(HistoryAnalysis.nearby(HistoryPresentation.curve(thread.series, 720), selected, 3_600_000))
        assertEquals(30.1f, HistoryAnalysis.threadLoad(thread, selected, 3_600_000))
    }

    @Test fun rawAnalysisKeepsMissingValuesAndRealGapsEvenWhenPreviewHasANearbyValue() {
        val raw = HistorySampleSeries.Builder(0, 10_000, gapMs = 2500)
        raw.add(1000, 60f); raw.add(2000, Float.NaN); raw.add(3000, 40f); raw.add(9000, 50f)
        val samples = raw.build()!!
        assertNull(samples.at(.2f))
        assertNull(samples.at(.8f))
        assertEquals(40f, samples.at(.3f))
        val thread = ThreadData("worker", 30f, 60f, "@t:0.2=99=0,0.8=99=0", "", samples)
        assertNull(HistoryAnalysis.threadLoad(thread, .2f, 10000))
        assertNull(HistoryAnalysis.threadLoad(thread, .8f, 10000))
        val frameRaw = HistorySampleSeries.Builder(0, 10_000).apply { add(2000, 80f) }.build()
        val fps = HistoryFpsStore.Report(60f, 60f, 60f, 1, listOf(HistoryFpsStore.Point(.2f, 60f)), analysisSamples = samples)
        val metrics = HistoryMetrics.Report(emptyMap(), 1, 0, 10000, 1f, frameSamples = frameRaw)
        assertNull(HistoryAnalysis.drops(fps, metrics, 10000).single().fps)
    }
}
