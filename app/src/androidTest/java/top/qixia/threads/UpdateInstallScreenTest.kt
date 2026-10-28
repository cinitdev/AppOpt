package top.qixia.threads

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.UpdateInstallScreen

class UpdateInstallScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedModuleAndApkHandoffHaveSeparateExplicitActions() {
        var done = 0
        var installs = 0
        compose.setContent { QixiaThreadsTheme {
            UpdateInstallScreen(UpdateInstallViewModel.State(statusTitle = "热更新成功",
                statusDetail = "无需重启手机", running = false, success = true, appUpdatePending = true),
                "v2.0.5", false, null, { done++ }, { done++ }, { installs++ })
        } }
        compose.onNodeWithText("重启系统").assertDoesNotExist()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertEquals(1, done); assertEquals(0, installs) }
        compose.onNodeWithText("更新 App").performClick()
        compose.onNodeWithText("暂不更新").performClick()
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithText("更新 App").performClick()
        compose.onNodeWithText("返回桌面并更新").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun rebootRequiredDoesNotOfferApkHandoff() {
        compose.setContent { QixiaThreadsTheme {
            UpdateInstallScreen(UpdateInstallViewModel.State(statusTitle = "等待重启",
                running = false, success = true, rebootRequired = true), "v2.0.5", false, null, {}, {})
        } }
        compose.onNodeWithText("重启系统").assertIsDisplayed()
        compose.onNodeWithText("更新 App").assertDoesNotExist()
        compose.onNodeWithText("完成").assertDoesNotExist()
    }
}
