package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.CoreEventKind
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import top.qixia.threads.db.SessionSummary
import kotlin.math.abs

@Composable
internal fun FrameDropAnalysisCard(state: HistoryDetailUiState, session: SessionSummary, drops: List<FrameDrop>,
    selected: FrameDrop?, onSelect: (FrameDrop?) -> Unit) {
    val metrics = state.sessionMetrics[session.id]
    val context by produceState<Pair<List<Pair<String, Float>>, List<String>>?>(null, state.threads, state.coreTimeline, selected, session) {
        value = null
        val point = selected ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val load = state.threads.filterNot(HistoryPresentation::isProcess).mapNotNull { thread ->
                HistoryAnalysis.nearby(HistoryPresentation.curve(thread.series, 720), point.progress, session.durationMs)
                    ?.value?.let { thread.name to it }
            }.sortedByDescending { it.second }.take(6)
            val time = session.endedAtMs - session.durationMs + (point.progress * session.durationMs).toLong()
            val events = state.coreTimeline?.events.orEmpty().filter { abs(it.timestampMs - time) <= 2000 }
                .sortedWith(compareBy<top.qixia.threads.CoreEvent> { it.kind == CoreEventKind.OBSERVE }
                    .thenBy { abs(it.timestampMs - time) }).take(8).sortedBy { it.timestampMs }.map {
                    val range = it.afterCpus?.let(CoreTimelinePresentation::cpus) ?: "未记录"
                    "${plotTime(it.timestampMs - (session.endedAtMs - session.durationMs))} · ${it.name.ifBlank { "线程 ${it.identity.tid}" }} (${it.identity.tid})\n" +
                        "允许 $range · ${it.runningCpu?.let { cpu -> "执行核采样 CPU $cpu" } ?: "执行核未采样"}\n${CoreTimelinePresentation.reason(it)}"
                }
            load to events
        }
    }
    HistorySurface {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            ReportSectionTitle("掉帧位置分析", "${drops.size} 个候选区间")
            Text("图中红点表示 FPS 明显下跌或最大帧间隔偏长的采样区间，点击红点或下方时间查看关联数据。",
                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            if (drops.isEmpty()) Text("当前区间没有可识别的候选点，或有效样本不足；不等于逐帧完全无卡顿。",
                color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                items(drops.size, key = { drops[it].progress }) { index ->
                    val drop = drops[index]
                    FilterChip(selected == drop, { onSelect(drop) }, label = { Text(plotTime((session.durationMs * drop.progress).toLong())) })
                }
            }
            selected?.let { point ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${plotTime((session.durationMs * point.progress).toLong())} · 采样观察", Modifier.weight(1f),
                        color = OceanPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    TextButton({ onSelect(null) }) { Text("收起") }
                }
                Text("FPS ${point.fps?.let(::historyNumber) ?: "—"} · 最大帧间隔 ${point.frameMaxMs?.let(::historyNumber) ?: "—"} ms",
                    color = OceanText, fontSize = 12.sp)
                val values = remember(metrics, point, session.durationMs) { metrics?.series.orEmpty().filterKeys {
                    it in setOf("cpu_usage", "gpu_usage", "gpu_mhz", "power_w") || it.startsWith("cpu_mhz.")
                }.map { (key, series) -> (if (key.startsWith("cpu_mhz.")) HistoryPlotMath.cpuLabel(key) + " 频率" else HistoryMetrics.title(key)) to
                    (HistoryAnalysis.at(series.points, point.progress, session.durationMs)?.let { historyNumber(it) + " " + HistoryMetrics.unit(key) } ?: "未采集") } }
                values.forEach { (label, value) -> Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(label, Modifier.weight(1f), color = OceanTextSecondary, fontSize = 12.sp)
                    Text(value, color = PorcelainOnTonal, fontSize = 12.sp)
                } }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OceanOutline)
                Text("当时的线程负载", fontWeight = FontWeight.SemiBold, color = OceanText, fontSize = 13.sp)
                if (context == null) Text("正在关联样本…", color = OceanTextSecondary, fontSize = 12.sp)
                else if (context!!.first.isEmpty()) Text("附近没有有效线程样本", color = OceanTextSecondary, fontSize = 12.sp)
                context?.first?.forEach { (name, load) -> Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(name, Modifier.weight(1f), color = OceanTextSecondary, fontSize = 12.sp)
                    Text("${historyNumber(load)}%", color = PorcelainOnTonal, fontSize = 12.sp)
                } }
                Text("前后 2 秒的核心事件", fontWeight = FontWeight.SemiBold, color = OceanText, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp))
                if (context?.second.isNullOrEmpty()) Text(if (state.coreTimeline == null) "本次没有核心事件记录" else "此时段未记录到核心事件",
                    color = OceanTextSecondary, fontSize = 12.sp)
                context?.second?.forEach { Text(it, color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 10.dp)) }
                Text("这些是时间上相邻的观测，不能单凭核心变化判定掉帧原因；系统迁核为采样观察。",
                    color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}
