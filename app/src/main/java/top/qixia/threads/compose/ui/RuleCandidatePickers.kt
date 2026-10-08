package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.*
import top.qixia.threads.compose.RuleEditTarget
import top.qixia.threads.compose.theme.*
import java.util.Locale

private fun RuleHistoryCandidate.selectionKey() = "$owner\u0000${thread.orEmpty()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleHistoryPicker(candidates: List<RuleHistoryCandidate>, initial: List<RuleEditTarget>, thread: Boolean,
    onDismiss: () -> Unit, onSelect: (List<RuleHistoryCandidate>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial.map { "${it.owner}\u0000${it.name}" }) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(candidates, query) { candidates.filter {
        query.isBlank() || it.owner.contains(query, true) || it.thread.orEmpty().contains(query, true)
    } }
    val picked = remember(candidates, selected) { candidates.filter { it.selectionKey() in selected } }
    val height = LocalConfiguration.current.screenHeightDp.dp * .85f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding(),
        containerColor = OceanBackground, dragHandle = null, sheetGesturesEnabled = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.fillMaxWidth().height(height).navigationBarsPadding().imePadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                RulePanelHandle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (thread) "从历史选择线程" else "从历史选择子进程", fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        color = OceanText, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭历史选择") }
                }
                Spacer(Modifier.height(8.dp))
                SearchField(query, { query = it }, "搜索线程或进程")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${picked.size} 项", fontSize = 12.sp, color = PorcelainOnTonal, modifier = Modifier.weight(1f))
                    TextButton({ selected = (selected + visible.map { it.selectionKey() }).distinct() }) { Text("全选结果", fontSize = 12.sp) }
                    TextButton({ selected = emptyList() }) { Text("清空", fontSize = 12.sp) }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visible.isEmpty()) item { EmptyState("暂无可选目标", "已有规则的目标不会重复列出；也可以返回手动填写。") }
                items(visible, key = { it.selectionKey() }) { candidate ->
                    val id = candidate.selectionKey()
                    Surface(color = OceanSurface, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(id in selected, role = Role.Checkbox) {
                            selected = if (it) selected + id else selected - id
                        }.padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selected, null)
                            Column(Modifier.weight(1f)) {
                                Text(candidate.thread ?: ":${candidate.owner.substringAfter(':')}", color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(candidate.owner, color = OceanTextSecondary, fontSize = 10.sp)
                                Text("平均 ${candidate.avg?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"}% · 峰值 ${candidate.max?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"}%",
                                    color = PorcelainOnTonal, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                                if (candidate.epoch > 0) Text(historyTime(candidate.epoch, "MM-dd HH:mm:ss"), color = OceanTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            RuleEditorFooter(null, "每个目标都可以单独调整核心。",
                secondary = { OutlinedButton(onDismiss, Modifier.weight(1f).height(48.dp)) { Text("取消") } },
                primary = { Button({ onSelect(picked) }, Modifier.weight(1.4f).height(48.dp), enabled = picked.isNotEmpty()) { Text("使用 ${picked.size} 个目标") } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleWildcardPicker(targets: List<RuleEditTarget>, history: List<RuleHistoryCandidate>,
    onDismiss: () -> Unit, onApply: (List<OwnedThreadWildcardSuggestion>) -> Unit) {
    val suggestions by produceState<List<OwnedThreadWildcardSuggestion>?>(null, targets, history) {
        value = withContext(Dispatchers.Default) {
            RuleHistoryCandidates.collectThreadWildcardSuggestions(targets.filter { it.name.isNotBlank() }.map {
                RuleHistoryCandidate(RuleHistoryKind.THREAD, it.owner, it.name, null, null, 0)
            }, history)
        }
    }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val height = LocalConfiguration.current.screenHeightDp.dp * .80f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding(),
        containerColor = OceanBackground, dragHandle = null, sheetGesturesEnabled = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.height(height).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                RulePanelHandle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("通配符建议", color = OceanText, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭通配符建议") }
                }
                Text("核对匹配范围后勾选。未勾选的线程保留精确名称；合并时保留所选核心的并集。",
                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestions == null) item { LoadingState("正在分析同类线程") }
                else if (suggestions!!.isEmpty()) item { EmptyState("没有可合并的建议", "继续使用当前名称，也可以返回手动输入通配符。") }
                items(suggestions.orEmpty(), key = { "${it.owner}\u0000${it.suggestion.pattern}" }) { owned ->
                    val id = "${owned.owner}\u0000${owned.suggestion.pattern}"
                    Surface(color = OceanSurface, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(id in selected, role = Role.Checkbox) {
                            selected = if (it) selected + id else selected - id
                        }.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(id in selected, null)
                            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                Text(owned.suggestion.pattern, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PorcelainOnTonal)
                                Text(owned.owner, color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
                                Text("匹配 ${owned.suggestion.matchedNames.size} 个名称", color = OceanText, fontSize = 12.sp)
                                Text(owned.suggestion.matchedNames.joinToString("、"), color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 5.dp, bottom = 8.dp))
                            }
                        }
                    }
                }
            }
            RuleEditorFooter(null, "通配符规则可能匹配下次启动时新建的同名线程。",
                secondary = { OutlinedButton(onDismiss, Modifier.weight(1f).height(48.dp)) { Text("取消") } },
                primary = { Button({ onApply(suggestions.orEmpty().filter { "${it.owner}\u0000${it.suggestion.pattern}" in selected }) },
                    Modifier.weight(1.4f).height(48.dp), enabled = suggestions != null && selected.isNotEmpty()) { Text("采用 ${selected.size} 条建议") } })
        }
    }
}
