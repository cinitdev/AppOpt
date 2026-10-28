package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.AppManageSheet

class AutomaticFallbackInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun managementSheetKeepsOpenWhileDraggingAndClosesWithButton() {
        var showing by mutableStateOf(true)
        val app = AppItemModel("com.qixia.fixture", "测试游戏", true, null,
            automaticAffinityEnabled = true, automaticFallbackSupported = true)
        compose.setContent { QixiaThreadsTheme {
            if (showing) AppManageSheet(app, true, { showing = false }, {}, {}, {}, true, {})
        } }
        compose.onNodeWithTag("app-manage-sheet").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, 20f), androidx.compose.ui.geometry.Offset(centerX, height * .85f), 500)
        }
        compose.onNodeWithContentDescription("关闭应用管理").assertIsDisplayed().performClick()
        compose.onNodeWithTag("app-manage-sheet").assertDoesNotExist()
    }

    @Test fun existingRuleEnablesDirectlyAndCanBeTurnedOff() {
        var app by mutableStateOf(AppItemModel("com.qixia.fixture", "测试游戏", true, null,
            state = top.qixia.threads.compose.AppRuleState.CONFIGURED, ruleCount = 4,
            automaticAffinityEnabled = true, automaticFallbackSupported = true, mainFallbackCpus = "0-3"))
        var changedCpus: String? = "未调用"
        compose.setContent { QixiaThreadsTheme {
            AppManageSheet(app, true, {}, {}, {}, {}, true, {}, onAutomaticFallback = { enabled, cpus ->
                changedCpus = cpus
                app = app.copy(automaticFallbackEnabled = enabled)
            })
        } }
        compose.onNodeWithText("刷新率").assertDoesNotExist()
        compose.onNodeWithText("CPU 0-3").assertDoesNotExist()
        capture("app-manage-compact.png")
        compose.onNodeWithContentDescription("主进程兜底模式").performScrollTo().performClick()
        compose.onNodeWithContentDescription("主进程兜底模式").assertIsOn()
        compose.runOnIdle { assertNull(changedCpus) }
        compose.onNodeWithText("选择主进程兜底核心").assertDoesNotExist()
        capture("fallback-enabled.png")
        compose.onNodeWithContentDescription("主进程兜底模式").performClick().assertIsOff()
    }

    @Test fun missingRuleOpensPickerAndCancelDoesNotEnable() {
        var calls = 0
        val app = AppItemModel("com.qixia.fixture", "测试游戏", true, null,
            automaticAffinityEnabled = true, automaticFallbackSupported = true)
        compose.setContent { QixiaThreadsTheme {
            AppManageSheet(app, true, {}, {}, {}, {}, true, {}, onAutomaticFallback = { _, _ -> calls++ })
        } }
        compose.onNodeWithContentDescription("主进程兜底模式").performScrollTo().performClick()
        compose.onNodeWithText("选择主进程兜底核心").assertIsDisplayed()
        compose.onNodeWithText("保存并开启").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("主进程兜底模式").assertIsOff()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
