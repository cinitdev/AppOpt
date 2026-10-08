package top.qixia.threads.compose

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LogTextFormatterTest {
    private fun event(sequence: Int, message: String, count: Int = 1) =
        "@QIXIA/1\t${1790848136200L + sequence}\t6189:$sequence\tINFO\tauto\t$count\t$message"

    @Test fun copiedStatusUsesRealNewlineAndReadableHeader() {
        val raw = event(26, "状态更新: pkg= state=idle\\n目标离开前台或自动分配已关闭，恢复系统调度")
        val entry = LogEventParser.parse(LogSource.DAEMON, raw).single()
        val copy = LogTextFormatter.readable(listOf(entry))
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(1790848136226L))
        assertEquals("[$time] [信息] [自动分配]\n状态更新: pkg= state=idle\n目标离开前台或自动分配已关闭，恢复系统调度", copy)
        assertFalse(copy.contains("@QIXIA/1"))
        assertFalse(copy.contains("\\n"))
        assertEquals(raw, entry.copyText)
    }

    @Test fun alreadyDecodedLiteralEscapesAndPathsAreNotDecodedTwice() {
        // 协议中的两个反斜杠代表消息中的一个字面反斜杠。
        val raw = event(1, "path=C:\\\\new\\\\temp\\n文本里的转义示例=\\\\n\\t未知转义=\\q")
        val entry = LogEventParser.parse(LogSource.DAEMON, raw).single()
        assertEquals("path=C:\\new\\temp\n文本里的转义示例=\\n\t未知转义=\\q", entry.message)
        val copy = LogTextFormatter.readable(listOf(entry))
        assertTrue(copy.endsWith(entry.message))
        assertTrue(copy.contains("path=C:\\new\\temp"))
        assertTrue(copy.contains("文本里的转义示例=\\n"))
        assertEquals(raw, entry.copyText)
    }

    @Test fun repeatCountAndFullTimeRangeRemainReadable() {
        val first = event(1, "等待前台")
        val last = event(1601, "等待前台", 4)
        val entry = LogEventParser.parse(LogSource.DAEMON, "$first\n$last").single()
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val copy = LogTextFormatter.readable(listOf(entry))
        assertTrue(copy.startsWith("[${clock.format(Date(1790848136201L))} 至 ${clock.format(Date(1790848137801L))}]"))
        assertTrue(copy.contains("[信息] [自动分配] · 5 次\n等待前台"))
        assertEquals("$first\n$last", entry.copyText)
    }

    @Test fun legacyTextKeepsMissingTimeAndFullMessage() {
        val raw = "[RS] 异常详情\n    at retained.stack\n    原始路径=C:\\new\\temp"
        val entry = LogEventParser.parse(LogSource.DAEMON, raw).single()
        val copy = LogTextFormatter.readable(listOf(entry))
        assertTrue(copy.startsWith("[时间未记录] [错误] [Rust]\n"))
        assertTrue(copy.endsWith("异常详情\n    at retained.stack\n    原始路径=C:\\new\\temp"))
        assertEquals(raw, entry.copyText)
    }

    @Test fun selectionOrderIsPreservedAndLongMessagesAreNotTruncated() {
        val longMessage = "详细消息".repeat(5000)
        val entries = LogEventParser.parse(LogSource.DAEMON, event(1, longMessage) + "\n" + event(2, "最新事件"))
        val copy = LogTextFormatter.readable(entries)
        assertTrue(copy.indexOf("最新事件") < copy.indexOf(longMessage))
        assertTrue(copy.endsWith(longMessage))
        assertEquals("", LogTextFormatter.readable(emptyList()))
    }
}
