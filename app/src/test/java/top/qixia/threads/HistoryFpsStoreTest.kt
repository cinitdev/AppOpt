package top.qixia.threads

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryFpsStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val pkg = "com.example.history"

    @Test fun deletingOneSessionDoesNotRemoveAnArchiveUsedByAnother() {
        run(100000L, 60f)
        val archive = folder.root.resolve("history_fps")
        HistoryFpsStore.removeSessions(folder.root, pkg, listOf(110L to 10000L), listOf(120L to 10000L))
        assertEquals(1, archive.listFiles()!!.size)
        HistoryFpsStore.removeSessions(folder.root, pkg, listOf(120L to 10000L), emptyList())
        assertEquals(0, archive.listFiles()!!.size)
        assertTrue(HistoryFpsStore.reports(folder.root, pkg, emptyList()).isEmpty())
        assertEquals(0, archive.listFiles()!!.size)
    }

    private fun run(start: Long, fps: Float) {
        val recorder = FpsSessionRecorder(folder.root) { start }
        recorder.begin(pkg); recorder.markRunning(pkg)
        repeat(20) { recorder.record(pkg, fps, start + it * 1000L) }
        recorder.finish(pkg, start + 20000L)
    }

    @Test fun independentRunsRemainAvailableAndNeverBorrowEachOthersAverage() {
        run(100000, 60f); run(200000, 120f)
        val reports = HistoryFpsStore.reports(folder.root, pkg, listOf(120L to 20000L, 220L to 20000L, 300L to 20000L))
        assertEquals(60f, reports.getValue(120).average, .01f)
        assertEquals(120f, reports.getValue(220).average, .01f)
        assertFalse(reports.containsKey(300))
    }
    @Test fun reportUsesOnlyTheCalibratedPartOfTheRun() {
        val recorder = FpsSessionRecorder(folder.root) { 100000L }
        recorder.begin(pkg); recorder.markRunning(pkg)
        repeat(20) { recorder.record(pkg, if (it < 10) 30f else 60f, 100000L + it * 1000L) }
        recorder.finish(pkg, 120000L)
        val report = HistoryFpsStore.reports(folder.root, pkg, listOf(120L to 10000L)).getValue(120)
        assertEquals(60f, report.average, .01f)
        assertEquals(10, report.samples)
        val cached = HistoryFpsStore.cachedSummary(folder.root, pkg, 120L, 10000L)!!
        assertEquals(60f, cached.average, .01f)
        assertEquals(10, cached.samples)
        assertTrue(cached.points.isNotEmpty())
        assertTrue(cached.points.all { it.timestampMs!! in 110000L..120000L })
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120L, 20000L))
    }

    @Test fun preparedHomeSummaryIsExactAndInvalidatesWhenItsSourceChanges() {
        run(100000, 60f)
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000))
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, listOf(120L to 20000L))
        assertEquals(60f, HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000)!!.average, .001f)
        val source = folder.root.resolve("history_fps").listFiles()!!.single { it.extension == "csv" }
        val original = source.readBytes()
        source.appendText("# source changed\n")
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000))
        assertTrue(source.length() > original.size)
    }

    @Test fun homeCurveIsBoundedToRealCalibrationSamplesAndPreservesMissingIntervals() {
        val source = linkedMapOf<Long, Float>()
        repeat(7200) { index ->
            if (index !in 3000..3599) {
                val time = 100000L + index * 1000L
                val value = if (index == 6500) 120f else 55f + index % 6
                source[time] = value
            }
        }
        val csv = folder.newFile("completed.csv").apply {
            writeText("# package=$pkg\n# started_at_ms=100000\ntimestamp_ms,fps\n" +
                source.entries.joinToString("\n") { "${it.key},${it.value}" } + "\n")
        }
        HistoryFpsStore.archive(folder.root, FpsSessionRecorder.AverageRecord(pkg,
            source.values.map(Float::toDouble).average().toFloat(), source.size.toLong(), 100000L, 7_300_000L), csv)
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, listOf(7300L to 7_200_000L))
        val cached = HistoryFpsStore.cachedSummary(folder.root, pkg, 7300L, 7_200_000L)!!
        assertTrue(cached.points.size in 1..80)
        assertEquals(source.size, cached.samples)
        assertEquals(source.keys.first(), cached.points.first().timestampMs)
        assertEquals(source.keys.last(), cached.points.last().timestampMs)
        assertTrue(cached.points.all { source[it.timestampMs] == it.fps })
        assertEquals(120f, cached.points.maxOf { it.fps }, 0f)
        assertTrue(cached.points.any { it.breakBefore && it.timestampMs!! >= 3_700_000L })
        assertTrue(folder.root.resolve("history_fps").listFiles()!!.single { it.extension == "summary" }.length() < 4096)
    }

    @Test fun legacyStatsOnlyCacheIsRebuiltOffTheHomePath() {
        run(100000L, 60f)
        val windows = listOf(120L to 20000L)
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, windows)
        val metadata = folder.root.resolve("history_fps").listFiles()!!.single { it.extension == "summary" }
        java.io.RandomAccessFile(metadata, "rw").use { it.writeInt(0x51465731) }
        val legacy = metadata.readBytes()
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120L, 20000L))
        assertArrayEquals(legacy, metadata.readBytes())
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, windows)
        assertTrue(HistoryFpsStore.cachedSummary(folder.root, pkg, 120L, 20000L)!!.points.isNotEmpty())
    }

    @Test fun summaryRetentionTracksDatabaseWindowsWithoutDeletingOtherCurves() {
        run(100000, 60f); run(200000, 90f)
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, listOf(120L to 20000L, 220L to 20000L))
        HistoryFpsStore.prune(folder.root, mapOf(pkg to listOf(220L to 20000L)))
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000))
        assertEquals(90f, HistoryFpsStore.cachedSummary(folder.root, pkg, 220, 20000)!!.average, .001f)
        assertEquals(2, folder.root.resolve("history_fps").listFiles()!!.count { it.extension == "csv" })
        assertEquals(1, folder.root.resolve("history_fps").listFiles()!!.count { it.extension == "summary" })
    }

    @Test fun retainedCalibrationCacheSurvivesManualRunPruningAndDisappearsWithItsSession() {
        run(100000, 58f)
        val windows = listOf(120L to 20000L)
        HistoryFpsStore.prepareHomeSummaries(folder.root, pkg, windows)
        repeat(32) { index ->
            val start = 200000L + index * 30000L
            val recorder = FpsSessionRecorder(folder.root, retainedCalibrations = { mapOf(pkg to windows) }) { start }
            recorder.begin(pkg); recorder.markRunning(pkg); recorder.record(pkg, 120f, start + 1000)
            recorder.finish(pkg, start + 2000)
        }
        assertEquals(58f, HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000)!!.average, .001f)
        HistoryFpsStore.removeSessions(folder.root, pkg, windows, emptyList())
        assertNull(HistoryFpsStore.cachedSummary(folder.root, pkg, 120, 20000))
    }
    @Test fun mismatchedOrCorruptArchiveIsNotDisplayedAsAValidReport() {
        run(100000L, 60f)
        val archive = folder.root.resolve("history_fps").listFiles()!!.single()
        assertTrue(HistoryFpsStore.readReports(archive, "other.pkg", listOf(120L to 20000L)).isEmpty())
        archive.appendText("119000,NaN\n")
        assertTrue(HistoryFpsStore.readReports(archive, pkg, listOf(120L to 20000L)).isEmpty())
    }
    @Test fun archivesAreBoundedPerPackage() {
        repeat(35) { run(100000L + it * 30000L, 60f) }
        assertEquals(30, folder.root.resolve("history_fps").listFiles()!!.count { it.extension == "csv" })
    }

    @Test fun staleLegacyCsvCannotOccupyANewerRunsArchiveKey() {
        run(100000, 60f)
        val record = FpsSessionRecorder.readLastAverage(folder.root, pkg)!!
        HistoryFpsStore.archive(folder.root, record.copy(startedAtMs = 200000),
            FpsSessionRecorder.lastSampleFile(folder.root, pkg))
        assertEquals(1, folder.root.resolve("history_fps").listFiles()!!.size)
    }

    @Test fun thirtyNewManualRunsDoNotEvictReferencedCalibration() {
        run(100000, 55f)
        val retained = mapOf(pkg to listOf(120L to 20000L))
        repeat(35) { index ->
            val start = 200000L + index * 30000L
            val recorder = FpsSessionRecorder(folder.root, retainedCalibrations = { retained }) { start }
            recorder.begin(pkg); recorder.markRunning(pkg)
            recorder.record(pkg, 120f, start + 1000)
            recorder.finish(pkg, start + 20000)
        }
        HistoryFpsStore.prune(folder.root, retained)
        assertEquals(55f, HistoryFpsStore.reports(folder.root, pkg, retained.getValue(pkg)).getValue(120).average, .001f)
        assertEquals(31, folder.root.resolve("history_fps").listFiles()!!.count { it.extension == "csv" })
    }

    @Test fun calibrationAwaitingNativeImportSurvivesUnrelatedFpsRuns() {
        val recorder = FpsSessionRecorder(folder.root) { 100000L }
        recorder.begin(pkg); recorder.markRunning(pkg); recorder.markCalibration(pkg)
        repeat(20) { recorder.record(pkg, 50f, 100000L + it * 1000L) }
        recorder.finish(pkg, 120000)
        repeat(35) { run(200000L + it * 30000L, 120f) }
        assertEquals(50f, HistoryFpsStore.reports(folder.root, pkg, listOf(120L to 20000L)).getValue(120).average, .001f)
    }

    @Test fun interruptedCalibrationKeepsPendingProtectionAfterRecorderRestarts() {
        val recorder = FpsSessionRecorder(folder.root) { 100000L }
        recorder.begin(pkg); recorder.markRunning(pkg); recorder.markCalibration(pkg)
        repeat(20) { recorder.record(pkg, 48f, 100000L + it * 1000L) }
        // begin 会恢复上一份 .current CSV，不依赖内存标志。
        FpsSessionRecorder(folder.root) { 200000L }.begin(pkg)
        repeat(35) { run(200000L + it * 30000L, 120f) }
        assertEquals(48f, HistoryFpsStore.reports(folder.root, pkg, listOf(119L to 19000L)).getValue(119).average, .001f)
    }

    @Test fun unreferencedPendingAllowanceRemainsBounded() {
        repeat(45) { index ->
            val start = 100000L + index * 30000L
            val recorder = FpsSessionRecorder(folder.root) { start }
            recorder.begin(pkg); recorder.markCalibration(pkg); recorder.markRunning(pkg)
            recorder.record(pkg, 60f, start + 1000)
            recorder.finish(pkg, start + 2000)
        }
        assertEquals(40, folder.root.resolve("history_fps").listFiles()!!.count { it.extension == "csv" })
    }
}
