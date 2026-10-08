package top.qixia.threads.design

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.HistoryMetricsStore
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.compose.ui.*
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.ThreadData

/** 仅用于显式运行的内存界面测试，不向设备历史数据库插入测试数据。 */
@Composable
internal fun HistoryVisualFixture(mode: String) {
    val coreCount = if (mode == "history-ten-core") 10 else 8
    val metrics = remember(coreCount) {
        HistoryMetricsStore.summarize((1..360).map { i ->
            val charging = i in 150..195
            HistoryMetrics.Sample(i * 2000L, charging, buildMap {
                if (!charging) put("power_w", 4.5f + kotlin.math.sin(i.toDouble()).toFloat() * .8f)
                put("cpu_c", 43f + i / 36f)
                put("gpu_c", 40f + i / 45f)
                put("battery_c", 33f + i / 90f)
                put("cpu_mhz.0_1_2_3", 691f + (i % 5) * 200f)
                put(if (coreCount == 10) "cpu_mhz.4_5_6_7" else "cpu_mhz.4_5_6", 1209f + (i % 7) * 172f)
                put(if (coreCount == 10) "cpu_mhz.8_9" else "cpu_mhz.7", 1804f + (i % 8) * 172f)
                put("gpu_mhz", 305f + (i % 4) * 90f)
                put("cpu_usage", 35f + (i % 13) * 2f)
                put("cpu_cluster.0_1_2_3", 20f + (i % 11) * 3f)
                put("cpu_cluster.4_5_6", 42f + (i % 9) * 4f)
                put("cpu_cluster.7", 50f + (i % 10) * 4f)
                repeat(coreCount) { cpu -> put("cpu_core.$cpu", (20 + cpu * 7 + i % 11).toFloat()) }
                repeat(coreCount) { cpu -> put("cpu_cycles_core.$cpu", (200 + cpu * 120 + (i % 11) * 80).toFloat()) }
                put("cpu_cycles.0_1_2_3", 200f + (i % 8) * 40f)
                put("cpu_cycles.4_5_6", 700f + (i % 9) * 60f)
                put("cpu_cycles.7", 1000f + (i % 10) * 90f)
                put("gpu_usage", 52f + i % 35)
                put("ddr_mbps", 3200f + (i % 3) * 533f)
                put("frame_max_ms", if (i == 210) 115f else 16f + i % 9)
                if (!charging) put("battery_ma", 1200f + i % 150)
                put("battery_pct", 85f - i / 120)
            })
        }, 0, 720000).copy(device = mapOf("platform" to "SM8250", "model" to "Redmi K40", "os" to "Android 12", "resolution" to "1080 × 2400", "refresh" to "120 Hz"))
    }
    val threads = remember { List(32) { i ->
        val name = listOf("UnityMain", "UnityGfxDeviceWorker", "RenderThread", "Thread-shared-2", "com.example.game:worker").getOrElse(i) { "Binder:1234_${i}" }
        ThreadData(name, 62f / (i + 1), 93f / (i + 1), List(900) { n ->
            if (n == 830) 99f else (24f + (n % 17) * 2 + kotlin.math.sin(n.toDouble()).toFloat() * 9) / (i + 1)
        }.joinToString(","), if (i == 4) "v3p:e1:worker%20one,12,19;e1:worker%20two,8,12" else "")
    } }
    val packages = remember(mode) { List(if (mode == "history-sources") 1 else 3) { i ->
        val calibrationSessions = List(3) { n -> SessionSummary((i * 3 + n + 1).toLong(), 1789860000L - n * 86400 - i * 900, 1440, threads.size) }
        val sessions = if (mode == "history-sources") listOf(calibrationSessions.first(),
            SessionSummary(-100, 1789861200L, 654, threads.size, source = HistorySource.AUTO_ALLOCATION,
                startedAtMs = 1789860480000L, recordedDurationMs = 720000L)) else calibrationSessions
        val fps = HistoryFpsStore.Report(59.3f, 42f, 60.1f, 720,
            List(180) { n -> HistoryFpsStore.Point(n / 179f, if (n == 95) 42f else 59f + kotlin.math.sin(n.toDouble()).toFloat()) }, low5 = 54.2f, jitter = 2.6f)
        HistoryPackageModel("visual.fixture.$i", listOf("王者荣耀", "快手极速版", "QQ")[i], null, sessions.first().epoch, sessions.size,
            sessions = sessions, sessionFps = if (i == 0) sessions.associate { it.id to fps } else emptyMap())
    } }
    var state by remember(mode) { mutableStateOf(HistoryUiState(loading = false,
        packages = if (mode == "history-empty") emptyList() else packages)) }
    var autoHistoryEnabled by remember(mode) { mutableStateOf(false) }
    var destination by remember(mode) { mutableStateOf(if (mode == "history-settings") MainDestination.SETTINGS else MainDestination.HISTORY) }
    QixiaThreadsTheme {
        Scaffold(containerColor = OceanBackground, bottomBar = {
            QixiaThreadsBottomNavigation(destination) { if (it == MainDestination.HISTORY || it == MainDestination.SETTINGS) destination = it }
        }) { padding ->
            if (destination == MainDestination.SETTINGS) SettingsScreen(
                state = QixiaThreadsUiState(settings = SettingsUiState(loading = false, hasRoot = true, readSuccess = true,
                    autoHistorySupported = true, autoHistoryEnabled = autoHistoryEnabled)),
                contentPadding = padding, onRefresh = {}, onPolicyChange = {}, onRestorePolicy = {},
                onExportDiagnostics = {}, onCheckModuleUpdate = {}, moduleDownloadState = ModuleUpdateDownloadViewModel.State(),
                onOpenModuleUpdate = {},
                onAutoHistoryChange = { autoHistoryEnabled = it })
            else HistoryScreen(state, padding, {}, { app, session ->
                state = state.copy(detail = HistoryDetailUiState(app.packageName, app.label, loading = false,
                    sessions = app.sessions, expandedSessionId = session.id, threads = threads, sessionFps = app.sessionFps,
                    sessionMetrics = if (app.packageName == "visual.fixture.0") app.sessions.associate { it.id to metrics } else emptyMap()))
            }, { state = state.copy(detail = null) }, { session ->
                state = state.copy(detail = state.detail?.copy(expandedSessionId = session.id, threads = threads))
            }, { app ->
                val ids = app.sessions.map { it.id }.toSet()
                state = state.copy(packages = state.packages.mapNotNull { existing ->
                    val remaining = existing.sessions.filterNot { it.id in ids }
                    if (remaining.isEmpty()) null else existing.copy(sessions = remaining, sessionCount = remaining.size)
                })
            }, { id ->
                state = state.copy(detail = null, packages = state.packages.map { it.copy(sessions = it.sessions.filterNot { s -> s.id == id }) })
            }, {}, {}, {}, onDeleteSessions = { ids ->
                state = state.copy(packages = state.packages.mapNotNull { app ->
                    val remaining = app.sessions.filterNot { it.id in ids }
                    if (remaining.isEmpty()) null else app.copy(sessions = remaining, sessionCount = remaining.size)
                })
            }, autoHistoryEnabled = autoHistoryEnabled)
        }
    }
}
