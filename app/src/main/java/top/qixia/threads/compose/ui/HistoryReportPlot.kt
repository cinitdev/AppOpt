package top.qixia.threads.compose.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryChartPalette
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.*

internal val ReportBlue = Color(HistoryChartPalette.fps)
internal val ReportTeal = Color(HistoryChartPalette.gpuUsage)
internal val ReportAmber = Color(HistoryChartPalette.temperature)
internal val ReportPurple = Color(HistoryChartPalette.cpuUsage)
internal val ReportRose = Color(HistoryChartPalette.battery)

internal data class HistoryPlotSeries(val data: HistoryMetrics.Series, val label: String, val unit: String,
    val color: Color, val rightAxis: Boolean = false, val legendKey: String = data.key, val legendLabel: String = label,
    val valueScale: Float = 1f) {
    init { require(valueScale.isFinite() && valueScale > 0f) { "Plot value scale must be positive and finite" } }
    val points = data.points.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }
    // 单位换算只影响图形，读数和统计保留记录原值。
    fun plotValue(value: Float): Float = value * valueScale
}

/** QixiaThreads 分析面板，缓存图形与共享游标独立于卡片布局。 */
@Composable
internal fun HistoryReportPlotCard(title: String, series: List<HistoryPlotSeries>, durationMs: Long,
    leftUnit: String, rightUnit: String? = null, description: String, emptyMessage: String,
    status: String? = null, cursor: MutableState<Float?>,
    options: @Composable RowScope.() -> Unit = {}, content: (@Composable () -> Unit)? = null,
    rightAxis: HistoryPlotMath.Axis? = null,
    showSeriesOptions: Boolean = true,
    legendContent: (@Composable (List<HistoryPlotSeries>, (String, Boolean) -> Unit) -> Unit)? = null,
    markers: List<Float> = emptyList(), onMarkerSelected: (Float) -> Unit = {}) {
    var hidden by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val visible = remember(series, hidden) { series.filterNot { it.data.key in hidden } }
    HistorySurface {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 28.dp).background(OceanPrimary, RoundedCornerShape(3.dp)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${historyDuration(durationMs)} · ${if (content != null) "样本分布" else "时间曲线"}",
                        color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                options()
                if (showSeriesOptions && series.size > 1) IconButton({ showOptions = true }, Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Tune, "$title 图表选项", tint = PorcelainOnTonal, modifier = Modifier.size(18.dp))
                }
                IconButton({ showInfo = true }, Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Info, "$title 说明", tint = OceanTextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            if (content != null) content() else HistoryReportPlot(series, visible, durationMs, leftUnit, rightUnit, cursor, emptyMessage, rightAxis, markers, onMarkerSelected)
            if (legendContent != null) legendContent(visible) { key, show ->
                hidden = if (show) hidden - key else (hidden + key).distinct()
            } else if (series.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                    series.distinctBy { it.legendKey }.forEach { item ->
                        val members = series.filter { it.legendKey == item.legendKey }.map { it.data.key }
                        val shown = members.count { it !in hidden }
                        val state = when (shown) { 0 -> ToggleableState.Off; members.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp)
                            .triStateToggleable(state, interactionSource = remember { MutableInteractionSource() }, indication = null,
                                role = Role.Checkbox) {
                                hidden = if (shown == members.size) (hidden + members).distinct() else hidden - members.toSet()
                            }, verticalAlignment = Alignment.CenterVertically) {
                            HistoryLegendMark(item.color, shown > 0)
                            Spacer(Modifier.width(5.dp))
                            Text(item.legendLabel + if (state == ToggleableState.Indeterminate) " · 部分" else "",
                                color = OceanText, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (series.isNotEmpty()) ReportStatistics(series, expanded, { expanded = !expanded })
            status?.let {
                Text(it, color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (showSeriesOptions && showOptions) HistorySeriesDialog(title, series, hidden, { showOptions = false }) {
        hidden = it
        showOptions = false
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text(title) },
        text = { Text(description + if (series.isEmpty()) "" else "\n\n有效样本\n" +
            series.joinToString("\n") { "${it.label}：${it.data.samples} 次" },
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ showInfo = false }) { Text("知道了") } })
}
