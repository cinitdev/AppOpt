package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.DaemonBridge
import top.qixia.threads.RuleSyntax
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(state: RuleEditorUiState, onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit, onRecheck: () -> Unit) {
    var editingIndex by rememberSaveable(state.app.packageName) { mutableStateOf<Int?>(null) }
    var formBackRequest by remember { mutableIntStateOf(0) }
    var rawMode by rememberSaveable(state.app.packageName) { mutableStateOf(false) }
    var query by rememberSaveable(state.app.packageName) { mutableStateOf("") }
    var filter by rememberSaveable(state.app.packageName) { mutableStateOf(RuleListFilter.ALL) }
    var expandedOwners by rememberSaveable(state.app.packageName) { mutableStateOf(emptyList<String>()) }
    var discard by remember { mutableStateOf(false) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var more by remember { mutableStateOf(false) }
    val document = remember(state.draft) { RuleSyntax.parse(state.draft) }
    val rules = document.rules
    val graphicSafe = document.segments.all { it.valid }
    val entries = remember(rules, query, filter, expandedOwners) {
        ruleListEntries(rules, state.app.packageName, query, filter, expandedOwners.toSet())
    }
    val listState = rememberLazyListState()
    val healthByLine = remember(state.health) { state.health.values.associateBy { it.ruleLine.trim() } }
    val automatic = state.app.automaticAffinityEnabled && !state.app.isSystemComponent
    fun close() {
        if (state.loading) return
        if (editingIndex != null) formBackRequest++
        else if (state.dirty) discard = true else onDismiss()
    }
    fun edit(index: Int) { formBackRequest = 0; editingIndex = index }
    // 编辑器有未保存修改时，外部点击不能在放弃确认前将其关闭。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden })
    val height = LocalConfiguration.current.screenHeightDp.dp * .93f
    ModalBottomSheet(onDismissRequest = { close() }, sheetState = sheetState, modifier = Modifier.statusBarsPadding(),
        containerColor = OceanBackground, dragHandle = null, sheetGesturesEnabled = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.fillMaxWidth().height(height).navigationBarsPadding().imePadding()) {
            val index = editingIndex
            if (index != null) {
                key(index) {
                    RuleForm(state, rules, index.takeIf { it in rules.indices }, formBackRequest,
                        onCancel = { editingIndex = null }, onCommit = { replacements ->
                            val updated = rules.toMutableList()
                            if (index in updated.indices) { updated.removeAt(index); updated.addAll(index, replacements) }
                            else updated.addAll(replacements)
                            onDraftChange(updated.joinToString("\n") { it.canonicalLine })
                            editingIndex = null
                        })
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    RulePanelHandle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppPackageIcon(state.app, state.app.label, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.app.label, color = OceanText, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.app.packageName, color = OceanTextSecondary, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            if (state.app.isSystemComponent) Text("系统组件", color = PorcelainOnTonal,
                                fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        IconButton(onClick = { close() }, enabled = !state.loading) { Icon(Icons.Outlined.Close, "关闭规则编辑") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (rawMode) "规则文本" else "规则管理", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OceanText)
                        Text("  ·  ${rules.size} 条", color = OceanTextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (!automatic && state.health.values.any { it.status == DaemonBridge.RuleHealthStatus.MISSED })
                            Text("有规则待复检", color = OceanWarning, fontSize = 11.sp)
                        Box {
                            IconButton({ more = true }, enabled = !state.loading) { Icon(Icons.Outlined.MoreHoriz, "规则工具") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem(text = { Text(if (rawMode) "图形编辑" else "文本编辑") },
                                    leadingIcon = { Icon(Icons.Outlined.Code, null) }, enabled = !rawMode || graphicSafe,
                                    onClick = { rawMode = !rawMode; more = false })
                                DropdownMenuItem(text = { Text(if (automatic) "规则检测已暂停" else "重新检查规则匹配") },
                                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, enabled = !state.dirty && !automatic,
                                    onClick = { more = false; onRecheck() })
                            }
                        }
                    }
                    if (automatic) {
                        Text("自动分配已开启 · 原有规则与检测已暂停", color = PorcelainOnTonal,
                            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (!rawMode) {
                        SearchField(query, { query = it }, "搜索线程、进程或 CPU")
                        Spacer(Modifier.height(10.dp))
                        PorcelainTabs(RuleListFilter.entries.map { it.label }, filter.ordinal,
                            { filter = RuleListFilter.entries[it] })
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (state.loading && state.originalLines.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingState("正在读取规则") }
                } else if (rawMode || !graphicSafe) {
                    Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                        Text(if (!graphicSafe) "有内容暂时无法解析，请在文本中修正后再使用图形编辑。" else "支持现有全部规则语法，保存前会检查应用和核心范围。",
                            color = if (graphicSafe) OceanTextSecondary else OceanWarning, fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(state.draft, onDraftChange, Modifier.fillMaxWidth().weight(1f), enabled = !state.loading,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            shape = RoundedCornerShape(20.dp), label = { Text("规则内容") })
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).testTag("rule-list"), state = listState,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entries.isEmpty()) item {
                            EmptyState(if (rules.isEmpty()) "还没有规则" else "没有找到匹配的规则",
                                if (rules.isEmpty()) "新增线程或进程规则，选择它可以使用的核心。" else "试试其他名称，或切换规则类型。")
                        }
                        items(entries, key = { it.key }) { entry ->
                            when (entry) {
                                is RuleListEntry.Process -> RuleProcessGroup(entry, !state.loading) {
                                    expandedOwners = if (entry.owner in expandedOwners) expandedOwners - entry.owner else expandedOwners + entry.owner
                                }
                                is RuleListEntry.Binding -> {
                                    val health = healthByLine[entry.rule.canonicalLine]
                                    val swipeState = rememberSwipeToDismissBoxState(confirmValueChange = {
                                        if (it == SwipeToDismissBoxValue.EndToStart && !state.loading) deleteIndex = entry.index
                                        false
                                    })
                                    SwipeToDismissBox(swipeState, enableDismissFromStartToEnd = false, gesturesEnabled = !state.loading,
                                        backgroundContent = {
                                            Box(Modifier.fillMaxSize().background(OceanError.copy(alpha = .10f), RoundedCornerShape(18.dp))
                                                .padding(20.dp), contentAlignment = Alignment.CenterEnd) {
                                                Icon(Icons.Outlined.DeleteOutline, "删除规则", tint = OceanError)
                                            }
                                        }) {
                                        RuleBindingRow(entry, state.app, state.dirty, automatic, health, !state.loading,
                                            { edit(entry.index) }, { deleteIndex = entry.index })
                                    }
                                }
                            }
                        }
                    }
                }
                RuleEditorFooter(state.error,
                    when {
                        state.loading -> "正在处理…"
                        automatic -> "规则可继续编辑 · 关闭自动分配后生效"
                        state.dirty -> "有未保存修改 · 保存后生效"
                        else -> "左滑删除 · 点击规则编辑"
                    },
                    secondary = {
                        OutlinedButton({ edit(-1) }, enabled = !state.loading && !rawMode && graphicSafe,
                            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("新增规则")
                        }
                    }, primary = {
                        Button(onSave, enabled = state.dirty && !state.loading && state.draft.isNotBlank(),
                            modifier = Modifier.weight(1.3f).height(48.dp), shape = RoundedCornerShape(15.dp)) {
                            if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("保存更改")
                        }
                    })
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存修改？") },
        text = { Text("已保存的规则保持不变。") },
        confirmButton = { TextButton(onDismiss) { Text("放弃并关闭", color = OceanError) } },
        dismissButton = { TextButton({ discard = false }) { Text("继续编辑") } })
    deleteIndex?.let { index ->
        AlertDialog(onDismissRequest = { deleteIndex = null }, title = { Text("删除这条规则？") },
            text = { Text(if (rules.size <= 1) "这是最后一条规则。移除全部规则请在应用页删除该应用配置。"
                else "${rules.getOrNull(index)?.thread ?: rules.getOrNull(index)?.owner}\n删除后点击保存才会生效。") },
            confirmButton = { TextButton({
                if (rules.size > 1) onDraftChange(rules.filterIndexed { i, _ -> i != index }.joinToString("\n") { it.canonicalLine })
                deleteIndex = null
            }) { Text(if (rules.size <= 1) "知道了" else "删除", color = OceanError) } },
            dismissButton = { TextButton({ deleteIndex = null }) { Text("取消") } })
    }
}

@Composable
private fun RuleProcessGroup(group: RuleListEntry.Process, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = PorcelainHeader.copy(alpha = .55f), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountTree, null, Modifier.size(20.dp), tint = PorcelainOnTonal)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(":" + group.owner.substringAfter(':'), color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("子进程 · ${group.count} 条规则", color = OceanTextSecondary, fontSize = 11.sp)
            }
            Icon(if (group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (group.expanded) "收起子进程规则" else "展开子进程规则", tint = PorcelainOnTonal)
        }
    }
}

@Composable
private fun RuleBindingRow(entry: RuleListEntry.Binding, app: AppItemModel, dirty: Boolean, suspended: Boolean,
    health: DaemonBridge.RuleHealth?, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val rule = entry.rule
    val base = app.packageName
    var menu by remember { mutableStateOf(false) }
    val type = if (rule.thread != null) "线程" else if (rule.owner == base) "主进程" else "进程兜底"
    Surface(color = OceanSurface, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit)
            .padding(start = if (entry.nested) 20.dp else 14.dp, top = 13.dp, bottom = 13.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.thread ?: if (rule.owner == base) "主进程" else "进程兜底", color = OceanText,
                    fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val status = ruleBindingStatus(rule, base, app.installed, dirty, suspended, health?.status,
                    systemComponent = app.isSystemComponent)
                Text("$type · $status", color = if (status == "待复检" || status == "应用未安装") OceanWarning else OceanTextSecondary,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                if (!dirty && !suspended && app.available && rule.owner == base && rule.thread == null) {
                    Text("承接主进程内未单独匹配的线程，无需线程名检查", color = OceanTextSecondary,
                        fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(11.dp)) {
                Column(Modifier.widthIn(min = 56.dp, max = 110.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CPU", fontSize = 9.sp, color = OceanTextSecondary)
                    Text(rule.cpus, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = PorcelainOnTonal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "规则操作 ${rule.thread ?: rule.owner}", tint = OceanTextSecondary, modifier = Modifier.size(19.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除", color = OceanError) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = OceanError) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
