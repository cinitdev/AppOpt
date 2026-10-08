package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.*
import top.qixia.threads.db.*

/** 仅用内存样本验证分析交互，截图标记为测试数据，不写入用户历史。 */
class AnalysisFeaturesInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val end = 1791432600000L
    private val session = SessionSummary(-41, end / 1000, 60, 3, HistorySource.AUTO_ALLOCATION,
        end - 30000, 30000, end)
    private val fps = HistoryFpsStore.Report(117.5f, 45f, 120f, 31,
        (0..30).map { HistoryFpsStore.Point(it / 30f, if (it == 15) 45f else 120f) })
    private fun metric(key: String, value: Float) = HistoryMetrics.Series(key, value, value, value, 31,
        (0..30).map { HistoryMetrics.Point(it / 30f, value) })
    private val metrics = HistoryMetrics.Report(mapOf("cpu_usage" to metric("cpu_usage", 43f),
        "gpu_usage" to metric("gpu_usage", 81f), "gpu_mhz" to metric("gpu_mhz", 680f),
        "cpu_mhz.4_5_6" to metric("cpu_mhz.4_5_6", 2200f), "power_w" to metric("power_w", 4.8f)),
        31, 0, 30000, 1f)
    private val detail = HistoryDetailUiState("com.example.analysis", "示例游戏 · 测试数据", false,
        sessions = listOf(session), expandedSessionId = session.id,
        threads = listOf(ThreadData("RenderThread", 27f, 27f, List(31) { "27" }.joinToString(","), "")),
        sessionFps = mapOf(session.id to fps), sessionMetrics = mapOf(session.id to metrics))

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun dropTimeSelectionLoadsNearbySamplesAndCanCollapse() {
        val drops = HistoryAnalysis.drops(fps, metrics, session.durationMs)
        compose.setContent { QixiaThreadsTheme {
            var selected by remember { mutableStateOf<FrameDrop?>(null) }
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                androidx.compose.material3.Text("示例游戏 · 测试数据")
                FrameDropAnalysisCard(detail, session, drops, selected, { selected = it })
            }
        } }
        compose.onNodeWithText("0:15").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("RenderThread").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("27.0%").assertExists()
        compose.onNodeWithText("CPU 4–6 频率").assertIsDisplayed()
        capture("analysis-drop-preview.png")
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("RenderThread").assertDoesNotExist()
    }

    @Test fun plotMarkerSelectsDropAndDragDoesNotSnapBack() {
        val cursor = mutableStateOf<Float?>(null)
        var selection: Float? = null
        compose.setContent { QixiaThreadsTheme {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                HistoryFpsPlotCard(fps, metrics, session.durationMs, cursor, listOf(.5f)) { selection = it }
            }
        } }
        val plot = compose.onAllNodes(hasContentDescription("趋势图", substring = true)).onFirst()
        plot.performTouchInput { click(Offset(width * .5f, height - 4f)) }
        compose.runOnIdle { assertEquals(.5f, selection); assertEquals(.5f, cursor.value) }
        plot.performTouchInput { swipe(Offset(width * .5f, height - 4f), Offset(width * .85f, height - 4f), 500) }
        compose.runOnIdle { assertNull(cursor.value) }
    }

    @Test fun reportOffersOtherCategoryAndPassesWholeSession() {
        val other = session.copy(id = 42, epoch = session.epoch - 3600, source = HistorySource.CALIBRATION)
        var compared: Pair<SessionSummary, SessionSummary>? = null
        compose.setContent { QixiaThreadsTheme {
            HistoryReportScreen(detail, PaddingValues(0.dp), {}, {}, {}, {}, emptySet(),
                comparisonCandidates = listOf(session, other), onCompare = { a, b -> compared = a to b })
        } }
        compose.onNodeWithContentDescription("对比另一条记录").performClick()
        compose.onNodeWithText("选择对照记录").assertIsDisplayed()
        compose.onNode(hasText("校准", substring = true) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(session to other, compared) }
    }

    @Test fun comparisonShowsDifferencesAndMissingMetricsHonestly() {
        val a = RunComparisonSummary(session, 117.5f, 45f, 120f, 31, 4.8f, 31, 62f, null, false,
            1f, emptyMap(), fps.points.map { it.fps }, true, 120f)
        val b = a.copy(session = session.copy(id = 42, epoch = session.epoch + 3600), averageFps = 119.2f,
            minimumFps = 83f, maximumFrameMs = 35f, averagePower = 4.3f, sampledFps = List(31) { 120f })
        compose.setContent { QixiaThreadsTheme {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { HistoryComparisonScreen(HistoryComparisonUiState("com.example.analysis", "示例游戏 · 测试数据",
                a.session, b.session, false, listOf(a, b)), PaddingValues(0.dp), {}, {})
            }
        } }
        compose.onNodeWithText("调度效果对比").assertIsDisplayed()
        compose.onNodeWithText("+1.7\nFPS").assertExists()
        compose.onNodeWithText("逐帧 P95 / P99", substring = true).assertExists()
        capture("analysis-comparison-preview.png")
        compose.onNodeWithText("电量与功率").performScrollTo()
        compose.onNodeWithText("电量净减少").assertExists()
    }

    @Test fun diagnosticExpandsRealGroupAndFailureWithoutClaimingOwnership() {
        val identity = ThreadIdentity(1200, 1201, 900)
        val row = AffinityDiagnosticRow(identity, "RenderThread", 32f, listOf(6), listOf(6),
            "/top-app", "/QiXiaRs/auto/1200-1201-900/6", end, "error", "cpuset_failed", "alive", "Operation not permitted (os error 1)")
        val report = AffinityDiagnosticReport("com.example.analysis", end - 1000, end, "active", "示例：接管写入失败", 1, listOf(row))
        compose.setContent { QixiaThreadsTheme {
            Box(Modifier.fillMaxSize().systemBarsPadding()) { AffinityDiagnosticsScreen(AffinityDiagnosticsUiState(report.packageName, "示例游戏 · 测试数据", false, report),
                PaddingValues(0.dp), {}, {})
            }
        } }
        compose.onNodeWithText("接管已核对").assertDoesNotExist()
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithText("/top-app").assertExists()
        compose.onNodeWithText("内核 / 文件操作返回", substring = true).assertExists()
        capture("analysis-diagnostics-preview.png")
        compose.onNodeWithText("需要核对").performClick()
        compose.onNodeWithText("RenderThread").assertExists()
    }
}
