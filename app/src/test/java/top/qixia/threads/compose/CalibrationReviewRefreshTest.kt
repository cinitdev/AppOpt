package top.qixia.threads.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import top.qixia.threads.CalibrationDraft
import top.qixia.threads.CalibrationReview
import top.qixia.threads.CalibrationThread

class CalibrationReviewRefreshTest {
    @Test
    fun newerDraftKeepsTheOpenPackageAndDropsItsOldEditsAndSnapshot() {
        val old = review("com.test.game", "100-1").copy(
            selections = listOf(setOf(6)), original = listOf("old-rule"),
            processSelections = mapOf("com.test.game" to setOf(0, 1))
        )
        val newer = review("com.test.game", "200-1").copy(original = listOf("cached-new-rule"))
        val other = review("com.test.other", "300-1")

        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(old), old.draft.fileName, error = "old error"),
            listOf(other, newer), setOf(old.draft.fileName)
        )

        assertEquals(newer.draft.fileName, result.state.activeId)
        assertSame(newer.draft, result.toOpen?.draft)
        assertEquals(newer.selections, result.state.active?.selections)
        assertEquals(emptyMap<String, Set<Int>>(), result.state.active?.processSelections)
        assertNull(result.state.active?.original)
        assertNull(result.state.error)
    }

    @Test
    fun unchangedDraftKeepsSavedChoicesAndDoesNotReopen() {
        val current = review("com.test.game", "100-1").copy(
            selections = listOf(setOf(5)), original = listOf("current-rule")
        )
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(current), current.draft.fileName, error = "rule conflict"),
            listOf(current), setOf(current.draft.fileName)
        )

        assertSame(current, result.state.active)
        assertEquals("rule conflict", result.state.error)
        assertNull(result.toOpen)
    }

    @Test
    fun anotherPackageWithTheSameDraftIdDoesNotStealTheOpenSheet() {
        val current = review("com.test.game", "100-1")
        val other = review("com.test.other", "100-1")
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(current), current.draft.fileName),
            listOf(other, current), setOf(current.draft.fileName)
        )

        assertSame(current, result.state.active)
        assertNull(result.toOpen)
    }

    @Test
    fun laterConfirmationKeepsTheSameDraftClosed() {
        val current = review("com.test.game", "100-1")
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(current)), listOf(current), setOf(current.draft.fileName)
        )

        assertNull(result.state.active)
        assertNull(result.toOpen)
        assertEquals(listOf(current), result.state.pending)
    }

    @Test
    fun newDraftCanOpenAfterOlderDraftWasDeferred() {
        val old = review("com.test.game", "100-1")
        val newer = review("com.test.game", "200-1")
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(old)), listOf(newer), setOf(old.draft.fileName)
        )

        assertSame(newer, result.state.active)
        assertSame(newer, result.toOpen)
    }

    @Test
    fun missingActiveDraftClearsItsKeyAndOpensAnUnseenPackage() {
        val old = review("com.test.game", "100-1")
        val other = review("com.test.other", "200-1")
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(old), old.draft.fileName),
            listOf(other), setOf(old.draft.fileName)
        )

        assertSame(other, result.state.active)
        assertSame(other, result.toOpen)
    }

    @Test
    fun completedOrRemovedDraftDoesNotLeaveAStaleActiveKey() {
        val old = review("com.test.game", "100-1")
        val result = reconcileCalibrationReviews(
            CalibrationReviewsState(listOf(old), old.draft.fileName), emptyList(), setOf(old.draft.fileName)
        )

        assertNull(result.state.activeId)
        assertNull(result.toOpen)
        assertEquals(emptyList<CalibrationReview>(), result.state.pending)
    }

    private fun review(packageName: String, id: String): CalibrationReview = CalibrationReview(
        CalibrationDraft(
            id = id, packageName = packageName, createdMs = id.substringBefore('-').toLong(),
            durationMs = 60_000, capacities = (0..7).associateWith { 1024L },
            threads = listOf(CalibrationThread(packageName, "RenderThread", 15.0, 35.0,
                100.0, setOf(7), "推荐核心")), wire = ""
        )
    )
}
