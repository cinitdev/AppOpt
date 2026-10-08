package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.LogEntryModel
import top.qixia.threads.compose.LogLevel
import top.qixia.threads.compose.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 仅调整展示，筛选和复制继续使用原始的独立事件。 */
@Composable
internal fun LogStartupCard(
    entries: List<LogEntryModel>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCopy: () -> Unit
) {
    if (entries.isEmpty()) return
    val details = remember(entries) { startupDetails(entries) }
    val eventTimes = remember(entries) {
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        entries.map { entry -> entry.timestampMs?.let { formatter.format(Date(it)) } ?: "时间未记录" }
    }
    val time = if (eventTimes.first() == eventTimes.last()) eventTimes.first()
        else "${eventTimes.first()} 至 ${eventTimes.last()}"

    HistorySurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(PorcelainHeader),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Terminal, null, tint = PorcelainOnTonal, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("守护启动", color = OceanText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(time, color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制这组启动日志", tint = PorcelainOnTonal, modifier = Modifier.size(18.dp))
                }
            }

            if (details.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(OceanBackground).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    details.forEach { (label, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 18.sp)
                            Text(value, color = OceanText, fontSize = 12.sp, lineHeight = 18.sp,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Text("启动过程中的相关信息已合并，展开可逐条查看。", color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} 条启动信息", color = OceanTextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "收起明细" else "展开明细", fontSize = 12.sp)
                    Spacer(Modifier.width(3.dp))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, modifier = Modifier.size(18.dp))
                }
            }

            if (expanded) {
                HorizontalDivider(color = OceanDivider)
                // 限制异常或过长启动日志的布局开销，复制时仍保留全部事件。
                var remainingCharacters = 65536
                entries.take(128).forEachIndexed { index, entry ->
                    if (remainingCharacters > 0) {
                        val displayed = entry.message.take(minOf(4096, remainingCharacters))
                        remainingCharacters -= displayed.length
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(eventTimes[index], color = OceanTextSecondary, fontSize = 10.sp,
                                    modifier = Modifier.weight(1f))
                                val color = when (entry.level) {
                                    LogLevel.WARNING -> OceanWarning
                                    LogLevel.ERROR -> OceanError
                                    else -> PorcelainOnTonal
                                }
                                Text(entry.tag + if (entry.repeatCount > 1) " · ${entry.repeatCount} 次" else "",
                                    color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            SelectionContainer {
                                Text(displayed, color = OceanText, fontSize = 12.sp, lineHeight = 19.sp)
                            }
                            if (displayed.length < entry.message.length) {
                                Text("内容较长，复制可查看完整记录。", color = OceanTextSecondary, fontSize = 10.sp)
                            }
                        }
                        if (index < entries.lastIndex) HorizontalDivider(color = OceanDivider)
                    }
                }
                Text(if (entries.size > 128 || remainingCharacters <= 0)
                    "部分长日志已省略显示，复制或导出可查看全部内容。"
                else "按发生顺序显示；复制保留正文换行，导出保留原始日志。",
                    color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp)
            }
        }
    }
}

private fun startupDetails(entries: List<LogEntryModel>): List<Pair<String, String>> {
    val messages = entries.map { it.message }
    fun after(prefix: String): String? = messages.firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)?.trim()?.takeIf(String::isNotEmpty)
    val version = after("启动 QixiaThreads Rust 守护") ?: after("QixiaThreads 版本")

    val device = after("设备品牌:") ?: after("设备品牌：")
    val android = after("Android 版本:") ?: after("Android 版本：")
    val rules = after("规则加载完成:") ?: after("规则加载完成：")
    return buildList {
        version?.let { add("版本" to "QiXiaRs $it") }
        listOfNotNull(device, android).takeIf { it.isNotEmpty() }?.let { add("设备" to it.joinToString(" · ")) }
        rules?.let { add("规则" to it) }
    }
}
