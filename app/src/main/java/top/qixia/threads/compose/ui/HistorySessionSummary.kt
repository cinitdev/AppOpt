package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.theme.*

/** 突出平均帧率，按帧率表现和温度功率分组；设备卡片保留三列图标排版。 */
@Composable
internal fun HistorySessionSummary(fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?, duration: Long, threadCount: Int) {
    var explanation by remember { mutableStateOf(false) }
    val device = metrics?.device.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HistorySurface {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("本次表现", color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("采集 ${historyDuration(duration)} · $threadCount 条线程记录",
                            color = OceanTextSecondary, fontSize = 10.sp)
                    }
                    IconButton({ explanation = true }, Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Info, "报告统计说明", Modifier.size(18.dp), tint = OceanPrimary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.1f)) {
                        Text("平均帧率", color = OceanTextSecondary, fontSize = 10.sp)
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Bottom) {
                            Text(fps?.average?.let(::historyNumber) ?: "—", color = OceanPrimary,
                                fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp, maxLines = 1, modifier = Modifier.alignByBaseline())
                            Text("FPS", color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.alignByBaseline())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryMetric("最高帧率", fps?.maximum, "FPS")
                        SummaryMetric("最低帧率", fps?.minimum, "FPS")
                    }
                }
                Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryMetric("低位帧率 · 5% LOW", fps?.low5, "FPS", Modifier.weight(1f), PorcelainOnTonal)
                        SummaryMetric("帧率波动", fps?.jitter, "%", Modifier.weight(1f), PorcelainOnTonal)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 12.dp), color = OceanDivider)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric("平均 SoC 温度", metrics?.series?.get("cpu_c")?.average, "°C", Modifier.weight(1f), textAlign = TextAlign.Center)
                    SummaryMetric("最高电池温度", metrics?.series?.get("battery_c")?.maximum, "°C", Modifier.weight(1f), textAlign = TextAlign.Center)
                    SummaryMetric("平均放电功率", metrics?.series?.get("power_w")?.average, "W", Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
        HistorySurface {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
                    DeviceColumn("平台", device["platform"], Icons.Outlined.Memory)
                    DeviceColumn("机型", device["model"], Icons.Outlined.Smartphone)
                    DeviceColumn("系统", device["os"], Icons.Outlined.Android)
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("分辨率 ${device["resolution"]?.takeIf { it.isNotBlank() } ?: "未记录"}",
                        color = OceanTextSecondary, fontSize = 10.sp)
                    Text("刷新率 ${device["refresh"]?.takeIf { it.isNotBlank() } ?: "未记录"}",
                        color = OceanTextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("报告统计") },
        text = { Text("平均、最高和最低帧率取全部有效 FPS 样本。波动 = FPS 标准差 ÷ 平均 FPS × 100%。\n\n5% LOW 为最低 5% 的一秒 FPS 样本均值，超过 100 个有效样本才显示。\n\nSoC 与电池温度分别统计。接电期间不估算整机放电功率。\n\n分辨率与刷新率为采集开始时读取的屏幕模式，不代表游戏渲染分辨率或平均 FPS。缺少的数据标为未记录或留空，不补入当前设备状态。") },
        confirmButton = { TextButton({ explanation = false }) { Text("知道了") } })
}

@Composable
private fun SummaryMetric(
    label: String, value: Float?, unit: String, modifier: Modifier = Modifier,
    color: Color = OceanText, textAlign: TextAlign = TextAlign.Start
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, Modifier.fillMaxWidth(), color = OceanTextSecondary, fontSize = 10.sp,
            lineHeight = 14.sp, textAlign = textAlign)
        Text(value?.let { "${historyNumber(it)} $unit" } ?: "—", color = color,
            modifier = Modifier.fillMaxWidth(), textAlign = textAlign,
            fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.DeviceColumn(label: String, value: String?, icon: ImageVector) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = OceanTextSecondary, fontSize = 10.sp)
        Icon(icon, null, Modifier.padding(vertical = 12.dp).size(28.dp), tint = OceanPrimary)
        Text(value?.takeIf { it.isNotBlank() } ?: "未记录", color = OceanText, fontSize = 11.sp,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}
