package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import top.qixia.threads.compose.theme.OceanBackground

/** 品牌首帧之后准备主页，在同一内容树上退场，避免结束时才首次构建主页。 */
@Composable
internal fun QixiaLaunchHost(
    showLaunch: Boolean,
    started: Boolean,
    onFinished: () -> Unit,
    content: @Composable (startupBlocked: Boolean) -> Unit
) {
    var prepareContent by remember { mutableStateOf(!showLaunch) }
    var brandDrawn by remember { mutableStateOf(false) }
    var contentDrawn by remember { mutableStateOf(!showLaunch) }
    LaunchedEffect(showLaunch, started, brandDrawn) {
        if (!showLaunch) prepareContent = true
        else if (started && brandDrawn) {
            // 先交付品牌首帧，主页初始化放在已经可见的两秒动画期间。
            withFrameNanos { }
            prepareContent = true
        }
    }
    Box(Modifier.fillMaxSize().background(OceanBackground)) {
        if (prepareContent) {
            Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (!contentDrawn) contentDrawn = true
            }.onPreviewKeyEvent { showLaunch }
                .then(if (showLaunch) Modifier.clearAndSetSemantics { } else Modifier)) {
                content(showLaunch)
            }
        }
        if (showLaunch) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) {
                QixiaLaunchScreen(started, readyToReveal = contentDrawn,
                    onFirstVisibleFrame = { brandDrawn = true }, onFinished = onFinished)
            }
        }
    }
}
