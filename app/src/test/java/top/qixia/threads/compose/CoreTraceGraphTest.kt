package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.ThreadIdentity

class CoreTraceGraphTest {
    private fun event(
        time: Long,
        kind: CoreEventKind = CoreEventKind.OBSERVE,
        source: CoreEventSource = CoreEventSource.SYSTEM,
        before: List<Int>? = null,
        after: List<Int>? = null,
        running: Int? = null,
        identity: ThreadIdentity = ThreadIdentity(123, 124, 999)
    ) = CoreEvent(time, identity, "RenderThread", kind, source, before, after, running, 15f, "")

    @Test fun lateEventsExpandRangeAndCpuLanesComeFromAllEvidence() {
        val graph = CoreTraceGraph.build(100, 500, listOf(
            event(50, before = listOf(0, 1), after = listOf(7, 9), running = 12),
            event(550, running = 15)
        ))
        assertEquals(50, graph.startMs)
        assertEquals(550, graph.endMs)
        assertEquals(listOf(0, 1, 7, 9, 12, 15), graph.lanes.map { it.cpu })
        assertTrue(graph.lanes.all { it.allowedIntervals.isEmpty() })
        assertEquals(listOf(0), graph.lanes.single { it.cpu == 12 }.samples.map { it.eventIndex })
        assertEquals(0f, graph.fraction(50))
        assertEquals(1f, graph.fraction(550))
    }

