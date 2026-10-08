package top.qixia.threads.compose.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.HistoryBatteryDrain
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.*
import kotlin.math.abs

@Composable
internal fun HistoryFpsPlotCard(fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?, durationMs: Long,
    cursor: MutableState<Float?>, markers: List<Float> = emptyList(), onMarkerSelected: (Float) -> Unit = {}) {
    val drain = remember(metrics) { HistoryBatteryDrain.from(metrics?.series?.get("battery_pct")) }
    val available = remember(fps, metrics, drain) { buildList {
        if (fps != null) add(HistoryPlotSeries(HistoryMetrics.Series("fps", fps.average, fps.minimum, fps.maximum,
            fps.samples, fps.points.map { HistoryMetrics.Point(it.progress, it.fps, it.breakBefore) }), "FPS", "FPS", ReportBlue))
        listOf("cpu_c", "cpu_usage", "gpu_usage").forEach { key -> metrics?.series?.get(key)?.let {
            add(HistoryPlotSeries(it, when (key) { "cpu_c" -> "CPU 温度"; "cpu_usage" -> "CPU 使用率"; else -> "GPU 使用率" },
                HistoryMetrics.unit(key), when (key) { "cpu_c" -> ReportAmber; "cpu_usage" -> ReportPurple; else -> ReportTeal }, true))
        } }
        drain?.let { add(HistoryPlotSeries(it.series, "累计耗电", "%", ReportRose, true)) }
    } }
    // 所有可用曲线默认选中，隐藏一条不会关闭其他指标。
    val auxiliaryAxis = remember(available) {
        HistoryPlotMath.fpsAuxiliaryAxis(available.filter { it.rightAxis }.flatMap { it.points.map { point -> point.value } })
    }
    val auxiliaryUnit = remember(available) {
        available.filter { it.rightAxis }.map { it.unit }.distinct().joinToString(" / ").ifEmpty { null }
    }
    val status = buildList {
        if (fps == null) add("本次未保存对应的 FPS 样本")
        if (available.none { it.data.key == "cpu_c" }) add("本次未采集 CPU 温度")
        if (available.none { it.data.key == "cpu_usage" }) add("本次未采集 CPU 使用率")
        if (available.none { it.data.key == "gpu_usage" }) add("本次未采集 GPU 使用率")
        if (drain == null) add("有效电量样本不足，无法计算本次耗电") else {
            val charging = (metrics?.chargingSamples ?: 0) > 0
            val summary = if (drain.consumed < 0) "电量净增加" else if (charging) "电量净减少" else "本次耗电"
            add("起始 ${historyNumber(drain.start)}% → 结束 ${historyNumber(drain.end)}% · $summary ${historyNumber(abs(drain.consumed))}%")
            if (charging) add("记录中含充电，曲线表示电量净变化；负值表示电量高于起始值。")
            else if (drain.series.minimum < 0) add("记录中电量有回升，曲线保留电量计的实际变化。")
        }
    }.joinToString("\n").ifEmpty { null }
    HistoryReportPlotCard("帧率与运行状态", available, durationMs, "FPS", auxiliaryUnit,
        description = "左轴为 FPS。温度（°C）、CPU/GPU 使用率（%）和累计耗电（%）同时显示，共用右轴的数值刻度；游标和指标表分别标明实际单位。右轴至少覆盖 0–100，并保留超出范围的温度与负耗电值。\n\n有数据的指标默认全部勾选，点击底部勾选框可单独隐藏或显示曲线，不会影响其他指标。未采集的指标不可勾选，不补零。\n\n耗电按本次首次有效电量读数减去各时刻读数计算，末值为首次与末次有效读数之差（例如 85% → 82% 为 3%）。充电或电量回升保留净变化；不代表应用单独消耗的电量。\n\n按住或横向拖动可联动查看真实样本，松开收起；指标默认收拢。温度仅用于报告。",
        emptyMessage = "本次未保存所选指标", status = status, cursor = cursor,
        rightAxis = auxiliaryAxis,
        markers = markers, onMarkerSelected = onMarkerSelected,
        legendContent = { visible, setVisible ->
            FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                fun isAvailable(key: String) = available.any { it.data.key == key }
                @Composable fun entry(key: String, label: String, color: Color) {
                    val selected = visible.any { it.data.key == key }
                    FpsLegend(label, color, selected, isAvailable(key)) {
                        setVisible(key, !selected)
                    }
                }
                entry("fps", "FPS", ReportBlue)
                entry("cpu_c", "TEMP", ReportAmber)
                entry("cpu_usage", "CPU%", ReportPurple)
                entry("gpu_usage", "GPU%", ReportTeal)
                entry("battery_used_pct", "耗电%", ReportRose)
            }
        })
}

@Composable
private fun FpsLegend(label: String, color: Color, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 7.dp)
        .toggleable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = available, role = Role.Checkbox, onValueChange = { onClick() })
        .semantics { stateDescription = if (!available) "未采集" else if (selected) "已显示" else "未显示" },
        verticalAlignment = Alignment.CenterVertically) {
        HistoryLegendMark(color, selected)
        Spacer(Modifier.width(4.dp))
        Text(label, color = OceanText, fontSize = 10.sp)
    }
}
