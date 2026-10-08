package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.theme.*

@Composable
internal fun HistorySessionSummary(fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?, duration: Long, threadCount: Int) {
    var explanation by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HistorySurface {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本次表现", color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    IconButton({ explanation = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Info, "报告统计说明", tint = OceanPrimary, modifier = Modifier.size(18.dp))
                    }
                }
                Text("采集 ${historyDuration(duration)}  ·  $threadCount 条线程记录", fontSize = 11.sp, color = OceanTextSecondary)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.25f)) {
                        Text("平均帧率", color = OceanTextSecondary, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fps?.average?.let(::historyNumber) ?: "—", color = OceanPrimary, fontSize = 42.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Text("FPS", color = OceanTextSecondary, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SessionStatisticLine("最高帧率", fps?.maximum, "FPS")
                        SessionStatisticLine("最低帧率", fps?.minimum, "FPS")
                    }
                }
                Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("低位帧率 · 5% LOW", color = OceanTextSecondary, fontSize = 10.sp)
                            Text("${fps?.low5?.let(::historyNumber) ?: "—"} FPS", color = PorcelainOnTonal,
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("帧率波动", color = OceanTextSecondary, fontSize = 10.sp)
                            Text("${fps?.jitter?.let(::historyNumber) ?: "—"}%", color = PorcelainOnTonal,
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = OceanDivider)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Triple("平均 SoC 温度", metrics?.series?.get("cpu_c")?.average, "°C"),
                        Triple("最高电池温度", metrics?.series?.get("battery_c")?.maximum, "°C"),
                        Triple("平均放电功率", metrics?.series?.get("power_w")?.average, "W")).forEach { (label, value, unit) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, color = OceanTextSecondary, fontSize = 9.sp)
                            Text("${value?.let(::historyNumber) ?: "—"} $unit", color = OceanText, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
        HistorySurface {
            val device = metrics?.device.orEmpty()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("采集设备", color = OceanText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf("平台" to "platform", "机型" to "model", "系统" to "os").forEach { (label, key) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, color = OceanTextSecondary, fontSize = 10.sp)
                            Text(device[key]?.takeIf { it.isNotBlank() } ?: "未记录", color = OceanText,
                                fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Text("分辨率 ${device["resolution"] ?: "未记录"}  ·  刷新率 ${device["refresh"] ?: "未记录"}",
                    color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("报告统计") },
        text = { Text("平均、最高和最低帧率取本次有效 FPS 样本。帧率波动 = FPS 标准差 ÷ 平均 FPS × 100%，数值越低，采样期间的帧率越稳定。\n\n低位帧率（5% LOW）取最低 5% 的一秒 FPS 样本均值；超过 100 个有效样本才显示。\n\nSoC 温度取 CPU 传感器读数，电池温度单独统计。接电期间不估算整机功率。没有采集的数据保持空白，旧记录不会补入当前设备状态。") },
        confirmButton = { TextButton({ explanation = false }) { Text("知道了") } })
}

@Composable
private fun SessionStatisticLine(label: String, value: Float?, unit: String) {
    Column {
        Text(label, color = OceanTextSecondary, fontSize = 10.sp)
        Text("${value?.let(::historyNumber) ?: "—"} $unit", color = OceanText, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}
