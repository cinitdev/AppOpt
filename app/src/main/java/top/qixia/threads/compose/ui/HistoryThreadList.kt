package top.qixia.threads.compose.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.AppToast
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.ThreadData

@Composable
internal fun ColumnScope.HistoryThreadList(threads: List<ThreadData>, loading: Boolean, error: String?, onRetry: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var peakSort by rememberSaveable { mutableStateOf(false) }
    val list = remember(threads, query, peakSort) {
        threads.filter { it.name.contains(query, true) }
            .sortedByDescending { if (peakSort) it.max else it.avg }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(query, peakSort) { scroll.scrollToItem(0) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("线程表现", color = OceanText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("保留本次全部活跃线程", color = OceanTextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(10.dp)) {
                Text("${list.size} 条记录", color = PorcelainOnTonal, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        SearchField(query, { query = it }, "搜索线程或进程名称")
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("排序", color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            listOf(false to "平均使用率", true to "峰值使用率").forEach { (peak, label) ->
                FilterChip(selected = peakSort == peak, onClick = { peakSort = peak },
                    label = { Text(label, fontSize = 11.sp) },
                    leadingIcon = if (peakSort == peak) ({ Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(14.dp)) }) else null,
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(containerColor = OceanSurface,
                        labelColor = OceanTextSecondary, selectedContainerColor = PorcelainHeader,
                        selectedLabelColor = PorcelainOnTonal, selectedLeadingIconColor = PorcelainOnTonal),
                    border = BorderStroke(1.dp, if (peakSort == peak) PorcelainHeader else OceanOutline))
            }
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            loading -> item { LoadingState("正在读取线程曲线") }
            error != null -> item { HistorySurface { Column(Modifier.padding(16.dp)) {
                Text("读取失败：$error", color = OceanError); TextButton(onRetry) { Text("重试") }
            } } }
            list.isEmpty() -> item { HistorySurface { EmptyState("没有符合条件的记录", "可调整搜索，或完成一次采集后查看活跃线程。") } }
            else -> itemsIndexed(list) { index, thread -> HistoryThreadCard(thread, index + 1) }
        }
    }
}

@Composable
private fun HistoryThreadCard(thread: ThreadData, rank: Int) {
    var expanded by rememberSaveable(thread.name, thread.details) { mutableStateOf(false) }
    val points = remember(thread.series) { HistoryPresentation.curve(thread.series) }
    val children = remember(thread.details) { HistoryPresentation.children(thread.details) }
    val context = LocalContext.current
    fun copyName() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QixiaThreads 负载名称", thread.name))
        AppToast.show(context, "名称已复制")
    }
    HistorySurface {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.sizeIn(minWidth = 36.dp, minHeight = 36.dp).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                        Text(rank.toString().padStart(2, '0'), color = PorcelainOnTonal, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(thread.name, color = OceanText, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (HistoryPresentation.isProcess(thread)) "进程汇总" else "线程采样",
                        color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(::copyName, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制 ${thread.name} 的完整名称", tint = OceanTextSecondary, modifier = Modifier.size(17.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("平均使用率", color = OceanTextSecondary, fontSize = 10.sp)
                    Text("${historyNumber(thread.avg)}%", color = OceanPrimary, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("峰值使用率", color = OceanTextSecondary, fontSize = 10.sp)
                    Text("${historyNumber(thread.max)}%", color = OceanText, fontSize = 17.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp, bottom = 3.dp))
                }
            }
            HistoryLineChart(points, "%", 100f, height = if (expanded) 150.dp else 84.dp)
            if (expanded) {
                Text("平均与峰值取完整记录；横轴为采样进度，点击曲线可查看样本。", color = OceanTextSecondary,
                    fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                if (children.isNotEmpty()) {
                    Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("子线程明细 · ${children.size} 条", color = PorcelainOnTonal, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            children.forEachIndexed { index, child ->
                                if (index > 0) HorizontalDivider(Modifier.padding(top = 10.dp), color = OceanOutline)
                                Text(child.name, color = OceanText, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 10.dp))
                                Text("平均 ${child.avg?.let(::historyNumber) ?: "—"}%  ·  峰值 ${child.max?.let(::historyNumber) ?: "—"}%",
                                    color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = OceanDivider)
            TextButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(if (expanded) "收起明细" else if (children.isNotEmpty()) "展开曲线与 ${children.size} 条子线程" else "展开曲线明细",
                    color = PorcelainOnTonal, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    tint = PorcelainOnTonal, modifier = Modifier.size(16.dp))
            }
        }
    }
}
