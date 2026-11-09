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

/** 只验证界面草稿与回调，不改写模块配置或启动分配器。 */
class AutomaticCustomInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editValidateSaveAndReturnToDefaultWithoutLosingOtherSwitches() {
        val cpu = RuleConfigLogic.readPresentCpuSet()!!.min().toString()
        var app by mutableStateOf(AppItemModel("com.qixia.custom.fixture", "自定义分配测试", true, null,
            automaticAffinityEnabled = true, automaticCustomSupported = true,
            automaticFallbackSupported = true, automaticFallbackEnabled = true, mainFallbackCpus = cpu,
            performanceRecordingEnabled = true))
        var calls = 0
        compose.setContent { QixiaThreadsTheme {
            AppManageSheet(app, true, {}, {}, {}, {}, true, {}, onAutomaticCustom = {
                calls++
                app = app.copy(automaticCustomRules = it)
            })
        } }
        compose.onNodeWithText("自定义", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rule-core-range").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save-custom-rules").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("custom-threshold-0").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement(cpu)
        compose.onNodeWithTag("custom-cores-0").performScrollTo().performClick()
        compose.onNodeWithText("添加档位").performScrollTo().performClick()
        compose.onNodeWithTag("custom-threshold-1").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement(cpu)
        compose.onNodeWithTag("save-custom-rules").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("custom-threshold-1").performScrollTo().performTextReplacement("30")
        compose.onNodeWithTag("custom-cores-1").performScrollTo().performClick()
        compose.onNodeWithText("自定义", useUnmergedTree = true).performScrollTo()
        capture("automatic-custom-editor.png")
        compose.onNodeWithTag("save-custom-rules").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AutomaticCustomRule(2, cpu), AutomaticCustomRule(30, cpu)), app.automaticCustomRules)
            assertEquals(1, calls)
            assertTrue(app.automaticFallbackEnabled && app.performanceRecordingEnabled)
        }
        compose.onNodeWithText("接管主进程中负载低于 2% 的线程").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("自动分配", useUnmergedTree = true).performScrollTo()
        capture("automatic-custom-saved.png")
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithTag("custom-threshold-0").performScrollTo().performTextReplacement("9")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(2, app.automaticMinimumLoad) }
        compose.onNodeWithText("默认算法").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(app.automaticCustomRules.isEmpty())
            assertTrue(app.automaticFallbackEnabled && app.performanceRecordingEnabled)
        }
        compose.onNodeWithText("接管主进程中负载低于 5% 的线程").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
