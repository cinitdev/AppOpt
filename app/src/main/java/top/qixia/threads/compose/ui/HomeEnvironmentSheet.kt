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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.qixia.threads.R
import top.qixia.threads.AutoStartAccess
import top.qixia.threads.AutoStartStatus
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
    val context = LocalContext.current
    val baseReady = !env.loading && env.featuresAvailable && env.overlayGranted && env.usageAccessGranted &&
        env.foregroundState?.available == true && env.statusMessage == null
    val ready = baseReady && env.autoStartStatus == AutoStartStatus.ALLOWED && !env.autoStartChecking
    val summary = when {
        env.loading -> "正在读取权限与服务状态"
        ready -> "所有必需权限与服务均已准备好"
        baseReady && env.autoStartChecking -> "基础权限与服务已就绪，正在检查应用自启动"
        baseReady && env.autoStartStatus == AutoStartStatus.BLOCKED -> "应用自启动未允许，可能影响后台胶囊拉起"
        baseReady -> "基础权限与服务已就绪，自启动状态请在系统设置中确认"
        env.statusMessage != null -> "状态读取失败，请返回首页下拉重试"
        !env.hasRoot -> stringResource(R.string.root_permission_hint, stringResource(R.string.app_name))
        !env.overlayGranted || !env.usageAccessGranted -> "部分权限尚未授权，点击“去授权”完成设置"
        env.pendingModuleUpdate -> "模块已更新，重启设备后生效"
        !env.moduleCompatible -> "模块尚未就绪，请检查模块安装状态"
        !env.daemonRuntime.running -> "Rust 守护进程尚未运行"
        else -> "前台监听尚未连接，请返回首页下拉重试"
    }
    val neutral = env.loading || (baseReady && env.autoStartStatus != AutoStartStatus.BLOCKED)
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
                    Text("${stringResource(R.string.qixia_page_eyebrow)} · RUNTIME STATUS", color = OceanTextSecondary, fontSize = 9.sp,
                        lineHeight = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(if (env.loading) "正在检查运行环境。" else if (ready) "一切就绪，安心运行。"
                        else if (neutral) "基础环境已就绪。" else "还有几项，需要准备。",
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
            EnvironmentStatusRow(R.drawable.ic_mint_window, "悬浮窗", "在游戏中显示帧率与校准控制",
                env.overlayGranted, env.loading, "已授权", "去授权", onOpenOverlayPermission)
            EnvironmentStatusRow(R.drawable.ic_mint_activity, "使用情况访问", "辅助判断目标应用前台状态",
                env.usageAccessGranted, env.loading, "已授权", "去授权", onOpenUsagePermission)
            EnvironmentStatusRow(R.drawable.ic_mint_activity, "应用自启动", when (env.autoStartStatus) {
                AutoStartStatus.ALLOWED -> "系统已允许，支持后台拉起性能胶囊"
                AutoStartStatus.BLOCKED -> "允许柒夏线程在后台启动悬浮服务"
                AutoStartStatus.UNKNOWN -> "系统状态无法可靠读取，点此手动确认"
            }, env.autoStartStatus == AutoStartStatus.ALLOWED, env.loading || env.autoStartChecking,
                "已允许", if (env.autoStartStatus == AutoStartStatus.BLOCKED) "去设置" else "待确认",
                { AutoStartAccess.openSettings(context) })
            EnvironmentStatusRow(R.drawable.ic_mint_key, "Root", "执行线程规则与读取模块状态",
                env.hasRoot, env.loading, "可用", "未授权")
            val version = (env.daemonRuntime.versionName ?: env.moduleVersion?.versionName)?.removePrefix("v")
            EnvironmentStatusRow(R.drawable.ic_mint_cpu, "Rust 守护进程", "QiXiaRs${version?.let { " · $it" }.orEmpty()}",
                env.featuresAvailable, env.loading, "运行中", when {
                    env.pendingModuleUpdate -> "待重启"
                    !env.moduleCompatible -> "未就绪"
                    else -> "未运行"
                })
            EnvironmentStatusRow(R.drawable.ic_mint_focus, "前台监听", "ActivityTaskManager 助手",
                env.foregroundState?.available == true, env.loading, "已连接", "未连接")
            Text("返回 App 后自动更新状态；自启动“待确认”不代表未授权。",
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp),
                color = MintMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
