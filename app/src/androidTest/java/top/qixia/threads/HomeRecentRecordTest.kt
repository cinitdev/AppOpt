package top.qixia.threads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.HomeRecentRecordCard
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary

/** 纯内存测试数据，不改动配置、文件或用户历史。 */
class HomeRecentRecordTest {
    @get:Rule val compose = createComposeRule()
    private val app = HistoryPackageModel("example.game", "示例游戏", null, 100, 0)
    private fun record(mode: HomeRecordMode, report: SessionSummary? = null) = HomeRecord(
        app, mode, 100_000, 400_000, HomeFpsSummary(58.6f, 100_000, 400_000, 60f,
            listOf(HomeFpsPoint(100_000, 57.2f), HomeFpsPoint(250_000, 60f), HomeFpsPoint(400_000, 58.6f))), report)

    @Test fun staticRulesShowAverageWithoutHistoryLink() {
        compose.setContent {
            QixiaThreadsTheme { HomeRecentRecordCard(HomeUiState(false, records = listOf(record(HomeRecordMode.RULES))), {}, { _, _ -> error("no report") }) }
        }
        compose.onNodeWithText("最近记录").assertIsDisplayed()
        compose.onNodeWithText("线程规则", substring = true).assertIsDisplayed()
        compose.onNodeWithText("58.6").assertIsDisplayed()
        compose.onNodeWithText("查看报告").assertDoesNotExist()
        compose.onNodeWithTag("home_fps_chart").assertDoesNotExist()
    }

    @Test fun automaticSummaryNeverOffersMissingReport() {
        compose.setContent {
            QixiaThreadsTheme { HomeRecentRecordCard(HomeUiState(false, records = listOf(record(HomeRecordMode.AUTOMATIC))), {}, { _, _ -> error("no report") }) }
        }
        compose.onNodeWithText("自动分配", substring = true).assertIsDisplayed()
        compose.onNodeWithText("平均帧率").assertIsDisplayed()
        compose.onNodeWithText("查看报告").assertDoesNotExist()
        compose.onNodeWithTag("home_fps_chart").assertDoesNotExist()
    }

    @Test fun automaticReportOpensExactSession() {
        val session = SessionSummary(-8, 100, 24, 12, source = HistorySource.AUTO_ALLOCATION)
        var selected: SessionSummary? = null
        compose.setContent {
            QixiaThreadsTheme { HomeRecentRecordCard(HomeUiState(false, records = listOf(record(HomeRecordMode.AUTOMATIC, session))), {}, { selectedApp, selectedSession ->
                assertEquals(app.packageName, selectedApp.packageName)
                selected = selectedSession
            }) }
        }
        compose.onNodeWithTag("home_fps_chart").assertIsDisplayed()
        compose.onNodeWithText("采样线程").assertIsDisplayed()
        compose.onNodeWithText("查看报告").performClick()
        compose.runOnIdle { assertEquals(session, selected) }
    }

    @Test fun calibrationUsesTheSameFpsChartAsAutomaticReport() {
        val session = SessionSummary(8, 400, 600, 12)
        compose.setContent {
            QixiaThreadsTheme { HomeRecentRecordCard(HomeUiState(false, records = listOf(record(HomeRecordMode.CALIBRATION, session))), {}, { _, _ -> }, ruleCount = 7) }
        }
        compose.onNodeWithTag("home_fps_chart").assertIsDisplayed()
        compose.onNodeWithText("查看报告").assertIsDisplayed()
        compose.onNodeWithText("当前规则").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
    }
}
