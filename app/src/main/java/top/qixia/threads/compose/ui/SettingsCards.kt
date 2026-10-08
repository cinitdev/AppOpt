package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.SettingsUiState
import top.qixia.threads.compose.SettingsTopologyPresentation
import top.qixia.threads.compose.theme.*

@Composable
internal fun SettingsSectionLabel(title: String) {
    Text(title, color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 3.dp, bottom = 8.dp))
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = OceanSurface, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(.7.dp, OceanOutline.copy(alpha = .8f))) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsAutoHistoryCard(settings: SettingsUiState, onChanged: (Boolean) -> Unit) {
    val enabled = settings.autoHistorySupported && settings.editable && !settings.saving &&
        !settings.autoHistorySaving && !settings.loading
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(11.dp)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.History, null, Modifier.size(20.dp), tint = PorcelainOnTonal)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("记录自动分配历史", color = OceanText, fontSize = 14.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(if (settings.autoHistorySaving) "正在应用更改…" else if (settings.autoHistoryEnabled) "已开启 · 即时生效" else "已关闭 · 默认不记录",
                    color = if (settings.autoHistoryEnabled) PorcelainOnTonal else OceanTextSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = settings.autoHistoryEnabled, onCheckedChange = onChanged, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "记录自动分配历史" })
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = OceanDivider)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text("已开启自动分配的应用，前台使用超过 3 分钟才保存记录，包含本次从开始使用时采集的 FPS、CPU / GPU 与功耗等指标。",
                color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
            Text("关闭后停止采集，已保存记录保留。", color = OceanTextSecondary,
                fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
            val unavailable = when {
                settings.loading -> null
                settings.lockedByPendingUpdate -> "模块更新待重启，暂时无法更改。"
                !settings.hasRoot -> "获取 Root 权限后可更改。"
                !settings.autoHistorySupported -> "自动分配记录配置未就绪，请确认模块已更新并重启。"
                else -> null
            }
            unavailable?.let {
                Text(it, color = OceanWarning, fontSize = 11.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

@Composable
internal fun SettingsEntry(
    title: String, description: String, value: String?, icon: ImageVector,
    enabled: Boolean = true, onClick: () -> Unit
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(21.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (enabled) OceanSurfaceHigh else OceanDivider, shape = RoundedCornerShape(11.dp)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) PorcelainOnTonal else OceanTextSecondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = OceanText, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(value ?: description,
                    color = if (value != null && enabled) PorcelainOnTonal else OceanTextSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                if (!enabled && value != null && description.isNotBlank()) {
                    Text(description, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(if (enabled) Icons.Outlined.ChevronRight else Icons.Outlined.Lock, null,
                Modifier.size(if (enabled) 19.dp else 16.dp), tint = OceanTextSecondary)
        }
    }
}

@Composable
internal fun SettingsCalibrationNote() {
    Surface(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        shape = RoundedCornerShape(13.dp), color = OceanSurfaceHigh.copy(alpha = .65f)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Tune, null, Modifier.padding(top = 1.dp).size(17.dp), tint = PorcelainOnTonal)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("校准后确认分配", color = OceanText, fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium)
                Text("按线程负载与核心能力推荐，确认保存后生效。", color = OceanTextSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTopologyContent(block: String, presentCpus: Set<Int>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val topology = remember(block, presentCpus) {
        SettingsTopologyPresentation.parse(block, presentCpus)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(11.dp)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Memory, null, Modifier.size(20.dp), tint = PorcelainOnTonal)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("核心拓扑", color = OceanText, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    presentCpus.isEmpty() -> "分组待确认 · 尚未读取设备核心"
                    !topology.confirmed -> "${presentCpus.size} 个核心 · 分组待确认"
                    else -> "${presentCpus.size} 个核心 · ${topology.groups.size} 个性能组"
                }, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
            TextButton({ expanded = !expanded }, enabled = topology.fields.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (expanded) "收起" else "详情", fontSize = 12.sp)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!topology.confirmed) {
            Text(if (presentCpus.isEmpty()) "暂无设备拓扑数据" else "CPU ${RuleConfigLogic.formatCpuRangeList(presentCpus)}",
                color = PorcelainOnTonal, fontSize = 12.sp, lineHeight = 18.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                topology.groups.forEachIndexed { index, group ->
                    Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Text("性能组 ${index + 1}",
                                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                            Text("CPU ${RuleConfigLogic.formatCpuRangeList(group.cpus)}", color = PorcelainOnTonal, fontSize = 12.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OceanDivider)
            topology.fields.forEach { (name, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(name.removePrefix("detected_"), Modifier.weight(.4f), color = OceanTextSecondary,
                        fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                    Text(value.ifBlank { "—" }, Modifier.weight(.6f), color = OceanText,
                        fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Text(topology.pendingReason ?: "按核心能力与硬件最高频率分组，同组可包含不同核心架构。", color = OceanTextSecondary, fontSize = 12.sp,
            lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
