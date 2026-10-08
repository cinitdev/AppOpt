package top.qixia.threads.design

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.res.ResourcesCompat
import top.qixia.threads.DaemonBridge
import top.qixia.threads.R
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.theme.MintBackground
import top.qixia.threads.compose.ui.QixiaThreadsBottomNavigation
import top.qixia.threads.compose.ui.HomeScreen
import top.qixia.threads.compose.ui.HomeEnvironmentSheet
import top.qixia.threads.compose.ui.homeEnvironmentBackdrop
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.HistorySource
import java.time.LocalDateTime
import java.time.ZoneId

/** 仅供调试的内存界面测试数据，正式启动入口不会打开。 */
@Composable
internal fun HomeVisualFixture(mode: String) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val state = remember(mode, configuration) {
        val started = LocalDateTime.of(2026, 9, 10, 21, 8).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val app = HistoryPackageModel("visual.fixture", "王者万象棋",
            ResourcesCompat.getDrawable(resources, R.drawable.mint_reference_avatar, context.theme), started / 1000, 1)
        val values = listOf(59f, 59.8f, 58.6f, 60f, 59f, 59.9f, 59.1f, 60f, 57.2f, 60f, 59.1f, 59.8f, 58.9f, 59.9f, 59.8f)
        val fps = HomeFpsSummary(59.8f, started, started + 758_000L, 60f,
            values.mapIndexed { i, value -> HomeFpsPoint(started + i * 758_000L / (values.size - 1), value) })
        val kind = when (mode) {
            "mint-rules" -> HomeRecordMode.RULES
            "mint-auto-summary", "mint-auto-report" -> HomeRecordMode.AUTOMATIC
            else -> HomeRecordMode.CALIBRATION
        }
        val report = when (mode) {
            "mint-rules", "mint-auto-summary" -> null
            "mint-auto-report" -> SessionSummary(-1, (started + 758_000L) / 1000, 1516, 24,
                source = HistorySource.AUTO_ALLOCATION, startedAtMs = started,
                recordedDurationMs = 758_000L, recordedEndedAtMs = started + 758_000L)
            else -> SessionSummary(1, (started + 758_000L) / 1000, 1516, 24,
                startedAtMs = started, recordedDurationMs = 758_000L, recordedEndedAtMs = started + 758_000L)
        }
        val latest = HomeRecord(app, kind, started, started + 758_000L, fps, report)
        val previous = listOf(
            HomeRecord(app.copy(packageName = "visual.video", label = "快手极速版"), HomeRecordMode.AUTOMATIC,
                started - 1_800_000L, started - 600_000L, HomeFpsSummary(58.7f, started - 1_800_000L, started - 600_000L, 60f), null),
            HomeRecord(app.copy(packageName = "visual.chat", label = "微信"), HomeRecordMode.RULES,
                started - 3_600_000L, started - 2_400_000L, HomeFpsSummary(59.1f, started - 3_600_000L, started - 2_400_000L, 60f), null))
        QixiaThreadsUiState(
            deviceMetrics = DeviceMetricsUiState(cpuPercent = 9, memoryPercent = 52, batteryPercent = 25),
            home = when (mode) {
                "mint-empty" -> HomeUiState(loading = false)
                "mint-error" -> HomeUiState(loading = false, error = "最近记录读取失败，下拉重试")
                else -> HomeUiState(loading = false, records = listOf(latest) + previous)
            },
            applications = ApplicationsUiState(loading = mode == "mint-loading",
                configured = if (mode == "mint-empty") emptyList() else listOf(
                    AppItemModel(app.packageName, app.label, true, app.icon, ruleCount = 6,
                        automaticAffinityEnabled = kind == HomeRecordMode.AUTOMATIC))),
            environment = EnvironmentUiState(loading = mode == "mint-environment-loading", hasRoot = true, moduleCompatible = true,
                statusMessage = if (mode == "mint-configuration-error") "配置读取失败" else null,
                overlayGranted = mode != "mint-environment-missing", usageAccessGranted = mode != "mint-environment-missing",
                moduleVersion = DaemonBridge.ModuleVersion("1.8.6", 186, "1.8.6", ""),
                daemonRuntime = DaemonBridge.DaemonRuntime(true, "1.8.6"),
                foregroundState = DaemonBridge.TaskForegroundState(true, "running", "task", null, null,
                    null, null, emptyList(), null, null, "", "", "", "")))
    }
    var environmentOpen by remember(mode) { mutableStateOf(mode.startsWith("mint-environment")) }
    QixiaThreadsTheme {
        Scaffold(modifier = Modifier.homeEnvironmentBackdrop(environmentOpen),
            containerColor = MintBackground, bottomBar = { QixiaThreadsBottomNavigation(MainDestination.HOME) {} }) { padding ->
            HomeScreen(state, padding, {}, {}, { environmentOpen = true }, {}, { _, _ -> })
        }
        if (environmentOpen) HomeEnvironmentSheet(state.environment, { environmentOpen = false }, {}, {})
    }
}
