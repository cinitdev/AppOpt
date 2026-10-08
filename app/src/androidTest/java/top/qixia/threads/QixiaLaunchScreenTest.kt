package top.qixia.threads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.QixiaLaunchScreen

/** 隔离的启动标识测试，不改动真实应用状态、历史、守护进程或权限。 */
class QixiaLaunchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemStartingThemeHasNoVisibleIconAndMatchesTheBrandBackground() {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_QixiaThreads_Compose_Starting)
        val attributes = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowSplashScreenAnimatedIcon,
            androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon,
            android.R.attr.windowSplashScreenBackground,
            androidx.core.splashscreen.R.attr.postSplashScreenTheme))
        try {
            repeat(2) { index ->
                val drawable = requireNotNull(attributes.getDrawable(index))
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(Canvas(bitmap))
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        assertEquals("系统启动图标应完全透明", 0, Color.alpha(bitmap.getPixel(x, y)))
                    }
                } finally { bitmap.recycle() }
            }
            assertEquals(context.getColor(R.color.compose_background), attributes.getColor(2, Color.BLACK))
            assertEquals(R.style.Theme_QixiaThreads_Compose, attributes.getResourceId(3, 0))
            val windowBackground = TypedValue()
            assertTrue(context.theme.resolveAttribute(android.R.attr.windowBackground, windowBackground, true))
            assertEquals(context.getColor(R.color.compose_background), windowBackground.data)
        } finally { attributes.recycle() }
    }

    @Test fun twoSecondDisplayStartsAfterNativeSplashHandoffAndFinishesOnce() {
        val nativeSplashDismissed = mutableStateOf(false)
        var finished = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            QixiaThreadsTheme {
                QixiaLaunchScreen(nativeSplashDismissed.value) { finished++ }
            }
        }

        // 进程初始化时，Android 可能仍保留系统启动窗口。
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(0, finished) }
        compose.runOnIdle { nativeSplashDismissed.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(650)

        compose.onNodeWithTag("qixia-launch").assertIsDisplayed()
        compose.onNodeWithText("柒夏线程").assertIsDisplayed()
        compose.onNodeWithText("Qixia Threads").assertIsDisplayed()
        compose.onNodeWithText("线程调度 · 性能监测").assertIsDisplayed()
        compose.onNodeWithText("一只小柒夏").assertIsDisplayed()
        compose.onNodeWithText("酷安").assertIsDisplayed()
        compose.onNodeWithContentDescription("一只小柒夏的酷安头像").assertIsDisplayed()

        // 在指定的 2000 毫秒时长两侧允许一帧的调度误差。
        compose.mainClock.advanceTimeBy(1_250)
        compose.runOnIdle { assertEquals(0, finished) }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { assertEquals(1, finished) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(1, finished) }
    }

    @Test fun recompositionKeepsOriginalDeadlineAndUsesLatestFinishCallback() {
        val callbackGeneration = mutableStateOf(0)
        val finishedGenerations = mutableListOf<Int>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val currentGeneration = callbackGeneration.value
            QixiaThreadsTheme {
                QixiaLaunchScreen(started = true) { finishedGenerations += currentGeneration }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { callbackGeneration.value = 1 }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { callbackGeneration.value = 2 }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { assertEquals(emptyList<Int>(), finishedGenerations) }

        // 重组不得再等待两秒，也不得调用过期回调。
        compose.mainClock.advanceTimeBy(250)
        compose.runOnIdle { assertEquals(listOf(2), finishedGenerations) }
        compose.runOnIdle { callbackGeneration.value = 3 }
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertEquals(listOf(2), finishedGenerations) }
    }

    @Test fun visibleFrameSignalWaitsForBrandEntranceInsteadOfTheBackgroundOnlyFrame() {
        val started = mutableStateOf(false)
        var visibleFrames = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            QixiaThreadsTheme {
                QixiaLaunchScreen(started.value, onFirstVisibleFrame = { visibleFrames++ }, onFinished = {})
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("qixia-launch").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, visibleFrames); started.value = true }
        // 更新 started 的这帧尚未经过 withFrameNanos 和入场动画，仍只有背景。
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, visibleFrames) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, visibleFrames) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, visibleFrames) }
    }
}
