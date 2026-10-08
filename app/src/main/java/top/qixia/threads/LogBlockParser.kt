package top.qixia.threads

/** 把守护日志中的规则区块和异常堆栈保留为单个可视化事件。 */
internal object LogBlockParser {
    data class Block(val lineNumber: Int, val text: String)

    private val tagPattern = Regex("^\\[[^]]+]\\s*")
    private val yamlHeaderPattern = Regex("^[A-Za-z0-9_][A-Za-z0-9_.:-]*:\\s*$")

    fun split(text: String): List<Block> {
        if (text.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        var firstLine = 0
        var current = StringBuilder()
        var braceDepth = 0
        var yamlBlock = false

        fun flush() {
            if (current.isEmpty()) return
            blocks += Block(firstLine, current.toString().trimEnd())
            current = StringBuilder()
            braceDepth = 0
            yamlBlock = false
        }

        fun start(index: Int, raw: String, ruleHeader: Boolean, yamlHeader: Boolean) {
            firstLine = index + 1
            current.append(raw.trimEnd())
            if (ruleHeader) braceDepth = braceDelta(raw).coerceAtLeast(1)
            yamlBlock = yamlHeader
        }

        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.isBlank()) {
                if (braceDepth > 0 || yamlBlock) current.append('\n')
                return@forEachIndexed
            }
            val trimmed = raw.trim()
            if (raw.startsWith("@QIXIA/1\t")) {
                flush()
                start(index, raw, false, false)
                flush()
                return@forEachIndexed
            }
            val tagged = tagPattern.containsMatchIn(raw)
            val ruleHeader = looksLikeBraceRuleHeader(raw, trimmed)
            val yamlHeader = raw.firstOrNull()?.isWhitespace() != true &&
                yamlHeaderPattern.matches(trimmed) && trimmed.contains('.')

            if (braceDepth > 0) {
                current.append('\n').append(raw.trimEnd())
                braceDepth += braceDelta(raw)
                if (braceDepth <= 0) flush()
                return@forEachIndexed
            }
            if (yamlBlock && raw.firstOrNull()?.isWhitespace() == true) {
                current.append('\n').append(raw.trimEnd())
                return@forEachIndexed
            }

            val continuation = current.isNotEmpty() && (
                raw.firstOrNull()?.isWhitespace() == true ||
                    raw.startsWith("at ") ||
                    raw.startsWith("Caused by:") ||
                    raw.startsWith("Suppressed:")
                )
            if (current.isEmpty()) {
                start(index, raw, ruleHeader, yamlHeader)
            } else if (tagged || ruleHeader || yamlHeader || !continuation) {
                flush()
                start(index, raw, ruleHeader, yamlHeader)
            } else {
                current.append('\n').append(raw.trimEnd())
            }
            if (braceDepth <= 0 && ruleHeader) flush()
        }
        flush()
        return blocks
    }

    private fun looksLikeBraceRuleHeader(raw: String, trimmed: String): Boolean {
        if (raw.firstOrNull()?.isWhitespace() == true || !trimmed.contains('{')) return false
        if (tagPattern.containsMatchIn(raw)) return false
        return trimmed.endsWith('{') || trimmed.contains("={") ||
            trimmed.startsWith("app(") || trimmed.startsWith("app ")
    }

    private fun braceDelta(text: String): Int =
        text.count { it == '{' } - text.count { it == '}' }
}
