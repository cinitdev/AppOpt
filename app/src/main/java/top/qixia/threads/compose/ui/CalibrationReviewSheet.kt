package top.qixia.threads.compose.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.qixia.threads.CalibrationDraft
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.CalibrationReviewsState
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationReviewSheet(state: CalibrationReviewsState, onDismiss: () -> Unit,
    onSelect: (Int, Set<Int>) -> Unit, onSave: () -> Unit, onDiscard: () -> Unit,
    onReload: () -> Unit, onSelectProcess: (String, Set<Int>) -> Unit = { _, _ -> }) {
    val review = state.active ?: return
    val threadOrder = remember(review.draft, review.selections) { review.threadDisplayOrder }
    var editing by remember(review.draft.fileName) { mutableStateOf<Int?>(null) }
    var editingProcess by remember(review.draft.fileName) { mutableStateOf<String?>(null) }
    var discardConfirm by remember(review.draft.fileName) { mutableStateOf(false) }
    val listState = key(review.draft.fileName) { rememberLazyListState() }
    val scope = rememberCoroutineScope()
    val advancedRuleIndex = threadOrder.size + (if (review.draft.version == 1) 1 else 0) +
        (if (review.draft.omitted > 0) 1 else 0)
    val configuredProcesses = review.processSelections.values.count { it.isNotEmpty() }
    val latestBusy = rememberUpdatedState(state.busy)
    val confirmTransition = remember { { target: SheetValue -> target != SheetValue.Hidden || !latestBusy.value } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = confirmTransition)
    // 固定视口，避免移动弹窗消耗的顶部边距反过来影响 fillMaxHeight，
    // 使快速滑动期间的锚点发生变化。
    val viewportHeight = LocalConfiguration.current.screenHeightDp.dp * .90f
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = sheetState, containerColor = OceanBackground,
        sheetGesturesEnabled = false, dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.height(viewportHeight).navigationBarsPadding().padding(top = 16.dp)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("确认本次线程分配", Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OceanText)
                    IconButton(onDismiss, enabled = !state.busy) { Icon(Icons.Outlined.Close, contentDescription = "稍后确认") }
                }
                key(review.draft.fileName) { CalibrationReviewAppHeader(review.draft) }
                Text("采集 ${review.draft.durationMs / 1000} 秒 · ${review.draft.threads.size} ${if (review.draft.version >= 2) "个线程规则组" else "个活跃线程"}",
                    color = OceanPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("点击核心可调整，确认后保存。保存将替换此应用原有规则，其余线程交由系统调度。" +
                    if (review.automatic) " 保存后将关闭此应用的动态自动分配。" else "",
                    color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 10.dp))
                CalibrationAdvancedRulesShortcut(configuredProcesses, !state.busy) {
                    scope.launch { listState.animateScrollToItem(advancedRuleIndex) }
                }
                Spacer(Modifier.height(12.dp))
            }
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (review.draft.version == 1) item {
                        Text("这份结果来自旧版采集，已保留原来的核心选择。重新采集后可使用新的分组与自适应推荐。",
                            color = OceanTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(threadOrder, key = { index -> review.draft.threads[index].let { "${review.draft.fileName}/${it.owner}/${it.name}" } }) { index ->
                        val row = review.draft.threads[index]
                        Surface(color = OceanSurface, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { editing = index }, enabled = !state.busy && row.editable && review.draft.capacities.isNotEmpty()) {
                                        val selected = review.selections[index]
                                        Text(if (selected.isEmpty()) "系统调度" else "CPU ${RuleConfigLogic.formatCpuRangeList(selected)}")
                                    }
                                }
                                if (row.owner != review.draft.packageName) Text(row.owner, color = OceanTextSecondary, fontSize = 11.sp)
                                val grouped = row.generatedPattern && (row.memberCount > 1 || row.name.any { it in "*?[" })
                                if (grouped) Text("通配组 · 本次匹配 ${row.memberCount} 个名称\n${row.members.take(4).joinToString("、")}${if (row.memberCount > 4) " 等" else ""}",
                                    color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                                Text(String.format(Locale.CHINA, if (grouped) "平均合计 %.1f%% · 峰值上界 %.1f%%" else "平均 %.1f%%  ·  峰值 %.1f%%  ·  活跃 %.0f%%", row.average, row.peak, row.activity),
                                    color = OceanTextSecondary, fontSize = 12.sp)
                                Text(if (review.selections[index] != row.suggested) "已手动调整 · 原建议：${if (row.suggested.isEmpty()) "系统调度" else "CPU ${RuleConfigLogic.formatCpuRangeList(row.suggested)}"}" else row.reason,
                                    color = OceanPrimary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                    if (review.draft.omitted > 0) item { Text("本次达到 128 组展示上限，另有 ${review.draft.omitted} 组保持系统调度。", color = OceanTextSecondary, fontSize = 12.sp) }
                    item(key = "advanced-rules") {
                        CalibrationAdvancedRulesCard(review, !state.busy) { editingProcess = it }
                    }
                    item { Text("仅采集期间平均负载达到 5% 的活跃线程参与生成建议；历史记录仍保留全部活跃线程。通配规则可能匹配同组其他成员，容量预算计入整组负载。核心池内由系统调度；保存后是固定规则。",
                        color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
                    item { Text("每个应用仅保留最新一次待确认建议；重新采集会替换旧建议，采集历史仍然保留。",
                        color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                state.error?.let {
                    Text(it, color = OceanError, fontSize = 12.sp)
                    TextButton(onReload, enabled = !state.busy) { Text("重新载入现有配置（保留核心选择）") }
                }
                Button(onSave, enabled = !state.busy && review.original != null, modifier = Modifier.fillMaxWidth()) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("保存并生效 · ${review.ruleCount} 条规则")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ discardConfirm = true }, enabled = !state.busy) { Text("放弃本次建议", color = OceanTextSecondary) }
                    TextButton(onDismiss, enabled = !state.busy) { Text("稍后确认") }
                }
            }
        }
    }
    editingProcess?.let { owner ->
        var selected by remember(review.draft.fileName, owner) { mutableStateOf(review.processSelections[owner].orEmpty()) }
        AlertDialog(onDismissRequest = { editingProcess = null }, title = { Text("进程兜底 · ${owner.substringAfter(':')}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("仅对未匹配线程规则的线程生效。不选核心即不生成进程规则。", fontSize = 12.sp)
                CoreSelector(review.draft.capacities.keys, selected, { selected = it })
                TextButton({ selected = emptySet() }) { Text("恢复系统调度") }
            } },
            confirmButton = { TextButton({ onSelectProcess(owner, selected); editingProcess = null }) { Text("确定") } },
            dismissButton = { TextButton({ editingProcess = null }) { Text("取消") } })
    }
    editing?.let { index ->
        var selected by remember(review.draft.fileName, index) { mutableStateOf(review.selections[index]) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("${review.draft.threads[index].name} · 核心") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("不选核心即交由系统调度。数值为系统报告的相对核心能力。", fontSize = 12.sp, color = OceanTextSecondary)
                CoreSelector(review.draft.capacities.keys, selected, { selected = it })
                review.draft.capacities.entries.groupBy { it.value }.forEach { (capacity, cores) ->
                    Text("CPU ${cores.joinToString(",") { it.key.toString() }} · ${if (capacity > 0) "能力 $capacity" else "能力未知"}", fontSize = 12.sp)
                }
                TextButton({ selected = emptySet() }) { Text("恢复系统调度") }
            } },
            confirmButton = { TextButton({ onSelect(index, selected); editing = null }) { Text("确定") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }
    if (discardConfirm) AlertDialog(onDismissRequest = { discardConfirm = false }, title = { Text("放弃本次建议？") },
        text = { Text("保留原有配置和采集历史，删除这次待确认建议。") },
        confirmButton = { TextButton({ discardConfirm = false; onDiscard() }) { Text("放弃") } },
        dismissButton = { TextButton({ discardConfirm = false }) { Text("取消") } })
}

@Composable
private fun CalibrationReviewAppHeader(draft: CalibrationDraft) {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val app by produceState<Pair<String, Drawable?>>(draft.packageName to null, context, draft.packageName, configuration) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            val info = runCatching { manager.getApplicationInfo(draft.packageName, 0) }.getOrNull()
            val label = info?.let { runCatching { manager.getApplicationLabel(it).toString() }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: draft.packageName
            val icon = info?.let { runCatching { manager.getApplicationIcon(it) }.getOrNull() }
                ?: manager.defaultActivityIcon
            label to icon
        }
    }
    val generatedAt = remember(draft.createdMs, configuration) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(draft.createdMs))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppDrawableIcon(app.second, "${app.first}图标", Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.first, color = OceanText, fontSize = 17.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(draft.packageName, color = OceanTextSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Text("生成时间：$generatedAt", color = OceanTextSecondary, fontSize = 12.sp)
}
