package top.qixia.threads

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
    private val metrics = HistoryMetricsStore.summarize((0..30).map { index ->
        HistoryMetrics.Sample(end - 30000 + index * 1000, false, mapOf("cpu_usage" to 43f,
            "gpu_usage" to 81f, "gpu_mhz" to 680f, "cpu_mhz.4_5_6" to 2200f, "power_w" to 4.8f))
    }, end - 30000, 30000)
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

    @Test fun continuousReportKeepsOriginalCursorReadingsAfterAnHour() {
        val duration = 3_600_000L
        val start = end - duration
        val longSession = session.copy(startedAtMs = start, recordedDurationMs = duration)
        val report = HistoryMetricsStore.summarize((1..1800).map { index ->
            HistoryMetrics.Sample(start + index * 2000L, false, buildMap {
                put("cpu_usage", 42f); put("gpu_usage", 65f); put("gpu_mhz", 680f)
                put("cpu_c", 62f); put("battery_c", 37f); put("power_w", 4.8f)
                put("battery_ma", 1250f); put("battery_pct", 90f - index / 150f)
                put("cpu_mhz.0_1_2_3", 1200f); put("cpu_mhz.4_5_6", 2200f); put("cpu_mhz.7", 2800f)
                (0..7).forEach { core -> put("cpu_core.$core", if (core == 7) 78f else 15f + (index * (core + 1) % 50)) }
            })
        }, start, duration).copy(device = mapOf("platform" to "SM8550", "model" to "K70 · 测试数据", "os" to "Android 15",
            "resolution" to "1440 × 3200", "refresh" to "120 Hz"))
        val rawFps = HistorySampleSeries.Builder(start, duration, gapMs = 2500).apply {
            (1..3600).forEach { add(start + it * 1000L, if (it % 71 == 0) 96f else 120f) }
        }.build()!!
        val longFps = HistoryFpsStore.summary(rawFps.points().map { it.value },
            rawFps.points().filterIndexed { index, _ -> index % 15 == 0 }
                .map { HistoryFpsStore.Point(it.progress, it.value) }).copy(analysisSamples = rawFps)
        compose.setContent { QixiaThreadsTheme {
            HistoryReportScreen(detail.copy(sessions = listOf(longSession),
                sessionFps = mapOf(session.id to longFps), sessionMetrics = mapOf(session.id to report)),
                PaddingValues(top = 24.dp, bottom = 24.dp), {}, {}, {}, {}, emptySet())
        } }
        compose.onNodeWithText("数据图表").assertIsDisplayed()
        capture("history-scene-overview.png")
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(3)
        capture("history-scene-fps.png")
        compose.onNodeWithContentDescription("切换帧率辅助指标").performClick()
        compose.onNodeWithText("CPU / GPU").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换帧率辅助指标").performClick()
        compose.onNodeWithText("累计耗电").assertIsDisplayed()
        compose.onNodeWithText("耗电%", substring = true).assertExists()
        list.performScrollToIndex(5)
        val chart = compose.onNode(hasContentDescription("总使用率", substring = true) and hasContentDescription("趋势图", substring = true))
        chart.performSemanticsAction(SemanticsActions.SetProgress) { it(1_606_000f / duration) }
        chart.assertTextContains("CPU 7：78.0 %", substring = true)
        capture("history-scene-cpu-cursor.png")
        chart.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        chart.assertTextContains("CPU 7：78.0 %", substring = true)
        chart.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        chart.assertTextContains("CPU 7：78.0 %", substring = true)
        list.performScrollToIndex(10)
        capture("history-scene-energy.png")
        compose.onNodeWithText("掉帧与线程").performClick()
        compose.onNodeWithText("掉帧位置分析").assertIsDisplayed()
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
        compose.onNodeWithText("2200.0 MHz").assertExists()
        compose.onNodeWithText("CPU 4–6 频率").assertIsDisplayed()
        capture("analysis-drop-preview.png")
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("RenderThread").assertDoesNotExist()
    }

    @Test fun longReportDropSelectionUsesOriginalMetricsRatherThanPlotPoints() {
        val duration = 2_040_000L
        val longSession = session.copy(startedAtMs = end - duration, recordedDurationMs = duration)
        val longMetrics = HistoryMetricsStore.summarize((1..1020).map { index ->
            HistoryMetrics.Sample(end - duration + index * 2000, false, mapOf("cpu_usage" to index / 20f,
                "gpu_usage" to 0f, "gpu_mhz" to 300f + index, "cpu_mhz.7" to 2000f + index, "power_w" to 3f))
        }, end - duration, duration)
        val longDetail = detail.copy(sessions = listOf(longSession), threads = emptyList(),
            sessionMetrics = mapOf(session.id to longMetrics))
        val drop = FrameDrop(1_606_000f / duration, 1f, 1755.6f, 120f)
        compose.setContent { QixiaThreadsTheme {
            var selected by remember { mutableStateOf<FrameDrop?>(null) }
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                FrameDropAnalysisCard(longDetail, longSession, listOf(drop), selected, { selected = it })
            }
        } }
        compose.onNodeWithText("26:46").performClick()
        compose.onNodeWithText("2803.0 MHz").assertExists()
        compose.onNodeWithText("1103.0 MHz").assertExists()
        compose.onNodeWithText("3.0 W").assertExists()
        compose.onNodeWithText("该时刻无有效样本").assertDoesNotExist()
        capture("analysis-long-record-preview.png")
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
