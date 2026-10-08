package top.qixia.threads.compose.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.R
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: QixiaThreadsUiState, padding: PaddingValues,
    onRefresh: () -> Unit, onManageApps: () -> Unit,
    onEnvironment: () -> Unit, onHistory: () -> Unit,
    onReport: (HistoryPackageModel, SessionSummary) -> Unit,
    pendingReviews: Int = 0, onReview: () -> Unit = {}
) = MintHomeTheme {
    PullToRefreshBox(state.refreshing || state.home.loading, onRefresh,
        Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(OceanBackground, ClearTechBackgroundEnd))).padding(padding)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = pageHeight).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Spacer(Modifier.height(13.dp))
                    HomeHeading()
                    Spacer(Modifier.height(16.dp))
                    if (state.environment.pendingModuleUpdate) {
                        HomePendingModuleCard(onEnvironment)
                        Spacer(Modifier.height(12.dp))
                    }
                    HomeSchedulingCard(state, onManageApps)
                    Spacer(Modifier.height(12.dp))
                    HomeEnvironmentCard(state.environment, onEnvironment)
                    if (pendingReviews > 0) {
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onReview, Modifier.fillMaxWidth()) { Text("$pendingReviews 个应用待确认 · 查看线程分配") }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("设备概览")
                    Spacer(Modifier.height(8.dp))
                    DeviceOverview(state.deviceMetrics)
                    Spacer(Modifier.height(16.dp))
                    HomeRecentRecordCard(state.home, onManageApps, onReport,
                        state.applications.configured.firstOrNull {
                            it.packageName == state.home.records.firstOrNull()?.app?.packageName
                        }?.ruleCount)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("最近使用", Modifier.weight(1f), color = MintText,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        HomeTextLink("历史报告", onHistory)
                    }
                    Spacer(Modifier.height(10.dp))
                    HomeRecentUsageCard(state.home, onReport)
                }
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qixia_page_eyebrow), color = OceanTextSecondary, fontSize = 8.sp,
                        lineHeight = 12.sp, letterSpacing = 1.3.sp)
                    Spacer(Modifier.weight(1f))
                    Text("从容调度 · 看见运行表现", color = OceanTextSecondary, fontSize = 8.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun HomeHeading() {
    Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HomeLineIcon(R.drawable.ic_mint_cpu, null, 19.dp, PorcelainOnTonal)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), color = PorcelainOnTonal, fontSize = 16.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("PERFORMANCE CENTER", color = OceanTextSecondary, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.3.sp)
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("每一帧，都值得流畅。", color = MintText, fontSize = 26.sp, lineHeight = 33.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-.6).sp)
            Spacer(Modifier.height(5.dp))
            Text("线程优化 · 从容掌控每一次运行", color = MintMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun HomePendingModuleCard(onDetails: () -> Unit) {
    val titleColor = Color(0xFF754100)
    val bodyColor = Color(0xFF79552A)
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = Color(0xFFFFF3DC),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFE9C58A))
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFFFE3AF), shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.RestartAlt, null, Modifier.size(23.dp), tint = titleColor)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("模块更新待重启", color = titleColor, fontSize = 18.sp,
                    lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("新模块尚未生效。请在方便时重启设备，校准与配置写入将在重启后恢复。",
                color = bodyColor, fontSize = 14.sp, lineHeight = 22.sp)
            TextButton(onClick = onDetails, modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(contentColor = titleColor)) {
                Text("查看详情", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                HomeLineIcon(R.drawable.ic_mint_chevron, null, 15.dp, titleColor)
            }
        }
    }
}

@Composable
private fun HomeEnvironmentCard(env: EnvironmentUiState, onClick: () -> Unit) {
    val count = listOf(env.overlayGranted, env.usageAccessGranted, env.hasRoot,
        env.daemonRuntime.running, env.foregroundState?.available == true).count { it }
    val ready = env.featuresAvailable && count == 5 && env.statusMessage == null
    val color = if (env.loading || ready) PorcelainOnTonal else OceanWarning
    Surface(onClick, color = OceanSurfaceHigh, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(.6.dp, MintBorder)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = PorcelainHeader, shape = RoundedCornerShape(12.dp)) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    HomeLineIcon(R.drawable.ic_mint_shield, null, 19.dp, color)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(when {
                    env.loading -> "正在检查权限与服务"
                    env.pendingModuleUpdate -> "模块等待重启生效"
                    ready -> "权限与服务已就绪"
                    else -> "权限与服务待检查"
                },
                    color = color, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(when {
                    env.loading -> "读取权限与守护状态"
                    env.pendingModuleUpdate -> "校准与配置写入暂不可用 · 点击查看详情"
                    ready -> "5 项检查通过 · 点击查看详情"
                    else -> "$count / 5 项已就绪 · 点击检查详情"
                },
                    color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Row(Modifier.width(29.dp).height(27.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(15, 20, 24, 19, 13).forEachIndexed { index, height ->
                    Box(Modifier.width(3.dp).height(height.dp).clip(CircleShape)
                        .background(if (index == 2) OceanPrimary else OceanPrimary.copy(alpha = .4f)))
                }
            }
            Spacer(Modifier.width(12.dp))
            HomeLineIcon(R.drawable.ic_mint_chevron, null, 14.dp, PorcelainOnTonal)
        }
    }
}

@Composable
private fun HomeRecentUsageCard(home: HomeUiState, onReport: (HistoryPackageModel, SessionSummary) -> Unit) {
    // 最新应用已显示在大卡片中，这个列表保持紧凑并提供其他有效内容。
    val records = home.records.drop(1).take(3)
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(.6.dp, MintBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (records.isEmpty()) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomeLineIcon(R.drawable.ic_mint_history, null, 22.dp, PorcelainOnTonal)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (home.loading) "正在整理使用摘要" else "其他最近使用的应用会出现在这里",
                            color = MintText, fontSize = 12.sp, lineHeight = 18.sp)
                        Text("仅展示最近表现，不额外生成历史报告", color = MintMuted, fontSize = 10.sp, lineHeight = 18.sp)
                    }
                }
            } else records.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider(color = MintBorder, thickness = .5.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppDrawableIcon(record.app.icon, record.app.label,
                        Modifier.size(35.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(record.app.label, color = MintText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text("${homeModeLabel(record.mode)} · ${homeDate(record.endedAtMs, "MM/dd HH:mm")}",
                            color = MintMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(record.fps?.average?.let { "${homeFpsText(it)} FPS" } ?: "帧率待采集",
                            color = PorcelainOnTonal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        record.report?.let { report ->
                            HomeTextLink("报告") { onReport(record.app, report) }
                        } ?: Text(homeDuration(record.endedAtMs - record.startedAtMs),
                            Modifier.padding(top = 5.dp), color = MintMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeLineIcon(@DrawableRes icon: Int, description: String?, size: Dp, color: Color) =
    Icon(painterResource(icon), description, Modifier.size(size), tint = color)

@Composable
internal fun HomeTextLink(label: String, onClick: () -> Unit) {
    Row(Modifier.height(26.dp).clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MintAction, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.width(4.dp))
        HomeLineIcon(R.drawable.ic_mint_chevron, null, 13.dp, MintAction)
    }
}

internal fun homeDate(time: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
