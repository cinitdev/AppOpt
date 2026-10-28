package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.HistoryBatteryDrain
import top.qixia.threads.compose.HistoryChartPalette
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.*

@Composable
internal fun HistoryFpsPlotCard(fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?, durationMs: Long,
    cursor: MutableState<Float?>, markers: List<Float> = emptyList(), onMarkerSelected: (Float) -> Unit = {}) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val drain = remember(metrics) { HistoryBatteryDrain.from(metrics?.series?.get("battery_pct")) }
    val available = remember(fps, metrics, drain) { buildList {
        if (fps != null) add(HistoryPlotSeries(HistoryMetrics.Series("fps", fps.average, fps.minimum, fps.maximum,
            fps.samples, fps.points.map { HistoryMetrics.Point(it.progress, it.fps, it.breakBefore) }), "FPS", "FPS", ReportBlue,
            sampleAt = fps.analysisSamples?.let { samples -> samples::pointAt }, plotSamples = fps.analysisSamples?.plotPoints()))
        listOf("battery_c", "cpu_usage", "gpu_usage").forEach { key -> metrics?.series?.get(key)?.let {
            add(HistoryPlotSeries(it, when (key) { "battery_c" -> "电池温度"; "cpu_usage" -> "CPU 使用率"; else -> "GPU 使用率" },
                HistoryMetrics.unit(key), when (key) { "battery_c" -> ReportAmber; "cpu_usage" -> ReportPurple; else -> ReportTeal }, true,
                legendLabel = when (key) { "battery_c" -> "TEMP(°C)"; "cpu_usage" -> "CPU(%)"; else -> "GPU(%)" },
                sampleAt = metrics.analysisSamples?.let { samples -> { position -> samples.pointAt(key, position) } },
                plotSamples = metrics.analysisSamples?.plotPoints(key)))
        } }
        drain?.let { value -> add(HistoryPlotSeries(value.series, "累计耗电", "%", ReportRose, true,
            sampleAt = metrics?.analysisSamples?.let { samples -> { position ->
                samples.pointAt("battery_pct", position)?.let { it.copy(value = value.start - it.value) }
            } }, plotSamples = metrics?.analysisSamples?.plotPoints("battery_pct")?.map { it.copy(value = value.start - it.value) })) }
    } }
    val keys = when (mode) { 0 -> setOf("battery_c"); 1 -> setOf("cpu_usage", "gpu_usage"); else -> setOf("battery_used_pct") }
    val visible = remember(available, mode) { available.filter { it.data.key == "fps" || it.data.key in keys } }
    val unit = if (mode == 0) "°C" else "%"
    val axis = remember(visible, mode, drain) {
        if (mode == 2 && drain != null) drain.axis
        else HistoryPlotMath.axis(visible.filter { it.rightAxis }.flatMap { it.points.map { point -> point.value } }, unit)
    }
    val status = when {
        fps == null -> "本次没有保存 FPS 样本"
        visible.none { it.rightAxis } -> when (mode) { 0 -> "本次没有电池温度样本"; 1 -> "本次没有 CPU / GPU 使用率样本"; else -> "电量样本不足，无法计算耗电" }
        mode == 2 && drain != null -> "起始 ${historyNumber(drain.start)}% → 结束 ${historyNumber(drain.end)}% · 净耗电 ${historyNumber(drain.consumed)}%"
        else -> null
    }
    HistoryReportPlotCard("帧率与运行状态", visible, durationMs, "FPS", unit,
        description = "左轴为 FPS；点击右上角切换温度、CPU/GPU 使用率和累计耗电。TEMP 为电池温度，CPU/GPU 传感器温度在下方独立图表中显示。\n\n各页曲线默认勾选，可独立隐藏。游标查询原始采样；绘图仅压缩顶点数量，保留极值和真实断点。\n\n累计耗电按本次首次有效电量减去当时电量计算，负值表示充电或电量回升，不代表应用独占耗电。点击红点查看掉帧关联；统计指标默认收拢。",
        emptyMessage = "本次没有对应的帧率样本", status = status, cursor = cursor, rightAxis = axis,
        showSeriesOptions = false, markers = markers, onMarkerSelected = onMarkerSelected,
        legendContent = { shown, setVisible ->
            val entries = if (mode == 2) listOf("fps", "battery_used_pct")
                else listOf("fps", "battery_c", "cpu_usage", "gpu_usage")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                entries.forEach { key ->
                    val item = available.firstOrNull { it.data.key == key }
                    val selected = shown.any { it.data.key == key }
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 6.dp)
                        .toggleable(selected, enabled = item != null, role = Role.Checkbox) { checked ->
                            if (key != "fps" && key !in keys) mode = if (key == "battery_c") 0 else 1
                            setVisible(key, checked)
                        }, verticalAlignment = Alignment.CenterVertically) {
                        val color = item?.color ?: when (key) {
                            "battery_c" -> ReportAmber; "cpu_usage" -> ReportPurple; "gpu_usage" -> ReportTeal; else -> ReportBlue
                        }
                        HistoryLegendMark(color, selected)
                        Spacer(Modifier.width(4.dp))
                        Text(when (key) { "fps" -> "FPS"; "battery_c" -> "TEMP"; "cpu_usage" -> "CPU%"; "gpu_usage" -> "GPU%"; else -> "耗电%" },
                            color = item?.labelColor ?: Color(HistoryChartPalette.label(color.toArgb())), fontSize = 10.sp)
                    }
                }
            }
        }, options = {
            TextButton({ mode = (mode + 1) % 3 }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(when (mode) { 0 -> "温度"; 1 -> "CPU / GPU"; else -> "累计耗电" }, fontSize = 11.sp)
                Icon(Icons.AutoMirrored.Outlined.CompareArrows, "切换帧率辅助指标", Modifier.padding(start = 4.dp).size(18.dp))
            }
        })
}
