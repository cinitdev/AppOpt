package top.suto.appopt

/** 把 applist.conf 中的有效规则无损转换为用户选择的生成格式。 */
object RuleFormatConverter {

    data class Conversion(
        val content: String,
        val ruleCount: Int,
        val groupCount: Int,
        val changed: Boolean
    )

    data class Result(
        val conversion: Conversion? = null,
        val error: String? = null
    ) {
        val success: Boolean
            get() = conversion != null
    }

    data class Detection(
        val format: CalibPolicy.RuleOutputFormat,
        val ruleCount: Int,
        val mixed: Boolean = false
    )

    private data class RuleGroup(
        val owner: String,
        val rules: MutableList<RuleSyntax.Rule> = mutableListOf(),
        val trivia: MutableList<String> = mutableListOf()
    )

    /** 根据现有规则的实际写法识别格式；只有兜底或 auto 时无法可靠判断，返回 null。 */
    fun detectFormat(text: String): Detection? {
        val document = RuleSyntax.parse(text)
        if (document.segments.any { !it.valid } || document.rules.isEmpty()) return null

        val blocks = document.segments.filter { it.block && it.rules.isNotEmpty() }
        if (blocks.isEmpty()) {
            return if (document.rules.any { it.thread != null }) {
                Detection(CalibPolicy.RuleOutputFormat.LEGACY, document.rules.size)
            } else {
                null
            }
        }

        val separateFallbackOwners = document.segments.asSequence()
            .filterNot { it.block }
            .flatMap { it.rules.asSequence() }
            .filter { it.thread == null && it.owner == groupOwner(it.owner) }
            .map { it.owner }
            .toSet()
        val detected = blocks.mapNotNull { segment ->
            val owner = segment.ownerHint ?: return@mapNotNull null
            detectBlockFormat(segment.rawLines, owner in separateFallbackOwners)
        }
        if (detected.isEmpty()) return null

        val counts = LinkedHashMap<CalibPolicy.RuleOutputFormat, Int>()
        detected.forEach { format -> counts[format] = (counts[format] ?: 0) + 1 }
        val format = counts.maxByOrNull { it.value }?.key ?: return null
        return Detection(
            format = format,
            ruleCount = document.rules.size,
            mixed = counts.size > 1
        )
    }

    fun convert(text: String, format: CalibPolicy.RuleOutputFormat): Result {
        if (text.isBlank()) {
            return Result(Conversion(text, 0, 0, changed = false))
        }

        val document = RuleSyntax.parse(text)
        val invalid = document.segments.firstOrNull { !it.valid }
        if (invalid != null) {
            val preview = invalid.rawLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return Result(error = "存在无法解析的规则${preview.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()}")
        }

        val groups = LinkedHashMap<String, RuleGroup>()
        val pendingTrivia = mutableListOf<String>()
        for (segment in document.segments) {
            if (segment.rules.isEmpty()) {
                pendingTrivia.addAll(segment.rawLines)
                continue
            }

            val owners = segment.rules.map { groupOwner(it.owner) }.distinct()
            if (owners.size != 1) {
                return Result(error = "同一区块中包含多个应用，无法安全转换")
            }
            val group = groups.getOrPut(owners.single()) { RuleGroup(owners.single()) }
            if (pendingTrivia.isNotEmpty()) {
                group.trivia.addAll(pendingTrivia)
                pendingTrivia.clear()
            }
            group.trivia.addAll(preservedTrivia(segment.rawLines))
            group.rules.addAll(segment.rules)
        }

        if (groups.isEmpty()) {
            return Result(Conversion(text, 0, 0, changed = false))
        }

        for (group in groups.values) {
            val fallbacks = group.rules.filter { it.owner == group.owner && it.thread == null }
            if (fallbacks.size > 1) {
                return Result(error = "${group.owner} 存在多条主进程兜底规则，请先保留一条")
            }
            val unsupported = group.rules.firstOrNull { rule ->
                (rule.thread != null && rule.owner != group.owner) ||
                    (rule.thread == null && rule.owner != group.owner &&
                        !rule.owner.startsWith("${group.owner}:"))
            }
            if (unsupported != null) {
                return Result(error = "规则无法写入所选区块格式：${unsupported.canonicalLine}")
            }
        }

        val output = mutableListOf<String>()
        groups.values.forEachIndexed { index, group ->
            if (index > 0 && output.lastOrNull()?.isNotEmpty() == true &&
                group.trivia.firstOrNull()?.isNotEmpty() != false) {
                output.add("")
            }
            output.addAll(group.trivia)
            output.addAll(formatGroup(group, format))
        }
        if (pendingTrivia.isNotEmpty()) {
            if (output.lastOrNull()?.isNotEmpty() == true && pendingTrivia.firstOrNull()?.isNotEmpty() != false) {
                output.add("")
            }
            output.addAll(pendingTrivia)
        }

        val content = output.joinToString("\n").trimEnd() + "\n"
        return Result(
            Conversion(
                content = content,
                ruleCount = groups.values.sumOf { it.rules.size },
                groupCount = groups.size,
                changed = content != text
            )
        )
    }

