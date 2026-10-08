package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.R
import top.qixia.threads.compose.QixiaThreadsUiState

/** 配置概览；开启自动模式不表示当前已有 CPU 分配。 */
@Composable
internal fun HomeSchedulingCard(state: QixiaThreadsUiState, onManageApps: () -> Unit) {
    val apps = state.applications
    val env = state.environment
    val loading = apps.loading || env.loading
    val readable = env.featuresAvailable && env.statusMessage == null
    val empty = !loading && readable && apps.configured.isEmpty()
    val subtitle = when {
        loading -> "正在读取应用配置"
        !readable -> "配置暂不可用，点击查看"
        empty -> "添加应用，开始线程优化"
        else -> "应用与核心分配，一目了然"
    }
    val showCounts = !loading && readable

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF234DB3), Color(0xFF3267D0))))
        .clickable(role = Role.Button, onClickLabel = "管理应用", onClick = onManageApps)
        .padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("线程调度", color = Color.White, fontSize = 23.sp,
                    lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color.White, fontSize = 11.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            ThreadRoutingIllustration(Modifier.size(72.dp, 48.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SchedulingCount(if (showCounts) apps.configured.size.toString() else "—",
                "已添加应用", Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(31.dp)
                .background(Color.White.copy(alpha = .2f)))
            SchedulingCount(if (showCounts) apps.configured.count { it.automaticAffinityEnabled }.toString() else "—",
                "开启自动分配", Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                HomeLineIcon(R.drawable.ic_mint_chevron, null, 16.dp, Color.White)
            }
        }
    }
}

@Composable
private fun SchedulingCount(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Color.White, fontSize = 29.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

/** 装饰性的线路图，不代表实时 CPU 数据或应用图标。 */
@Composable
private fun ThreadRoutingIllustration(modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 76f, size.height / 50f, pivot = Offset.Zero) {
            val ink = Color.White.copy(alpha = .72f)
            listOf(10f, 25f, 40f).forEachIndexed { index, y ->
                val destinationY = 19f + index * 6f
                val lane = Path().apply {
                    moveTo(5f, y)
                    lineTo(17f, y)
                    cubicTo(29f, y, 26f, destinationY, 38f, destinationY)
                    lineTo(47f, destinationY)
                }
                drawPath(lane, ink, style = Stroke(1.7f, cap = StrokeCap.Round))
                drawCircle(Color.White, 2.6f, Offset(5f, y))
            }
            drawRoundRect(Color.White.copy(alpha = .12f), Offset(46f, 10f), Size(28f, 30f), CornerRadius(8f))
            drawRoundRect(ink, Offset(46f, 10f), Size(28f, 30f), CornerRadius(8f), style = Stroke(1.5f))
            for (x in listOf(53f, 62f)) for (y in listOf(18f, 27f)) {
                drawRoundRect(Color.White, Offset(x, y), Size(5f, 5f), CornerRadius(1.5f))
            }
        }
    }
}
