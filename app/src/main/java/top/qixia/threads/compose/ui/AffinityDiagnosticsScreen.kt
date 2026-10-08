package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.AffinityDiagnosticsUiState
import top.qixia.threads.compose.CoreTimelinePresentation
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AffinityDiagnosticsScreen(state: AffinityDiagnosticsUiState, padding: PaddingValues,
    onBack: () -> Unit, onRefresh: () -> Unit) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable(state.packageName) { mutableStateOf("") }
    var onlyAttention by rememberSaveable(state.packageName) { mutableStateOf(false) }
    var expanded by rememberSaveable(state.packageName) { mutableStateOf<String?>(null) }
    val report = state.report
    val rows = remember(report, query, onlyAttention) { report?.rows.orEmpty().filter {
        (query.isBlank() || it.name.contains(query, true) || it.identity.tid.toString().contains(query)) &&
            (!onlyAttention || !it.matched && (it.average >= 5f || it.operation == "error"))
    } }
    Column(Modifier.fillMaxSize().background(OceanBackground).padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回应用") }
            Column(Modifier.weight(1f)) {
                Text("核心接管诊断", color = OceanText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(state.label, color = OceanTextSecondary, fontSize = 12.sp)
            }
            IconButton(onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新接管诊断", tint = OceanPrimary) }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                HistorySurface { Column(Modifier.padding(16.dp)) {
                    Text("分配目标 × 实际状态", color = OceanText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("负载和目标来自最近分配采样；实际范围与 cpuset 在点开或刷新时由 Rust 读取。范围相同且所属组正确，才标记为已接管。",
                        color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                    report?.let {
                        Text("负载采样 ${diagnosticTime(it.sampledMs)}\n实际读回 ${diagnosticTime(it.readMs)}",
                            color = PorcelainOnTonal, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(it.detail.ifBlank { "当前没有诊断快照" }, color = OceanTextSecondary, fontSize = 12.sp,
                            lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                        if (it.state !in setOf("active", "observing")) Text("应用离开前台会正常释放接管，下方保留最近目标与操作结果，不能据此认定被系统抢走。",
                            color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                        if (it.readMs - it.sampledMs > 5000 && it.sampledMs > 0) Text("负载快照已不是当前时刻，请核对采样时间。", color = OceanWarning, fontSize = 11.sp)
                    }
                } }
            }
            if (state.loading) item { LoadingState("正在读取核心接管状态") }
            else if (state.error != null) item {
                Text(state.error, color = OceanError, fontSize = 13.sp)
                TextButton(onRefresh) { Text("重试") }
            } else if (report?.rows.isNullOrEmpty()) item {
                EmptyState("暂无线程快照", "先让此应用在前台运行并产生负载，再返回这里。诊断仅保留本次守护运行期间最近四个应用的有界快照。")
            } else {
                item { SearchField(query, { query = it }, "搜索线程名称或 TID") }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlyAttention, { onlyAttention = false }, label = { Text("全部 ${report!!.rows.size}") })
                    FilterChip(onlyAttention, { onlyAttention = true }, label = { Text("需要核对") })
                } }
                items(rows, key = { it.identity.stableKey }) { row ->
                    val open = expanded == row.identity.stableKey
                    Surface(onClick = { expanded = if (open) null else row.identity.stableKey },
                        color = OceanSurface, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.name, Modifier.weight(1f), color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${historyNumber(row.average)}%", color = OceanPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("PID ${row.identity.pid} · TID ${row.identity.tid}", color = OceanTextSecondary, fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp))
                            Text(row.status(report!!.state), color = if (row.matched) OceanPrimary else OceanTextSecondary,
                                fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                            DiagnosticValue("最近目标", row.desired?.let(CoreTimelinePresentation::cpus) ?: "尚无目标")
                            DiagnosticValue("实际允许", row.actual?.let(CoreTimelinePresentation::cpus) ?: "未读到")
                            if (open) {
                                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OceanOutline)
                                Text("当前 cpuset", fontSize = 11.sp, color = OceanTextSecondary)
                                Text(row.group.ifBlank { "未读到" }, color = PorcelainOnTonal, fontSize = 11.sp, lineHeight = 18.sp)
                                Text("目标 cpuset", fontSize = 11.sp, color = OceanTextSecondary, modifier = Modifier.padding(top = 8.dp))
                                Text(row.expectedGroup.ifBlank { "尚无目标" }, color = PorcelainOnTonal, fontSize = 11.sp, lineHeight = 18.sp)
                                Text("最近操作 · ${diagnosticTime(row.operationMs)}", color = OceanText, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                                Text(row.explanation(), color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                                if (row.error.isNotBlank()) Text("内核 / 文件操作返回：${row.error}", color = OceanError,
                                    fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                                Text("${row.operation.ifBlank { "无操作" }} / ${row.reason.ifBlank { "无结果" }}", color = OceanTextSecondary,
                                    fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
                item { Text("显示 ${rows.size} 条 / 快照共 ${report!!.total} 条；最多保留负载最高的 512 条。所有读取只用于诊断，不改变分配策略。",
                    color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp) }
            }
        }
    }
}

@Composable private fun DiagnosticValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, Modifier.width(76.dp), color = OceanTextSecondary, fontSize = 12.sp)
        Text(value, Modifier.weight(1f), color = PorcelainOnTonal, fontSize = 12.sp)
    }
}
internal fun diagnosticTime(ms: Long): String = if (ms <= 0L) "尚未采样" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