    private fun formatGroup(
        group: RuleGroup,
        format: CalibPolicy.RuleOutputFormat
    ): List<String> {
        if (format == CalibPolicy.RuleOutputFormat.LEGACY) {
            return group.rules.map(RuleSyntax.Rule::canonicalLine)
        }

        val fallback = group.rules.firstOrNull { it.owner == group.owner && it.thread == null }
        val threads = group.rules.filter { it.owner == group.owner && it.thread != null }
        val children = group.rules.filter { it.owner != group.owner && it.thread == null }
        val members = threads.size + children.size

        if (format == CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK) {
            val output = mutableListOf<String>()
            if (threads.isEmpty()) {
                fallback?.let { output.add(it.canonicalLine) }
            } else {
                output.add(fallback?.let { "${group.owner}=${it.cpus} {" } ?: "${group.owner} {")
                threads.forEach { output.add("    ${it.thread}=${it.cpus}") }
                output.add("}")
            }
            children.forEach { output.add(it.canonicalLine) }
            return output
        }

        if (members == 0) return fallback?.let { listOf(it.canonicalLine) }.orEmpty()

        val output = mutableListOf<String>()
        when (format) {
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK -> {
                output.add(fallback?.let { "${group.owner}=${it.cpus}{" } ?: "${group.owner}{")
            }
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK -> output.add("${group.owner} {")
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> output.add("${group.owner}{")
            CalibPolicy.RuleOutputFormat.LEGACY,
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK -> error("已在前面处理")
        }
        threads.forEach { output.add("    ${it.thread}=${it.cpus}") }
        children.forEach { output.add("    ${it.owner.removePrefix(group.owner)}=${it.cpus}") }
        when (format) {
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK -> {
                output.add("}")
                fallback?.let { output.add(it.canonicalLine) }
            }
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> {
                output.add(fallback?.let { "}=${it.cpus}" } ?: "}")
            }
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK -> output.add("}")
            CalibPolicy.RuleOutputFormat.LEGACY,
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK -> error("已在前面处理")
        }
        return output
    }

    internal fun preservedTrivia(rawLines: List<String>): List<String> {
        return buildList {
            for (rawLine in rawLines) {
                val trimmed = rawLine.trim()
                when {
                    trimmed.isEmpty() -> add("")
                    trimmed.startsWith("#") || trimmed.startsWith("//") -> add(rawLine)
                    "//" in rawLine -> add(rawLine.substring(rawLine.indexOf("//")))
                }
            }
        }
    }

    private fun groupOwner(owner: String): String {
        val base = owner.substringBefore(':')
        return if (base != owner && base.contains('.')) base else owner
    }

    private fun detectBlockFormat(
        rawLines: List<String>,
        hasSeparateFallback: Boolean
    ): CalibPolicy.RuleOutputFormat? {
        val codeLines = rawLines.map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = codeLines.firstOrNull() ?: return null
        val close = codeLines.lastOrNull { it.startsWith("}") } ?: return null
        val open = header.indexOf('{')
        if (open <= 0) return null
        val spaced = header.getOrNull(open - 1)?.isWhitespace() == true
        val headerFallback = header.substring(0, open).contains('=')
        val tailFallback = close.substringAfter('}', "").trim().startsWith('=')
        return when {
            tailFallback && spaced -> CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK
            tailFallback -> CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK
            headerFallback && spaced -> CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK
            headerFallback -> CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK
            hasSeparateFallback && spaced -> CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK
            hasSeparateFallback -> CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK
            else -> null
        }
    }
}
