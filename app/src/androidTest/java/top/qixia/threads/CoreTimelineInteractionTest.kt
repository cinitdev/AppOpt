package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.HistoryCoreTimeline

/** 仅使用内存事件，不修改设备规则或历史。 */
class CoreTimelineInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val start = 1790776800000L
    private val id = ThreadIdentity(100, 101, 900)
    private val events = listOf(
        CoreEvent(start, id, "RenderThread", CoreEventKind.ASSIGN, CoreEventSource.QIXIA, (0..7).toList(), listOf(5), 5, 32f, "initial"),
        CoreEvent(start + 5000, id, "RenderThread", CoreEventKind.RELEASE, CoreEventSource.SYSTEM, listOf(5), (0..7).toList(), 5, 3.2f, "low_average"),
        CoreEvent(start + 7000, id, "RenderThread", CoreEventKind.OBSERVE, CoreEventSource.SYSTEM, (0..7).toList(), (0..7).toList(), 2, 2f, "cpu_changed"))
    private fun show(rows: List<CoreEvent> = events, version: Int? = 1) {
        compose.setContent { QixiaThreadsTheme { Column(Modifier.fillMaxSize().systemBarsPadding()) {
            HistoryCoreTimeline(CoreTimelineReport(-1, start, start + 10000, rows, coreEventsVersion = version), false, null, {})
        } } }
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithTag("core-data-table").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun progress(value: Float) = compose.onNodeWithTag("core-time-scrubber")
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun globalCursorRewindsEveryRowWithoutShowingFutureData() {
        val futureId = ThreadIdentity(100, 102, 950)
        show(events + CoreEvent(start + 6000, futureId, "FutureWorker", CoreEventKind.ASSIGN,
            CoreEventSource.QIXIA, (0..7).toList(), listOf(6), 6, 22f, "initial"))
        progress(0f)
        compose.onNodeWithTag("core-row-" + id.stableKey).assert(hasAnyDescendant(hasText("自动分配")))
        compose.onNodeWithTag("core-row-" + futureId.stableKey).assert(hasAnyDescendant(hasText("尚无记录")))
        progress(.5f)
        compose.onNodeWithTag("core-row-" + id.stableKey).assert(hasAnyDescendant(hasText("系统调度")))
        compose.onNodeWithTag("core-row-" + id.stableKey).assert(hasAnyDescendant(hasText("CPU 0–7")))
        compose.onNodeWithTag("core-row-" + futureId.stableKey).assert(hasAnyDescendant(hasText("尚无记录")))
        compose.onNodeWithTag("core-time-scrubber").performTouchInput { swipeRight() }
        compose.onNodeWithTag("core-row-" + futureId.stableKey).assert(hasAnyDescendant(hasText("CPU 6")))
        capture("core-table-test.png")
    }
    @Test fun horizontalColumnsKeepTheThreadNameVisible() {
        show()
        repeat(3) { compose.onNodeWithTag("core-table-columns").performTouchInput { swipeLeft() } }
        compose.onNodeWithText("RenderThread").assertIsDisplayed()
        compose.onNodeWithText("最近事件").assertIsDisplayed()
        compose.onNodeWithText("2.0%").assertIsDisplayed()
        progress(0f)
        compose.onNodeWithText("32.0%").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一记录时刻").performClick()
        compose.onNodeWithText("3.2%").assertIsDisplayed()
    }
    @Test fun releaseReasonAndSystemObservationStayDistinct() {
        show()
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithText("收起核心图").performClick()
        compose.onNodeWithText("分配 / 释放 2").performClick()
        compose.onNodeWithTag("core-event-1").performScrollTo().performClick()
        compose.onNodeWithText("最近采样平均使用率低于 5%，解除 QixiaThreads 限制，交回系统调度。").assertIsDisplayed()
        compose.onNodeWithText("范围归属 · 系统调度").assertIsDisplayed()
        capture("core-table-events-test.png")
        compose.onNodeWithText("采样观察 1").performClick()
        compose.onNodeWithText("执行核采样 · CPU 2").assertIsDisplayed()
        compose.onNodeWithText("采样发现上次执行核心变化，不代表记录了期间的每次迁核。").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回调度表格").performClick()
        compose.onNodeWithTag("core-time-scrubber").assertIsDisplayed()
    }
    @Test fun helpExplainsFivePercentWithoutChangingThePolicy() {
        show()
        compose.onNodeWithContentDescription("记录方式与分配策略说明").performClick()
        compose.onNodeWithText("怎样看调度数据").assertIsDisplayed()
        compose.onNodeWithText("首次有效样本达到 5% 就参与分配", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("RenderThread").assertIsDisplayed()
    }
    @Test fun filteredChartStillLocatesEverySavedOperation() {
        show()
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithText("分配 / 释放 2").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithTag("core-lane-plot").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("core-lane-plot").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一条事件").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("定位明细").performClick()
        compose.onNodeWithTag("core-lane-plot").assertDoesNotExist()
        compose.onNodeWithTag("core-event-1").assertIsDisplayed()
    }
    @Test fun legacyReleaseDoesNotClaimAutomaticOwnership() {
        show(listOf(events[1].copy(source = CoreEventSource.QIXIA, beforeCpus = null, afterCpus = null, legacy = true)), null)
        compose.onNodeWithTag("core-row-" + id.stableKey).assert(hasAnyDescendant(hasText("未确认")))
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithText("收起核心图").performClick()
        compose.onNodeWithText("范围归属 · 旧版未记录").assertIsDisplayed()
        compose.onNodeWithText("范围归属 · 自动分配").assertDoesNotExist()
    }

    @Test fun longListKeepsSheetAnchoredAtBothScrollBoundaries() {
        show(List(300) { i -> events[2].copy(timestampMs = start + i * 1000L) })
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithText("收起核心图").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithTag("core-event-299").fetchSemanticsNodes().isNotEmpty()
        }
        val close = compose.onNodeWithContentDescription("返回调度表格")
        val bounds = close.fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("core-event-table")
        list.performScrollToIndex(1)
        repeat(3) { list.performTouchInput { swipeDown() } }
        compose.onNodeWithTag("core-event-0").assertIsDisplayed()
        assertEquals(bounds, close.fetchSemanticsNode().boundsInRoot)
        list.performScrollToIndex(300)
        repeat(3) { list.performTouchInput { swipeUp() } }
        compose.onNodeWithTag("core-event-299").assertIsDisplayed()
        assertEquals(bounds, close.fetchSemanticsNode().boundsInRoot)
        close.performClick()
        compose.onNodeWithTag("core-time-scrubber").assertIsDisplayed()
    }
}
