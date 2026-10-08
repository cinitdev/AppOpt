package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.R
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.SessionSummary
import java.util.Locale

@Composable
internal fun HomeRecentRecordCard(
    home: HomeUiState,
    onManageApps: () -> Unit,
    onReport: (HistoryPackageModel, SessionSummary) -> Unit,
    ruleCount: Int? = null
) {
    val latest = home.records.firstOrNull()
    Surface(color = Color.White, shape = RoundedCornerShape(25.dp),
        border = BorderStroke(.65.dp, MintBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(OceanPrimary, Modifier.size(5.dp))
                Spacer(Modifier.width(7.dp))
                Text("最近记录", Modifier.weight(1f), fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, color = MintMuted)
                latest?.report?.let { report ->
                    TextButton(onClick = { onReport(latest.app, report) }) {
                        Text("查看报告", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(3.dp))
                        HomeLineIcon(R.drawable.ic_mint_chevron, null, 14.dp, OceanPrimary)
                    }
                }
            }
            if (latest == null) {
                Column(Modifier.fillMaxWidth().heightIn(min = 192.dp).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    HomeLineIcon(R.drawable.ic_mint_activity, null, 32.dp, PorcelainOnTonal)
                    Spacer(Modifier.height(15.dp))
                    Text(when {
                        home.error != null -> "暂时无法读取最近记录"
                        home.loading -> "正在整理最近记录"
                        else -> "从一次流畅运行开始"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MintText)
                    Spacer(Modifier.height(8.dp))
                    Text(home.error ?: "单次运行超过 3 分钟后显示记录",
                        color = MintMuted, fontSize = 11.sp, lineHeight = 18.sp)
                    if (!home.loading && home.error == null) {
                        TextButton(onClick = onManageApps) { Text("管理应用", fontSize = 12.sp) }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppDrawableIcon(latest.app.icon, latest.app.label,
                        Modifier.size(40.dp).clip(CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(latest.app.label, color = MintText, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text("${homeDate(latest.endedAtMs, "MM/dd HH:mm")} · ${homeModeLabel(latest.mode)}",
                            color = MintMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(color = PorcelainHeader, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (latest.report != null) {
                                HomeLineIcon(R.drawable.ic_mint_check, null, 12.dp, PorcelainOnTonal)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (latest.report != null) "已完成" else "帧率摘要", color = PorcelainOnTonal, fontSize = 10.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(homeFpsText(latest.fps?.average), color = MintPrimary,
                        fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-2).sp,
                        fontWeight = FontWeight.ExtraBold)
                    Column(Modifier.padding(start = 10.dp, top = 8.dp)) {
                        Text("FPS", color = PorcelainOnTonal, fontSize = 14.sp)
                        Text(if (latest.fps == null) "暂无有效帧率" else "平均帧率",
                            color = MintMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.padding(start = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(OceanPrimary, Modifier.size(4.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("上次运行", color = MintMuted, fontSize = 10.sp)
                    }
                }
                if (latest.report != null) {
                    HomeRecordFpsChart(latest.fps, latest.startedAtMs, latest.endedAtMs)
                } else {
                    Text("仅保留最近帧率摘要", Modifier.padding(top = 4.dp, bottom = 14.dp),
                        color = MintMuted, fontSize = 11.sp)
                }
                HorizontalDivider(color = MintBorder, thickness = .5.dp)
                Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 6.dp)) {
                    HomeRecordStat("峰值帧率", homeFpsText(latest.fps?.peak), "FPS", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(38.dp), color = MintBorder, thickness = .5.dp)
                    if (latest.report != null) {
                        HomeRecordStat("采样线程", latest.report.threadCount.toString(), "个",
                            Modifier.weight(1f).padding(start = 12.dp))
                    } else {
                        HomeRecordStat("本次时长", homeDuration(latest.endedAtMs - latest.startedAtMs), "",
                            Modifier.weight(1f).padding(start = 12.dp))
                    }
                    VerticalDivider(Modifier.height(38.dp), color = MintBorder, thickness = .5.dp)
                    if (latest.mode == HomeRecordMode.AUTOMATIC && latest.report == null) {
                        HomeRecordStat("历史报告", "未生成", "", Modifier.weight(1f).padding(start = 12.dp))
                    } else if (latest.mode == HomeRecordMode.AUTOMATIC) {
                        HomeRecordStat("本次时长", homeDuration(latest.endedAtMs - latest.startedAtMs), "",
                            Modifier.weight(1f).padding(start = 12.dp))
                    } else {
                        HomeRecordStat("当前规则", ruleCount?.toString() ?: "—", "条",
                            Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
                home.error?.let { Text(it, color = OceanWarning, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun HomeRecordStat(label: String, value: String, unit: String, modifier: Modifier) {
        Column(modifier) {
            Text(label, color = MintMuted, fontSize = 11.sp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = MintText, fontSize = if (unit.isEmpty()) 12.sp else 19.sp,
                    fontWeight = FontWeight.SemiBold)
                if (unit.isNotEmpty()) Text(" $unit", color = MintMuted, fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
        }
}

@Composable
internal fun HomeRecordModeBadge(mode: HomeRecordMode) {
    Surface(color = PorcelainHeader, shape = RoundedCornerShape(8.dp)) {
        Text(homeModeLabel(mode), Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = PorcelainOnTonal, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun homeModeLabel(mode: HomeRecordMode): String = when (mode) {
    HomeRecordMode.CALIBRATION -> "校准"
    HomeRecordMode.AUTOMATIC -> "自动分配"
    HomeRecordMode.RULES -> "线程规则"
}

internal fun homeFpsText(value: Float?): String = value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"

internal fun homeDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0)
    return when {
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }
}
