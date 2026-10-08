package top.qixia.threads.compose

import top.qixia.threads.LogBlockParser
import top.qixia.threads.StableLogEntryId

/** 明确的 v1 事件与旧版日志块共用展示模型，原文仍保留。 */
internal object LogEventParser {
    private val tagPattern = Regex("^\\[([^]]+)]\\s*")
    private val zeroCount = Regex("(?:失败|错误|异常|无效规则|系统限制|抢写|缺少映射)(?:[=:：]|\\s+)0(?![\\d.])|(?:已)?跳过[=:：]\\d+")
    private val fieldPattern = Regex("([\\p{L}\\p{N}_/-]+)[=:：](\\[[^\\]\\r\\n]*]|[^\\s,，;；]+)")
    private val sequencePattern = Regex("\\d+:\\d+")

    fun parse(source: LogSource, text: String): List<LogEntryModel> {
        val occurrences = mutableMapOf<String, Int>()
        val result = mutableListOf<LogEntryModel>()
        val startup = LogStartupSession()
        // 损坏或不完整的记录回退为原文，不让报告崩溃。
        LogBlockParser.split(text).forEach { block ->
            val raw = block.text
            val occurrence = occurrences.getOrDefault(raw, 0)
            occurrences[raw] = occurrence + 1
            val record = structured(raw)
            val match = tagPattern.find(raw)
            val tag = record?.tag ?: match?.groupValues?.get(1).orEmpty()
            val message = record?.message ?: if (match != null) raw.removeRange(match.range).trimStart() else raw
            val level = record?.level ?: legacyLevel(message)
            val category = category(source, tag, message)
            val id = StableLogEntryId.from(source.ordinal, raw, occurrence)
            val entry = LogEntryModel(
                id, block.lineNumber,
                displayTag(source, tag), level, message, raw, record?.count ?: 1,
                record?.timestamp, category = category, title = title(message, category),
                fields = fieldPattern.findAll(message).take(24).map { it.groupValues[1] to it.groupValues[2] }.toList(),
                startupId = if (source == LogSource.DAEMON && record != null)
                    startup.groupId(id, record.processId, record.sequence, record.timestamp, tag, level, message)
                else null
            )
            val previous = result.lastOrNull()
            if (previous != null && (previous.timestampMs == null) == (entry.timestampMs == null) &&
                previous.startupId == entry.startupId && previous.level == entry.level &&
                previous.tag == entry.tag && previous.message == entry.message) {
                result[result.lastIndex] = previous.copy(
                    timestampMs = entry.timestampMs, lineNumber = entry.lineNumber,
                    repeatCount = (previous.repeatCount.toLong() + entry.repeatCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    copyText = previous.copyText + "\n" + entry.copyText
                )
            } else result += entry
        }
        return result.takeLast(1000).asReversed()
    }

    private data class Record(val timestamp: Long, val level: LogLevel, val tag: String, val count: Int,
        val message: String, val processId: String, val sequence: Long)
    private fun structured(raw: String): Record? {
        if (!raw.startsWith("@QIXIA/1\t")) return null
        val parts = raw.split('\t', limit = 7)
        if (parts.size != 7 || !sequencePattern.matches(parts[2])) return null
        val timestamp = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val level = when (parts[3]) {
            "INFO" -> LogLevel.INFO; "WARN" -> LogLevel.WARNING; "ERROR" -> LogLevel.ERROR; "SUCCESS" -> LogLevel.SUCCESS
            else -> return null
        }
        val count = parts[5].toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: return null
        val sequence = parts[2].substringAfter(':').toLongOrNull() ?: return null
        return Record(timestamp, level, unescape(parts[4]), count, unescape(parts[6]),
            parts[2].substringBefore(':'), sequence)
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c != '\\' || index == text.length) append(c)
            else when (val next = text[index++]) {
                'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t'); '\\' -> append('\\')
                else -> { append('\\'); append(next) }
            }
        }
    }

    private fun legacyLevel(message: String): LogLevel {
        val text = zeroCount.replace(message, "").lowercase()
        return when {
            listOf("fatal", "panic", "failed", "exception", "失败", "错误", "异常", "无法", "崩溃", "not attached").any(text::contains) -> LogLevel.ERROR
            listOf("警告", "降级", "超时", "未检测", "未找到", "缺少", "重试", "已停用", "不可用").any(text::contains) -> LogLevel.WARNING
            listOf("成功", "完成", "已启动", "已加载", "已激活", "已确认", "已更新", "已监听").any(text::contains) -> LogLevel.SUCCESS
            else -> LogLevel.INFO
        }
    }

    private fun category(source: LogSource, tag: String, message: String): LogCategory = when {
        tag.equals("auto", true) -> LogCategory.ALLOCATION
        tag.equals("CALIB", true) || tag.contains("校准") -> LogCategory.CALIBRATION
        tag.equals("FPS", true) || tag.equals("Fallback", true) || tag.contains("eBPF", true) -> LogCategory.FPS
        source == LogSource.FOREGROUND || tag.contains("前台") -> LogCategory.FOREGROUND
        listOf("规则", "运行摘要", "绑核", "命中详情", "进程索引", "扫描计划", "UID 映射", "cpuset辅助").any(message::contains) -> LogCategory.RULES
        else -> LogCategory.SERVICE
    }

    private fun displayTag(source: LogSource, tag: String) = when (tag.lowercase()) {
        "rs" -> "Rust"; "auto" -> "自动分配"; "calib" -> "校准"; "fps" -> "帧率"; "ctrl" -> "控制服务"
        "" -> source.label; else -> tag.take(32)
    }

    private fun title(message: String, category: LogCategory): String {
        val first = message.lineSequence().firstOrNull().orEmpty()
        val colon = first.indexOfFirst { it == ':' || it == '：' }
        if (colon in 2..32 && !first.take(colon).contains('=') && !first.take(colon).contains('.')) return first.take(colon)
        return if (first.length <= 42) first else category.label + "记录"
    }
}
