package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.db.ThreadData

class HistoryCurveTest {
    @Test fun downsamplingKeepsLateSpikeAndCompleteTimeRange() {
        val values = List(10000) { if (it == 9800) 99 else 4 }.joinToString(",")
        val points = HistoryPresentation.curve(values)
        assertTrue(points.size <= 240)
        assertEquals(0f, points.first().progress, 0f)
        assertEquals(1f, points.last().progress, 0f)
        assertEquals(99f, points.maxOf { it.value }, 0f)
        assertTrue(points.zipWithNext().all { (a, b) -> a.progress < b.progress })
    }
    @Test fun invalidSamplesDoNotShiftRemainingSamplesOrCreateNegativeLoad() {
        val points = HistoryPresentation.curve("10,NaN,-3,Infinity,20")
        assertEquals(listOf(10f, 20f), points.map { it.value })
        assertEquals(listOf(0f, 1f), points.map { it.progress })
    }
    @Test fun halfSecondHistoryUnitsAndProcessMarkersRemainCompatible() {
        assertEquals(221500L, HistoryPresentation.durationMs(443))
        assertTrue(HistoryPresentation.isProcess(ThreadData("pkg:worker", 200f, 300f, "200", "v3p:")))
        assertFalse(HistoryPresentation.isProcess(ThreadData("RenderThread", 30f, 40f, "30", "")))
        assertEquals(0L, HistoryPresentation.durationMs(-1))
    }
}
