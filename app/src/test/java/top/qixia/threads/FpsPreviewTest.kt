package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class FpsPreviewTest {
    @Test fun longRunKeepsRealEndpointsAndExtremaWithinFixedBudgetWithoutInventingGaps() {
        val start = 1_800_000_000_000L
        val end = start + 100_000_000L
        fun value(index: Int) = when (index) {
            98_765 -> 144f
            1_234 -> 0f
            else -> 55f + index % 6
        }
        val preview = FpsPreview(start, end)
        for (index in 0..100_000) preview.add(start + index * 1000L, value(index))
        val points = preview.points()
        assertTrue(points.size in 4..80)
        assertEquals(start, points.first().timestampMs)
        assertEquals(end, points.last().timestampMs)
        assertEquals(144f, points.maxOf { it.fps }, 0f)
        assertEquals(0f, points.minOf { it.fps }, 0f)
        assertTrue(points.zipWithNext().all { (a, b) -> a.timestampMs!! < b.timestampMs!! })
        assertTrue(points.all { it.fps == value(((it.timestampMs!! - start) / 1000L).toInt()) })
        assertFalse(points.any { it.breakBefore })
        // 降采样会增大保留点之间的时间距离，但并不代表样本缺失。
        val smaller = FpsPreview(start, end, maxPoints = 40)
        points.forEach { smaller.add(it.timestampMs!!, it.fps, it.breakBefore) }
        assertTrue(smaller.points().size <= 40)
        assertFalse(smaller.points().any { it.breakBefore })
    }

    @Test fun gapSurvivesEvenWhenItsFirstSampleIsNotAnExtremum() {
        val preview = FpsPreview(0, 100_000, maxPoints = 4)
        preview.add(0, 60f)
        preview.add(1000, 0f)
        preview.add(2000, 120f)
        preview.add(80_000, 59f)
        preview.add(81_000, 60f)
        val points = preview.points()
        assertEquals(listOf(0L, 1000L, 2000L, 81_000L), points.map { it.timestampMs })
        assertEquals(listOf(false, false, false, true), points.map { it.breakBefore })
    }

    @Test fun exactMillisecondsRemainDistinctWhenFloatProgressRoundsToTheSameValue() {
        val start = 1_800_000_000_000L
        val end = start + 86_400_000L
        val preview = FpsPreview(start, end)
        preview.add(end - 2, 61f)
        preview.add(end - 1, 0f)
        preview.add(end, 120f)
        assertEquals(listOf(end - 2, end - 1, end), preview.points().map { it.timestampMs })
        assertEquals(listOf(61f, 0f, 120f), preview.points().map { it.fps })
    }

    @Test fun invalidOrDuplicateSamplesCannotFabricateAChart() {
        val preview = FpsPreview(1000, 2000)
        preview.add(999, 60f)
        preview.add(2001, 60f)
        preview.add(1500, Float.NaN)
        preview.add(1500, -1f)
        assertTrue(preview.points().isEmpty())
        preview.add(1500, 0f)
        preview.add(1500, 120f)
        preview.add(1499, 120f)
        assertEquals(1, preview.points().size)
        assertEquals(0f, preview.points().single().fps, 0f)
    }
}
