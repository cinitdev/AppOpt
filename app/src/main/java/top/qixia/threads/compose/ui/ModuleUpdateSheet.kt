package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdateDownloadViewModel.Stage
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleUpdateSheet(
    update: ModuleUpdater.UpdateInfo?,
    state: ModuleUpdateDownloadViewModel.State,
    onDismiss: () -> Unit,
    onBegin: (ModuleUpdater.UpdateInfo) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit
) {
    if (update == null) return
    val busy = state.stage == Stage.DOWNLOADING || state.stage == Stage.DETECTING_MANAGER ||
        state.stage == Stage.RETAINING_MANUAL
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = OceanSurface,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(OceanOutline, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = PorcelainHeader, shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.SystemUpdateAlt, null, tint = PorcelainAction, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ROOT 模块更新", color = PorcelainOnTonal, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                    Text("更新 QixiaThreads", color = OceanText, fontSize = 23.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(onDismiss) {
                    Icon(Icons.Outlined.Close, "关闭更新详情", tint = OceanTextSecondary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            // 主体只保留一个滚动区域，较长更新说明也不会遮住操作按钮。
            key(update.remoteVersionCode, update.zipUrl) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp).padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    UpdateVersionSummary(update)
                    if (state.stage != Stage.IDLE) UpdateDownloadStatus(state, busy)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(3.dp, 16.dp).background(PorcelainAction, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text("更新内容", color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(16.dp))
                        if (update.changelogLoadFailed) {
                            Text("更新说明暂时未能加载。可稍后重新检查更新，也可继续下载模块。",
                                color = OceanWarning, fontSize = 14.sp, lineHeight = 23.sp)
                        } else if (update.changelogText.isBlank()) {
                            Text("此版本暂未提供更新说明。", color = OceanTextSecondary, fontSize = 14.sp)
                        } else {
                            UpdateChangelog(update.changelogText, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            HorizontalDivider(color = OceanDivider)
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = OceanTextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "关闭弹窗后可在设置中查看进度" else "模块刷入后需重启设备生效",
                        color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(12.dp))
                UpdateSheetActions(state.stage, update, onDismiss, onBegin, onRetry, onCancel, onInstall)
            }
        }
    }
}

@Composable
private fun UpdateVersionSummary(update: ModuleUpdater.UpdateInfo) {
    Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            UpdateVersionLabel("当前版本", update.localVersion, update.localVersionCode, false, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = PorcelainAction,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
            UpdateVersionLabel("新版本", update.remoteVersion, update.remoteVersionCode, true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun UpdateVersionLabel(label: String, version: String, code: Int, highlighted: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = OceanTextSecondary, fontSize = 11.sp)
        Text(if (version.startsWith("v", ignoreCase = true)) version else "v$version",
            color = if (highlighted) PorcelainAction else OceanText, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold)
        Text("版本号 $code", color = OceanTextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun UpdateDownloadStatus(state: ModuleUpdateDownloadViewModel.State, busy: Boolean) {
    val failed = state.stage == Stage.FAILED
    val color = if (failed) OceanError else PorcelainOnTonal
    Surface(color = if (failed) OceanError.copy(alpha = .05f) else OceanSurfaceHigh,
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(17.dp), color = color, strokeWidth = 2.dp)
                else Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null,
                    tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text(state.status.ifBlank { "正在准备模块更新" }, color = color, fontSize = 13.sp,
                    lineHeight = 21.sp, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.stage == Stage.DOWNLOADING || state.stage == Stage.RESUME_READY) {
                state.percent?.let { percent ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.weight(1f).height(5.dp), color = PorcelainAction, trackColor = OceanOutline)
                        Spacer(Modifier.width(10.dp))
                        Text("${percent.coerceIn(0, 100)}%", color = PorcelainOnTonal, fontSize = 12.sp)
                    }
                }
            }
            state.manualPath?.let { path ->
                SelectionContainer {
                    Text(path, color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun UpdateSheetActions(
    stage: Stage, update: ModuleUpdater.UpdateInfo, onDismiss: () -> Unit,
    onBegin: (ModuleUpdater.UpdateInfo) -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit
) {
    val processing = stage == Stage.DOWNLOADING || stage == Stage.DETECTING_MANAGER || stage == Stage.RETAINING_MANUAL
    val label = when (stage) {
        Stage.IDLE -> "下载模块"
        Stage.RESUME_READY -> "继续下载"
        Stage.READY_TO_INSTALL -> "开始刷入"
        Stage.FAILED -> "重试下载"
        Stage.MANUAL_READY, Stage.HANDED_OFF -> "完成"
        else -> "查看后台进度"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stage != Stage.MANUAL_READY && stage != Stage.HANDED_OFF) {
            TextButton(onClick = if (processing) onCancel else onDismiss,
                modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OceanTextSecondary)) {
                Text(if (processing) "取消更新" else "稍后再说", fontSize = 13.sp)
            }
        }
        Button(onClick = {
            when (stage) {
                Stage.IDLE, Stage.RESUME_READY -> onBegin(update)
                Stage.READY_TO_INSTALL -> onInstall()
                Stage.FAILED -> onRetry()
                else -> onDismiss()
            }
        }, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PorcelainAction, contentColor = Color.White)) {
            Icon(when (stage) {
                Stage.READY_TO_INSTALL -> Icons.Outlined.SystemUpdateAlt
                Stage.FAILED -> Icons.Outlined.Refresh
                Stage.MANUAL_READY, Stage.HANDED_OFF -> Icons.Outlined.Check
                else -> Icons.Outlined.CloudDownload
            }, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
