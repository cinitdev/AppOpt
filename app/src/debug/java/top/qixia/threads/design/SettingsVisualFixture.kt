package top.qixia.threads.design

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import top.qixia.threads.CalibPolicy
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.compose.ui.*

/** 设置与扩展的界面预览；所有开关只更新内存，不读写 Root 配置。 */
@Composable
internal fun SettingsVisualFixture() {
    val destination = MainDestination.SETTINGS
    var showRefresh by remember { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    var settings by remember { mutableStateOf(SettingsUiState(
        loading = false, hasRoot = true, readSuccess = true, cpusetSupported = true,
        autoHistorySupported = true, presentCpus = (0..7).toSet(),
        policy = CalibPolicy.DEFAULT.copy(detectedTopologyBlock = """
            detected_complete=1
            detected_clusters=3
            detected_all=0-7
            detected_group_0=0-3
            detected_group_1=4-6
            detected_group_2=7
            detected_freq_complete=1
            detected_freq_domains=3
            detected_freq_domain_0=0-3
            detected_freq_domain_1=4-6
            detected_freq_domain_2=7
        """.trimIndent())
    )) }
    val updates = UpdateUiState(moduleResult = ModuleUpdater.CheckResult.NoUpdate("已是最新版本",
        "v2.0.6", 206, "v2.0.6", 206))
    var refresh by remember { mutableStateOf(RefreshRateUiState(loading = false, available = true,
        rates = listOf(60f, 120f))) }
    QixiaThreadsTheme {
        Scaffold(containerColor = OceanBackground, bottomBar = {
            QixiaThreadsBottomNavigation(destination) {
                if (it == MainDestination.SETTINGS) {
                    showRefresh = false
                }
            }
        }) { padding ->
            if (showRefresh) RefreshRateScreen(refresh, padding, null, { showRefresh = false },
                { key, value -> refresh = refresh.copy(policy = refresh.policy.with(key, value)) }, {})
            else pages.SaveableStateProvider("settings") {
                SettingsScreen(QixiaThreadsUiState(settings = settings, updates = updates), padding,
                    onRefresh = {}, onPolicyChange = { change -> settings = settings.copy(policy = change(settings.policy)) },
                    onRestorePolicy = { settings = settings.copy(policy = CalibPolicy.DEFAULT.copy(detectedTopologyBlock = settings.policy.detectedTopologyBlock)) },
                    onExportDiagnostics = {}, onCheckModuleUpdate = {},
                    moduleDownloadState = ModuleUpdateDownloadViewModel.State(), onOpenModuleUpdate = {},
                    onAutoHistoryChange = { settings = settings.copy(autoHistoryEnabled = it) },
                    onRefreshRate = { showRefresh = true })
            }
        }
    }
}
