package top.qixia.threads.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.CoreTableIndex
import top.qixia.threads.compose.CoreTimelinePresentation as Timeline
import top.qixia.threads.compose.theme.OceanDivider
import top.qixia.threads.compose.theme.OceanOutline
import top.qixia.threads.compose.theme.OceanPrimary
import top.qixia.threads.compose.theme.OceanSurface
import top.qixia.threads.compose.theme.OceanSurfaceHigh
import top.qixia.threads.compose.theme.OceanText
import top.qixia.threads.compose.theme.OceanTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个持续保留的时间选择控制所有行，拖动时刻度尺不扫描事件。 */
@Composable
internal fun CoreTimeNavigator(index: CoreTableIndex, selectedTime: MutableLongState) {
    val enabled = index.endMs > index.startMs
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = OceanSurface,
        border = BorderStroke(1.dp, OceanOutline.copy(alpha = .75f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            CoreTimeReadout(index, selectedTime)
            Box(
                Modifier.fillMaxWidth().padding(top = 3.dp).height(48.dp)
                    .testTag("core-time-scrubber")
                    .semantics {
                        contentDescription = "调度记录时间游标，左右滑动更新整张表格"
                        val progress = index.progressAt(selectedTime.longValue).coerceIn(0f, 1f)
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        stateDescription = "记录进度 " + Timeline.relativeTime(selectedTime.longValue, index.startMs)
                        if (!enabled) disabled()
                        setProgress { value ->
                            if (!enabled || !value.isFinite()) false
                            else {
                                val time = index.timeAt(value.coerceIn(0f, 1f))
                                val changed = selectedTime.longValue != time
                                selectedTime.longValue = time
                                changed
                            }
                        }
                    }
                    .pointerInput(index, selectedTime, enabled) {
                        detectTapGestures { position ->
                            if (enabled) {
                                val inset = 6.dp.toPx()
                                val progress = ((position.x - inset) / (size.width - inset * 2).coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                selectedTime.longValue = index.timeAt(progress)
                            }
                        }
                    }
                    .pointerInput(index, selectedTime, enabled) {
                        detectHorizontalDragGestures { change, _ ->
                            if (enabled) {
                                change.consume()
                                val inset = 6.dp.toPx()
                                val progress = ((change.position.x - inset) / (size.width - inset * 2).coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                selectedTime.longValue = index.timeAt(progress)
                            }
                        }
                    },
            ) {
                // 预先缓存的静态刻度尺，只有游标图层依赖选中时间。
                Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                    val inset = 6.dp.toPx()
                    val width = (size.width - inset * 2).coerceAtLeast(1f)
                    val baseline = 21.dp.toPx()
                    onDrawBehind {
                        drawLine(OceanOutline, Offset(inset, baseline), Offset(inset + width, baseline), 3.dp.toPx(), StrokeCap.Round)
                        repeat(21) { tick ->
                            val x = inset + width * tick / 20f
                            drawLine(OceanOutline, Offset(x, baseline + 9.dp.toPx()),
                                Offset(x, baseline + (if (tick % 5 == 0) 17 else 13).dp.toPx()), 1.dp.toPx())
                        }
                    }
                })
                Canvas(Modifier.fillMaxSize()) {
                    // 只有这里的绘制随时间变化，拖动时不扫描事件。
                    val progress = index.progressAt(selectedTime.longValue).coerceIn(0f, 1f)
                    val inset = 6.dp.toPx()
                    val x = inset + (size.width - inset * 2).coerceAtLeast(1f) * progress
                    val y = 21.dp.toPx()
                    drawLine(OceanPrimary, Offset(inset, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
                    drawCircle(OceanPrimary.copy(alpha = .12f), 11.dp.toPx(), Offset(x, y))
                    drawCircle(OceanPrimary, 7.dp.toPx(), Offset(x, y))
                    drawLine(OceanSurface, Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("开始 " + Timeline.relativeTime(index.startMs, index.startMs), color = OceanTextSecondary, fontSize = 10.sp)
                Text("拖动查看该时刻的线程", color = OceanTextSecondary, fontSize = 10.sp)
                Text(Timeline.relativeTime(index.endMs, index.startMs) + " 结束", color = OceanTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CoreTimeReadout(index: CoreTableIndex, selectedTime: MutableLongState) {
    val timestamp = selectedTime.longValue
    val formatter = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }
    val absoluteTime = remember(timestamp / 1000L) { formatter.format(Date(timestamp)) }
    val previous = index.previousTime(timestamp)
    val next = index.nextTime(timestamp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("当前游标", color = OceanTextSecondary, fontSize = 10.sp)
            Text(absoluteTime, color = OceanText, fontSize = 16.sp, lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold)
        }
        val colors = IconButtonDefaults.iconButtonColors(
            containerColor = OceanSurfaceHigh, contentColor = OceanPrimary,
            disabledContainerColor = OceanSurfaceHigh, disabledContentColor = OceanTextSecondary.copy(alpha = .35f),
        )
        IconButton(onClick = { previous?.let { selectedTime.longValue = it } }, enabled = previous != null,
            modifier = Modifier.size(44.dp), colors = colors) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一记录时刻", Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { next?.let { selectedTime.longValue = it } }, enabled = next != null,
            modifier = Modifier.size(44.dp), colors = colors) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一记录时刻", Modifier.size(18.dp))
        }
    }
}
