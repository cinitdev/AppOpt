package top.qixia.threads

/** 将 applist.conf 的全部受支持语法展开为统一规则，其他功能只处理这一种内部语义。 */
object RuleSyntax {

    enum class Format {
        STANDARD,
        TAGGED,
        NATURAL,
        NESTED,
        FUNCTION,
        YAML
    }

    data class Rule(
        val owner: String,
        val thread: String?,
        val cpus: String
    ) {
        val canonicalLine: String
            get() = "${thread?.let { "$owner{$it}" } ?: owner}=$cpus"
    }

    data class Segment(
        val rawLines: List<String>,
        val rules: List<Rule>,
        val ownerHint: String? = null,
        val block: Boolean = false,
        val valid: Boolean = true,
        val format: Format? = null
    )

    data class Document(val segments: List<Segment>) {
        val rules: List<Rule> = segments.flatMap(Segment::rules)
    }

    private data class Header(
        val owner: String,
        val fallback: String?,
        val format: Format,
        val valid: Boolean = true
    )

    private data class FunctionProcessHeader(
        val name: String,
        val fallback: String?
    )

    fun parse(text: String): Document {
        val lines = text.lines()
        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val code = codePart(raw)
            if (code.isEmpty() || code.startsWith("#")) {
                segments += Segment(listOf(raw), emptyList())
                index++
                continue
            }

            val yamlHeader = parseYamlHeader(raw)
            if (yamlHeader != null) {
                val end = yamlEnd(lines, index)
                val parsed = parseYamlBody(lines.subList(index + 1, end), yamlHeader.owner)
                val segmentValid = yamlHeader.valid && parsed != null
                segments += Segment(
                    rawLines = lines.subList(index, end),
                    rules = if (segmentValid) parsed.orEmpty() else emptyList(),
                    ownerHint = yamlHeader.owner,
                    block = true,
                    valid = segmentValid,
                    format = Format.YAML
                )
                index = end
                continue
            }

            val header = parseBraceHeader(code)
            if (header != null) {
                val end = braceBlockEnd(lines, index)
                if (end == null) {
                    segments += Segment(
                        rawLines = lines.subList(index, lines.size),
                        rules = emptyList(),
                        ownerHint = header.owner,
                        block = true,
                        valid = false,
                        format = header.format
                    )
                    break
                }
                val close = codePart(lines[end - 1])
                val resolvedHeader = resolveTailFallback(header, close)
                val body = lines.subList(index + 1, end - 1)
                val nested = resolvedHeader.format == Format.TAGGED && body.any {
                    codePart(it) == "threads {" || codePart(it) == "processes {"
                }
                val effectiveHeader = if (nested) resolvedHeader.copy(format = Format.NESTED) else resolvedHeader
                val parsed = when (effectiveHeader.format) {
                    Format.NESTED -> parseNestedBody(body, effectiveHeader)
                    Format.FUNCTION -> parseFunctionBody(body, effectiveHeader)
                    else -> parseBraceBody(body, effectiveHeader)
                }
                val segmentValid = effectiveHeader.valid && parsed != null
                segments += Segment(
                    rawLines = lines.subList(index, end),
                    rules = if (segmentValid) parsed.orEmpty() else emptyList(),
                    ownerHint = effectiveHeader.owner,
                    block = true,
                    valid = segmentValid,
                    format = effectiveHeader.format
                )
                index = end
                continue
            }

            val legacy = parseLegacyRule(code)
            segments += Segment(
                rawLines = listOf(raw),
                rules = legacy?.let(::listOf).orEmpty(),
                ownerHint = legacy?.owner,
                valid = legacy != null
            )
            index++
        }
        return Document(segments)
    }

    fun parseLegacyRule(rawLine: String): Rule? {
        val code = codePart(rawLine)
        val eq = code.indexOf('=')
        if (eq <= 0) return null
        val key = code.substring(0, eq).trim()
        val cpus = code.substring(eq + 1).trim()
        if (key.isEmpty() || cpus.isEmpty()) return null
        val open = key.indexOf('{')
        if (open < 0) {
            if ('}' in key) return null
            return Rule(key, null, cpus)
        }
        val close = key.lastIndexOf('}')
        if (open == 0 || close != key.lastIndex || close <= open + 1 || key.indexOf('{', open + 1) >= 0) {
            return null
        }
        val owner = key.substring(0, open).trim()
        val thread = key.substring(open + 1, close).trim()
        return if (owner.isEmpty() || thread.isEmpty()) null else Rule(owner, thread, cpus)
    }

    private fun parseBraceHeader(code: String): Header? {
        if (!code.endsWith('{')) return null
        val prefix = code.dropLast(1).trim()
        if (prefix == "app" || prefix.startsWith("app ")) {
            val match = NATURAL_HEADER.matchEntire(code)
                ?: return Header("", null, Format.NATURAL, valid = false)
            return Header(match.groupValues[1], match.groupValues[2].ifEmpty { null }, Format.NATURAL)
        }
        if (prefix.startsWith("app(")) {
            val args = prefix.removePrefix("app(").removeSuffix(")")
            if (!prefix.endsWith(')')) return Header("", null, Format.FUNCTION, valid = false)
            val values = splitFunctionHeaderArgs(args)
                ?: return Header("", null, Format.FUNCTION, valid = false)
            val owner = values.firstOrNull().orEmpty()
            val fallback = values.getOrNull(1)
            return Header(
                owner,
                fallback,
                Format.FUNCTION,
                owner.isNotEmpty() && values.size in 1..2 && values.none(String::isEmpty)
            )
        }
        if (prefix.isEmpty() || prefix.contains('}')) return null
        val eq = prefix.indexOf('=')
        if (eq >= 0) {
            val owner = prefix.substring(0, eq).trim()
            val fallback = prefix.substring(eq + 1).trim()
            return if (fallback.isEmpty()) {
                Header(owner, null, Format.TAGGED, owner.isNotEmpty())
            } else {
                Header(owner, fallback, Format.STANDARD, owner.isNotEmpty())
            }
        }
        return Header(prefix, null, Format.STANDARD, prefix.isNotEmpty())
    }

    private fun resolveTailFallback(header: Header, close: String): Header {
        if (header.format != Format.STANDARD) {
            return header.copy(valid = header.valid && close == "}")
        }
        val tail = close.removePrefix("}").trim()
        if (tail.isEmpty()) return header
        val fallback = tail.removePrefix("=").trim()
        return header.copy(
            fallback = fallback.takeIf { it.isNotEmpty() && header.fallback == null },
            valid = header.valid && tail.startsWith('=') && fallback.isNotEmpty() && header.fallback == null
        )
    }

    private fun parseBraceBody(lines: List<String>, header: Header): List<Rule>? {
        val rules = mutableListOf<Rule>()
        var bodyFallback = false
        for (raw in lines) {
            val code = codePart(raw)
            if (code.isEmpty() || code.startsWith("#")) continue
            val rule = when (header.format) {
                Format.STANDARD -> parseStandardMember(header.owner, code)
                Format.TAGGED -> parseTaggedMember(header.owner, code)
                Format.NATURAL -> parseNaturalMember(header.owner, code)
                Format.FUNCTION -> parseFunctionMember(header.owner, code)
                Format.NESTED, Format.YAML -> null
            } ?: return null
            if (header.format == Format.TAGGED && rule.owner == header.owner && rule.thread == null) {
                if (bodyFallback || header.fallback != null) return null
                bodyFallback = true
            }
            rules += rule
        }
        header.fallback?.let { rules += Rule(header.owner, null, it) }
        return rules
    }

    private fun parseFunctionBody(lines: List<String>, header: Header): List<Rule>? {
        val rules = mutableListOf<Rule>()
        var index = 0
        while (index < lines.size) {
            val code = codePart(lines[index])
            if (code.isEmpty() || code.startsWith("#")) {
                index++
                continue
            }

            val process = parseFunctionProcessHeader(code)
            if (process == null) {
                if (code == "}" || code.endsWith('{')) return null
                rules += parseFunctionMember(header.owner, code) ?: return null
                index++
                continue
            }

            val childOwner = processOwner(header.owner, process.name)
            process.fallback?.let { rules += Rule(childOwner, null, it) }
            index++
            var closed = false
            while (index < lines.size) {
                val childCode = codePart(lines[index])
                when {
                    childCode.isEmpty() || childCode.startsWith("#") -> Unit
                    childCode == "}" -> {
                        closed = true
                        index++
                        break
                    }
                    childCode.endsWith('{') -> return null
                    else -> {
                        val childRule = parseFunctionMember(childOwner, childCode) ?: return null
                        if (childRule.thread == null) return null
                        rules += childRule
                    }
                }
                index++
            }
            if (!closed) return null
        }
        header.fallback?.let { rules += Rule(header.owner, null, it) }
        return rules
    }

    private fun parseFunctionProcessHeader(code: String): FunctionProcessHeader? {
        if (!code.startsWith("process(") || !code.endsWith("{") ) return null
        val call = code.dropLast(1).trimEnd()
        if (!call.endsWith(')')) return null
        val args = call.removePrefix("process(").dropLast(1)
        val values = splitFunctionArgs(args)
        if (values.size !in 1..2 || values.any(String::isEmpty) || !validMemberName(values[0])) {
            return null
        }
        return FunctionProcessHeader(values[0], values.getOrNull(1))
    }

    private fun parseNestedBody(lines: List<String>, header: Header): List<Rule>? {
        val rules = mutableListOf<Rule>()
        var section: String? = null
        var fallback = header.fallback
        for (raw in lines) {
            val code = codePart(raw)
            if (code.isEmpty() || code.startsWith("#")) continue
            when {
                code == "threads {" && section == null -> section = "threads"
                code == "processes {" && section == null -> section = "processes"
                code == "}" && section != null -> section = null
                section == "threads" -> splitAssignment(code)?.let { (name, cpus) ->
                    if (!validMemberName(name)) return null
                    rules += Rule(header.owner, name, cpus)
                } ?: return null
                section == "processes" -> splitAssignment(code)?.let { (name, cpus) ->
                    if (!validMemberName(name)) return null
                    rules += processRule(header.owner, name, cpus)
                } ?: return null
                else -> {
                    val assignment = splitAssignment(code) ?: return null
                    if (assignment.first != "fallback" || fallback != null) return null
                    fallback = assignment.second
                }
            }
        }
        if (section != null) return null
        fallback?.let { rules += Rule(header.owner, null, it) }
        return rules
    }

    private fun parseYamlHeader(raw: String): Header? {
        if (leadingSpaces(raw) != 0) return null
        val owner = codePart(raw).removeSuffix(":").trim()
        if (!codePart(raw).endsWith(':') || owner.isEmpty() || owner.any(Char::isWhitespace) ||
            owner == "threads" || owner == "processes"
        ) return null
        return Header(owner, null, Format.YAML)
    }

    private fun yamlEnd(lines: List<String>, start: Int): Int {
        var index = start + 1
        while (index < lines.size) {
            val code = codePart(lines[index])
            if (code.isNotEmpty() && !code.startsWith("#") && leadingSpaces(lines[index]) == 0) break
            index++
        }
        return index
    }

    private fun parseYamlBody(lines: List<String>, owner: String): List<Rule>? {
        val rules = mutableListOf<Rule>()
        var section: String? = null
        var fallback: String? = null
        for (raw in lines) {
            val code = codePart(raw)
            if (code.isEmpty() || code.startsWith("#")) continue
            when (code) {
                "threads:" -> {
                    if (leadingSpaces(raw) != 4) return null
                    section = "threads"
                    continue
                }
                "processes:" -> {
                    if (leadingSpaces(raw) != 4) return null
                    section = "processes"
                    continue
                }
            }
            val split = code.lastIndexOf(':')
            if (split <= 0) return null
            val name = code.substring(0, split).trim()
            val cpus = code.substring(split + 1).trim()
            if (name.isEmpty() || cpus.isEmpty()) return null
            when {
                section == "threads" && leadingSpaces(raw) == 8 && validMemberName(name) ->
                    rules += Rule(owner, name, cpus)
                section == "processes" && leadingSpaces(raw) == 8 && validMemberName(name) ->
                    rules += processRule(owner, name, cpus)
                name == "fallback" && leadingSpaces(raw) == 4 && fallback == null -> fallback = cpus
                else -> return null
            }
        }
        fallback?.let { rules += Rule(owner, null, it) }
        return rules
    }

    private fun parseStandardMember(owner: String, code: String): Rule? {
        val (name, cpus) = splitAssignment(code) ?: return null
        if (!validMemberName(name)) return null
        return if (name.startsWith(':') || name.startsWith("$owner:")) {
            processRule(owner, name, cpus)
        } else {
            Rule(owner, name, cpus)
        }
    }

    private fun parseTaggedMember(owner: String, code: String): Rule? {
        val (name, cpus) = splitAssignment(code) ?: return null
        return when {
            name == "fallback" -> Rule(owner, null, cpus)
            name.startsWith("thread:") && validMemberName(name.substring(7)) ->
                Rule(owner, name.substring(7), cpus)
            name.startsWith("process:") && validMemberName(name.substring(8)) ->
                processRule(owner, name.substring(8), cpus)
            else -> null
        }
    }

    private fun parseNaturalMember(owner: String, code: String): Rule? {
        return when {
            code.startsWith("thread ") -> splitAssignment(code.substring(7))?.let {
                if (!validMemberName(it.first)) return null
                Rule(owner, it.first, it.second)
            }
            code.startsWith("process ") -> splitAssignment(code.substring(8))?.let {
                if (!validMemberName(it.first)) return null
                processRule(owner, it.first, it.second)
            }
            else -> null
        }
    }

    private fun parseFunctionMember(owner: String, code: String): Rule? {
        val process = code.startsWith("process(")
        val prefix = if (process) "process(" else "thread("
        if (!code.startsWith(prefix) || !code.endsWith(')')) return null
        val values = splitFunctionArgs(code.substring(prefix.length, code.length - 1))
        if (values.size != 2 || values.any(String::isEmpty) || !validMemberName(values[0])) return null
        return if (process) processRule(owner, values[0], values[1]) else Rule(owner, values[0], values[1])
    }

    private fun processRule(owner: String, name: String, cpus: String): Rule {
        return Rule(processOwner(owner, name), null, cpus)
    }

    private fun processOwner(owner: String, name: String): String {
        return when {
            name.startsWith("$owner:") -> name
            name.startsWith(':') -> owner + name
            else -> "$owner:$name"
        }
    }

    private fun splitAssignment(code: String): Pair<String, String>? {
        val eq = code.indexOf('=')
        if (eq <= 0) return null
        val name = code.substring(0, eq).trim()
        val cpus = code.substring(eq + 1).trim()
        return if (name.isEmpty() || cpus.isEmpty()) null else name to cpus
    }

    private fun splitFunctionArgs(args: String): List<String> {
        val trimmed = args.trimStart()
        if (trimmed.startsWith('"')) {
            val (name, remainder) = parseQuotedFunctionName(trimmed) ?: return emptyList()
            val rest = remainder.trim()
            if (rest.isEmpty()) return listOf(name)
            if (!rest.startsWith(',')) return emptyList()
            val cpus = rest.removePrefix(",").trim()
            return if (cpus.isEmpty()) emptyList() else listOf(name, cpus)
        }
        for (comma in args.indices) {
            if (args[comma] != ',') continue
            val name = args.substring(0, comma).trim()
            val cpus = args.substring(comma + 1).trim()
            if (validMemberName(name) && RuleConfigLogic.parseNativeCpuRangeList(cpus) != null) {
                return listOf(name, cpus)
            }
        }
        val comma = args.lastIndexOf(',')
        return if (comma < 0) listOf(args.trim()) else listOf(
            args.substring(0, comma).trim(),
            args.substring(comma + 1).trim()
        )
    }

    private fun splitFunctionHeaderArgs(args: String): List<String>? {
        val trimmed = args.trim()
        if (trimmed.startsWith('"')) {
            val (name, remainder) = parseQuotedFunctionName(trimmed) ?: return null
            val rest = remainder.trim()
            if (rest.isEmpty()) return listOf(name)
            if (!rest.startsWith(',')) return null
            val fallback = rest.removePrefix(",").trim()
            return fallback.takeIf { it.isNotEmpty() }?.let { listOf(name, it) }
        }
        val comma = trimmed.indexOf(',')
        return if (comma < 0) listOf(trimmed) else listOf(
            trimmed.substring(0, comma).trim(),
            trimmed.substring(comma + 1).trim()
        )
    }

    private fun parseQuotedFunctionName(input: String): Pair<String, String>? {
        if (!input.startsWith('"')) return null
        val value = StringBuilder()
        var escaped = false
        var index = 1
        while (index < input.length) {
            val ch = input[index]
            when {
                escaped -> {
                    value.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '"' -> return value.toString() to input.substring(index + 1)
                ch.isISOControl() -> return null
                else -> value.append(ch)
            }
            index++
        }
        return null
    }

    private fun braceBlockEnd(lines: List<String>, start: Int): Int? {
        var depth = 0
        for (index in start until lines.size) {
            val code = codePart(lines[index])
            if (code.isEmpty() || code.startsWith("#")) continue
            depth += code.count { it == '{' }
            depth -= code.count { it == '}' }
            if (depth == 0) return index + 1
            if (depth < 0) return null
        }
        return null
    }

    private fun leadingSpaces(raw: String): Int = raw.length - raw.trimStart().length

    private fun validMemberName(name: String): Boolean {
        return name.isNotEmpty() && name.none { it == '{' || it == '}' || it == '=' }
    }

    private fun codePart(rawLine: String): String = rawLine.substringBefore("//").trim()

    private val NATURAL_HEADER = Regex("^app\\s+(\\S+)(?:\\s+fallback\\s+(\\S+))?\\s*\\{$")
}
