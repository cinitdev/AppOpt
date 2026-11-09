package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.AutomaticCustomRule
import top.qixia.threads.AutomaticCustomRules
import top.qixia.threads.DaemonBridge
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.SettingsTopologyPresentation
import top.qixia.threads.compose.theme.*

/** 编辑草稿留在管理弹窗，完整校验后一次保存；普通模式保持紧凑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomaticCustomControls(app: AppItemModel, enabled: Boolean,
    onSave: (List<AutomaticCustomRule>) -> Unit, onEditingChange: (Boolean) -> Unit = {}) {
    val focus = LocalFocusManager.current
    val custom = app.automaticCustomRules.isNotEmpty()
    var editing by rememberSaveable(app.packageName) { mutableStateOf(false) }
    var drafts by rememberSaveable(app.packageName) { mutableStateOf(listOf<String>()) }
    var expanded by rememberSaveable(app.packageName) { mutableIntStateOf(-1) }
    var allowed by remember(app.packageName) { mutableStateOf<Set<Int>>(emptySet()) }
    var groups by remember(app.packageName) { mutableStateOf<List<SettingsTopologyPresentation.Group>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<AutomaticCustomRule>?>(null) }

    DisposableEffect(editing) {
        onEditingChange(editing)
        onDispose { onEditingChange(false) }
    }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        loading = true
        val topology = withContext(Dispatchers.IO) {
            val present = RuleConfigLogic.readPresentCpuSet().orEmpty().filterTo(sortedSetOf()) { it in 0..63 }
            val policy = runCatching { DaemonBridge.readCalibPolicyRaw().content }.getOrDefault("")
            present to SettingsTopologyPresentation.parse(policy, present).groups
        }
        allowed = topology.first
        groups = topology.second
        loading = false
    }
    LaunchedEffect(app.automaticCustomRules, enabled) {
        if (enabled && pending != null && app.automaticCustomRules == pending) {
            editing = false
            pending = null
        }
    }

    fun edit() {
        drafts = if (custom) app.automaticCustomRules.map { "${it.threshold}:${it.cpus}" } else listOf("5:")
        expanded = if (custom) -1 else 0
        editing = true
    }

    Column(Modifier.fillMaxWidth().testTag("automatic-custom-controls")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分配模式", color = OceanText, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            FilterChip(selected = !custom && !editing, onClick = {
                focus.clearFocus()
                pending = null
                editing = false
                if (custom) onSave(emptyList())
            }, enabled = enabled, label = { Text("默认算法", fontSize = 12.sp) })
            FilterChip(selected = custom || editing, onClick = { edit() },
                enabled = enabled && app.automaticCustomSupported,
                label = { Text("自定义", fontSize = 12.sp) })
        }
        if (!app.automaticCustomSupported) {
            Text("更新模块后可使用自定义分配。", color = OceanWarning, fontSize = 11.sp)
        } else if (!editing && custom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.automaticCustomRules.joinToString(" · ") { "≥${it.threshold}% → CPU ${it.cpus}" },
                    color = PorcelainOnTonal, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { edit() }, enabled = enabled) { Text("编辑", fontSize = 12.sp) }
            }
        }
        if (editing) {
            val rules = remember(drafts) {
                AutomaticCustomRules.decode(drafts.joinToString("|") { it.replace(',', '+') })
            }
            val validCores = rules != null && allowed.isNotEmpty() && rules.all {
                allowed.containsAll(RuleConfigLogic.parseNativeCpuRangeList(it.cpus).orEmpty())
            }
            Text("按线程近 6 秒平均负载匹配，预热不足 6 秒时使用已有样本；同时满足多档时，使用最高阈值对应的核心。",
                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            drafts.forEachIndexed { index, value ->
                val threshold = value.substringBefore(':')
                val cpus = value.substringAfter(':')
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = threshold, onValueChange = { input ->
                        if (input.length <= 3 && input.all { it in '0'..'9' })
                            drafts = drafts.toMutableList().also { it[index] = "$input:$cpus" }
                    }, enabled = enabled, singleLine = true,
                        label = { Text("平均 ≥", fontSize = 11.sp) }, suffix = { Text("%") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.width(100.dp).testTag("custom-threshold-$index"))
                    TextButton(onClick = { focus.clearFocus(); expanded = if (expanded == index) -1 else index },
                        enabled = enabled && !loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("custom-cores-$index")) {
                        Text(if (cpus.isBlank()) "选择核心范围" else "CPU $cpus", fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        focus.clearFocus()
                        drafts = drafts.filterIndexed { i, _ -> i != index }
                        expanded = -1
                    }, enabled = enabled && drafts.size > 1) {
                        Icon(Icons.Outlined.Close, "删除第 ${index + 1} 档", modifier = Modifier.size(18.dp))
                    }
                }
                if (expanded == index && !loading) {
                    val selected = RuleConfigLogic.parseNativeCpuRangeList(cpus).orEmpty()
                    fun updateCores(value: String) {
                        drafts = drafts.toMutableList().also { it[index] = "$threshold:$value" }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.filter { it.cpus.isNotEmpty() && allowed.containsAll(it.cpus) }.forEach { group ->
                            val checked = selected.containsAll(group.cpus)
                            FilterChip(checked, {
                                updateCores(RuleConfigLogic.formatCpuRangeList(if (checked) selected - group.cpus else selected + group.cpus))
                            }, enabled = enabled,
                                label = { Text("CPU ${RuleConfigLogic.formatCpuRangeList(group.cpus)}", fontSize = 11.sp) })
                        }
                    }
                    RuleCoreChooser(allowed, cpus, { if (enabled) updateCores(it) })
                }
            }
            val error = when {
                loading -> null
                allowed.isEmpty() -> "未能读取设备核心，请收起后重新进入编辑。"
                rules == null -> "阈值需为 1～100 的整数且不能重复，每档至少选择一个核心。"
                !validCores -> "核心范围包含设备不存在的 CPU，请重新选择。"
                else -> null
            }
            if (error != null) Text(error, color = OceanWarning, fontSize = 11.sp,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
            Text("低于最低档：主进程按兜底设置处理，未开兜底及子进程交回系统。仅前台生效，不改动已有线程规则。",
                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { focus.clearFocus(); drafts = drafts + ":"; expanded = drafts.lastIndex },
                    enabled = enabled && drafts.size < AutomaticCustomRules.MAX_RULES) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                    Text("添加档位", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { focus.clearFocus(); pending = null; editing = false }, enabled = enabled) { Text("取消", fontSize = 12.sp) }
                Button(onClick = {
                    focus.clearFocus()
                    if (rules == app.automaticCustomRules) editing = false
                    else if (rules != null) { pending = rules; onSave(rules) }
                }, enabled = enabled && !loading && validCores,
                    modifier = Modifier.testTag("save-custom-rules")) { Text("保存", fontSize = 12.sp) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = OceanOutline.copy(alpha = .6f))
    }
}
