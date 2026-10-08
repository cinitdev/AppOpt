package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.CoreTimelineReport
import top.qixia.threads.ThreadIdentity

class CoreTableIndexTest {
    private val identity = ThreadIdentity(10, 11, 12)

    private fun event(time: Long, kind: CoreEventKind = CoreEventKind.OBSERVE,
        source: CoreEventSource = CoreEventSource.SYSTEM, cpus: List<Int>? = listOf(0, 1, 2, 3),
        running: Int? = null, average: Float? = null, id: ThreadIdentity = identity,
        name: String = "RenderThread", legacy: Boolean = false) =
        CoreEvent(time, id, name, kind, source, null, cpus, running, average, "", legacy)

    private fun index(vararg events: CoreEvent, start: Long = 0, end: Long = 100) =
        CoreTableIndex.build(CoreTimelineReport(1, start, end, events.toList(), coreEventsVersion = 1))

    @Test fun snapshotsNeverReadTheFutureAndAggregateSameMillisecondInRecordedOrder() {
        val first = event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, listOf(5), running = 5, average = 20f)
        val change = event(30, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, listOf(7))
        val release = event(30, CoreEventKind.RELEASE, cpus = listOf(0, 1, 2, 3))
        val future = event(80, running = 1, average = 3f)
        val thread = index(future, first, change, release).threads.single()
        assertNull(thread.snapshotAt(9))
        assertSame(first, thread.snapshotAt(10)!!.latestEvent)
        assertSame(first, thread.snapshotAt(29)!!.latestEvent)
        val sameTime = thread.snapshotAt(30)!!
        assertEquals(2, sameTime.eventIndex)
        assertSame(release, sameTime.latestEvent)
        assertSame(release, sameTime.rangeEvent)
        assertSame(first, sameTime.sampleEvent)
        assertSame(first, sameTime.averageEvent)
        assertSame(release, thread.snapshotAt(79)!!.latestEvent)
        assertSame(future, thread.snapshotAt(80)!!.latestEvent)
    }

    @Test fun rangeCannotSurviveErrorsUnknownOwnershipMissingRangesOrExit() {
        val index = index(
            event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, listOf(7)),
            event(20, CoreEventKind.ERROR, CoreEventSource.QIXIA, listOf(7)),
            event(30, source = CoreEventSource.QIXIA, cpus = listOf(7)),
            event(40, source = CoreEventSource.UNKNOWN, cpus = listOf(7)),
            event(50, source = CoreEventSource.QIXIA, cpus = listOf(7)),
            event(60, source = CoreEventSource.QIXIA, cpus = null),
            event(70, source = CoreEventSource.QIXIA, cpus = listOf(7)),
            event(80, CoreEventKind.EXIT, CoreEventSource.QIXIA, listOf(7)))
        val thread = index.threads.single()
        listOf(10L, 30L, 50L, 70L).forEach { assertNotNull(thread.snapshotAt(it)!!.rangeEvent) }
        listOf(20L, 40L, 60L, 80L).forEach { assertNull(thread.snapshotAt(it)!!.rangeEvent) }
        assertNull(thread.snapshotAt(100)!!.rangeEvent)
    }

    @Test fun oldSamplesRetainOwnTimesAndExitClearsEvenAnExitWithStalePayload() {
        val sampled = event(10, running = 5, average = 16f)
        val thread = index(sampled, event(20, cpus = listOf(5)),
            event(30, CoreEventKind.EXIT, running = 7, average = 90f),
            event(40, CoreEventKind.ERROR, cpus = null),
            event(50, running = 1, average = 0f)).threads.single()
        assertSame(sampled, thread.snapshotAt(29)!!.sampleEvent)
        assertEquals(10, thread.snapshotAt(29)!!.sampleEvent!!.timestampMs)
        assertSame(sampled, thread.snapshotAt(29)!!.averageEvent)
        assertNull(thread.snapshotAt(30)!!.sampleEvent)
        assertNull(thread.snapshotAt(30)!!.averageEvent)
        assertNull(thread.snapshotAt(49)!!.sampleEvent)
        assertNull(thread.snapshotAt(49)!!.averageEvent)
        assertEquals(1, thread.snapshotAt(50)!!.sampleEvent!!.runningCpu)
        assertEquals(0f, thread.snapshotAt(50)!!.averageEvent!!.averagePercent)
    }

    @Test fun legacyReleaseDoesNotClaimQixiaThreadsStillOwnsRangeAndLegacyIdentityIsRetained() {
        val old = identity.copy(startTicks = 0)
        val thread = index(
            event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, listOf(7), id = old, legacy = true),
            event(20, CoreEventKind.RELEASE, CoreEventSource.QIXIA, listOf(0, 1, 2, 3), id = old, legacy = true))
            .threads.single()
        assertEquals(old, thread.identity)
        assertEquals(listOf(7), thread.snapshotAt(19)!!.rangeEvent!!.afterCpus)
        assertNull(thread.snapshotAt(20)!!.rangeEvent)
        assertEquals(CoreEventKind.RELEASE, thread.snapshotAt(20)!!.latestEvent.kind)
    }

    @Test fun identitiesDoNotMergeAndOrderingDoesNotDependOnCursorTime() {
        val reused = identity.copy(startTicks = 99)
        val lowerTid = identity.copy(tid = 1)
        val table = index(
            event(10, id = identity), event(20, id = identity),
            event(40, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, id = reused),
            event(30, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, id = lowerTid),
            event(25, id = ThreadIdentity(0, 0, 0), kind = CoreEventKind.ERROR, name = ""))
        assertEquals(listOf(lowerTid, reused, identity, ThreadIdentity(0, 0, 0)), table.threads.map { it.identity })
        assertEquals(listOf(1, 1, 0, 0), table.threads.map { it.operations })
        assertEquals(listOf(0, 0, 2, 0), table.threads.map { it.observations })
        assertNull(table.threads[0].snapshotAt(29))
        assertNotNull(table.threads[2].snapshotAt(29))
        assertEquals("调度器记录", table.threads.last().name)
        assertEquals(4, table.threads.size)
    }

    @Test fun expandedTimeRangeNavigationAndNonFiniteProgressAreSafe() {
        val table = index(event(-20), event(50), event(50), event(140), start = 0, end = 100)
        assertEquals(-20, table.startMs)
        assertEquals(140, table.endMs)
        assertEquals(listOf(-20L, 50L, 140L), table.eventTimes)
        assertEquals(-20, table.timeAt(Float.NaN))
        assertEquals(-20, table.timeAt(Float.NEGATIVE_INFINITY))
        assertEquals(140, table.timeAt(Float.POSITIVE_INFINITY))
        assertEquals(60, table.timeAt(.5f))
        assertEquals(0f, table.progressAt(Long.MIN_VALUE))
        assertEquals(1f, table.progressAt(Long.MAX_VALUE))
        assertEquals(.5f, table.progressAt(60))
        assertNull(table.previousTime(-20))
        assertEquals(-20L, table.previousTime(50))
        assertEquals(50L, table.previousTime(140))
        assertEquals(50L, table.previousTime(100))
        assertEquals(50L, table.nextTime(-20))
        assertEquals(140L, table.nextTime(50))
        assertEquals(140L, table.nextTime(100))
        assertNull(table.nextTime(140))
    }

    @Test fun emptyInstantAndReversedReportBoundsAreDefined() {
        val empty = index(start = 55, end = 55)
        assertTrue(empty.threads.isEmpty())
        assertTrue(empty.eventTimes.isEmpty())
        assertEquals(55, empty.timeAt(.5f))
        assertEquals(0f, empty.progressAt(55))
        assertNull(empty.previousTime(55))
        assertNull(empty.nextTime(55))
        val reversed = index(start = 100, end = 0)
        assertEquals(0, reversed.startMs)
        assertEquals(100, reversed.endMs)
        assertEquals(50, reversed.timeAt(.5f))
    }

    @Test fun invalidOrAbsentSamplesCannotReplaceEarlierEvidence() {
        val valid = event(10, running = 7, average = 5f)
        val thread = index(valid, event(20, running = -1, average = Float.NaN),
            event(30, cpus = emptyList(), running = null, average = null)).threads.single()
        val snapshot = thread.snapshotAt(30)!!
        assertSame(valid, snapshot.sampleEvent)
        assertSame(valid, snapshot.averageEvent)
        assertNull(snapshot.rangeEvent)
    }

    @Test fun cpuColumnsKeepRecordedTopologyStableIncludingSparseAndMoreThanEightCores() {
        val first = event(10, cpus = listOf(0, 2, 5), running = 7)
        val later = event(90, cpus = listOf(8, 9, 10, 11), running = 11).copy(beforeCpus = listOf(3, 6))
        val table = index(first, later, event(50, cpus = listOf(-1), running = -1))
        assertEquals(listOf(0, 2, 3, 5, 6, 7, 8, 9, 10, 11), table.cpuIds)
        // 标签来自固定的拓扑记录，快照本身仍不能读取未来核心状态。
        assertEquals(listOf(0, 2, 5), table.threads.single().snapshotAt(10)!!.rangeEvent!!.afterCpus)
        assertEquals(7, table.threads.single().snapshotAt(10)!!.sampleEvent!!.runningCpu)
        assertTrue(index(start = 0, end = 1).cpuIds.isEmpty())
    }
}
