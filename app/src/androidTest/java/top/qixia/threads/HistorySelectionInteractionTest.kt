package top.qixia.threads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.HistoryPackageModel
import top.qixia.threads.compose.HistoryUiState
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.HistoryScreen
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary

/** 仅使用模拟快照和回调，不打开、导入或删除设备真实历史。 */
class HistorySelectionInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val first = SessionSummary(1, 1790776800, 120, 7)
    private val second = SessionSummary(2, 1790776900, 120, 9)
    private val automatic = SessionSummary(-1, 1790777000, 120, 5, HistorySource.AUTO_ALLOCATION)
    private val state = mutableStateOf(snapshot())
    private val deleted = mutableListOf<Set<Long>>()
    private var opened = 0

    private fun snapshot(firstSessions: List<SessionSummary> = listOf(first, automatic),
        secondSessions: List<SessionSummary> = listOf(second)) = HistoryUiState(loading = false, packages = listOf(
        app("Alpha", "test.alpha", firstSessions), app("Beta", "test.beta", secondSessions)))

    private fun app(label: String, packageName: String, sessions: List<SessionSummary>) =
        HistoryPackageModel(packageName, label, null, sessions.maxOfOrNull { it.epoch } ?: 0,
            sessions.size, sessions = sessions)

    private fun show() {
        compose.setContent {
            QixiaThreadsTheme {
                HistoryScreen(state.value, PaddingValues(0.dp), onRefresh = {},
                    onOpenSession = { _, _ -> opened++ }, onBack = {}, onSelectSession = {},
                    onDeletePackage = {}, onDeleteSession = {}, onExportPackage = {},
                    onExportSession = {}, onNewCalibration = {}, onDeleteSessions = { deleted += it })
            }
        }
        compose.onNodeWithContentDescription("批量管理记录").performClick()
    }

    @Test fun selectingDoesNotOpenAReportAndDeletionRequiresConfirmation() {
        show()
        compose.onNodeWithTag("history-records").performScrollToKey(first.id)
        compose.onNodeWithTag("history-record-${first.id}").performClick().assertIsOn()
        compose.onNodeWithText("删除所选 · 1").performClick()
        compose.runOnIdle { assertEquals(0, opened); assertTrue(deleted.isEmpty()) }
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("删除所选 · 1").performClick()
        compose.onNodeWithText("删除 1 条").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(first.id)), deleted) }
        compose.onNodeWithContentDescription("批量管理记录").assertExists()
    }

    @Test fun changingCategoryOrSearchClearsHiddenSelections() {
        show()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除所选 · 2").assertIsEnabled()
        compose.onNodeWithTag("history-source-AUTO_ALLOCATION").performClick()
        compose.onNodeWithText("删除所选 · 0").assertIsNotEnabled()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除所选 · 1").assertIsEnabled()
        compose.onNodeWithTag("history-source-CALIBRATION").performClick()
        compose.onNodeWithText("删除所选 · 0").assertIsNotEnabled()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Alpha")
        compose.onNodeWithText("删除所选 · 0").assertIsNotEnabled()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除所选 · 1").performClick()
        compose.onNodeWithText("删除 1 条").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(first.id)), deleted) }
    }

    @Test fun confirmationNeverAddsNewRecordsWhenHistoryRefreshes() {
        show()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除所选 · 2").performClick()
        compose.runOnIdle {
            state.value = snapshot(firstSessions = listOf(automatic),
                secondSessions = listOf(second, second.copy(id = 3, epoch = second.epoch + 1)))
        }
        compose.onNodeWithText("删除 1 条记录？").assertIsDisplayed()
        compose.onNodeWithText("删除 1 条").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(second.id)), deleted) }
    }

    @Test fun busyOrMissingSelectionsCannotBeDeletedTwice() {
        state.value = state.value.copy(busySessionIds = setOf(first.id))
        show()
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("删除所选 · 1").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(busySessionIds = setOf(first.id, second.id))
        }
        compose.onNodeWithText("所选记录已更新").assertIsDisplayed()
        compose.onNodeWithText("删除 0 条").assertIsNotEnabled()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
    }
}
