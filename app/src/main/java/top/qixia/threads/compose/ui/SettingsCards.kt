package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
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
internal fun SettingsSectionLabel(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), color = OceanTextSecondary, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
        if (detail != null) Text(detail, color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = OceanSurface, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(.7.dp, OceanOutline.copy(alpha = .8f))) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsGroup(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SettingsSectionLabel(title, detail)
        SettingsCard(content)
    }
}

@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp), color = OceanDivider)
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, enabled: Boolean = true) {
    Surface(color = if (enabled) OceanSurfaceHigh else OceanDivider, shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (enabled) PorcelainOnTonal else OceanTextSecondary)
        }
    }
}

@Composable
internal fun SettingsAutoHistoryCard(settings: SettingsUiState, onChanged: (Boolean) -> Unit) {
    val enabled = settings.autoHistorySupported && settings.editable && !settings.saving &&
        !settings.autoHistorySaving && !settings.loading
    SettingsCard {
        Row(Modifier.fillMaxWidth().toggleable(settings.autoHistoryEnabled, enabled = enabled,
            role = Role.Switch, onValueChange = onChanged)
            .semantics { contentDescription = "记录自动分配历史" }
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsRowIcon(Icons.Outlined.History, enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("记录自动分配历史", color = OceanText, fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(if (settings.autoHistorySaving) "正在保存…" else if (settings.autoHistoryEnabled) "已开启" else "已关闭 · 默认不记录",
                    color = if (settings.autoHistoryEnabled) PorcelainOnTonal else OceanTextSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = settings.autoHistoryEnabled, onCheckedChange = null, enabled = enabled)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 15.dp)) {
            Surface(Modifier.fillMaxWidth(), color = OceanSurfaceHigh, shape = RoundedCornerShape(12.dp)) {
                Text("自动分配应用前台使用超过 3 分钟后保存 FPS、负载和功耗等数据，历史保留 7 天。关闭不会删除已有记录。",
                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingsRowIcon(icon, enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = OceanText, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
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
internal fun SettingsJoyoseToggle(settings: SettingsUiState, onChanged: (Boolean) -> Unit) {
    val state = settings.joyose
    val enabled = settings.editable && !settings.loading && !settings.saving && !settings.autoHistorySaving
    Row(Modifier.fillMaxWidth().toggleable(state.disabled, enabled = enabled,
        role = Role.Switch, onValueChange = onChanged).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingsRowIcon(Icons.Outlined.Tune, enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("禁用 Joyose SmartOpService", color = OceanText, fontSize = 15.sp,
                lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            val status = if (state.disabled) "实际状态：已禁用" else "实际状态：已启用"
            val mismatch = state.requestedDisabled != null && state.requestedDisabled != state.disabled
            Text(if (settings.saving) "正在应用…" else if (mismatch) "$status · 配置尚未生效" else status,
                color = if (mismatch) OceanWarning else OceanTextSecondary,
                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            Text("停用小米的智能游戏优化服务，减少其对游戏性能策略的干预。仅禁用此服务，Joyose 其他组件保持原状。",
                color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp))
            Text("可能影响部分系统游戏优化。关闭开关即可恢复；卸载模块恢复接管前状态。",
                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = state.disabled, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTopologyContent(block: String, presentCpus: Set<Int>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
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
                if (presentCpus.isNotEmpty()) {
                    Text(if (topology.domainsConfirmed) "${topology.domains.size} 个独立调频域" else "调频域待确认",
                        color = PorcelainOnTonal, fontSize = 12.sp, lineHeight = 18.sp)
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topology.groups.forEachIndexed { index, group ->
                    Surface(Modifier.fillMaxWidth(), color = OceanSurfaceHigh, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("性能组 ${index + 1}", color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                            Text("CPU ${RuleConfigLogic.formatCpuRangeList(group.cpus)}", color = PorcelainOnTonal, fontSize = 12.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                            if (topology.domainsConfirmed) {
                                topology.domains.forEachIndexed { domainIndex, domain ->
                                    if (domain.cpus.any { it in group.cpus }) {
                                        val crossGroup = if (domain.cpus.any { it !in group.cpus }) "（跨性能组共享）" else ""
                                        Text("调频域 ${domainIndex + 1} · CPU ${RuleConfigLogic.formatCpuRangeList(domain.cpus)}$crossGroup",
                                            color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                                            modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OceanDivider)
            Text("独立调频范围", color = OceanText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (topology.domainsConfirmed) {
                topology.domains.forEachIndexed { index, domain ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("调频域 ${index + 1}", Modifier.weight(1f), color = OceanTextSecondary, fontSize = 12.sp)
                        Text("CPU ${RuleConfigLogic.formatCpuRangeList(domain.cpus)}", Modifier.weight(1f),
                            color = PorcelainOnTonal, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Text(topology.domainPendingReason.orEmpty(), color = OceanTextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Text("性能组表示能力相近的核心；调频域表示内核可以独立调节频率的核心范围。同一性能组可以包含多个调频域。",
                color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { rawExpanded = !rawExpanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (rawExpanded) "收起诊断字段" else "查看诊断字段", fontSize = 12.sp)
            }
            if (rawExpanded) {
                Text("low / main / high 等是内部范围别名，可能重复，不代表额外硬件分组。",
                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                topology.fields.forEach { (name, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(name.removePrefix("detected_"), Modifier.weight(.55f), color = OceanTextSecondary,
                            fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                        Text(value.ifBlank { "—" }, Modifier.weight(.45f), color = OceanText,
                            fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Text(topology.pendingReason ?: if (!topology.domainsConfirmed) topology.domainPendingReason.orEmpty()
            else "性能相近不代表共用调频域，核心分配仍可单独指定 CPU。", color = OceanTextSecondary, fontSize = 12.sp,
            lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
