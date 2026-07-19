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
        val mixed: Boolean = false,
        val detectedFormats: Set<CalibPolicy.RuleOutputFormat> = setOf(format)
    ) {
        val requiresAuthorMigration: Boolean
            get() = detectedFormats.any(CalibPolicy.RuleOutputFormat::requiresAuthorMigration)
    }

    private data class RuleGroup(
        val owner: String,
        val rules: MutableList<RuleSyntax.Rule> = mutableListOf(),
        val trivia: MutableList<String> = mutableListOf()
    )

    /** 根据现有规则的实际写法识别格式；只有兜底或 auto 时无法可靠判断，返回 null。 */
    fun detectFormat(text: String): Detection? {
        val document = RuleSyntax.parse(text)
        if (document.rules.isEmpty()) return null

        val validSegments = document.segments.filter(RuleSyntax.Segment::valid)
        val blocks = validSegments.filter { it.block && it.rules.isNotEmpty() }
        if (blocks.isEmpty()) {
            return if (document.rules.any { it.thread != null }) {
                Detection(CalibPolicy.RuleOutputFormat.LEGACY, document.rules.size)
            } else {
                null
            }
        }

        val separateFallbackOwners = validSegments.asSequence()
            .filterNot { it.block }
            .flatMap { it.rules.asSequence() }
            .filter { it.thread == null && it.owner == groupOwner(it.owner) }
            .map { it.owner }
            .toSet()
        val detected = blocks.mapNotNull { segment ->
            val owner = segment.ownerHint ?: return@mapNotNull null
            when (segment.format) {
                RuleSyntax.Format.TAGGED -> CalibPolicy.RuleOutputFormat.TAGGED_BLOCK
                RuleSyntax.Format.NATURAL -> CalibPolicy.RuleOutputFormat.NATURAL_BLOCK
                RuleSyntax.Format.NESTED -> CalibPolicy.RuleOutputFormat.NESTED_BLOCK
                RuleSyntax.Format.FUNCTION -> CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK
                RuleSyntax.Format.YAML -> CalibPolicy.RuleOutputFormat.YAML
                RuleSyntax.Format.STANDARD, null ->
                    detectBlockFormat(segment.rawLines, owner in separateFallbackOwners)
            }
        }
        if (detected.isEmpty()) return null

        val counts = LinkedHashMap<CalibPolicy.RuleOutputFormat, Int>()
        detected.forEach { format -> counts[format] = (counts[format] ?: 0) + 1 }
        val format = counts.maxByOrNull { it.value }?.key ?: return null
        return Detection(
            format = format,
            ruleCount = document.rules.size,
            mixed = counts.size > 1,
            detectedFormats = counts.keys.toSet()
        )
    }

    fun convert(text: String, format: CalibPolicy.RuleOutputFormat): Result {
        val outputFormat = format.generationTarget()
        if (text.isBlank()) {
            return Result(Conversion(text, 0, 0, changed = false))
        }

        val document = RuleSyntax.parse(text)

        val groups = LinkedHashMap<String, RuleGroup>()
        val pendingTrivia = mutableListOf<String>()
        for (segment in document.segments) {
            // 无法识别的原文可能属于其他工具或未来语法，保留原位但不让它阻断有效规则转换。
            if (!segment.valid) {
                pendingTrivia.addAll(segment.rawLines)
                continue
            }
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

        groups.values.forEach { group ->
            val deduplicated = deduplicateRules(group.rules)
            group.rules.clear()
            group.rules.addAll(deduplicated)
        }

        if (groups.isEmpty()) {
            return Result(Conversion(text, 0, 0, changed = false))
        }

        for (group in groups.values) {
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
            output.addAll(formatGroup(group, outputFormat))
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

        // 只有主进程兜底时保持单行，避免生成没有实际成员的空区块。
        if (members == 0) {
            return fallback?.let { listOf(it.canonicalLine) }.orEmpty()
        }

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

        val output = mutableListOf<String>()
        when (format) {
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> output.add("${group.owner}{")
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK -> output.add("${group.owner}={")
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK -> output.add(
                fallback?.let { "app ${group.owner} fallback ${it.cpus} {" } ?: "app ${group.owner} {"
            )
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK -> output.add("${group.owner}={")
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK -> output.add(
                fallback?.let { "app(${group.owner}, ${it.cpus}) {" } ?: "app(${group.owner}) {"
            )
            CalibPolicy.RuleOutputFormat.YAML -> output.add("${group.owner}:")
            CalibPolicy.RuleOutputFormat.LEGACY,
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK -> error("已在前面处理")
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK,
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK -> error("旧区块格式只能转换为原作者格式")
        }
        when (format) {
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> {
                threads.forEach { output.add("    ${it.thread}=${it.cpus}") }
                children.forEach { output.add("    ${it.owner.removePrefix(group.owner)}=${it.cpus}") }
            }
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK -> {
                threads.forEach { output.add("    thread:${it.thread}=${it.cpus}") }
                children.forEach {
                    output.add("    process:${it.owner.removePrefix("${group.owner}:")}=${it.cpus}")
                }
            }
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK -> {
                threads.forEach { output.add("    thread ${it.thread}=${it.cpus}") }
                children.forEach {
                    output.add("    process ${it.owner.removePrefix("${group.owner}:")}=${it.cpus}")
                }
            }
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK -> {
                if (threads.isNotEmpty()) {
                    output.add("    threads {")
                    threads.forEach { output.add("        ${it.thread}=${it.cpus}") }
                    output.add("    }")
                }
                if (children.isNotEmpty()) {
                    output.add("    processes {")
                    children.forEach {
                        output.add("        ${it.owner.removePrefix("${group.owner}:")}=${it.cpus}")
                    }
                    output.add("    }")
                }
            }
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK -> {
                threads.forEach { output.add("    thread(${it.thread}, ${it.cpus})") }
                children.forEach {
                    output.add("    process(${it.owner.removePrefix("${group.owner}:")}, ${it.cpus})")
                }
            }
            CalibPolicy.RuleOutputFormat.YAML -> {
                if (threads.isNotEmpty()) {
                    output.add("    threads:")
                    threads.forEach { output.add("        ${it.thread}: ${it.cpus}") }
                }
                if (children.isNotEmpty()) {
                    output.add("    processes:")
                    children.forEach {
                        output.add("        ${it.owner.removePrefix("${group.owner}:")}: ${it.cpus}")
                    }
                }
            }
            CalibPolicy.RuleOutputFormat.LEGACY,
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK,
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK -> error("已在前面处理")
        }
        when (format) {
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> {
                output.add(fallback?.let { "}=${it.cpus}" } ?: "}")
            }
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK -> {
                fallback?.let { output.add("    fallback=${it.cpus}") }
                output.add("}")
            }
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK,
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK -> output.add("}")
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK -> {
                fallback?.let { output.add("    fallback=${it.cpus}") }
                output.add("}")
            }
            CalibPolicy.RuleOutputFormat.YAML -> fallback?.let { output.add("    fallback: ${it.cpus}") }
            CalibPolicy.RuleOutputFormat.LEGACY,
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK -> error("已在前面处理")
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK,
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK -> error("旧区块格式只能转换为原作者格式")
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

    /** 同一线程、子进程或主进程兜底只保留覆盖核心最多的一条。 */
    private fun deduplicateRules(rules: List<RuleSyntax.Rule>): List<RuleSyntax.Rule> {
        val selected = LinkedHashMap<Pair<String, String?>, RuleSyntax.Rule>()
        for (rule in rules) {
            val key = rule.owner to rule.thread
            val current = selected[key]
            if (current == null || cpuPreference(rule.cpus) > cpuPreference(current.cpus)) {
                selected[key] = rule
            }
        }
        return selected.values.toList()
    }

    private data class CpuPreference(
        val validity: Int,
        val count: Int,
        val highest: Int,
        val lowerCoverage: Int
    ) : Comparable<CpuPreference> {
        override fun compareTo(other: CpuPreference): Int {
            return compareValuesBy(
                this,
                other,
                CpuPreference::validity,
                CpuPreference::count,
                CpuPreference::highest,
                CpuPreference::lowerCoverage
            )
        }
    }

    private fun cpuPreference(cpus: String): CpuPreference {
        val parsed = RuleConfigLogic.parseCpuRangeList(cpus)
        if (parsed != null && parsed.isNotEmpty()) {
            return CpuPreference(
                validity = 2,
                count = parsed.size,
                highest = parsed.maxOrNull() ?: -1,
                lowerCoverage = -(parsed.minOrNull() ?: 0)
            )
        }
        return if (cpus.equals("auto", ignoreCase = true)) {
            CpuPreference(1, 0, -1, 0)
        } else {
            CpuPreference(0, 0, -1, 0)
        }
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
            spaced -> CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK
            else -> CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK
        }
    }
}
