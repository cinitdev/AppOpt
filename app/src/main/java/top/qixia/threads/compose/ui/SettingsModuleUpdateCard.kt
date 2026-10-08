package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdateDownloadViewModel.Stage
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.compose.EnvironmentUiState
import top.qixia.threads.compose.ModuleUpdateCardState
import top.qixia.threads.compose.ModuleUpdateTone
import top.qixia.threads.compose.UpdateUiState
import top.qixia.threads.compose.moduleUpdateCardState
import top.qixia.threads.compose.theme.*

@Composable
internal fun SettingsModuleUpdateCard(
    environment: EnvironmentUiState,
    updates: UpdateUiState,
    download: ModuleUpdateDownloadViewModel.State,
    onCheck: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    val content = moduleUpdateCardState(environment, updates, download)
    val statusColor = when (content.tone) {
        ModuleUpdateTone.INFO -> PorcelainAction
        ModuleUpdateTone.SUCCESS -> OceanSuccess
        ModuleUpdateTone.WARNING -> OceanWarning
        ModuleUpdateTone.ERROR -> OceanError
    }
    val showDetails = !environment.pendingModuleUpdate &&
        (download.hasSession || updates.moduleResult is ModuleUpdater.CheckResult.UpdateAvailable)
    val checking = updates.checkingModule && !download.hasSession && !environment.pendingModuleUpdate
    val downloading = download.hasSession && download.stage == Stage.DOWNLOADING && !environment.pendingModuleUpdate
    val badge = when {
        environment.pendingModuleUpdate -> "待重启"
        download.hasSession -> when (download.stage) {
            Stage.DOWNLOADING -> "下载中"
            Stage.FAILED -> "更新中断"
            Stage.READY_TO_INSTALL, Stage.MANUAL_READY -> "待安装"
            Stage.RESUME_READY -> "待继续"
            else -> "更新处理中"
        }
        checking -> "正在检查"
        updates.moduleResult is ModuleUpdater.CheckResult.UpdateAvailable -> "有新版本"
        content.tone == ModuleUpdateTone.ERROR -> "检查失败"
        content.tone == ModuleUpdateTone.WARNING -> "待重启"
        content.tone == ModuleUpdateTone.SUCCESS -> "已是最新"
        else -> "待检查"
    }
    // 普通版本状态由徽标说明，需要操作的提示则完整显示。
    val showStatus = environment.pendingModuleUpdate || download.hasSession ||
        content.tone == ModuleUpdateTone.ERROR || content.tone == ModuleUpdateTone.WARNING ||
        (updates.moduleResult is ModuleUpdater.CheckResult.NoUpdate && content.tone != ModuleUpdateTone.SUCCESS)
    val fontScale = LocalDensity.current.fontScale
    SettingsCard {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 18.dp)) {
            val stackActions = maxWidth / fontScale < 280.dp || fontScale > 1.2f
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模块更新", color = OceanText, fontSize = 16.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Surface(color = if (content.tone == ModuleUpdateTone.INFO) OceanSurfaceHigh
                        else statusColor.copy(alpha = .08f), shape = RoundedCornerShape(7.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (checking) {
                                CircularProgressIndicator(Modifier.size(11.dp), color = statusColor, strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(badge, color = statusColor, fontSize = 11.sp, lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(21.dp))
                if (stackActions || (content.remoteVersionName?.length ?: 0) > 8) {
                    UpdateVersionHeading(content)
                    if (showDetails) {
                        Spacer(Modifier.height(14.dp))
                        UpdateDetailsAction(download.hasSession, onOpenUpdate)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        UpdateVersionHeading(content, Modifier.weight(1f))
                        if (showDetails) {
                            Spacer(Modifier.width(12.dp))
                            UpdateDetailsAction(download.hasSession, onOpenUpdate)
                        }
                    }
                }
                if (showStatus) {
                    Spacer(Modifier.height(16.dp))
                    Text(content.status, color = statusColor, fontSize = 12.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (downloading) {
                    Spacer(Modifier.height(10.dp))
                    val progressModifier = Modifier.fillMaxWidth().height(4.dp)
                    if (download.percent != null) LinearProgressIndicator(
                        progress = { download.percent.coerceIn(0, 100) / 100f },
                        modifier = progressModifier, color = PorcelainAction, trackColor = OceanOutline
                    ) else LinearProgressIndicator(
                        modifier = progressModifier, color = PorcelainAction, trackColor = OceanOutline
                    )
                }
                Spacer(Modifier.height(19.dp))
                HorizontalDivider(color = OceanOutline.copy(alpha = .65f))
                Spacer(Modifier.height(4.dp))
                if (stackActions) {
                    Text("当前版本 ${content.localVersion}", color = OceanTextSecondary,
                        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
                    UpdateCheckAction(updates, download, onCheck, Modifier.align(Alignment.End))
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前版本 ${content.localVersion}", color = OceanTextSecondary,
                            fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        UpdateCheckAction(updates, download, onCheck)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateVersionHeading(content: ModuleUpdateCardState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(content.remoteVersionName ?: "云端版本", color = OceanText,
            fontSize = if (content.remoteVersionName != null) 34.sp else 24.sp,
            lineHeight = 41.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
        Text(content.remoteVersionCode?.let { "构建 $it · QixiaThreads" }
            ?: if (content.remoteVersionName != null) "QixiaThreads Root 模块" else content.remoteVersion,
            color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun UpdateDetailsAction(hasSession: Boolean, onOpenUpdate: () -> Unit) {
    Button(onOpenUpdate, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PorcelainAction, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(if (hasSession) "查看进度" else "查看更新", fontSize = 13.sp,
            lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(7.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
    }
}

@Composable
private fun UpdateCheckAction(
    updates: UpdateUiState,
    download: ModuleUpdateDownloadViewModel.State,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onCheck, enabled = !updates.checkingModule && !download.hasSession,
        modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = PorcelainAction,
            disabledContentColor = OceanTextSecondary),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
        Text(if (updates.checkingModule) "检查中" else "重新检查", fontSize = 12.sp, lineHeight = 18.sp)
    }
}
