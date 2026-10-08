package top.qixia.threads.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.qixia.threads.R
import top.qixia.threads.compose.theme.*

internal const val QIXIA_LAUNCH_DURATION_MS = 2_000L

/** 系统启动画面退出后开始计时，执行有限时长的品牌动画。 */
@Composable
internal fun QixiaLaunchScreen(started: Boolean, readyToReveal: Boolean = true,
    onFirstVisibleFrame: () -> Unit = {}, onFinished: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    val signature = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val finish by rememberUpdatedState(onFinished)
    val revealReady by rememberUpdatedState(readyToReveal)
    val firstVisibleFrame by rememberUpdatedState(onFirstVisibleFrame)
    var visibleFrameReported by remember { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        withFrameNanos { }
        launch { entrance.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        launch { signature.animateTo(1f, tween(420, delayMillis = 180)) }
        // 实际时长不受用户设置的动画时长缩放影响。
        // 禁用动画时，内容保持静止并显示指定的两秒。
        delay(QIXIA_LAUNCH_DURATION_MS - 220L)
        // 只等待主页首帧，不等待 Root、网络或历史加载完成。
        snapshotFlow { revealReady }.first { it }
        launch { exit.animateTo(0f, tween(220)) }
        delay(220L)
        finish()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }
            .drawWithContent {
                drawContent()
                // 在绘制阶段读取入场透明度，避免仅更新图层属性时漏掉首次可见帧。
                if (!visibleFrameReported && entrance.value > 0f) {
                    visibleFrameReported = true
                    firstVisibleFrame()
                }
            }
            .background(OceanBackground).testTag("qixia-launch")
    ) {
        val compact = maxHeight < 480.dp
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(OceanBackground, Color.White)))
                .safeDrawingPadding().padding(horizontal = 28.dp, vertical = if (compact) 12.dp else 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    alpha = entrance.value
                    translationY = 16.dp.toPx() * (1f - entrance.value)
                    scaleX = .97f + .03f * entrance.value
                    scaleY = scaleX
                }, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painterResource(R.drawable.qixia_launcher_affinity_day), null,
                    Modifier.size(if (compact) 64.dp else 100.dp).clip(RoundedCornerShape(if (compact) 20.dp else 30.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
                Text(stringResource(R.string.app_name), fontSize = if (compact) 28.sp else 34.sp,
                    lineHeight = if (compact) 36.sp else 45.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, color = OceanText, textAlign = TextAlign.Center)
                Spacer(Modifier.height(7.dp))
                Text(stringResource(R.string.qixia_english_name), fontSize = 14.sp, lineHeight = 21.sp,
                    letterSpacing = 2.sp, fontWeight = FontWeight.Medium, color = PorcelainOnTonal,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
                Box(Modifier.size(28.dp, 3.dp).clip(CircleShape).background(OceanPrimary.copy(alpha = .45f)))
                Spacer(Modifier.height(13.dp))
                Text(stringResource(R.string.qixia_tagline), fontSize = 13.sp, lineHeight = 21.sp,
                    letterSpacing = 1.sp, color = OceanTextSecondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(if (compact) .7f else 1.25f))
            Row(
                Modifier.graphicsLayer {
                    alpha = signature.value
                    translationY = 8.dp.toPx() * (1f - signature.value)
                }.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 保留原竖版截图，通过居中裁切显示方形头像。
                Image(painterResource(R.drawable.qixia_author_avatar),
                    stringResource(R.string.qixia_author_name) + "的酷安头像",
                    Modifier.size(if (compact) 38.dp else 48.dp).clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape).testTag("qixia-author-avatar"),
                    contentScale = ContentScale.Crop)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.qixia_author_name), fontSize = 14.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold, color = OceanText)
                    Text(stringResource(R.string.qixia_author_source), fontSize = 11.sp,
                        lineHeight = 17.sp, color = OceanTextSecondary, letterSpacing = 1.sp)
                }
            }
        }
    }
}
