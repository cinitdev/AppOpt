package top.qixia.threads.compose

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用于剪贴板的易读文本；协议原文仍保留在 copyText 中用于诊断导出。 */
internal object LogTextFormatter {
    fun readable(entries: List<LogEntryModel>): String {
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return entries.joinToString("\n\n") { entry ->
            val time = entry.timestampMs?.let { end ->
                val start = entry.firstTimestampMs ?: end
                if (start == end) clock.format(Date(end))
                else "${clock.format(Date(start))} 至 ${clock.format(Date(end))}"
            } ?: "时间未记录"
            buildString {
                append("[$time] [${entry.level.label}] [${entry.tag}]")
                if (entry.repeatCount > 1) append(" · ${entry.repeatCount} 次")
                append('\n')
                // 解析器已对协议转义解码一次，重复解码会破坏路径或消息中的
                // 字面反斜杠，例如 `\\n`。
                append(entry.message)
            }
        }
    }
}
