package top.qixia.threads.compose.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.qixia.threads.R
import top.qixia.threads.AutoStartStatus
import top.qixia.threads.AutoStartAccess
import androidx.compose.ui.platform.LocalContext
import top.qixia.threads.compose.EnvironmentUiState
import top.qixia.threads.compose.theme.*

/** 只模糊下层应用画面，不截取位图，也不持续执行后台工作。 */
@Composable
internal fun Modifier.homeEnvironmentBackdrop(visible: Boolean): Modifier {
    val radius by animateDpAsState(if (visible) 8.dp else 0.dp, tween(220), label = "environment blur")
    return graphicsLayer {
        renderEffect = if (radius > 0.dp) BlurEffect(radius.toPx(), radius.toPx(), TileMode.Clamp) else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeEnvironmentSheet(
    env: EnvironmentUiState,
    onDismiss: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenUsagePermission: () -> Unit
) = MintHomeTheme {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ready = !env.loading && env.featuresAvailable && env.overlayGranted && env.usageAccessGranted &&
        env.foregroundState?.connected == true && env.statusMessage == null
    val autoStart = env.autoStart
    val context = LocalContext.current
    val summary = when {
        env.loading -> "正在读取权限与服务状态"
        ready && autoStart.status == AutoStartStatus.BLOCKED -> "基础功能可用，建议允许应用自启动"
        ready -> "基础权限与服务正常，可以开始使用"
        env.statusMessage != null -> "状态读取失败，请返回首页下拉重试"
        !env.hasRoot -> stringResource(R.string.root_permission_hint, stringResource(R.string.app_name))
        !env.overlayGranted || !env.usageAccessGranted -> "部分权限尚未授权，点击“去授权”完成设置"
        env.pendingModuleUpdate -> "模块已更新，重启设备后生效"
        !env.moduleCompatible -> "模块尚未就绪，请检查模块安装状态"
        !env.daemonRuntime.running -> "Rust 守护进程尚未运行"
        env.foregroundState?.recovering == true -> "系统任务接口短暂不可用，前台监听正在自动恢复"
        else -> "前台监听尚未连接，请返回首页下拉重试"
    }
    val neutral = env.loading || ready
    val accent = if (neutral) MintAction else OceanWarning
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = OceanSurface,
        contentColor = MintText,
        tonalElevation = 0.dp,
        scrimColor = OceanText.copy(alpha = .22f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 26.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(OceanOutline))
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 21.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("权限与后台服务", color = OceanTextSecondary, fontSize = 11.sp,
                        lineHeight = 16.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("运行环境",
                        fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.5).sp)
                }
                Box(Modifier.offset(x = 7.dp, y = (-5).dp).size(44.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "关闭运行环境", onClick = {
                        scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
                    }), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(PorcelainHeader),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.rotate(45f)) {
                            HomeLineIcon(R.drawable.ic_mint_plus, "关闭运行环境", 20.dp, PorcelainOnTonal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = if (neutral) OceanSurfaceHigh else Color(0xFFF7F1E7),
                shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    HomeLineIcon(R.drawable.ic_mint_shield, null, 17.dp, accent)
                    Spacer(Modifier.width(14.dp))
                    Text(summary, color = accent, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            EnvironmentStatusRow(R.drawable.ic_mint_window, "悬浮窗权限", "允许在其他应用上层显示悬浮胶囊",
                env.overlayGranted, env.loading, "已授权", "去授权", onOpenOverlayPermission)
            EnvironmentStatusRow(R.drawable.ic_mint_activity, "使用情况访问", "辅助识别当前正在使用的应用",
                env.usageAccessGranted, env.loading, "已授权", "去授权", onOpenUsagePermission)
            EnvironmentStatusRow(R.drawable.ic_mint_start, "应用自启动", autoStart.description,
                autoStart.status in setOf(AutoStartStatus.ALLOWED, AutoStartStatus.NOT_REQUIRED),
                autoStart.status == AutoStartStatus.CHECKING, autoStart.label, autoStart.label,
                onPermission = { AutoStartAccess.openSettings(context, autoStart) },
                neutral = autoStart.status in setOf(AutoStartStatus.UNREADABLE, AutoStartStatus.UNSUPPORTED, AutoStartStatus.NOT_REQUIRED))
            EnvironmentStatusRow(R.drawable.ic_mint_key, "Root", "执行线程规则与读取模块状态",
                env.hasRoot, env.loading, "可用", "未授权")
            val version = (env.daemonRuntime.versionName ?: env.moduleVersion?.versionName)?.removePrefix("v")
            EnvironmentStatusRow(R.drawable.ic_mint_cpu, "Rust 守护进程", "QiXiaRs${version?.let { " · $it" }.orEmpty()}",
                env.featuresAvailable, env.loading, "运行中", when {
                    env.pendingModuleUpdate -> "待重启"
                    !env.moduleCompatible -> "未就绪"
                    else -> "未运行"
                })
            val foreground = env.foregroundState
            EnvironmentStatusRow(R.drawable.ic_mint_focus, "前台监听", when {
                foreground?.recovering == true -> "暂时无法获取当前应用，正在自动重试"
                foreground?.connected == true && foreground.status == "empty" -> "助手已连接，当前暂无前台任务"
                else -> "识别应用切换，配合线程分配与性能记录"
            }, foreground?.connected == true, env.loading, "已连接",
                if (foreground?.recovering == true) "恢复中" else "未连接",
                neutral = foreground?.recovering == true, divider = false)
            Text("返回 App 后自动刷新状态，检测不会修改系统权限。",
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
                color = MintMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
