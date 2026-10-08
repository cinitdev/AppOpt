package top.qixia.threads

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoreTimelineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun event(time: Long = 2000, pid: Int = 12, tid: Int = 13, start: Long = 100, name: String = "worker",
        kind: String = "observe", source: String = "system", before: String = "0-3", after: String = "0-3",
        cpu: String = "2", average: String = "8.5", reason: String = "sample") =
        listOf("E", time, pid, tid, start, hex(name), kind, source, before, after, cpu, average, reason).joinToString("\t")

    private fun read(body: String, header: String = "core_events\t1\n", footer: String = "", startMs: Long = 1000): AutoHistoryRecord.Record {
        val file = File(temp.root, "auto_1000_12.log")
        file.writeText("QIXIA_AUTO_HISTORY\t1\npackage\tapp.one\nstart_ms\t$startMs\nsource\tauto\n" +
            header + body + "\nend_ms\t${startMs + 10000}\nduration_ms\t10000\nsamples\t5\n" + footer)
        return requireNotNull(AutoHistoryRecord.read(file))
    }

    @Test fun stableIdentityKeepsSameNamesAndReusedTidsSeparateAndPreservesRenames() {
        val report = read(listOf(
            event(time = 4000, tid = 13, start = 200),
            event(time = 2000, tid = 13, start = 100, kind = "assign", source = "qixia", before = "0-7", after = "4,6-7"),
            event(time = 3000, tid = 14, start = 100),
            event(time = 5000, tid = 13, start = 200, name = "renamed")
        ).joinToString("\n")).coreTimeline
        assertEquals(listOf(2000L, 3000L, 4000L, 5000L), report.events.map { it.timestampMs })
        assertEquals(3, report.events.map { it.identity }.distinct().size)
        assertEquals("12:13:100", report.events.first().identity.stableKey)
        assertEquals(report.events[2].identity, report.events[3].identity)
        assertEquals("renamed", report.events.last().name)
        assertEquals(listOf(4, 6, 7), report.events.first().afterCpus)
        assertEquals(CoreTimelineCoverage.SAMPLED, report.coverage)
        assertFalse(report.legacyMissingDetails)
    }

    @Test fun sampledExecutionNeverBecomesAnQixiaThreadsAssignmentAndUnknownOwnershipStaysUnknown() {
        val report = read(listOf(
            event(source = "qixia", cpu = "4", after = "4-7"),
            event(time = 3000, source = "unknown", before = "-", after = "-", cpu = "-", average = "-", reason = "-"),
            event(time = 4000, kind = "external", source = "unknown", before = "4-7", after = "0-3")
        ).joinToString("\n")).coreTimeline
        assertEquals(CoreEventKind.OBSERVE, report.events.first().kind)
        assertEquals(CoreEventSource.QIXIA, report.events.first().source)
        assertEquals(CoreEventSource.UNKNOWN, report.events[1].source)
        assertNull(report.events[1].afterCpus)
        assertNull(report.events[1].runningCpu)
        assertNull(report.events[1].averagePercent)
        assertEquals("", report.events[1].reason)
        assertEquals(CoreTimelineCoverage.SAMPLED, report.coverage)
        assertEquals(CoreEventKind.EXTERNAL, report.events.last().kind)
        assertTrue(report.exportText().contains("不代表记录了全部系统迁核"))
    }

    @Test fun everyEventKindRetainsRangesAndReasonWithoutInventingMissingValues() {
        val kinds = listOf("assign", "release", "observe", "external", "exit", "error")
        val report = read(kinds.mapIndexed { index, kind ->
            event(time = 2000L + index, kind = kind, before = "4", after = "-", cpu = "-", reason = "reason_$kind")
        }.joinToString("\n")).coreTimeline
        assertEquals(CoreEventKind.entries.toList(), report.events.map { it.kind })
        assertTrue(report.events.all { it.beforeCpus == listOf(4) && it.afterCpus == null && it.runningCpu == null })
        assertEquals("reason_release", report.events[1].reason)
    }

    @Test fun malformedNewEventsDoNotDestroyIndependentMetricsOrValidEvents() {
        val invalid = listOf(
            event(time = 0), event(tid = -1), event(start = -1), event(cpu = "64"),
            event(average = "NaN"), event(before = "4-2"), event(after = "0,100"),
            event(source = "some_tool"), event(kind = "migration"), event() + "\textra",
            event().replace(hex("worker"), "ff"), event(reason = "x".repeat(17_000))
        )
        val record = read((listOf("T\t2000\t12\t13\t100\t${hex("worker")}\t8.5",
            "F\t2000\t60\t16.7", "M\t2000\t0\tcpu_core.0=20", event()) + invalid).joinToString("\n"))
        assertEquals(1, record.threads.size)
        assertEquals(60f, record.fps!!.average, .001f)
        assertEquals(20f, record.metrics!!.series.getValue("cpu_core.0").average, .001f)
        assertEquals(1, record.coreTimeline.events.size)
        assertEquals(invalid.size.toLong(), record.coreTimeline.droppedEvents)
        assertEquals(CoreTimelineCoverage.INCOMPLETE, record.coreTimeline.coverage)
    }

    @Test fun legacyActionsKeepTheirIdentityButNeverInventSystemObservationsOrReleaseReasons() {
        val record = read("A\t2000\t12\t13\t100\t4-6\n" +
            "A\t4000\t12\t13\t100\trestore\n" +
            "T\t3000\t12\t13\t100\t${hex("old worker")}\t8", header = "")
        val report = record.coreTimeline
        assertEquals(2, record.affinityChanges.size)
        assertEquals(CoreTimelineCoverage.LEGACY_ACTIONS_ONLY, report.coverage)
        assertTrue(report.legacyMissingDetails)
        assertTrue(report.events.all { it.legacy && it.name == "old worker" && it.source == CoreEventSource.QIXIA })
        assertEquals(listOf(CoreEventKind.ASSIGN, CoreEventKind.RELEASE), report.events.map { it.kind })
        assertTrue(report.events.all { it.beforeCpus == null && it.runningCpu == null && it.reason.isEmpty() })
        assertEquals(listOf(4, 5, 6), report.events.first().afterCpus)
        assertNull(report.events.last().afterCpus)
        assertTrue(report.exportText().contains("未记录系统执行核变化"))
    }

    @Test fun modernEventsOwnTheTimelineWhenLegacyMirrorsAreAlsoPresent() {
        val record = read("A\t2000\t12\t13\t100\t4-6\n" +
            event(kind = "assign", source = "qixia", before = "0-7", after = "4-6", reason = "load"))
        assertEquals(1, record.affinityChanges.size)
        assertEquals(1, record.coreTimeline.events.size)
        assertFalse(record.coreTimeline.events.single().legacy)
        assertEquals("load", record.coreTimeline.events.single().reason)
    }

    @Test fun producerLossAndRecoveryAreVisibleEvenWhenRetainedEventsAreValid() {
        val report = read(event(), footer = "core_events_dropped\t7\ncore_events_incomplete\t1\n").coreTimeline
        assertEquals(7L, report.droppedEvents)
        assertTrue(report.incomplete)
        assertEquals(CoreTimelineCoverage.INCOMPLETE, report.coverage)
        assertTrue(report.exportText().contains("缺失时段不能解释为没有发生变化"))
        assertTrue(read(event(), footer = "recovered\t1\n").coreTimeline.incomplete)
    }

    @Test fun missingUnsupportedAndDamagedProtocolMetadataDoNotClaimSampleCoverage() {
        val withoutHeader = read(event(), header = "").coreTimeline
        assertTrue(withoutHeader.events.isEmpty())
        assertTrue(withoutHeader.incomplete)
        assertTrue(withoutHeader.legacyMissingDetails)
        val unsupported = read(event(), header = "core_events\t2\n").coreTimeline
        assertEquals(2, unsupported.coreEventsVersion)
        assertTrue(unsupported.incomplete)
        assertTrue(unsupported.events.isEmpty())
        val badFooter = read(event(), footer = "core_events_dropped\t-1\n").coreTimeline
        assertEquals(1, badFooter.events.size)
        assertTrue(badFooter.incomplete)
    }

    @Test fun eventRetentionHasAnExplicitLimitAndReportsLocalLoss() {
        val record = read(buildString {
            repeat(CoreTimelineParser.MAX_EVENTS + 2) { appendLine(event()) }
            appendLine("F\t3000\t58")
        }, footer = "core_events_dropped\t3\n")
        assertEquals(CoreTimelineParser.MAX_EVENTS, record.coreTimeline.events.size)
        assertEquals(5L, record.coreTimeline.droppedEvents)
        assertTrue(record.coreTimeline.incomplete)
        assertEquals(58f, record.fps!!.average, .001f)
    }

    @Test fun controllerErrorsMayUseZeroIdentityButOtherEventsNeedARealThread() {
        val report = read(listOf(
            event(pid = 0, tid = 0, start = 0, name = "", kind = "error", source = "qixia", cpu = "-", average = "-"),
            event(pid = 0, tid = 0, start = 0, name = "app.one", kind = "error", source = "qixia", cpu = "-", average = "-"),
            event(pid = 0, tid = 0, start = 0, name = "", kind = "observe"),
            event(pid = 0, kind = "error"),
            event(start = 0, kind = "error")
        ).joinToString("\n")).coreTimeline
        assertEquals(2, report.events.size)
        assertTrue(report.events.all { it.kind == CoreEventKind.ERROR && it.identity == ThreadIdentity(0, 0, 0) })
        assertEquals(listOf("", "app.one"), report.events.map { it.name })
        assertEquals(3L, report.droppedEvents)
        assertTrue(report.incomplete)
    }

    @Test fun recoveryReleaseAndErrorKeepValidIdentityWhenTheirNamesWereNotCaptured() {
        val report = read(listOf(
            event(name = "RenderThread", kind = "assign", source = "qixia"),
            event(time = 3000, name = "", kind = "release", source = "qixia", reason = "recovery_observed"),
            event(time = 4000, name = "", kind = "error", source = "qixia", reason = "restore_failed"),
            event(time = 5000, tid = 14, start = 200, name = "", kind = "release", source = "qixia", reason = "guard_retry")
        ).joinToString("\n")).coreTimeline
        assertEquals(4, report.events.size)
        assertEquals(listOf("RenderThread", "", "", ""), report.events.map { it.name })
        assertEquals(1, report.events.take(3).map { it.identity }.distinct().size)
        assertNotEquals(report.events.first().identity, report.events.last().identity)
        assertEquals(listOf("recovery_observed", "restore_failed", "guard_retry"), report.events.drop(1).map { it.reason })
        assertEquals(0L, report.droppedEvents)
        assertFalse(report.incomplete)
        assertEquals(CoreTimelineCoverage.SAMPLED, report.coverage)
    }

    @Test fun rollingSegmentsKeepTrueEarlierEventTimesWithinOneSegmentButNeverFutureEvents() {
        val start = 3_600_000L
        val earliest = start - CoreTimelineParser.MAX_EVENT_LEAD_MS
        val report = read(listOf(event(time = earliest), event(time = start - 1000),
            event(time = earliest - 1), event(time = start + 10001)).joinToString("\n"), startMs = start).coreTimeline
        assertEquals(listOf(earliest, start - 1000), report.events.map { it.timestampMs })
        assertTrue(report.events.first().timestampMs - report.startMs < 0)
        assertTrue(report.hasEventsBeforeStart)
        assertEquals(2L, report.droppedEvents)
        assertTrue(report.incomplete)
        assertTrue(report.exportText().contains("保留原始时间"))
        val intact = read(event(time = start - 1000), startMs = start).coreTimeline
        assertTrue(intact.hasEventsBeforeStart)
        assertEquals(CoreTimelineCoverage.SAMPLED, intact.coverage)
    }

    @Test fun oneCapturePoolSharesFieldsAcrossSegmentsWithoutMergingEvents() {
        val pool = CoreEventPool()
        var decoded = 0
        val decoder: (String) -> String = { encoded ->
            decoded++
            encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        }
        val first = CoreTimelineParser(1, 1000, 3000, decoder, pool).apply {
            consume("core_events\t1")
            consume(event(time = 2500, before = "0-3", after = "4,6-7"))
            consume(event(time = 2000, before = "3,1,0,2,2", after = "7,4,6"))
            consume(event(time = 2500, before = "0-3", after = "4,6-7"))
        }.finish()
        val second = CoreTimelineParser(2, 3000, 5000, decoder, pool).apply {
            consume("core_events\t1")
            consume(event(time = 4000, before = "0-3", after = "4,6-7"))
        }.finish()
        assertEquals(listOf(2000L, 2500L, 2500L), first.events.map { it.timestampMs })
        assertEquals(4, first.events.size + second.events.size)
        assertNotSame(first.events[1], first.events[2])
        val expected = first.events.first()
        (first.events + second.events).forEach { actual ->
            assertSame(expected.identity, actual.identity)
            assertSame(expected.name, actual.name)
            assertSame(expected.beforeCpus, actual.beforeCpus)
            assertSame(expected.afterCpus, actual.afterCpus)
            assertSame(expected.reason, actual.reason)
        }
        assertEquals(1, decoded)
        assertEquals(listOf(4, 6, 7), expected.afterCpus)
        assertFalse(first.incomplete)
        assertFalse(second.incomplete)
    }

    @Test fun sharingAPoolKeepsTheTwentyThousandLimitPerSegment() {
        val pool = CoreEventPool()
        val reports = (1L..2L).map { id ->
            CoreTimelineParser(id, 1000, 3000, { "worker" }, pool).apply {
                consume("core_events\t1")
                val line = event()
                repeat(CoreTimelineParser.MAX_EVENTS + 1) { consume(line) }
            }.finish()
        }
        assertEquals(40_000, reports.sumOf { it.events.size })
        reports.forEach { report ->
            assertEquals(20_000, report.events.size)
            assertEquals(1L, report.droppedEvents)
            assertTrue(report.incomplete)
        }
        assertSame(reports[0].events.first().identity, reports[1].events.last().identity)
        assertSame(reports[0].events.first().afterCpus, reports[1].events.last().afterCpus)
    }

    @Test fun sharedFieldsCannotMutatePreviouslyParsedCpuRangesAndKeepCpu63() {
        val pool = CoreEventPool()
        val range = requireNotNull(pool.cpus("0,62-63"))
        assertEquals(listOf(0, 62, 63), range)
        assertSame(range, pool.cpus("63,0,62"))
        try {
            (range as MutableList<Int>).clear()
            fail("A caller must not mutate a range shared by other events")
        } catch (_: UnsupportedOperationException) {
            assertEquals(listOf(0, 62, 63), pool.cpus("0,62-63"))
        }
        assertNull(pool.cpus("-"))
        listOf("64", "63-64", "4-2", "", "0,,1").forEach { invalid ->
            try {
                pool.cpus(invalid)
                fail("Accepted malformed CPU range $invalid")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun highCardinalityPoolEvictionPreservesExistingFieldsAndReparsesEvictedNames() {
        val pool = CoreEventPool()
        val firstIdentity = pool.identity(ThreadIdentity(1, 1, 1))
        val firstName = pool.name("first") { String(charArrayOf('o', 'l', 'd')) }
        val firstRange = requireNotNull(pool.cpus("0"))
        val firstReason = pool.reason(String(charArrayOf('o', 'l', 'd')))
        repeat(10_000) { index ->
            pool.identity(ThreadIdentity(1, index + 2, 1))
            pool.name("name-$index") { "decoded-$index" }
            pool.reason("reason-$index")
        }
        (2..1000).forEach { mask ->
            pool.cpus((0..9).filter { mask and (1 shl it) != 0 }.joinToString(","))
        }
        assertEquals(ThreadIdentity(1, 1, 1), firstIdentity)
        assertEquals("old", firstName)
        assertEquals(listOf(0), firstRange)
        assertEquals("old", firstReason)
        assertNotSame(firstIdentity, pool.identity(ThreadIdentity(1, 1, 1)))
        var decoded = false
        assertEquals("old", pool.name("first") { decoded = true; "old" })
        assertTrue(decoded)
        assertNotSame(firstRange, pool.cpus("0"))
        assertNotSame(firstReason, pool.reason(String(charArrayOf('o', 'l', 'd'))))
    }

    @Test fun invalidDecodedNamesAreNeverCachedAsSuccessfulEvents() {
        val pool = CoreEventPool()
        var attempts = 0
        val parser = CoreTimelineParser(1, 1000, 3000, {
            attempts++
            error("Invalid name")
        }, pool)
        parser.consume("core_events\t1")
        repeat(2) { parser.consume(event()) }
        val report = parser.finish()
        assertEquals(2, attempts)
        assertTrue(report.events.isEmpty())
        assertEquals(2L, report.droppedEvents)
        assertTrue(report.incomplete)
    }
}
