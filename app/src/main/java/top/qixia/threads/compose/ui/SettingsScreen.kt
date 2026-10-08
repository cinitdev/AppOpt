package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.CalibPolicy
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.compose.QixiaThreadsUiState

import top.qixia.threads.compose.theme.*

@Composable
fun SettingsScreen(
    state: QixiaThreadsUiState, contentPadding: PaddingValues, onRefresh: () -> Unit,
    onPolicyChange: ((CalibPolicy) -> CalibPolicy) -> Unit,
    onRestorePolicy: () -> Unit, onExportDiagnostics: () -> Unit,
    onCheckModuleUpdate: () -> Unit,
    moduleDownloadState: ModuleUpdateDownloadViewModel.State,
    onOpenModuleUpdate: () -> Unit,
    onAutoHistoryChange: (Boolean) -> Unit = {}
) {
    val settings = state.settings
    val p = settings.policy
    val enabled = settings.editable && !settings.saving && !settings.autoHistorySaving && !settings.loading
    val canRefresh = !settings.saving && !settings.autoHistorySaving && !settings.loading
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(OceanBackground, ClearTechBackgroundEnd)))
        .padding(contentPadding)) {
        ScreenHeader("设置", subtitle = "更新、规则与设备管理",
            status = when { settings.loading -> "读取中"; settings.saving -> "保存中"; settings.editable -> "即时保存"; else -> "只读模式" },
            statusColor = if (settings.editable) OceanSuccess else OceanTextSecondary,
            extraAction = {
                IconButton(onClick = onRefresh, enabled = canRefresh,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(PorcelainHeader)) {
                    Icon(Icons.Outlined.Refresh, "重新读取设置", tint = if (canRefresh) PorcelainOnTonal else OceanTextSecondary,
                        modifier = Modifier.size(22.dp))
                }
            })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "module-update") {
                SettingsModuleUpdateCard(state.environment, state.updates, moduleDownloadState,
                    onCheckModuleUpdate, onOpenUpdate = onOpenModuleUpdate)
            }
            item(key = "auto-history") {
                Column {
                    SettingsSectionLabel("历史记录")
                    SettingsAutoHistoryCard(settings, onAutoHistoryChange)
                }
            }
            if (settings.loading) item(key = "loading") { LoadingState("正在读取设置") }
            else {
                if (!settings.editable) item(key = "read-only") {
                    Surface(color = OceanWarning.copy(alpha = .07f), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = OceanWarning)
                                Spacer(Modifier.width(8.dp))
                                Text("当前为只读模式", color = OceanWarning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(when {
                                settings.lockedByPendingUpdate -> "模块更新待重启，暂时锁定配置写入。"
                                !settings.hasRoot -> "需要 Root 权限才能读取和保存实际设置。"
                                else -> "设置读取失败，当前展示默认值，请重新读取后再编辑。"
                            }, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                            TextButton(onClick = onRefresh, enabled = canRefresh) { Text("重新读取") }
                        }
                    }
                }
                item(key = "rules") {
                    Column {
                        SettingsSectionLabel("规则与校准")
                        SettingsCard {
                            SettingsEntry("规则保存格式", "选择后同步转换现有规则", formatLabel(p.ruleOutputFormat),
                                Icons.Outlined.DataObject, enabled) { dialog = "format" }
                            SettingsCalibrationNote()
                        }
                        Text("更改后自动保存，无需额外确认。", color = OceanTextSecondary,
                            fontSize = 11.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
                    }
                }
                item(key = "device") {
                    Column {
                        SettingsSectionLabel("设备与运行")
                        SettingsCard {
                            SettingsEntry("cpuset 根目录", if (settings.cpusetSupported) "修改后将安全重启守护进程" else "设备暂不支持自定义 cpuset",
                                "/dev/cpuset/${p.cpusetName}", Icons.Outlined.FolderOpen, enabled && settings.cpusetSupported) { dialog = "cpuset" }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = OceanDivider)
                            SettingsTopologyContent(p.detectedTopologyBlock, settings.presentCpus)
                        }
                    }
                }
                item(key = "support") {
                    Column {
                        SettingsSectionLabel("帮助与维护")
                        SettingsCard {
                            SettingsEntry("导出诊断包", "日志、规则和系统状态保存到 Download/QixiaThreads", null,
                                Icons.Outlined.FilePresent, onClick = onExportDiagnostics)
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = OceanDivider)
                            SettingsEntry("恢复默认设置", "立即恢复，保留核心识别结果", null, Icons.Outlined.RestartAlt, enabled) { dialog = "defaults" }
                        }
                    }
                }
            }
        }
    }
    when (val key = dialog) {
        null -> Unit
        "defaults" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = OceanSurface,
            title = { Text("恢复默认设置？", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("规则格式和 cpuset 名称将立即恢复默认并写入设备，保留设备拓扑及历史记录开关。") },
            confirmButton = { TextButton(onClick = { onRestorePolicy(); dialog = null }) { Text("恢复默认") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        "format" -> ChoiceDialog("规则生成格式", formatOptions.map { formatLabel(it) }, formatOptions.indexOf(p.ruleOutputFormat.generationTarget()),
            { dialog = null }) { value -> onPolicyChange { it.copy(ruleOutputFormat = formatOptions[value]) }; dialog = null }
        "cpuset" -> {
            var name by remember { mutableStateOf(p.cpusetName) }
            val valid = CalibPolicy.normalizeCpusetNameOrNull(name) != null
            AlertDialog(onDismissRequest = { dialog = null }, containerColor = OceanSurface,
                title = { Text("cpuset 根目录", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                text = { Column {
                    Text("点击完成后立即保存，并安全重启守护进程。", color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("目录名称") }, singleLine = true,
                        shape = RoundedCornerShape(13.dp), isError = !valid,
                        supportingText = { Text(if (valid) "/dev/cpuset/${name.trim()}" else "请填写有效的目录名称，不包含路径分隔符。", fontSize = 11.sp) })
                } },
                confirmButton = { TextButton(onClick = { onPolicyChange { it.copy(cpusetName = name.trim()) }; dialog = null }, enabled = valid) { Text("完成") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        }
        else -> Unit
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onSelected: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = OceanSurface,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("选择后立即保存，并转换现有规则。", color = OceanTextSecondary, fontSize = 12.sp,
                lineHeight = 19.sp, modifier = Modifier.padding(bottom = 10.dp))
            options.forEachIndexed { index, option ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                    .background(if (index == selected) OceanSurfaceHigh else Color.Transparent)
                    .selectable(index == selected, role = Role.RadioButton, onClick = { onSelected(index) })
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option, Modifier.weight(1f), fontSize = 14.sp,
                        color = if (index == selected) PorcelainOnTonal else OceanText,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal)
                    RadioButton(index == selected, null, Modifier.size(22.dp))
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private val formatOptions = CalibPolicy.RuleOutputFormat.entries.filterNot { it.requiresAuthorMigration }
private fun formatLabel(format: CalibPolicy.RuleOutputFormat): String = when (format.generationTarget()) {
    CalibPolicy.RuleOutputFormat.LEGACY -> "旧版单行格式"
    CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> "扩展区块格式"
    CalibPolicy.RuleOutputFormat.TAGGED_BLOCK -> "类型标签区块"
    CalibPolicy.RuleOutputFormat.NATURAL_BLOCK -> "自然语句区块"
    CalibPolicy.RuleOutputFormat.NESTED_BLOCK -> "分类嵌套区块"
    CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK -> "函数式格式"
    CalibPolicy.RuleOutputFormat.YAML -> "YAML 风格"
    else -> "原作者区块格式"
}
