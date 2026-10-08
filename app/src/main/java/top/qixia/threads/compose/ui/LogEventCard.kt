package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogEventCard(entry: LogEntryModel, expanded: Boolean, onExpand: () -> Unit, onCopy: () -> Unit) {
    val color = when (entry.level) {
        LogLevel.ERROR -> OceanError; LogLevel.WARNING -> OceanWarning
        LogLevel.SUCCESS -> OceanSuccess; LogLevel.INFO -> PorcelainOnTonal
    }
    val icon = when (entry.category) {
        LogCategory.ALLOCATION -> Icons.Outlined.Memory
        LogCategory.RULES -> Icons.AutoMirrored.Outlined.Rule
        LogCategory.CALIBRATION -> Icons.Outlined.Science
        LogCategory.FPS -> Icons.Outlined.Speed
        LogCategory.FOREGROUND -> Icons.Outlined.Visibility
        else -> Icons.Outlined.Terminal
    }
    val time = remember(entry.timestampMs, entry.firstTimestampMs) {
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        entry.timestampMs?.let { end ->
            if (entry.firstTimestampMs != null && entry.firstTimestampMs != end)
                "${formatter.format(Date(entry.firstTimestampMs))}\n至 ${formatter.format(Date(end))}"
            else formatter.format(Date(end))
        } ?: "时间未记录"
    }
    HistorySurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.category.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(time, color = OceanTextSecondary, fontSize = 10.sp)
                }
                StatusPill(entry.level.label, color)
            }
            Text(entry.title, color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.message != entry.title) Text(entry.message, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.tag + if (entry.repeatCount > 1) " · ${entry.repeatCount} 次" else "", color = OceanTextSecondary,
                    fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "收起" else "详情", fontSize = 12.sp)
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制此条日志", tint = PorcelainOnTonal, modifier = Modifier.size(17.dp))
                }
            }
            if (expanded) {
                HorizontalDivider(color = OceanDivider)
                if (entry.fields.isNotEmpty()) {
                    Text("事件参数", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OceanText)
                    entry.fields.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (key, value) ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(OceanBackground).padding(10.dp)) {
                                    Text(key, fontSize = 10.sp, color = OceanTextSecondary)
                                    SelectionContainer { Text(value, fontSize = 12.sp, color = OceanText, fontWeight = FontWeight.Medium) }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Text("消息详情", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OceanText)
                // 旧版标准输出日志可能包含无界的多行块；限制文字布局规模，
                // 复制和导出保持完整，以保留全部诊断内容。
                SelectionContainer { Text(entry.message.take(16384), fontSize = 11.sp, lineHeight = 18.sp, color = OceanText, fontFamily = FontFamily.Monospace) }
                if (entry.message.length > 16384) Text("长日志仅显示前半部分，复制或导出可查看完整内容。", fontSize = 10.sp, color = OceanTextSecondary)
                Text("复制保留正文换行，导出保留原始日志。" + if (entry.timestampMs == null) "旧格式没有时间字段，不补写推测时间。" else "",
                    fontSize = 10.sp, lineHeight = 16.sp, color = OceanTextSecondary)
            }
        }
    }
}
