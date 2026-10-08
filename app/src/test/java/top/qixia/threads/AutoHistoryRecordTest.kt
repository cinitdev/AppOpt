package top.qixia.threads

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.qixia.threads.compose.HistoryPresentation
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary

class AutoHistoryRecordTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hex(name: String) = name.toByteArray().joinToString("") { "%02x".format(it) }
    private fun write(body: String, name: String = "auto_1000_12.log", duration: Long = 10_000): File =
        File(temp.root, name).also { it.writeText("QIXIA_AUTO_HISTORY\t1\npackage\tapp.one\nstart_ms\t1000\nsource\tauto\n" +
            body.trimIndent() + "\nend_ms\t${1000 + duration}\nduration_ms\t$duration\nsamples\t5\n") }

    @Test fun keepsAllObservedActiveThreadsAndDoesNotApplyAllocationFivePercentThreshold() {
        val file = write("""
            T	2000	12	13	100	${hex("worker")}	0.5
            T	4000	12	13	100	${hex("worker")}	0
            T	2000	12	14	101	${hex("sleeping")}	0
            T	4000	12	14	101	${hex("sleeping")}	0
            T	2000	12	15	102	${hex("render")}	50
            T	4000	12	15	102	${hex("render")}	70
        """)
        val record = AutoHistoryRecord.read(file)!!
        assertEquals(record.entry, AutoHistoryRecord.summary(file))
        assertEquals(listOf("render", "worker"), record.threads.map { it.name })
        assertEquals(.25f, record.threads.last().avg, .001f)
        assertEquals(HistorySource.AUTO_ALLOCATION, record.entry.session.source)
        assertEquals(10_000L, record.entry.session.durationMs)
        assertEquals(1000L, record.entry.session.startedAtMs)
        assertNull(record.fps)
    }

    @Test fun rejectsForeignProtocolWithoutModifyingTheArchive() {
        val file = write("F\t2000\t60\t16.7")
        assertNotNull(AutoHistoryRecord.read(file))
        val foreign = file.readText().replace("QIXIA_AUTO_HISTORY", "OTHER_AUTO_HISTORY")
        file.writeText(foreign)
        assertNull(AutoHistoryRecord.read(file))
        assertEquals(foreign, file.readText())
    }

    @Test fun realRunDurationIsIndependentOfCalibrationRoundsAndLegacyRowsStayCompatible() {
        assertEquals(2500L, SessionSummary(1, 100, 5, 2).durationMs)
        val record = AutoHistoryRecord.read(write("F\t2000\t60\t16.7", duration = 12_345))!!
        assertEquals(12_345L, record.entry.session.durationMs)
        assertEquals(60f, record.fps!!.average, .001f)
        assertEquals(16.7f, record.metrics!!.series.getValue("frame_max_ms").average, .001f)
    }

    @Test fun sameSecondSessionsHaveSeparateStableIdsAndSameNameReusedTidRemainsDistinct() {
        assertTrue(AutoHistoryRecord.sessionId("auto_1000_12.log") < 0)
        assertEquals(AutoHistoryRecord.sessionId("auto_1000_12.log"), AutoHistoryRecord.sessionId("auto_1000_12.log"))
        assertNotEquals(AutoHistoryRecord.sessionId("auto_1000_12.log"), AutoHistoryRecord.sessionId("auto_1001_12.log"))
        val record = AutoHistoryRecord.read(write("""
            T	2000	12	13	100	${hex("worker")}	1
            T	4000	12	13	200	${hex("worker")}	20
        """))!!
        assertEquals(2, record.threads.size)
        assertEquals(listOf(20f, 1f), record.threads.map { it.avg })
    }

    @Test fun preservesNativeUnitsDropsChargingPowerAndDoesNotFillMissingMetricsWithZero() {
        val record = AutoHistoryRecord.read(write("""
            M	2000	0	power_w=4	battery_ma=1000	battery_pct=70	cpu_core.7=60	cpu_mhz.4_5_6=2400
            M	4000	1	power_w=6	battery_ma=1500	battery_pct=70	cpu_core.7=40
            M	6000	-1	power_w=8	battery_ma=2000	cpu_core.7=20
            M	8000	0	power_w=5	battery_ma=1200	battery_pct=69
        """))!!
        val metrics = record.metrics!!
        assertEquals(4.5f, metrics.series.getValue("power_w").average, .001f)
        assertEquals(1100f, metrics.series.getValue("battery_ma").average, .001f)
        assertEquals(1, metrics.chargingSamples)
        assertEquals(2, metrics.series.getValue("power_w").samples)
        assertTrue(metrics.series.getValue("power_w").points.last().breakBefore)
        assertEquals(40f, metrics.series.getValue("cpu_core.7").average, .001f)
        assertNull(metrics.series["gpu_mhz"])
    }

    @Test fun threadCurvesKeepTimelineGapsWithoutTreatingUnobservedThreadsAsIdle() {
        val record = AutoHistoryRecord.read(write("""
            T	2000	12	13	100	${hex("轻线程")}	1
            T	9000	12	13	100	${hex("轻线程")}	3
        """))!!
        val thread = record.threads.single()
        assertEquals(2f, thread.avg, .001f)
        val points = HistoryPresentation.curve(thread.series)
        assertEquals(.1f, points.first().progress, .001f)
        assertEquals(.8f, points.last().progress, .001f)
        assertTrue(points.last().breakBefore)
    }

    @Test fun acceptsSmallNativeTimingOvershootWithoutLosingTheWholeRun() {
        val record = AutoHistoryRecord.read(write("T\t2000\t12\t13\t100\t${hex("worker")}\t101"))!!
        assertEquals(100f, record.threads.single().avg, .001f)
    }

    @Test fun boundsLongThreadCurvesAndPreservesLatePeakAndMissingWindow() {
        val text = (1..10_000).filter { it !in 7000..7010 }.joinToString("\n") { sample ->
            "T\t${1000 + sample * 2000L}\t12\t13\t100\t${hex("worker")}\t${if (sample == 9500) 99 else 1}"
        }
        val record = AutoHistoryRecord.read(write(text, duration = 20_000_000))!!
        val points = HistoryPresentation.curve(record.threads.single().series)
        assertTrue(points.size <= 240)
        assertEquals(99f, points.maxOf { it.value }, .001f)
        assertTrue(points.any { it.breakBefore })
    }

    @Test fun ignoresUnfinishedFilesAndRejectsWrongVersionNamesAndInvalidUtf8() {
        val pending = write("F\t2000\t60\t16.7", name = "auto_1000_12.tmp")
        assertNull(AutoHistoryRecord.read(pending))
        val file = write("T\t2000\t12\t13\t100\tff\t5")
        assertNull(AutoHistoryRecord.read(file))
        file.writeText("QIXIA_AUTO_HISTORY\t1\npackage\tapp.one\nstart_ms\t1000\nsource\tauto\nF\t2000\t60\t16.7\n")
        assertNull(AutoHistoryRecord.read(file))
        file.writeText(file.readText().replace("HISTORY\t1", "HISTORY\t2") + "end_ms\t4000\n")
        assertNull(AutoHistoryRecord.read(file))
    }

    @Test fun affinityChangesRemainIndependentFromThreadMetricsAndCanBeExported() {
        val record = AutoHistoryRecord.read(write("A\t2000\t12\t13\t100\t4-6\nA\t4000\t12\t13\t100\trestore"))!!
        assertEquals(listOf("4-6", "restore"), record.affinityChanges.map { it.cpus })
        assertEquals(100L, record.affinityChanges.first().startTicks)
    }

    @Test fun fpsMeasurementGapsRemainMissingInsteadOfZeroOrConnectedLines() {
        val record = AutoHistoryRecord.read(write("F\t2000\t60\t16.7\nF\t9000\t58\t18.0"))!!
        assertEquals(59f, record.fps!!.average, .001f)
        assertEquals(2, record.fps!!.samples)
        assertTrue(record.fps!!.points.last().breakBefore)
    }

    @Test fun newCapturesMustStrictlyExceedThreeMinutesButLegacyShortRunsStayReadable() {
        assertNotNull(AutoHistoryRecord.read(write("F\t2000\t60\t16.7")))
        for (usage in listOf(179_999L, 180_000L, 180_001L)) {
            val file = write("minimum_usage_ms\t180000\nforeground_ms\t0\nF\t2000\t60\t16.7")
            file.appendText("foreground_ms\t$usage\n")
            assertEquals("usage=$usage", usage > 180_000L, AutoHistoryRecord.read(file) != null)
        }
    }

    @Test fun admissionUsesFinalWholeCaptureUsageEvenForShortSegmentsWithLongBodies() {
        val file = write("minimum_usage_ms\t180000\nforeground_ms\t0\n" +
            (1..1500).joinToString("\n") { "F\t${1000 + it}\t60\t16.7" }, duration = 10_000)
        file.appendText("foreground_ms\t180001\n")
        assertTrue(file.length() > 16_384)
        val record = AutoHistoryRecord.read(file)!!
        assertEquals(10_000L, record.entry.session.durationMs)
        assertEquals(1500, record.fps!!.samples)
    }

    @Test fun newCapturesWithoutValidProofOfForegroundUsageAreRejected() {
        for (proof in listOf("", "foreground_ms\tinvalid", "foreground_ms\t-1")) {
            assertNull(AutoHistoryRecord.read(write("minimum_usage_ms\t180000\n$proof\nF\t2000\t60\t16.7")))
        }
    }

    private fun segment(name: String, start: Long, end: Long, usage: Long, body: String,
        capture: String = "auto_1000_12_0"): File = File(temp.root, name).also { file ->
        file.writeText("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\n" +
            "core_events\t1\nminimum_usage_ms\t180000\ncapture_id\t$capture\nforeground_ms\t$usage\n" +
            body + "\nend_ms\t$end\nduration_ms\t${end - start}\nforeground_ms\t$usage\n")
    }

    @Test fun groupedSegmentsRecomputeRawStatisticsAndKeepTheShortTail() {
        val first = segment("auto_1000_12_0.log", 1000, 181001, 180001,
            "T\t2000\t12\t13\t100\t${hex("worker")}\t10\nF\t2000\t30\t33\nM\t2000\t0\tpower_w=3")
        val tail = segment("auto_181001_12_0.log", 181001, 182001, 181001,
            "T\t181200\t12\t13\t100\t${hex("worker")}\t90\nT\t181800\t12\t13\t100\t${hex("worker")}\t90\n" +
                "F\t181200\t60\t16\nF\t181800\t120\t8\nM\t181200\t0\tpower_w=9\nM\t181800\t0\tpower_w=9")
        val record = AutoHistoryRecord.read(listOf(tail, first), first.name)!!
        assertEquals(record.entry, AutoHistoryRecord.summary(listOf(tail, first), first.name))
        assertEquals(181001L, record.entry.session.durationMs)
        assertEquals(1000L, record.entry.session.startedAtMs)
        assertEquals(182001L, record.entry.session.endedAtMs)
        assertEquals(63.333f, record.threads.single().avg, .01f)
        assertEquals(70f, record.fps!!.average, .001f)
        assertEquals(7f, record.metrics!!.series.getValue("power_w").average, .001f)
        assertEquals(listOf(first.name, tail.name), record.entry.memberFileNames)
        assertEquals("auto_1000_12_0", record.entry.captureId)
        assertEquals(181001L, record.entry.foregroundMs)
    }

    @Test fun summaryCountsMoreThanEightThousandIdentitiesAcrossValidSegmentsExactly() {
        val files = (0..2).map { index ->
            val start = 1000L + index * 200000L
            segment("auto_${start}_12_0.log", start, start + 190000, 900000,
                (1..3500).joinToString("\n") { tid ->
                    "T\t${start + 1000}\t12\t$tid\t${index + 1}\t776f726b6572\t0.5"
                } + "\nT\t${start + 2000}\t12\t1\t${index + 1}\t776f726b6572\t0")
        }
        val summary = AutoHistoryRecord.summary(files, files.first().name)!!
        assertEquals(10500, summary.session.threadCount)
        assertEquals(10500, summary.identityCount)
        assertEquals(3, summary.memberFileNames.size)
    }

    @Test fun renamingAnExistingIdentityChangesItsLabelWithoutResettingStatistics() {
        val file = write("T\t2000\t12\t13\t100\t${hex("zygote")}\t10\n" +
            "T\t4000\t12\t13\t100\t${hex("RenderThread")}\t30\n" +
            "T\t6000\t12\t13\t100\t20\t20\nA\t7000\t12\t13\t100\t5")
        val record = AutoHistoryRecord.read(file)!!
        assertEquals("RenderThread", record.threads.single().name)
        assertEquals(20f, record.threads.single().avg, .001f)
        assertEquals(30f, record.threads.single().max, .001f)
        assertEquals("RenderThread", record.coreTimeline.events.single().name)
    }

    @Test fun anEarlyPublishedSegmentUsesItsActualTimeRangeEvenWithWholeCaptureUsage() {
        val first = segment("auto_1000_12_0.log", 1000, 61000, 180001, "F\t2000\t60\t16")
        assertEquals(60_000L, AutoHistoryRecord.read(first)!!.entry.session.durationMs)
        assertEquals(60_000L, AutoHistoryRecord.read(listOf(first))!!.entry.session.durationMs)
        assertEquals(180_001L, AutoHistoryRecord.read(listOf(first))!!.entry.foregroundMs)
    }

    @Test fun groupedEventsKeepThePerSegmentLimitWithoutDroppingEventsAfterTwentyThousand() {
        fun event(time: Long) = "E\t$time\t12\t13\t100\t${hex("render")}\tassign\tqixia\t0-7\t7\t7\t20\tassigned"
        val first = segment("auto_1000_12_0.log", 1000, 181001, 180001,
            (1..20_000).joinToString("\n") { event(1000L + it) })
        val tail = segment("auto_181001_12_0.log", 181001, 182001, 181001, event(181500))
        val report = AutoHistoryRecord.read(listOf(first, tail), first.name)!!.coreTimeline
        assertEquals(20_001, report.events.size)
        assertEquals(181500L, report.events.last().timestampMs)
        assertEquals(0L, report.droppedEvents)
        assertFalse(report.incomplete)
        assertTrue(report.exportText().contains("181500\t12\t13"))
    }

    @Test fun groupingNeverJoinsDifferentCapturesOrLegacyFiles() {
        val first = segment("auto_1000_12_0.log", 1000, 181001, 180001, "F\t2000\t60\t16")
        val other = segment("auto_181001_12_0.log", 181001, 182001, 181001,
            "F\t181500\t60\t16", capture = "auto_181001_12_0")
        assertNull(AutoHistoryRecord.read(listOf(first, other), first.name))
        assertNull(AutoHistoryRecord.summary(listOf(first, other), first.name))
        val legacy = write("F\t2000\t60\t16")
        assertNull(AutoHistoryRecord.read(listOf(first, legacy), first.name))
        assertNull(AutoHistoryRecord.read(emptyList()))
    }

    @Test fun lightweightSummaryKeepsExactFpsLowAndJitterAndReusedThreadIdentities() {
        val file = write((1..150).joinToString("\n") { index ->
            "F\t${1000 + index * 10}\t${30 + index % 90}\t16.7\n" +
                "T\t${1000 + index * 10}\t12\t13\t${index / 50}\t${hex("worker")}\t0.5\n" +
                "T\t${1000 + index * 10}\t12\t14\t1\t${hex("idle")}\t0"
        })
        val full = AutoHistoryRecord.read(file)!!
        assertEquals(4, full.entry.session.threadCount)
        assertNotNull(full.entry.fps!!.low5)
        assertEquals(full.entry, AutoHistoryRecord.summary(file))
        val original = file.readText()
        file.writeText(original.replace("F\t1010\t31", "F\t1010\tNaN"))
        assertNull(AutoHistoryRecord.summary(file))
        assertNull(AutoHistoryRecord.read(file))
    }
}
