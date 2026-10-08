package top.qixia.threads.compose.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.AppToast
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    state: LogsUiState, contentPadding: PaddingValues, onRefresh: () -> Unit,
    onSelectSource: (LogSource) -> Unit, onSelectFilter: (LogFilter) -> Unit, onExport: () -> Unit,
    onSelectCategory: (LogCategory) -> Unit, onQuery: (String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember(state.source) { mutableStateOf<String?>(null) }
    val cards = remember(state.visibleEntries) { logCards(state.visibleEntries) }
    val dates = remember(cards) {
        val formatter = SimpleDateFormat("MM月dd日", Locale.getDefault())
        cards.map { it.timestampMs?.let { time -> formatter.format(Date(time)) } ?: "旧格式记录 · 无时间信息" }
    }
    val refreshed = remember(state.updatedAtMs) {
        state.updatedAtMs?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) }
    }
    fun copy(text: String) {
        if (text.length > 96000) {
            AppToast.show(context, "日志较长，请使用右上角导出以保留完整内容")
            return
        }
        val copied = runCatching { context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("QixiaThreads 日志", text)) }.isSuccess
        AppToast.show(context, if (copied) "日志已复制" else "复制失败，可使用右上角导出")
    }
    PorcelainPage("运行日志", contentPadding, subtitle = "每一次调度，都有迹可循",
        onRefresh = { if (!state.loading) onRefresh() }, contentSpacing = 12.dp,
        headerExtra = {
            IconButton(onClick = onExport, enabled = state.visibleEntries.isNotEmpty()) {
                Icon(Icons.Outlined.Download, "导出当前筛选的原始日志", tint = if (state.visibleEntries.isEmpty()) OceanTextSecondary else PorcelainOnTonal)
            }
        }) {
        item(key = "sources") {
            PorcelainTabs(LogSource.entries.map { it.label }, state.source.ordinal, { onSelectSource(LogSource.entries[it]) })
        }
        item(key = "overview") { LogOverview(state, refreshed, onSelectFilter) }
        item(key = "search") { SearchField(state.query, onQuery, "搜索包名、线程名或事件内容") }
        item(key = "categories") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogCategory.entries.filter { it == LogCategory.ALL || state.entries.any { entry -> entry.category == it } || state.category == it }.forEach { category ->
                    FilterChip(state.category == category, { onSelectCategory(category) }, label = { Text(category.label, fontSize = 12.sp) }, shape = RoundedCornerShape(12.dp))
                }
            }
        }
        state.readError?.let { error -> item(key = "read-error") {
            Surface(color = OceanWarning.copy(alpha = .08f), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("日志暂时无法读取", color = OceanWarning, fontWeight = FontWeight.SemiBold)
                    Text(error + if (state.entries.isNotEmpty()) "。以下保留上次读取的记录。" else "", color = OceanTextSecondary, fontSize = 12.sp)
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("重新读取") }
                }
            }
        } }
        item(key = "timeline-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("事件时间线", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OceanText)
                    Text("${state.visibleEntries.size} 条事件 · ${cards.size} 组记录 · 最新在前", fontSize = 11.sp, color = OceanTextSecondary)
                }
                TextButton(onClick = { copy(LogTextFormatter.readable(state.visibleEntries)) }, enabled = state.visibleEntries.isNotEmpty()) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("复制结果", fontSize = 12.sp)
                }
            }
        }
        when {
            state.loading && state.entries.isEmpty() -> item(key = "loading") { LoadingState("正在读取${state.source.label}日志") }
            state.visibleEntries.isEmpty() -> item(key = "empty") {
                HistorySurface {
                    Column {
                        EmptyState(if (state.entries.isEmpty()) "还没有日志记录" else "没有匹配的事件",
                            if (state.readError != null) "恢复读取后即可查看运行事件。"
                            else if (state.entries.isEmpty()) "服务产生新事件后，点击右上角刷新查看。" else "试试其他级别、分类，或调整搜索关键词。")
                        if (state.entries.isNotEmpty()) TextButton(onClick = { onSelectFilter(LogFilter.ALL); onSelectCategory(LogCategory.ALL); onQuery("") }, Modifier.align(Alignment.CenterHorizontally)) { Text("清除筛选") }
                    }
                }
            }
            else -> itemsIndexed(cards, key = { _, card -> card.key }, contentType = { _, card -> if (card.startup) "log-startup" else "log-event" }) { index, card ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (index == 0 || dates[index] != dates[index - 1]) Text(dates[index], color = OceanTextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                    val onExpand = { expanded = if (expanded == card.key) null else card.key }
                    val onCopy = { copy(LogTextFormatter.readable(card.entries)) }
                    if (card.startup) LogStartupCard(card.entries, expanded == card.key, onExpand, onCopy)
                    else LogEventCard(card.entries.single(), expanded == card.key, onExpand, onCopy)
                }
            }
        }
        item(key = "window-note") {
            Text("仅展示最近读取的日志窗口；进入页面或手动刷新时读取。\n同一次启动信息归为一组，连续重复事件合并计数。复制保留正文换行，右上角可导出原始日志。",
                color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun LogOverview(state: LogsUiState, refreshed: String?, onFilter: (LogFilter) -> Unit) {
    Surface(color = PorcelainHeader, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, OceanOutline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timeline, null, tint = PorcelainOnTonal, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("运行轨迹", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = OceanText)
                    Text(if (state.loading) "正在更新记录…" else refreshed?.let { "$it 更新 · ${state.source.label}" } ?: "等待首次读取",
                        color = OceanTextSecondary, fontSize = 10.sp)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val values = listOf(state.entries.size, state.warningCount + state.errorCount, state.errorCount)
                val colors = listOf(PorcelainOnTonal, OceanWarning, OceanError)
                LogFilter.entries.forEachIndexed { index, filter ->
                    Surface(onClick = { onFilter(filter) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                        color = OceanSurface, border = BorderStroke(if (state.filter == filter) 1.5.dp else .5.dp,
                            if (state.filter == filter) colors[index] else OceanOutline)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(values[index].toString(), color = colors[index], fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(filter.label + if (state.filter == filter) " · 已选" else "", color = OceanTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (state.entries.isNotEmpty()) Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val counts = listOf(state.entries.size - state.warningCount - state.errorCount, state.warningCount, state.errorCount)
                val colors = listOf(PorcelainOnTonal, OceanWarning, OceanError)
                counts.forEachIndexed { index, count -> if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(colors[index])) }
            }
        }
    }
}
