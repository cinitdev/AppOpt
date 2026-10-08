package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.*
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*

private val RuleTargetsSaver = listSaver<List<RuleEditTarget>, String>(
    save = { rows -> rows.flatMap { listOf(it.owner, it.name, it.cpus) } },
    restore = { values -> values.chunked(3).map { RuleEditTarget(it[0], it[1], it[2]) } }
)

@Composable
internal fun ColumnScope.RuleForm(
    state: RuleEditorUiState, existing: List<RuleSyntax.Rule>, editingIndex: Int?, backRequest: Int,
    onCancel: () -> Unit, onCommit: (List<RuleSyntax.Rule>) -> Unit
) {
    val initial = editingIndex?.let(existing::get)
    val base = state.app.packageName
    val initialType = if (initial?.thread != null || initial == null) 2 else if (initial.owner == base) 0 else 1
    val initialThreads = remember { listOf(RuleEditTarget(if (initialType == 2) initial?.owner ?: base else base,
        if (initialType == 2) initial?.thread.orEmpty() else "", if (initialType == 2) initial?.cpus.orEmpty() else "")) }
    val initialChildren = remember { listOf(RuleEditTarget(if (initialType == 1) initial!!.owner else "$base:",
        cpus = if (initialType == 1) initial!!.cpus else "")) }
    var type by rememberSaveable { mutableIntStateOf(initialType) }
    var mainCpus by rememberSaveable { mutableStateOf(if (initialType == 0) initial!!.cpus else "") }
    var threads by rememberSaveable(stateSaver = RuleTargetsSaver) { mutableStateOf(initialThreads) }
    var children by rememberSaveable(stateSaver = RuleTargetsSaver) { mutableStateOf(initialChildren) }
    var expandedTarget by rememberSaveable { mutableIntStateOf(0) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var pendingTargets by remember { mutableStateOf<List<RuleEditTarget>?>(null) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val targets = if (type == 1) children else threads
    val dirty = type != initialType || threads != initialThreads || children != initialChildren ||
        mainCpus != if (initialType == 0) initial!!.cpus else ""
    fun back() { if (dirty) discard = true else onCancel() }
    fun setTargets(value: List<RuleEditTarget>) {
        if (type == 1) children = value else threads = value
        error = null
    }
    LaunchedEffect(backRequest) { if (backRequest > 0) back() }
    val filteredHistory = remember(type, state.historyCandidates, existing, editingIndex) {
        val excluded = existing.filterIndexed { index, _ -> index != editingIndex }.map { it.owner to it.thread }.toSet()
        state.historyCandidates.filter {
            it.kind == (if (type == 1) RuleHistoryKind.CHILD_PROCESS else RuleHistoryKind.THREAD) &&
                (it.owner to it.thread) !in excluded
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        RulePanelHandle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回规则列表") }
            Column(Modifier.weight(1f)) {
                Text(if (initial == null) "新增规则" else "编辑规则", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OceanText)
                Text(state.app.label, color = OceanTextSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            AppPackageIcon(state.app, state.app.label, Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        PorcelainTabs(listOf("主进程", "子进程", "线程"), type, { type = it; expandedTarget = 0; error = null })
        Spacer(Modifier.height(10.dp))
    }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (type == 0) {
            item {
                Surface(color = OceanSurface, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("主进程", color = OceanText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(base, color = OceanTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Text("为主进程内未单独匹配规则的线程设置核心范围。", color = OceanTextSecondary,
                            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 14.dp))
                        RuleCoreChooser(state.allowedCpus, mainCpus) { mainCpus = it; error = null }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (type == 2) "线程" else "子进程"}目标 · ${targets.size}", color = OceanText, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton({ showHistory = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.History, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("历史选择", fontSize = 12.sp)
                    }
                    if (type == 2) TextButton({ showSuggestions = true }, enabled = threads.any { it.name.isNotBlank() },
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("通配建议", fontSize = 12.sp) }
                }
            }
            itemsIndexed(targets) { index, target ->
                RuleTargetCard(target, index, base, type == 2, expandedTarget == index, targets.size > 1,
                    state.allowedCpus, onExpand = { expandedTarget = if (expandedTarget == index) -1 else index },
                    onChange = { next -> setTargets(targets.mapIndexed { i, old -> if (i == index) next else old }) },
                    onDelete = {
                        setTargets(targets.filterIndexed { i, _ -> i != index })
                        expandedTarget = when { expandedTarget > index -> expandedTarget - 1; expandedTarget == index -> 0; else -> expandedTarget }
                    })
            }
            item {
                OutlinedButton({
                    setTargets(targets + RuleEditTarget(if (type == 1) "$base:" else base, cpus = targets.lastOrNull()?.cpus.orEmpty()))
                    expandedTarget = targets.size
                }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (type == 2) "再添加一个线程" else "再添加一个子进程")
                }
            }
        }
    }
    RuleEditorFooter(error, "完成后返回列表，点击“保存更改”才会生效。",
        secondary = {
            OutlinedButton({ back() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("取消") }
        }, primary = {
            Button({
                val replacements = if (type == 0) listOf(RuleSyntax.Rule(base, null, mainCpus.trim())) else targets.map {
                    RuleSyntax.Rule(it.owner.trim(), if (type == 2) it.name.trim() else null, it.cpus.trim())
                }
                error = when {
                    state.allowedCpus.isEmpty() -> "未读取到可用核心，请重新打开编辑器"
                    replacements.any { RuleConfigLogic.parseCpuRangeList(it.cpus).isNullOrEmpty() } -> "请为每个目标选择至少一个有效核心"
                    type == 1 && replacements.any { !it.owner.startsWith("$base:") || it.owner == "$base:" } -> "请填写有效的子进程后缀"
                    type == 2 && replacements.any { it.thread.isNullOrBlank() } -> "请填写每个线程的名称"
                    hasDuplicateRuleTargets(existing, replacements, editingIndex) -> "该进程或线程已存在规则，请编辑原有规则"
                    else -> {
                        val validation = DaemonBridge.validateConfigRulesForPackages(state.app.configPackages,
                            replacements.joinToString("\n") { it.canonicalLine }, state.allowedCpus)
                        when {
                            validation.foreignLines.isNotEmpty() -> "目标进程不属于当前应用"
                            validation.invalidCoreLines.isNotEmpty() -> "选择的核心不在当前设备范围内"
                            !validation.ok -> "名称或通配符格式无效，请检查输入"
                            else -> null
                        }
                    }
                }
                if (error == null) onCommit(replacements)
            }, Modifier.weight(1.3f).height(48.dp), enabled = !state.loading && state.allowedCpus.isNotEmpty(), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("完成")
            }
        })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次编辑？") },
        text = { Text("返回规则列表，保留进入此页前的草稿。") },
        confirmButton = { TextButton(onCancel) { Text("放弃", color = OceanError) } },
        dismissButton = { TextButton({ discard = false }) { Text("继续编辑") } })
    if (showHistory) RuleHistoryPicker(filteredHistory, targets, type == 2, { showHistory = false }) { selection ->
        val next = replaceRuleTargets(targets, selection.map { it.owner to it.thread.orEmpty() })
        showHistory = false
        if (targets.any { old -> (old.name.isNotBlank() || (type == 1 && old.owner != "$base:")) &&
                next.none { it.owner == old.owner && it.name == old.name } }) pendingTargets = next
        else { setTargets(next); expandedTarget = 0 }
    }
    pendingTargets?.let { next ->
        AlertDialog(onDismissRequest = { pendingTargets = null }, title = { Text("替换当前目标列表？") },
            text = { Text("未选中的目标会移出本次编辑。相同目标保留各自的核心选择，新目标沿用第一项的核心。") },
            confirmButton = { TextButton({ setTargets(next); expandedTarget = 0; pendingTargets = null }) { Text("确认替换") } },
            dismissButton = { TextButton({ pendingTargets = null }) { Text("返回检查") } })
    }
    if (showSuggestions) RuleWildcardPicker(threads, state.historyCandidates, { showSuggestions = false }) { selected ->
        threads = applyRuleWildcards(threads, selected)
        expandedTarget = 0; error = null; showSuggestions = false
    }
}

@Composable
private fun RuleTargetCard(target: RuleEditTarget, index: Int, base: String, thread: Boolean, expanded: Boolean,
    removable: Boolean, allowed: Set<Int>, onExpand: () -> Unit, onChange: (RuleEditTarget) -> Unit, onDelete: () -> Unit) {
    Surface(color = OceanSurface, shape = RoundedCornerShape(20.dp),
        border = if (expanded) BorderStroke(1.dp, OceanOutline) else null) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (expanded) "${if (thread) "线程" else "子进程"} ${index + 1}" else
                        (if (thread) target.name else target.owner.substringAfter(':')).ifBlank { "待填写目标" },
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OceanText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!expanded) Text(if (target.cpus.isBlank()) "尚未选择核心" else "CPU ${target.cpus}",
                        color = PorcelainOnTonal, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (removable) IconButton(onDelete) { Icon(Icons.Outlined.Close, "移除目标 ${index + 1}", Modifier.size(18.dp), tint = OceanTextSecondary) }
                IconButton(onExpand) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起目标 ${index + 1}" else "展开目标 ${index + 1}", tint = OceanTextSecondary) }
            }
            if (expanded) {
                if (thread) {
                    OutlinedTextField(target.owner, { onChange(target.copy(owner = it)) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("所属进程") }, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(target.name, { onChange(target.copy(name = it)) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("线程名称") }, placeholder = { Text("例如 RenderThread 或 Binder:*") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp), shape = RoundedCornerShape(14.dp))
                    Text("支持 * 任意字符、? 单个字符、[0-9] 数字", fontSize = 11.sp, color = OceanTextSecondary,
                        modifier = Modifier.padding(top = 7.dp))
                } else {
                    OutlinedTextField(target.owner.removePrefix("$base:"), {
                        onChange(target.copy(owner = "$base:${it.removePrefix("$base:").removePrefix(":")}"))
                    }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("子进程后缀") },
                        placeholder = { Text("例如 push") }, shape = RoundedCornerShape(14.dp))
                    Text(target.owner, color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                }
                Spacer(Modifier.height(20.dp))
                RuleCoreChooser(allowed, target.cpus) { onChange(target.copy(cpus = it)) }
            }
        }
    }
}
