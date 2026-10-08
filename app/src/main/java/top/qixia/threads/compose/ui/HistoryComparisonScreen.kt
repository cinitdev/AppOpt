package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*

@Composable
internal fun HistoryComparisonScreen(state: HistoryComparisonUiState, padding: PaddingValues,
    onBack: () -> Unit, onRetry: () -> Unit) {
    BackHandler(onBack = onBack)
    val reference = remember(state.summaries) {
        state.summaries.mapNotNull { it.referenceFps }.maxOrNull()
    }
    Column(Modifier.fillMaxSize().background(OceanBackground).padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回报告") }
            Column {
                Text("调度效果对比", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = OceanText)
                Text(state.label, fontSize = 12.sp, color = OceanTextSecondary)
            }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                HistorySurface {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(state.first, state.second).forEachIndexed { index, session ->
                            Column(Modifier.weight(1f)) {
                                Text(if (index == 0) "A · 基准记录" else "B · 对照记录", color = OceanPrimary,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(historyTime(session.epoch, "MM-dd HH:mm:ss"), color = OceanText,
                                    fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                                Text(HistoryPresentation.sourceLabel(session.source), color = OceanTextSecondary, fontSize = 11.sp)
                                Text(historyDuration(session.durationMs), color = OceanTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            if (state.loading) item { LoadingState("正在汇总两次完整记录，包含全部分片") }
            else if (state.error != null) item {
                Text("对比读取失败：${state.error}", color = OceanError)
                TextButton(onRetry) { Text("重新读取") }
            } else if (state.summaries.size == 2) {
                val a = state.summaries[0]; val b = state.summaries[1]
                fun low(summary: RunComparisonSummary) = reference?.takeIf { summary.completeFpsSamples && summary.fpsSamples >= 8 }
                    ?.let { threshold -> summary.sampledFps.count { it < threshold * .8f } * 100f / summary.fpsSamples }
                item {
                    HistorySurface {
                        Column(Modifier.padding(16.dp)) {
                            Text("流畅表现", color = OceanText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            ComparisonHeader()
                            ComparisonMetric("平均 FPS", a.averageFps, b.averageFps, "FPS")
                            ComparisonMetric("最低采样 FPS", a.minimumFps, b.minimumFps, "FPS")
                            ComparisonMetric("低帧采样占比", low(a), low(b), "%", "百分点")
                            ComparisonMetric("最大帧间隔", a.maximumFrameMs, b.maximumFrameMs, "ms")
                            Text(reference?.let { "共同参考 ${historyNumber(it)} FPS，低帧阈值为其 80%。占比仅在完整 FPS 样本可用时显示；不是逐帧慢帧比例。" }
                                ?: "FPS 样本不足，无法建立共同参考。", color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 12.dp))
                            Text("逐帧 P95 / P99：这些记录未保存完整逐帧分布，无法计算。不会用区间最大值冒充逐帧耗时。",
                                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                item {
                    HistorySurface {
                        Column(Modifier.padding(16.dp)) {
                            Text("电量与功率", color = OceanText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            ComparisonHeader()
                            ComparisonMetric("平均放电功率", a.averagePower, b.averagePower, "W")
                            ComparisonMetric("电量净减少", a.batteryUsed, b.batteryUsed, "%", "百分点")
                            ComparisonMetric("指标时段覆盖", a.metricsCoverage * 100, b.metricsCoverage * 100, "%", "百分点")
                            Text("功率是整机放电采样均值，电量是首末有效读数之差，不代表应用单独耗电。记录时长不同时，不宜直接用总耗电判断优劣。",
                                color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
                            if (a.charging || b.charging) Text("包含充电：电量显示净变化，充电期间不纳入放电功率。",
                                color = OceanWarning, fontSize = 11.sp, lineHeight = 18.sp)
                        }
                    }
                }
                item {
                    Text("差值 = B − A。建议使用相同设备、画质、帧率档位和相近游戏场景；此处展示实测差异，不自动判断是哪项规则或算法造成。数据缺失显示“—”。",
                        color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                    if (a.device.isNotEmpty() && b.device.isNotEmpty() && a.device != b.device)
                        Text("两次设备元数据不同，请先确认硬件和采集环境。", color = OceanWarning, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun ComparisonHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
        Text("指标", Modifier.weight(1.4f), fontSize = 11.sp, color = OceanTextSecondary)
        listOf("A", "B", "差值").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.End,
            fontSize = 11.sp, color = OceanTextSecondary) }
    }
}

@Composable private fun ComparisonMetric(label: String, a: Float?, b: Float?, unit: String, differenceUnit: String = unit) {
    HorizontalDivider(color = OceanOutline.copy(alpha = .5f))
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.4f), fontSize = 12.sp, color = OceanText)
        listOf(a, b).forEach { value -> Text(value?.let { historyNumber(it) + "\n$unit" } ?: "—",
            Modifier.weight(1f), textAlign = TextAlign.End, color = PorcelainOnTonal, fontSize = 12.sp) }
        val delta = if (a != null && b != null) b - a else null
        Text(delta?.let { (if (it > 0) "+" else "") + historyNumber(it) + "\n$differenceUnit" } ?: "—",
            Modifier.weight(1f), textAlign = TextAlign.End, color = OceanText, fontSize = 12.sp)
    }
}
