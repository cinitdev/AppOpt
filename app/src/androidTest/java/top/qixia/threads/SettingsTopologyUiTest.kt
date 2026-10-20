package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.SettingsTopologyContent

/** 使用实机读取的 8100 拓扑渲染界面，不写入配置或启动守护进程。 */
class SettingsTopologyUiTest {
    @get:Rule val compose = createComposeRule()
    private val performance = """
        detected_complete=1
        detected_clusters=2
        detected_all=0-7
        detected_group_0=0-3
        detected_group_1=4-7
    """.trimIndent()
    private val frequency = """
        detected_freq_complete=1
        detected_freq_domains=3
        detected_freq_domain_0=0-3
        detected_freq_domain_1=4-6
        detected_freq_domain_2=7
    """.trimIndent()

    private fun show(block: String, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                QixiaThreadsTheme {
                    Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SettingsTopologyContent(block, (0..7).toSet())
                        }
                    }
                }
            }
        }
    }

    @Test fun actual8100LayoutShowsSeparateDomainsInsideSamePerformanceCard() {
        show("$performance\n$frequency")
        compose.onNodeWithText("8 个核心 · 2 个性能组").assertIsDisplayed()
        compose.onNodeWithText("3 个独立调频域").assertIsDisplayed()
        compose.onNodeWithText("调频域 2 · CPU 4-6").assertIsDisplayed()
        compose.onNodeWithText("调频域 3 · CPU 7").assertIsDisplayed()
        capture("topology-8100.png")
        compose.onNodeWithText("详情").performClick()
        compose.onNodeWithText("独立调频范围").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看诊断字段").performScrollTo().performClick()
        compose.onNodeWithText("freq_domain_2").performScrollTo().assertIsDisplayed()
    }

    @Test fun narrowLargeFontKeepsTheLastDomainReachable() {
        show("$performance\n$frequency", fontScale = 1.5f)
        compose.onNodeWithText("调频域 3 · CPU 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("详情").performScrollTo().performClick()
        compose.onNodeWithText("独立调频范围").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingDomainsDoNotAppearAsTwoConfirmedDomains() {
        show(performance)
        compose.onNodeWithText("调频域待确认").assertIsDisplayed()
        compose.onNodeWithText("CPU 4-7").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
