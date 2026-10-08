package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.compose.HistoryChartPalette
import top.qixia.threads.compose.HistoryCpuGroups
import top.qixia.threads.compose.HistoryPlotMath
import top.qixia.threads.compose.theme.*

internal enum class MetricsGroup(val title: String, val unit: String) {
    FRAME_TIME("帧耗时", "ms"), CPU_USAGE("CPU 使用率", "%"), CPU_FREQUENCY("CPU 频率", "MHz"),
    CPU_CYCLES("CPU Cycles", "M"), GPU_FREQUENCY("GPU 频率与使用率", "MHz"),
    DDR("DDR", "MHz"), POWER("功率、电流与电量", "W / A"), TEMPERATURE("CPU / GPU 温度", "°C")
}

@Composable
internal fun HistoryMetricsCard(group: MetricsGroup, report: HistoryMetrics.Report?, durationMs: Long,
    cursor: MutableState<Float?>) {
    var mode by rememberSaveable(group) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val options = when (group) {
        MetricsGroup.CPU_FREQUENCY -> listOf("频率曲线", "频率分布")
        else -> emptyList()
    }
    val items = remember(group, report, mode) { report?.series?.values?.filter { item ->
        val key = item.key
        when (group) {
            MetricsGroup.CPU_USAGE -> key == "cpu_usage" || key.startsWith("cpu_core.")
            MetricsGroup.CPU_FREQUENCY -> key.startsWith("cpu_mhz.")
            MetricsGroup.CPU_CYCLES -> key.startsWith("cpu_cycles_core.") || key == "cpu_c"
            MetricsGroup.GPU_FREQUENCY -> key == "gpu_mhz" || key == "gpu_usage"
            MetricsGroup.DDR -> key.startsWith("ddr_")
            MetricsGroup.POWER -> key in listOf("power_w", "battery_ma", "battery_pct")
            MetricsGroup.TEMPERATURE -> key in listOf("cpu_c", "gpu_c", "battery_c")
            MetricsGroup.FRAME_TIME -> key == "frame_max_ms"
        }
    }?.sortedWith(compareBy<HistoryMetrics.Series> {
        if (group == MetricsGroup.POWER) when (it.key) { "power_w" -> 0; "battery_ma" -> 1; else -> 2 }
        else if (it.key == "cpu_usage") -1 else it.key.substringAfter('.').substringBefore('_').toIntOrNull() ?: 999
    }.thenBy { it.key }).orEmpty() }
    val cpuGroups = remember(report) { HistoryCpuGroups.fromKeys(report?.series?.keys.orEmpty()) }
    val series = remember(items, group, cpuGroups) { items.mapIndexed { index, item ->
        val label = when (item.key) {
            "cpu_usage" -> "总使用率"
            "gpu_usage" -> "GPU 使用率"
            "gpu_mhz" -> "GPU 频率"
            "power_w" -> "放电功率"
            "battery_ma" -> "放电电流"
            "battery_pct" -> "电量"
            "cpu_c" -> "CPU 温度"
            "gpu_c" -> "GPU 温度"
            "battery_c" -> "电池温度"
            "ddr_mhz" -> "DDR 频率"
            "ddr_mbps" -> "DDR 数据率"
            "frame_max_ms" -> "最大帧间隔"
            else -> HistoryPlotMath.cpuLabel(item.key)
        }
        val cpuGroup = cpuGroups.forMetric(item.key)
        HistoryPlotSeries(item, label, HistoryMetrics.unit(item.key), Color(cpuGroup?.color ?: HistoryChartPalette.metric(item.key, index)),
            rightAxis = item.key == "battery_pct" || item.key == "gpu_usage" || group == MetricsGroup.CPU_CYCLES && item.key == "cpu_c",
            legendKey = cpuGroup?.key ?: item.key,
            legendLabel = if (group == MetricsGroup.POWER) when (item.key) {
                "power_w" -> "功率 W"; "battery_ma" -> "电流 A"; else -> "电量 %"
            } else cpuGroup?.label ?: label,
            valueScale = if (group == MetricsGroup.POWER && item.key == "battery_ma") .001f else 1f)
    } }
    val note = when (group) {
        MetricsGroup.CPU_USAGE -> "绘制总使用率和每一颗核心的独立曲线。按本次记录保存的核心簇分组，同组核心共用颜色和图例，游标与指标表仍逐核心显示。点击组图例可统一开关，在图表选项中可单独选择核心。没有核心簇信息的旧记录保留逐核心配色。不会用簇平均值代替每颗核心的使用率，离线、缺失或计数重置不补零。"
        MetricsGroup.CPU_FREQUENCY -> "按实际核心簇显示驱动报告的频率。频率分布是各频率在有效样本中出现的比例，不代表驱动精确的驻留时间。档位很多时，相邻频率合并为最多 32 组并标注区间，避免长记录展开大量控件。"
        MetricsGroup.CPU_CYCLES -> "硬件 CPU_CYCLES 计数器，按相邻采样的经过时间换算为每秒百万周期（M）。每颗核心独立绘图，同一核心簇共用颜色和图例，与 CPU 使用率保持一致；游标与指标表仍逐核心显示。点击组图例可统一开关，在图表选项中可单独选择核心。右轴为 CPU 温度。内核不开放 PMU 时留空，不以频率乘使用率伪造计数。"
        MetricsGroup.GPU_FREQUENCY -> "左轴 MHz 为 GPU 频率，右轴 % 为驱动报告的 GPU 使用率。两者分别采集，设备不支持的指标留空。"
        MetricsGroup.DDR -> "根据设备节点提供的单位展示内存频率（MHz）或数据率（Mbps），不把总线带宽请求当作 DDR 实际频率。不支持的设备显示未采集。"
        MetricsGroup.POWER -> "放电功率、放电电流与剩余电量同时显示，可通过底部勾选框独立隐藏。左轴为功率 W / 电流 A，电流仅在绘图时由 mA 换算为 A（1000 mA = 1 A）；游标与指标表仍显示原始 mA。右轴为剩余电量 %。功率由电池电流 × 电压估算。充电时电池读数不等于整机功率，因此排除这些时段的功率和放电电流，曲线保留缺口。"
        MetricsGroup.TEMPERATURE -> "CPU / GPU 取已识别传感器的最高读数，电池温度独立显示。温度只用于报告，不参与分核算法。"
        MetricsGroup.FRAME_TIME -> "记录目标 Surface 相邻提交之间的真实间隔，图中每点为采样窗口最大值。不等同于 GPU 渲染耗时；旧模块或不支持的帧源留空，不用 1000 / FPS 代替。"
    } + "\n\n设备指标每 2 秒记录一次。最高、最低、平均取全部有效原始样本；长曲线缩减绘图点并保留极值和缺失断点。图例始终保留指标颜色，勾选表示显示，取消勾选表示隐藏。点击图例可切换曲线；按住或横向拖动，联动查看各图同一时刻附近的采样，松开后收起。展开指标可查看完整统计。"
    val missing = when (group) {
        MetricsGroup.CPU_USAGE -> if (items.none { it.key.startsWith("cpu_cluster.") || it.key.startsWith("cpu_core.") }) "此记录未保存分核心使用率；新采集会记录全部核心" else null
        MetricsGroup.CPU_CYCLES -> if (items.none { it.key.startsWith("cpu_cycles_core.") }) "本次未取得硬件周期计数" else null
        MetricsGroup.GPU_FREQUENCY -> if (items.none { it.key == "gpu_usage" }) "本次未取得 GPU 使用率" else null
        MetricsGroup.POWER -> buildList {
            if (items.none { it.key == "power_w" }) add("本次未取得放电功率")
            if (items.none { it.key == "battery_ma" }) add("本次未取得放电电流")
            if (items.none { it.key == "battery_pct" }) add("本次未取得电量")
            if ((report?.chargingSamples ?: 0) > 0) add("${report?.chargingSamples} 个接电样本不计入放电统计")
        }.joinToString(" · ").ifEmpty { null }
        else -> null
    }
    Column {
        HistoryReportPlotCard(group.title,
            if (group == MetricsGroup.CPU_FREQUENCY && mode == 1) emptyList() else series, durationMs,
            if (group == MetricsGroup.DDR && items.any { it.key == "ddr_mbps" }) "Mbps" else group.unit,
            rightUnit = when (group) { MetricsGroup.CPU_CYCLES -> "°C"; MetricsGroup.POWER, MetricsGroup.GPU_FREQUENCY -> "%"; else -> null },
            description = note, emptyMessage = if (group == MetricsGroup.CPU_FREQUENCY && mode == 1) "频率样本分布见下表" else "本次未采集此指标", status = missing,
            cursor = cursor,
            showSeriesOptions = group != MetricsGroup.CPU_FREQUENCY,
            options = {
                if (options.isNotEmpty()) Box {
                    TextButton({ menu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(options[mode], fontSize = 10.sp) }
                    DropdownMenu(menu, { menu = false }) { options.forEachIndexed { i, option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { mode = i; menu = false })
                    } }
                }
            }, content = if (group == MetricsGroup.CPU_FREQUENCY && mode == 1) ({ HistoryFrequencyDistribution(report) }) else null)
    }
}

@Composable
private fun HistoryFrequencyDistribution(report: HistoryMetrics.Report?) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (report?.distributions.isNullOrEmpty()) Text("此记录没有频率分布样本", color = OceanTextSecondary)
            report?.distributions?.forEach { (key, values) ->
                Text(HistoryPlotMath.cpuLabel(key), color = OceanText, modifier = Modifier.padding(vertical = 8.dp))
                values.forEach { (minimum, maximum, percent) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text((if (minimum == maximum) historyNumber(minimum) else "${historyNumber(minimum)}–${historyNumber(maximum)}") + " MHz", color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("${historyNumber(percent)}%", color = PorcelainOnTonal, fontSize = 11.sp)
                    }
                    LinearProgressIndicator(progress = { percent / 100 }, color = Color(HistoryChartPalette.metric(key)), trackColor = OceanSurfaceHigh,
                        modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
                }
            }
        }
}
