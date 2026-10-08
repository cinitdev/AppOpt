package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Rect
import top.qixia.threads.UsageGuide
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun QixiaThreadsApp(
    viewModel: QixiaThreadsViewModel,
    onLaunchApp: (String) -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenUsagePermission: () -> Unit,
    moduleDownloadState: ModuleUpdateDownloadViewModel.State,
    onBeginModuleUpdate: (ModuleUpdater.UpdateInfo) -> Unit,
    onRetryModuleUpdate: () -> Unit,
    onCancelModuleUpdate: () -> Unit,
    onInstallModuleUpdate: () -> Unit,
    startupBlocked: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reviews by viewModel.calibrationReviews.state.collectAsStateWithLifecycle()
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.HOME.name) }
    val destination = MainDestination.fromSavedName(destinationName)
    val applicationPageState = rememberSaveableStateHolder()
    var showHomeEnvironment by rememberSaveable { mutableStateOf(false) }
    var showModuleUpdate by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val guideAnchors = remember { mutableStateMapOf<String, Rect>() }
    var guidePending by rememberSaveable { mutableStateOf(UsageGuide.pendingSteps(context).isNotEmpty()) }
    // 点击时读取状态。局部函数引用在重组前后可能被判定相同，
    // 却仍持有旧导航栏捕获的目标页面。
    val navigate: (MainDestination) -> Unit = remember(viewModel) {
        { next ->
            viewModel.closeAffinityDiagnostics()
            destinationName = next.name
        }
    }
    LaunchedEffect(destination) { viewModel.onDestinationShown(destination) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    BackHandler(enabled = state.ruleEditor == null && destination != MainDestination.HOME) {
        if (destination == MainDestination.HISTORY && state.history.detail != null) viewModel.closeHistoryDetail()
        else navigate(MainDestination.HOME)
    }

    CompositionLocalProvider(LocalGuideAnchors provides guideAnchors) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize().homeEnvironmentBackdrop(showHomeEnvironment),
        containerColor = OceanBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { QixiaThreadsBottomNavigation(destination, navigate) }
    ) { padding ->
        val diagnostic = state.affinityDiagnostics
        if (diagnostic != null) AffinityDiagnosticsScreen(diagnostic, padding,
            viewModel::closeAffinityDiagnostics, viewModel::refreshAffinityDiagnostics)
        else when (destination) {
            MainDestination.HOME -> HomeScreen(
                state, padding, viewModel::refreshDashboard,
                { viewModel.selectApplicationTab(ApplicationTab.LIBRARY); navigate(MainDestination.APPLICATIONS) },
                { showHomeEnvironment = true },
                { viewModel.closeHistoryDetail(); navigate(MainDestination.HISTORY) },
                { app, session -> viewModel.openHistoryRecord(app, session); navigate(MainDestination.HISTORY) },
                reviews.pending.size, { viewModel.calibrationReviews.open() }
            )
            MainDestination.APPLICATIONS -> applicationPageState.SaveableStateProvider("applications") {
                ApplicationsScreen(state, padding, viewModel::refreshDashboard,
                    { showHomeEnvironment = true },
                    viewModel::selectApplicationTab, viewModel::setApplicationQuery, viewModel::setHideMissing,
                    viewModel::addApplication, viewModel::deleteApplication, { onLaunchApp(it.packageName) },
                    viewModel::openRuleEditor, viewModel::setAutoStartCalibration, viewModel::setAutomaticAffinity,
                    viewModel::openAffinityDiagnostics)
            }
            MainDestination.HISTORY -> HistoryScreen(
                state.history, padding, { viewModel.loadHistory(force = true) },
                viewModel::openHistoryRecord, viewModel::closeHistoryDetail, viewModel::toggleHistorySession,
                viewModel::deleteHistoryPackage, viewModel::deleteHistorySession,
                viewModel::exportHistoryPackage, viewModel::exportHistorySession,
                { viewModel.selectApplicationTab(ApplicationTab.LIBRARY); navigate(MainDestination.APPLICATIONS) },
                onDeleteSessions = viewModel::deleteHistorySessions,
                autoHistoryEnabled = state.settings.autoHistoryEnabled,
                onSelectWindow = viewModel::selectHistoryWindow,
                onCompare = viewModel::compareHistory,
                onCloseComparison = viewModel::closeHistoryComparison
            )
            MainDestination.LOGS -> LogsScreen(
                state.logs, padding, { viewModel.loadLogs(force = true) },
                viewModel::selectLogSource, viewModel::selectLogFilter, viewModel::exportVisibleLogs,
                viewModel::selectLogCategory, viewModel::setLogQuery
            )
            MainDestination.SETTINGS -> SettingsScreen(
                state, padding, { viewModel.loadSettings(force = true) },
                viewModel::updatePolicy, viewModel::restoreDefaultPolicy,
                onExportDiagnostics = viewModel::exportDiagnostics,
                onCheckModuleUpdate = viewModel::checkModuleUpdate,
                moduleDownloadState = moduleDownloadState,
                onOpenModuleUpdate = { showModuleUpdate = true },
                onAutoHistoryChange = viewModel::setAutomaticHistory
            )
        }
    }
    if (guidePending && !startupBlocked && !state.refreshing && !state.environment.loading &&
        reviews.active == null && !showHomeEnvironment && state.ruleEditor == null) {
        FirstUseGuide(guideAnchors) { guidePending = false }
    }
    }
    }
    state.ruleEditor?.let { editor ->
        RuleEditorScreen(editor, viewModel::updateRuleDraft, viewModel::closeRuleEditor,
            viewModel::saveRuleEditor, viewModel::recheckRules)
    }
    if (reviews.active != null && !startupBlocked) {
        CalibrationReviewSheet(reviews, viewModel.calibrationReviews::dismiss,
            viewModel.calibrationReviews::select, { viewModel.calibrationReviews.finish() },
            { viewModel.calibrationReviews.finish(discard = true) }, viewModel.calibrationReviews::reloadOriginal,
            viewModel.calibrationReviews::selectProcess)
    }
    if (showHomeEnvironment && reviews.active == null) {
        HomeEnvironmentSheet(state.environment, { showHomeEnvironment = false },
            onOpenOverlayPermission, onOpenUsagePermission)
    }
    ModuleUpdatePromptHost(
        updates = state.updates,
        downloadState = moduleDownloadState,
        open = showModuleUpdate,
        onOpenChange = { showModuleUpdate = it },
        startupAllowed = destination == MainDestination.HOME && !startupBlocked && !guidePending &&
            !state.environment.loading && !state.environment.pendingModuleUpdate &&
            reviews.active == null && state.ruleEditor == null && !showHomeEnvironment,
        onConsumeStartupPrompt = viewModel::dismissModuleUpdatePrompt,
        onBegin = onBeginModuleUpdate,
        onRetry = onRetryModuleUpdate,
        onCancel = onCancelModuleUpdate,
        onInstall = onInstallModuleUpdate
    )
}

@Composable
internal fun QixiaThreadsBottomNavigation(selected: MainDestination, onSelected: (MainDestination) -> Unit) {
    val accent = PorcelainOnTonal
    val muted = OceanTextSecondary
    Surface(color = OceanSurface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = OceanDivider)
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 10.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically) {
                MainDestination.entries.forEach { destination ->
                    val active = selected == destination
                    val icon = when (destination) {
                        MainDestination.HOME -> Icons.Outlined.GridView
                        MainDestination.APPLICATIONS -> Icons.Outlined.Apps
                        MainDestination.HISTORY -> Icons.Outlined.History
                        MainDestination.LOGS -> Icons.Outlined.Description
                        MainDestination.SETTINGS -> Icons.Outlined.Settings
                    }
                    Column(Modifier.weight(1f).guideAnchor(destination.name).selectable(active, role = Role.Tab, onClick = { onSelected(destination) })
                        .heightIn(min = 64.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp, 30.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (active) PorcelainHeader else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(22.dp), tint = if (active) accent else muted)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(destination.label, color = if (active) accent else muted, fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
