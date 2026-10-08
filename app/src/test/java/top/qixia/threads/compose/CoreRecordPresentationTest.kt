package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.ThreadIdentity
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class CoreRecordPresentationTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val time = Instant.parse("2026-09-30T23:59:59.007Z").toEpochMilli()
    private fun event(
        before: List<Int>? = listOf(0, 1, 2, 3),
        after: List<Int>? = listOf(7),
        average: Float? = 16.4f,
        kind: CoreEventKind = CoreEventKind.ASSIGN,
        source: CoreEventSource = CoreEventSource.QIXIA,
        running: Int? = 5,
        legacy: Boolean = false,
        reason: String = "initial"
    ) = CoreEvent(time, ThreadIdentity(1, 2, 3), "RenderThread", kind, source, before, after,
        running, average, reason, legacy)

    @Test fun dateIncludesMillisecondsAndUsesTheRequestedTimezone() {
        val record = event()
        val universal = CoreRecordPresentation.prepare(listOf(record), Locale.US, utc).single()
        assertEquals("09/30", universal.date)
        assertEquals("23:59:59.007", universal.time)
        val local = CoreRecordPresentation.prepare(listOf(record), Locale.US, TimeZone.getTimeZone("GMT+08:00")).single()
        assertEquals("10/01", local.date)
        assertEquals("07:59:59.007", local.time)
    }

    @Test fun nonContiguousCpuMasksStayExactAndPreparedRowsRetainNoCallerLists() {
        val before = mutableListOf(5, 1, 3, 1)
        val record = event(before, listOf(7, 0, 2, 1, 9))
        val rows = CoreRecordPresentation.prepare(listOf(record, record), Locale.US, utc)
        assertEquals("CPU 1, 3, 5", rows[0].before)
        assertEquals("CPU 0–2, 7, 9", rows[0].after)
        assertEquals(rows[0].before, rows[1].before)
        before.clear()
        assertEquals("CPU 1, 3, 5", rows[0].before)
    }

    @Test fun allSameMillisecondEventsAreKeptInOriginalOrder() {
        val assign = event()
        val release = event(after = listOf(0, 1, 2, 3), kind = CoreEventKind.RELEASE, source = CoreEventSource.SYSTEM)
        val rows = CoreRecordPresentation.prepare(listOf(assign, assign, release), Locale.US, utc)
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals(listOf(CoreEventKind.ASSIGN, CoreEventKind.ASSIGN, CoreEventKind.RELEASE), rows.map { it.kind })
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.time == "23:59:59.007" })
    }

    @Test fun legacyReleaseDoesNotClaimQixiaThreadsOwnsTheRestoredRange() {
        val row = CoreRecordPresentation.prepare(listOf(event(before = null, after = null,
            kind = CoreEventKind.RELEASE, source = CoreEventSource.QIXIA, legacy = true)), Locale.US, utc).single()
        assertEquals("旧版未记录", row.sourceLabel)
        assertEquals("范围未记录", row.before)
        assertEquals("范围未记录", row.after)
        assertTrue(row.reason.contains("旧版记录"))
    }

    @Test fun zeroLoadIsNotMissingAndPercentIsNotMultipliedByOneHundred() {
        val rows = CoreRecordPresentation.prepare(listOf(event(average = 0f), event(average = 5f),
            event(average = null, running = null), event(average = Float.NaN)), Locale.US, utc)
        assertEquals(listOf("0.0%", "5.0%", "未采集", "未采集"), rows.map { it.average })
        assertEquals("执行核采样 · CPU 5", rows[0].runningCpuLabel)
        assertNull(rows[2].runningCpuLabel)
    }

    @Test fun localeAndReleaseReasonArePreservedWithoutChangingTheThreshold() {
        val row = CoreRecordPresentation.prepare(listOf(event(average = 4.9f, kind = CoreEventKind.RELEASE,
            source = CoreEventSource.SYSTEM, reason = "low_average")), Locale.GERMANY, utc).single()
        assertEquals("4,9%", row.average)
        assertEquals("系统调度", row.sourceLabel)
        assertTrue(row.reason.contains("低于 5%"))
        assertEquals(CoreEventKind.RELEASE, row.kind)
    }

    @Test fun sameRangeObservationsKeepExecutionSamplesAndManagementSource() {
        val managed = event(before = listOf(4, 5), after = listOf(5, 4),
            kind = CoreEventKind.OBSERVE, running = 4, reason = "cpu_changed")
        val system = managed.copy(timestampMs = time + 600, source = CoreEventSource.SYSTEM, runningCpu = 5)
        val rows = CoreRecordPresentation.prepare(listOf(managed, system), Locale.US, utc)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.rangeUnchanged })
        assertEquals(listOf("自动分配", "系统调度"), rows.map { it.sourceLabel })
        assertEquals(listOf("执行核采样 · CPU 4", "执行核采样 · CPU 5"), rows.map { it.runningCpuLabel })
        assertTrue(rows.all { it.kind == CoreEventKind.OBSERVE })
        assertTrue(rows.all { it.reason.contains("不代表记录了期间的每次迁核") })
        assertEquals("CPU 4–5", rows[0].after)
    }

    @Test fun manyDifferentMasksDoNotChangeSubsequentRepeatedMaskFormatting() {
        val first = event(before = listOf(2, 6, 10), after = listOf(7))
        val events = listOf(first) + (0..100).map { cpu -> event(before = listOf(cpu, cpu + 2), after = listOf(cpu + 3)) } + first
        val rows = CoreRecordPresentation.prepare(events, Locale.US, utc)
        assertEquals("CPU 2, 6, 10", rows.first().before)
        assertEquals(rows.first().before, rows.last().before)
        assertEquals(rows.first().after, rows.last().after)
        assertEquals(events.size, rows.size)
        assertTrue(CoreRecordPresentation.prepare(emptyList(), Locale.US, utc).isEmpty())
    }
}
