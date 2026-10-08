package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test

class LogEventParserTest {
    @Test fun legacyEventsCannotBorrowTimestampsFromNewRecords() {
        val entries = LogEventParser.parse(LogSource.DAEMON, "[RS] 已启动\n" + event(1, "SUCCESS", "RS", "已启动"))
        assertEquals(2, entries.size)
        assertNull(entries.last().timestampMs)
        assertEquals(1800700000001L, entries.first().firstTimestampMs)
    }
    @Test fun coreListsRemainCompleteInEventParameters() {
        val entry = LogEventParser.parse(LogSource.DAEMON, "[auto] 调整: CPU=[4,5,7] name=RenderThread").single()
        assertTrue(entry.fields.contains("CPU" to "[4,5,7]"))
    }
    private fun event(sequence: Int, level: String, tag: String, message: String, count: Int = 1) =
        "@QIXIA/1\t${1800700000000L + sequence}\t42:$sequence\t$level\t$tag\t$count\t$message"

    @Test fun explicitSeverityWinsOverWordsInTheMessage() {
        val events = LogEventParser.parse(LogSource.DAEMON, listOf(
            event(1, "INFO", "RS", "运行摘要: 失败=0 异常=0"),
            event(2, "WARN", "FPS", "后端失败，已降级"),
            event(3, "ERROR", "auto", "写入被拒绝")
        ).joinToString("\n"))
        assertEquals(listOf(LogLevel.ERROR, LogLevel.WARNING, LogLevel.INFO), events.map { it.level })
        assertEquals(LogCategory.ALLOCATION, events.first().category)
        assertEquals("运行摘要", events.last().title)
        assertEquals(listOf("失败" to "0", "异常" to "0"), events.last().fields)
    }

    @Test fun wireEscapesMultilineAndBackslashesRoundTrip() {
        val raw = event(1, "INFO", "CALIB", "已生成建议\\nthread\\tcore\\\\name\\r结束")
        val entry = LogEventParser.parse(LogSource.DAEMON, raw).single()
        assertEquals("已生成建议\nthread\tcore\\name\r结束", entry.message)
        assertEquals(raw, entry.copyText)
        assertEquals(1800700000001L, entry.timestampMs)
    }

    @Test fun duplicateSummaryPreservesEveryRawRecordAndTimeRange() {
        val first = event(1, "ERROR", "RS", "读取失败")
        val repeat = event(9, "ERROR", "RS", "读取失败", 29)
        val entry = LogEventParser.parse(LogSource.DAEMON, "$first\n$repeat").single()
        assertEquals(30, entry.repeatCount)
        assertEquals(1800700000001L, entry.firstTimestampMs)
        assertEquals(1800700000009L, entry.timestampMs)
        assertEquals("$first\n$repeat", entry.copyText)
    }

    @Test fun legacyStackAndRulesAreKeptWithoutInventingTimestamps() {
        val entries = LogEventParser.parse(LogSource.DAEMON, "[RS] 运行摘要: 失败=0 异常=0 缺少映射=0\n[RS] 写入失败\n    at original\ncom.game {\n    Main=4\n}")
        assertEquals(3, entries.size)
        assertEquals(LogLevel.INFO, entries.last().level)
        assertEquals(LogLevel.ERROR, entries[1].level)
        assertTrue(entries[1].message.contains("at original"))
        assertTrue(entries.all { it.timestampMs == null })
        assertTrue(entries.first().copyText.endsWith("}"))
    }

    @Test fun incompleteLegacyRuleCannotSwallowTheNextStructuredEvent() {
        val entries = LogEventParser.parse(LogSource.DAEMON, "com.game {\n    broken\n" + event(1, "INFO", "RS", "启动完成"))
        assertEquals(2, entries.size)
        assertEquals("启动完成", entries.first().message)
    }

    @Test fun malformedWireRecordsRemainAvailableForDiagnosis() {
        val raw = "@QIXIA/1\tnot-time\t42:1\tERROR\tRS\t1\tbroken"
        assertEquals(raw, LogEventParser.parse(LogSource.DAEMON, raw).single().copyText)
    }

    @Test fun categorySeverityAndMultipleSearchWordsComposeTogether() {
        val entries = LogEventParser.parse(LogSource.DAEMON, listOf(
            event(1, "INFO", "auto", "分配完成: pkg=com.game name=RenderThread"),
            event(2, "ERROR", "auto", "分配失败: pkg=com.game name=Main"),
            event(3, "WARN", "FPS", "采集降级: pkg=com.game name=RenderThread")
        ).joinToString("\n"))
        val state = LogsUiState(entries = entries, query = "com.game renderthread", category = LogCategory.ALLOCATION)
        assertEquals(1, state.visibleEntries.size)
        assertEquals(0, state.copy(filter = LogFilter.ATTENTION).visibleEntries.size)
        assertEquals(2, state.copy(category = LogCategory.ALL).visibleEntries.size)
        assertEquals(1, state.copy(query = "", filter = LogFilter.ERROR).visibleEntries.size)
    }

    @Test fun tailMovementKeepsTimestampedEventIdentityStable() {
        val one = event(1, "INFO", "FPS", "开始采集")
        val two = event(2, "INFO", "FPS", "停止采集")
        assertEquals(LogEventParser.parse(LogSource.DAEMON, "$one\n$two").first().id,
            LogEventParser.parse(LogSource.DAEMON, two).single().id)
    }

    @Test fun oldZeroCountMatchingCannotHideNonZeroFailures() {
        val entries = LogEventParser.parse(LogSource.DAEMON, "[RS] 失败=01\n[RS] 失败=10")
        assertTrue(entries.all { it.level == LogLevel.ERROR })
    }
}
