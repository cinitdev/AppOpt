package top.qixia.threads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.AppManageSheet
import top.qixia.threads.compose.ui.HistoryScreen
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import java.io.File

class PerformanceRecordingUiTest {
    @get:Rule val compose = createComposeRule()
    private fun save(name: String) {
        val node = if (name.contains("management")) compose.onNodeWithTag("app-manage-sheet") else compose.onRoot()
        val image = node.captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun performanceSwitchIsIndependentFromAutomaticAllocation() {
        val app = mutableStateOf(AppItemModel("com.demo.game", "示例游戏", true, null, state = AppRuleState.PENDING))
        var automaticChanged = false
        compose.setContent { QixiaThreadsTheme {
            AppManageSheet(app.value, true, {}, {}, {}, { automaticChanged = true }, true, {},
                onPerformanceRecording = { app.value = app.value.copy(performanceRecordingEnabled = it) })
        } }
        compose.onNodeWithContentDescription("性能记录开关").performClick()
        compose.onNodeWithContentDescription("性能记录开关").assertIsOn()
        compose.onNodeWithText("打开应用").assertExists()
        assertFalse(automaticChanged)
        save("performance-management.png")
    }
    @Test fun mergedHistoryShowsThirdSourceAndRecordingShortcut() {
        val now = System.currentTimeMillis()
        val sources = HistorySource.entries.mapIndexed { index, source ->
            SessionSummary(if (source == HistorySource.CALIBRATION) 1 else -(index + 1L), now / 1000 - index * 90,
                30, 0, source, now - 70_000 - index * 90_000, 60_000, now - 10_000 - index * 90_000)
        }
        var create = false
        val pkg = HistoryPackageModel("com.demo.game", "示例游戏", null, now / 1000, 3, sessions = sources)
        compose.setContent { QixiaThreadsTheme {
            HistoryScreen(HistoryUiState(loading = false, packages = listOf(pkg)), PaddingValues(0.dp), {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
                onNewRecording = { create = true })
        } }
        compose.onNodeWithText("性能记录", substring = false).assertExists()
        compose.onNodeWithText("开始记录").performClick()
        assertTrue(create)
        save("performance-history.png")
    }
}
