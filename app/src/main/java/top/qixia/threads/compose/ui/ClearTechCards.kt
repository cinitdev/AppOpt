package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.QixiaThreadsUiState
import top.qixia.threads.compose.DeviceMetricsUiState
import top.qixia.threads.compose.theme.*
import java.util.Locale

@Composable
fun PerformanceHero(
    label: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    // 使用原生渐变表面效果，与提供的 CSS 保持一致。
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
        .background(Brush.linearGradient(listOf(OceanPrimary, ClearTechGradientEnd)))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = .85f))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            Text(description, color = Color.White, fontSize = 12.sp, lineHeight = 19.sp)
            content()
        }
    }
}

@Composable
fun DashboardHero(state: QixiaThreadsUiState) {
    val env = state.environment
    val permissionsReady = env.overlayGranted && env.usageAccessGranted
    PerformanceHero(
        label = "当前设备状态",
        title = when {
            env.loading -> "正在检查运行环境"
            !env.featuresAvailable -> "运行环境待完善"
            !permissionsReady -> "再一步，开始校准"
            else -> "性能校准已就绪"
        },
        description = when {
            env.loading -> "正在读取模块、守护进程与应用配置"
            !env.featuresAvailable -> env.statusMessage ?: "请在运行环境中检查 Root、模块与守护进程"
            !permissionsReady -> "授予悬浮窗与使用情况访问权限后即可开始"
            else -> "${env.configuredAppCount} 个应用 · ${env.ruleCount} 条规则 · ${env.moduleVersion?.versionName ?: "模块已连接"}"
        }
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CPU 使用率", color = Color.White, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
            Text(state.deviceMetrics.cpuPercent?.let { "$it%" } ?: "等待采样", color = Color.White,
                fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        LiveCpuSparkline(state.deviceMetrics.cpuHistory)
    }
}

@Composable
private fun LiveCpuSparkline(history: List<Float>) {
    val values = remember(history) { history.filter(Float::isFinite).map { it.coerceIn(0f, 100f) } }
    if (values.size < 2) {
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) {
            Text("正在收集负载趋势", color = Color.White, fontSize = 10.sp)
        }
        return
    }
    Canvas(Modifier.fillMaxWidth().height(36.dp).semantics {
        contentDescription = "最近 ${values.size} 次 CPU 使用率采样，最新 ${values.last().toInt()}%"
    }) {
        val inset = 3.dp.toPx()
        val step = (size.width - inset * 2) / (values.size - 1)
        fun point(index: Int) = Offset(inset + index * step, inset + (size.height - inset * 2) * (1f - values[index] / 100f))
        val path = Path().apply {
            val first = point(0)
            moveTo(first.x, first.y)
            for (index in 1 until values.size) {
                val previous = point(index - 1)
                val next = point(index)
                val mid = (previous.x + next.x) / 2
                cubicTo(mid, previous.y, mid, next.y, next.x, next.y)
            }
        }
        drawPath(path, Color(0xFFD9E5FF), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Color.White, 3.dp.toPx(), point(values.lastIndex))
    }
}

@Composable
fun DeviceOverview(metrics: DeviceMetricsUiState) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("CPU 使用率", metrics.cpuPercent?.let { "$it%" } ?: "—", "实时使用率", Modifier.weight(1f))
        MetricTile("内存占用", metrics.memoryPercent?.let { "$it%" } ?: "—", "设备内存", Modifier.weight(1f))
        MetricTile("电池电量", metrics.batteryPercent?.let { "$it%" } ?: "—", "当前剩余", Modifier.weight(1f))
    }
}

@Composable
fun MetricTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxHeight(), color = OceanSurface, shape = RoundedCornerShape(15.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
            Text(label, color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = OceanText, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DashboardMetrics(state: QixiaThreadsUiState) {
    val lastSession = state.applications.configured.filter { it.averageFps != null }
        .maxByOrNull { it.fpsSessionEndedAtMs }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("电池温度", state.deviceMetrics.temperatureCelsius?.let { String.format(Locale.US, "%.1f°", it) } ?: "—",
            if (state.deviceMetrics.temperatureCelsius == null) "等待数据" else "实时读数", Modifier.weight(1f))
        MetricTile("上次均帧", lastSession?.averageFps?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
            lastSession?.let { "FPS · ${it.label}" } ?: "暂无完成会话", Modifier.weight(1f))
        MetricTile("绑定规则", if (state.environment.loading) "—" else "${state.environment.ruleCount}",
            "${state.environment.configuredAppCount} 个应用", Modifier.weight(1f))
    }
}

@Composable
fun ActiveSessionCard(state: QixiaThreadsUiState, onManage: (String) -> Unit) {
    val pkg = state.deviceMetrics.activeTargetPackage ?: return
    val app = state.applications.configured.firstOrNull { it.packageName == pkg } ?: return
    Surface(onClick = { onManage(pkg) }, color = OceanSurface, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppDrawableIcon(app.icon, "${app.label} 图标")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("悬浮校准会话", color = OceanTextSecondary, fontSize = 11.sp)
                }
                StatusPill("进行中", OceanSuccess)
                Icon(Icons.Outlined.ChevronRight, null, tint = OceanTextSecondary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(OceanSuccess)
                Spacer(Modifier.width(6.dp))
                Text("通过悬浮球查看与控制本次会话", color = OceanTextSecondary, fontSize = 11.sp)
            }
        }
    }
}
