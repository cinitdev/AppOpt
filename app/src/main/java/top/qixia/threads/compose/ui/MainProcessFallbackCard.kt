package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.DaemonBridge
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.SettingsTopologyPresentation
import top.qixia.threads.compose.theme.*

@Composable
internal fun MainProcessFallbackControls(app: AppItemModel, enabled: Boolean,
    onChange: (Boolean, String?) -> Unit) {
    var choosing by rememberSaveable(app.packageName) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("主进程兜底", color = OceanText, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("接管主进程中负载低于 5% 的线程", color = OceanTextSecondary,
                        fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(app.automaticFallbackEnabled, { on ->
                    if (on && app.mainFallbackCpus == null) choosing = true else onChange(on, null)
                }, enabled = enabled && (app.automaticFallbackSupported || app.automaticFallbackEnabled),
                    modifier = Modifier.semantics { contentDescription = "主进程兜底模式" },
                    colors = SwitchDefaults.colors(checkedTrackColor = PorcelainAction, checkedThumbColor = Color.White,
                        uncheckedTrackColor = OceanOutline, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
            }
            if (!app.automaticFallbackSupported) {
                Text("更新模块后可使用主进程兜底。", color = OceanWarning, fontSize = 12.sp)
            } else if (app.automaticFallbackEnabled) {
                Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(13.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(app.mainFallbackCpus?.let { "CPU $it" } ?: "尚未设置兜底核心",
                                color = PorcelainOnTonal, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        TextButton(onClick = { choosing = true }, enabled = enabled) {
                            Text(if (app.mainFallbackCpus == null) "选择核心" else "更改", fontSize = 12.sp)
                        }
                    }
                }
                Text("仅前台主进程生效，子进程仍按原有策略。",
                    color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    if (choosing) MainProcessFallbackDialog(app.mainFallbackCpus, { choosing = false }) { cpus ->
        choosing = false
        onChange(true, cpus)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainProcessFallbackDialog(initial: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var cpus by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    var allowed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var groups by remember { mutableStateOf<List<SettingsTopologyPresentation.Group>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val present = RuleConfigLogic.readPresentCpuSet().orEmpty().filterTo(sortedSetOf()) { it < 64 }
            val policy = runCatching { DaemonBridge.readCalibPolicyRaw().content }.getOrDefault("")
            present to SettingsTopologyPresentation.parse(policy, present).groups
        }
        allowed = loaded.first
        groups = loaded.second
        loading = false
    }
    val selected = remember(cpus) { RuleConfigLogic.parseNativeCpuRangeList(cpus).orEmpty() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = OceanBackground,
        title = { Text("选择主进程兜底核心", color = OceanText, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("主进程中平均负载低于 5% 的线程使用这里的核心。达到 5% 后仍由自动分配算法选择核心。",
                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PorcelainAction)
                if (groups.isNotEmpty()) {
                    Text("按设备性能组选择", color = OceanText, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { group ->
                            val checked = selected.containsAll(group.cpus)
                            FilterChip(checked, {
                                cpus = RuleConfigLogic.formatCpuRangeList(if (checked) selected - group.cpus else selected + group.cpus)
                            }, label = { Text("CPU ${RuleConfigLogic.formatCpuRangeList(group.cpus)}", fontSize = 12.sp) },
                                leadingIcon = if (checked) ({ Icon(Icons.Outlined.Check, null, Modifier.size(15.dp)) }) else null)
                        }
                    }
                }
                if (!loading) RuleCoreChooser(allowed, cpus, { cpus = it })
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(RuleConfigLogic.formatCpuRangeList(selected)) },
                enabled = !loading && selected.isNotEmpty() && allowed.containsAll(selected),
                colors = ButtonDefaults.buttonColors(containerColor = PorcelainAction)) { Text("保存并开启") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
