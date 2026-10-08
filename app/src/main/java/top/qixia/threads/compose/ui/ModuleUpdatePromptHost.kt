package top.qixia.threads.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdateDownloadViewModel.Stage
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.compose.UpdateUiState

/** 启动提醒与设置入口共用弹窗，显示详情不会发起下载或刷入。 */
@Composable
internal fun ModuleUpdatePromptHost(
    updates: UpdateUiState,
    downloadState: ModuleUpdateDownloadViewModel.State,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    startupAllowed: Boolean,
    onConsumeStartupPrompt: () -> Unit,
    onBegin: (ModuleUpdater.UpdateInfo) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val available = (updates.moduleResult as? ModuleUpdater.CheckResult.UpdateAvailable)?.update
    val handedOff = downloadState.stage == Stage.HANDED_OFF
    LaunchedEffect(updates.startupPromptPending, startupAllowed, lifecycleState,
        downloadState.hasSession, handedOff, available) {
        if (updates.startupPromptPending) {
            if (downloadState.hasSession || handedOff) {
                // 已有用户发起的任务时不再用启动提醒打断，设置仍可查看该任务。
                onConsumeStartupPrompt()
            } else if (startupAllowed && lifecycleState == Lifecycle.State.RESUMED && available != null) {
                onConsumeStartupPrompt()
                onOpenChange(true)
            }
        }
    }
    LaunchedEffect(handedOff) {
        if (handedOff) onOpenChange(false)
    }
    if (open && !handedOff) {
        ModuleUpdateSheet(
            update = downloadState.update.takeIf { downloadState.hasSession } ?: available,
            state = downloadState.takeIf { it.hasSession } ?: ModuleUpdateDownloadViewModel.State(),
            onDismiss = {
                onConsumeStartupPrompt()
                onOpenChange(false)
            },
            onBegin = onBegin,
            onRetry = onRetry,
            onCancel = onCancel,
            onInstall = onInstall
        )
    }
}
