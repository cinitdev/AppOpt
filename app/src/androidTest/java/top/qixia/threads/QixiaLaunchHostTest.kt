package top.qixia.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.QixiaLaunchHost
import top.qixia.threads.compose.ui.QixiaLaunchScreen

/** 用简单主页验证首帧衔接，不初始化真实 ViewModel 或设备服务。 */
class QixiaLaunchHostTest {
    @get:Rule val compose = createComposeRule()
    private val homeColor = Color(0xFF163D82)

    @Test fun preparesHomeDuringBrandDisplayAndFadesBackgroundWithoutRecreatingHome() {
        val started = mutableStateOf(false)
        val showLaunch = mutableStateOf(true)
        var instances = 0
        var disposals = 0
        var taps = 0
        var blocked = true
        var focused = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            QixiaThreadsTheme {
                QixiaLaunchHost(showLaunch.value, started.value, { showLaunch.value = false }) { launchBlocked ->
                    val instance = remember { ++instances }
                    val focus = remember { FocusRequester() }
                    DisposableEffect(instance) { onDispose { disposals++ } }
                    SideEffect { blocked = launchBlocked }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    Box(Modifier.fillMaxSize().background(homeColor)
                        .focusProperties { canFocus = true }.focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }.clickable { taps++ }) { Text("主页操作") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("qixia-launch").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, instances); started.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // Android 实际 draw 通知发生在测试时钟推进之后，再推进状态提交、预热和主页绘制所需的帧。
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, instances); assertTrue(blocked); assertTrue(focused) }
        compose.onNodeWithText("主页操作").assertDoesNotExist()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("qixia-launch").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, taps) }

        compose.mainClock.advanceTimeBy(1_280)
        compose.waitForIdle()
        val duringFade = compose.onRoot().captureToImage().toPixelMap()
        // 品牌背景也必须退场，不能把已经绘制的主页继续盖成浅色空屏。
        assertTrue("淡出时应透出底下的蓝色主页", duringFade[5, 5].red < .9f)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("qixia-launch").assertDoesNotExist()
        compose.onNodeWithText("主页操作").assertIsDisplayed().performClick()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, instances)
            assertEquals(0, disposals)
            assertEquals(2, taps)
            assertFalse(blocked)
        }
    }

    @Test fun restoredActivityShowsHomeImmediatelyWithoutWaitingForBrandHandoff() {
        var blocked = true
        compose.setContent {
            QixiaThreadsTheme {
                QixiaLaunchHost(showLaunch = false, started = false, onFinished = { error("不应重播启动动画") }) {
                    SideEffect { blocked = it }
                    Text("已恢复的主页")
                }
            }
        }
        compose.onNodeWithText("已恢复的主页").assertIsDisplayed()
        compose.onNodeWithTag("qixia-launch").assertDoesNotExist()
        compose.runOnIdle { assertFalse(blocked) }
    }

    @Test fun doesNotFadeToEmptyBackgroundWhenFirstHomeFrameIsLate() {
        val ready = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var finished = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            QixiaThreadsTheme {
                Box(Modifier.fillMaxSize().background(homeColor)) {
                    if (visible.value) QixiaLaunchScreen(started = true, readyToReveal = ready.value) {
                        finished++
                        visible.value = false
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        compose.onNodeWithTag("qixia-launch").assertIsDisplayed()
        val waiting = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("主页尚未绘制时应保留品牌画面", waiting[5, 5].red > .9f)
        compose.runOnIdle { assertEquals(0, finished); ready.value = true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("qixia-launch").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, finished) }
    }
}
