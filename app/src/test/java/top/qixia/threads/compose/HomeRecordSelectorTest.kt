package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary

class HomeRecordSelectorTest {
    private fun app(pkg: String) = HistoryPackageModel(pkg, pkg, null, 0, 0)
    private fun report(pkg: String = "app.one", start: Long = 1000, end: Long = 201000,
        mode: HomeRecordMode = HomeRecordMode.AUTOMATIC, fps: Float? = 60f): HomeRecord {
        val session = SessionSummary(if (mode == HomeRecordMode.AUTOMATIC) -1 else 1, end / 1000, 0, 1,
            if (mode == HomeRecordMode.AUTOMATIC) HistorySource.AUTO_ALLOCATION else HistorySource.CALIBRATION,
            start, end - start, end)
        return HomeRecord(app(pkg).copy(sessions = listOf(session), sessionCount = 1), mode, start, end,
            fps?.let { HomeFpsSummary(it, start, end) }, session)
    }
    private fun usage(pkg: String = "app.one", start: Long = 1000, end: Long = 201000,
        mode: HomeRecordMode = HomeRecordMode.AUTOMATIC, fps: Float? = 90f) =
        RecentUsageReader.Entry(pkg, mode, start, end, fps?.let { HomeFpsSummary(it, start, end, it) }, if (fps == null) 0 else 10)

    @Test fun disabledHistoryAndRulesHaveUsageSummariesWithoutHistorySessions() {
        val entries = listOf(usage(), usage("app.rules", mode = HomeRecordMode.RULES), usage("app.removed"))
        val result = HomeRecordSelector.select(emptyList(), entries, mapOf("app.one" to true, "app.rules" to true), ::app)
        assertEquals(setOf("app.one", "app.rules"), result.map { it.app.packageName }.toSet())
        assertTrue(result.all { it.report == null && it.app.sessions.isEmpty() })
    }

    @Test fun existingCalibrationAndAutomaticReportsSurviveConfigurationAndRecordingSwitchChanges() {
        val reports = listOf(report(mode = HomeRecordMode.CALIBRATION), report("app.oldauto"))
        assertEquals(reports.toSet(), HomeRecordSelector.select(reports, emptyList(), emptyMap(), ::app).toSet())
    }

    @Test fun oneVisitMergesDespiteIndependentStartClocksAndPrefersRealReportFps() {
        val original = report()
        val result = HomeRecordSelector.select(listOf(original), listOf(usage(start = 2000, end = 200000)),
            mapOf("app.one" to true), ::app).single()
        assertEquals(original, result)
        assertEquals(60f, result.fps!!.average, .001f)
    }

    @Test fun matchingUsageCanFillMissingReportFpsOnlyForTheSameVisit() {
        val original = report(fps = null)
        val same = usage(start = 2000, end = 200000)
        val result = HomeRecordSelector.select(listOf(original), listOf(same), mapOf("app.one" to true), ::app).single()
        assertEquals(original.report, result.report)
        assertEquals(same.fps, result.fps)
        assertNull(HomeRecordSelector.select(listOf(original), listOf(usage(start = 100, end = 2000)),
            mapOf("app.one" to true), ::app).single().fps)
    }

    @Test fun adjacentShortNewVisitKeepsPreviousReportAndFpsUnchanged() {
        val previous = report()
        val recent = usage(start = 200500, end = 210000, fps = null)
        val result = HomeRecordSelector.select(listOf(previous), listOf(recent), mapOf("app.one" to true), ::app).single()
        assertEquals(previous, result)
    }

    @Test fun subThresholdNewUsageIsNotRecordedOrUsedToReplaceAnOlderReport() {
        val previous = report()
        val recent = usage(start = 300000, end = 320000)
        val configured = mapOf("app.one" to true)
        assertTrue(HomeRecordSelector.select(emptyList(), listOf(recent), configured, ::app).isEmpty())
        assertEquals(previous, HomeRecordSelector.select(listOf(previous), listOf(recent), configured, ::app).single())
    }

    @Test fun allFourModesRequireMoreThanThreeMinutes() {
        for (duration in listOf(179_999L, 180_000L, 180_001L)) {
            val reports = listOf(report("app.calibration", end = 1000 + duration, mode = HomeRecordMode.CALIBRATION),
                report("app.auto.recorded", end = 1000 + duration))
            val usage = listOf(usage("app.auto.summary", end = 1000 + duration),
                usage("app.rules", end = 1000 + duration, mode = HomeRecordMode.RULES))
            val result = HomeRecordSelector.select(reports, usage, usage.associate { it.packageName to true }, ::app)
            assertEquals("duration=$duration", if (duration > 180_000L) 4 else 0, result.size)
        }
        assertFalse(HomeRecordSelector.hasRecordableDuration(-1L, Long.MAX_VALUE))
        assertFalse(HomeRecordSelector.hasRecordableDuration(Long.MAX_VALUE, 1L))
    }

    @Test fun newerShortCalibrationDoesNotDisplaceAnOlderQualifyingRecord() {
        val previous = report(mode = HomeRecordMode.CALIBRATION)
        val short = report(start = 300000, end = 310000, mode = HomeRecordMode.CALIBRATION)
        assertEquals(listOf(previous), HomeRecordSelector.select(listOf(short, previous), emptyList(), emptyMap(), ::app))
    }

    @Test fun calibrationAverageRequiresBothMatchingBoundariesAndCoverage() {
        val exact = HomeFpsSummary(60f, 1000, 201000)
        assertEquals(exact, HomeRecordSelector.matchingCalibrationFps(1500, 201500, exact))
        assertNull(HomeRecordSelector.matchingCalibrationFps(202000, 402000, exact))
        assertNull(HomeRecordSelector.matchingCalibrationFps(101000, 201000, exact))
        assertNull(HomeRecordSelector.matchingCalibrationFps(1000, 2000, HomeFpsSummary(60f, 1900, 2900)))
        assertNull(HomeRecordSelector.matchingCalibrationFps(1000, 201000, exact.copy(average = Float.NaN)))
    }

    @Test fun packageDeduplicationAndBoundKeepNewestTwentyFourInOrder() {
        val entries = (1..30).map { usage("app.$it", start = it * 1000L, end = it * 1000L + 200000) }
        val result = HomeRecordSelector.select(listOf(report("app.30", 1, 100)), entries,
            entries.associate { it.packageName to true }, ::app)
        assertEquals(24, result.size)
        assertEquals("app.30", result.first().app.packageName)
        assertEquals("app.7", result.last().app.packageName)
        assertEquals(24, result.map { it.app.packageName }.distinct().size)
    }
}