    @Test fun knownPermissionsSurviveObservationsButStopAtReleaseAndUnknownEvidence() {
        val graph = CoreTraceGraph.build(0, 100, listOf(
            event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(5)),
            event(20, source = CoreEventSource.QIXIA, after = listOf(5), running = 5),
            event(30, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(5, 7)),
            event(40, CoreEventKind.RELEASE, after = (0..7).toList(), running = 5),
            event(50, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(5)),
            event(60, CoreEventKind.ERROR, CoreEventSource.QIXIA, after = listOf(5)),
            event(70, source = CoreEventSource.QIXIA, after = listOf(5)),
            event(80, source = CoreEventSource.UNKNOWN, after = listOf(5))
        ))
        val cpu5 = graph.lanes.single { it.cpu == 5 }
        assertEquals(listOf(.1f to .4f, .5f to .6f, .7f to .8f),
            cpu5.allowedIntervals.map { it.startFraction to it.endFraction })
        assertEquals(listOf(0, 4, 6), cpu5.allowedIntervals.map { it.startEventIndex })
        assertEquals(listOf(3, 5, 7), cpu5.allowedIntervals.map { it.endEventIndex })
        assertEquals(listOf(.3f to .4f), graph.lanes.single { it.cpu == 7 }.allowedIntervals.map { it.startFraction to it.endFraction })
        assertEquals(listOf(1, 3), cpu5.samples.map { it.eventIndex })
    }

    @Test fun exitAndMissingRangeEndPermissionWithoutInventingSystemOccupancy() {
        val graph = CoreTraceGraph.build(0, 100, listOf(
            event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(9)),
            event(20, CoreEventKind.EXIT, CoreEventSource.QIXIA, after = listOf(9)),
            event(30, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(9)),
            event(40, source = CoreEventSource.QIXIA),
            event(50, running = 9), event(90, running = 9)
        ))
        val lane = graph.lanes.single()
        assertEquals(listOf(.1f to .2f, .3f to .4f), lane.allowedIntervals.map { it.startFraction to it.endFraction })
        assertEquals(listOf(.5f, .9f), lane.samples.map { it.fraction })
    }

    @Test fun cursorIsStableAtDuplicateTimesAndCanStepThroughEveryEvent() {
        val events = listOf(event(20), event(20), event(20), event(80), event(80))
        val graph = CoreTraceGraph.build(0, 100, events)
        assertEquals(0, graph.nearestEventIndex(-1f))
        assertEquals(0, graph.nearestEventIndex(.2f))
        assertEquals(0, graph.nearestEventIndex(.5f))
        assertEquals(3, graph.nearestEventIndex(.6f))
        assertEquals(3, graph.nearestEventIndex(1f))
        assertEquals(0, graph.nearestEventIndex(Float.NaN))
        assertEquals(1, graph.nextIndex(0))
        assertEquals(2, graph.nextIndex(1))
        assertEquals(3, graph.nextIndex(2))
        assertEquals(3, graph.previousIndex(4))
        assertNull(graph.previousIndex(0))
        assertNull(graph.nextIndex(4))
        assertNull(graph.nextIndex(-1))
    }

    @Test fun compressedDrawingStaysBoundedAndNeverFillsGaps() {
        val events = (0 until 10_000).map { index ->
            event(index.toLong(), if (index % 2 == 0) CoreEventKind.ASSIGN else CoreEventKind.RELEASE,
                if (index % 2 == 0) CoreEventSource.QIXIA else CoreEventSource.SYSTEM,
                after = listOf(10), running = 10)
        }
        val graph = CoreTraceGraph.build(0, 10_000, events, bucketCount = 32)
        val lane = graph.lanes.single()
        assertTrue(graph.compressed)
        assertTrue(lane.samples.size <= 32)
        assertTrue(lane.allowedIntervals.size <= 32)
        assertEquals(10_000, graph.events.size)
        assertTrue(lane.allowedIntervals.all { it.endEventIndex == it.startEventIndex + 1 })
        assertTrue(lane.allowedIntervals.all { it.endFraction - it.startFraction < .00011f })
        assertEquals(5_000, graph.nearestEventIndex(.5f))
    }

    @Test fun emptyAndControllerOnlyDataAreGraceful() {
        val empty = CoreTraceGraph.build(100, 100, emptyList())
        assertEquals(101, empty.endMs)
        assertTrue(empty.lanes.isEmpty())
        assertNull(empty.nearestEventIndex(.5f))
        val controller = CoreTraceGraph.build(0, 100, listOf(event(20, CoreEventKind.ERROR,
            CoreEventSource.QIXIA, after = listOf(0, 7), running = 7, identity = ThreadIdentity(0, 0, 0))))
        assertTrue(controller.lanes.isEmpty())
        assertFalse(controller.compressed)
        assertEquals(0, controller.nearestEventIndex(.5f))
    }

    @Test fun activePermissionContinuesOnlyToKnownReportEnd() {
        val graph = CoreTraceGraph.build(0, 100, listOf(event(50, CoreEventKind.ASSIGN,
            CoreEventSource.QIXIA, after = listOf(11))))
        val interval = graph.lanes.single().allowedIntervals.single()
        assertEquals(.5f, interval.startFraction)
        assertEquals(1f, interval.endFraction)
        assertNull(interval.endEventIndex)
    }

    @Test fun filteredCursorKeepsOriginalIndicesAndStableDuplicateTimeTies() {
        val graph = CoreTraceGraph.build(0, 100,
            listOf(event(10), event(20), event(20), event(20), event(50), event(80), event(80), event(90)))
        val indices = listOf(2, 3, 5, 6)
        assertEquals(2, graph.nearestEventIndex(-1f, indices))
        assertEquals(2, graph.nearestEventIndex(.2f, indices))
        assertEquals(2, graph.nearestEventIndex(.2001f, indices))
        assertEquals(2, graph.nearestEventIndex(.5f, indices))
        assertEquals(5, graph.nearestEventIndex(.6f, indices))
        assertEquals(5, graph.nearestEventIndex(.8f, indices))
        assertEquals(5, graph.nearestEventIndex(2f, indices))
        assertEquals(2, graph.nearestEventIndex(Float.NaN, indices))
        assertEquals(2, graph.nearestEventIndex(Float.NEGATIVE_INFINITY, indices))
        assertEquals(5, graph.nearestEventIndex(Float.POSITIVE_INFINITY, indices))
        assertEquals(6, graph.nearestEventIndex(.1f, listOf(6)))
        assertEquals(6, graph.nearestEventIndex(.9f, listOf(6)))
        assertNull(graph.nearestEventIndex(.5f, emptyList()))
    }

    @Test fun invisibleEventsDoNotMarkGraphAsCompressed() {
        val graph = CoreTraceGraph.build(0, 10_000,
            (0 until 10_000).map { event(it.toLong(), CoreEventKind.ERROR) }, bucketCount = 1)
        assertFalse(graph.compressed)
        assertTrue(graph.lanes.isEmpty())
        assertEquals(10_000, graph.events.size)
    }

    @Test fun legacyIdentityKeepsEvidenceButLegacyReleaseAndExitNeverExtendOwnershipOrExecution() {
        val legacyId = ThreadIdentity(123, 124, 0)
        val graph = CoreTraceGraph.build(0, 100, listOf(
            event(10, CoreEventKind.ASSIGN, CoreEventSource.QIXIA, after = listOf(7), running = 7, identity = legacyId).copy(legacy = true),
            event(20, CoreEventKind.RELEASE, CoreEventSource.QIXIA, after = listOf(7), identity = legacyId).copy(legacy = true),
            event(30, CoreEventKind.EXIT, after = listOf(7), running = 7, identity = legacyId)))
        val lane = graph.lanes.single()
        assertEquals(listOf(.1f to .2f), lane.allowedIntervals.map { it.startFraction to it.endFraction })
        assertEquals(listOf(0), lane.samples.map { it.eventIndex })
        assertEquals(3, graph.events.size)
    }
}
