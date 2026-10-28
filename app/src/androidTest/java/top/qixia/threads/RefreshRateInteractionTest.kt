package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import qixia.refresh.RefreshPolicy
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.RefreshRateScreen

class RefreshRateInteractionTest {
    @get:Rule val compose=createComposeRule()
    private val fixture=RefreshRateUiState(loading=false,available=true,rates=listOf(60f,90f,120f,165f),
        apps=listOf(RefreshApp("com.example.game","测试游戏")),runtime=mapOf("version" to "1"))
    @Test fun selectingHighRefreshPersistsAndGlobalUiCanScroll() {
        var state by mutableStateOf(fixture)
        compose.setContent { QixiaThreadsTheme { RefreshRateScreen(state,PaddingValues(top=32.dp,bottom=24.dp),null,{},{k,v -> state=state.copy(policy=state.policy.with(k,v))}) } }
        compose.onNodeWithText("应用/游戏").assertIsDisplayed()
        compose.onNodeWithText("游戏刷新率").assertDoesNotExist()
        compose.onNodeWithText("实际屏幕").assertDoesNotExist()
        compose.onNodeWithContentDescription("活动刷新率").performClick()
        compose.onNodeWithText("选择刷新率").assertDoesNotExist()
        compose.onAllNodesWithText("高 (165Hz)").onLast().performClick()
        compose.runOnIdle { assertEquals("165.0",state.policy.get("active_hz")) }
        capture("refresh-global.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("兼容模式"))
        compose.onNodeWithText("兼容模式").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("DisplayModes [165] [120] [90] [60]"))
        compose.onNodeWithText("DisplayModes [165] [120] [90] [60]").assertIsDisplayed()
        compose.onNodeWithText("显示模式映射").assertDoesNotExist()
        compose.onNodeWithContentDescription("DisplayModes").performTextReplacement("1 2 -1 0")
        compose.onNodeWithContentDescription("DisplayModes").performImeAction()
        compose.runOnIdle { assertEquals("1 2 -1 0",state.policy.get("display_modes")) }
        capture("refresh-display-modes.png")
    }
    @Test fun appRulesCanBeEditedAndClearedWithoutGlobalChanges() {
        var state by mutableStateOf(fixture)
        compose.setContent { QixiaThreadsTheme { RefreshRateScreen(state,PaddingValues(top=32.dp,bottom=24.dp),"com.example.game",{},{k,v -> state=state.copy(policy=state.policy.with(k,v))}) } }
        compose.onNodeWithContentDescription("关闭应用刷新率").assertIsDisplayed()
        compose.onNodeWithTag("app-refresh-sheet").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, 20f), androidx.compose.ui.geometry.Offset(centerX, height * .85f), 500)
        }
        compose.onNodeWithContentDescription("关闭应用刷新率").assertIsDisplayed()
        capture("refresh-app-sheet.png")
        compose.onNodeWithContentDescription("闲置刷新率").performClick()
        compose.onNodeWithText("选择刷新率").assertDoesNotExist()
        compose.onNodeWithText("90 Hz").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertEquals("90.0,-2",state.policy.values["app.com.example.game"]);assertFalse(state.policy.on("enabled")) }
        capture("refresh-apps.png")
        compose.onNodeWithText("测试游戏").performClick()
        compose.onNodeWithText("清除该应用规则").performClick()
        compose.runOnIdle { assertFalse(state.policy.values.containsKey("app.com.example.game")) }
        compose.onNodeWithText("测试游戏").performClick()
        compose.onNodeWithContentDescription("关闭应用刷新率").performClick()
        compose.onNodeWithContentDescription("关闭应用刷新率").assertDoesNotExist()
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
