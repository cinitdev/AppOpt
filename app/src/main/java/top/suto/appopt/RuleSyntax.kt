package top.suto.appopt

/**
 * applist.conf 的统一语法解析器。
 *
 * 新版区块语法只负责减少手写规则时的重复内容；解析后仍转换成旧版规则，
 * 这样 App、C 守护进程、Rust 守护进程和规则健康状态使用完全相同的规则身份。
 */
object RuleSyntax {

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
        val valid: Boolean = true
    )

    data class Document(val segments: List<Segment>) {
        val rules: List<Rule> = segments.flatMap(Segment::rules)
    }

    private data class BlockHeader(
        val owner: String,
        val fallbackCpus: String?,
        val valid: Boolean
    )

    private data class OpenBlock(
        val header: BlockHeader,
        val rawLines: MutableList<String>,
        val bodyRules: MutableList<Rule>,
        var valid: Boolean = true
    )

    private data class BlockClose(val fallbackCpus: String?, val valid: Boolean)

    fun parse(text: String): Document {
        val segments = ArrayList<Segment>()
        var openBlock: OpenBlock? = null

        for (rawLine in text.lineSequence()) {
            val current = openBlock
            if (current != null) {
                current.rawLines.add(rawLine)
                val code = codePart(rawLine)
                if (code.isEmpty() || code.startsWith("#")) continue

                val close = parseBlockClose(code)
                if (close != null) {
                    val ambiguousFallback = current.header.fallbackCpus != null && close.fallbackCpus != null
                    val fallback = current.header.fallbackCpus ?: close.fallbackCpus
                    val blockValid = current.valid && close.valid && !ambiguousFallback
                    val rules = if (blockValid) {
                        buildList {
                            addAll(current.bodyRules)
                            fallback?.let { add(Rule(current.header.owner, null, it)) }
                        }
                    } else {
                        emptyList()
                    }
                    segments.add(
                        Segment(
                            rawLines = current.rawLines.toList(),
                            rules = rules,
                            ownerHint = current.header.owner,
                            block = true,
                            valid = blockValid
                        )
                    )
                    openBlock = null
                    continue
                }

                val bodyRule = parseBlockBody(current.header.owner, code)
                if (bodyRule == null) {
                    current.valid = false
                } else {
                    current.bodyRules.add(bodyRule)
                }
                continue
            }

            val code = codePart(rawLine)
            val header = parseBlockHeader(code)
            if (header != null) {
                openBlock = OpenBlock(
                    header,
                    mutableListOf(rawLine),
                    mutableListOf(),
                    valid = header.valid
                )
                continue
            }

            val legacy = parseLegacyRule(code)
            segments.add(
                Segment(
                    rawLines = listOf(rawLine),
                    rules = legacy?.let(::listOf).orEmpty(),
                    ownerHint = legacy?.owner,
                    valid = legacy != null || code.isEmpty() || code.startsWith("#")
                )
            )
        }

        openBlock?.let { current ->
            segments.add(
                Segment(
                    rawLines = current.rawLines.toList(),
                    rules = emptyList(),
                    ownerHint = current.header.owner,
                    block = true,
                    valid = false
                )
            )
        }
        return Document(segments)
    }

    fun parseLegacyRule(rawLine: String): Rule? {
        val line = codePart(rawLine)
        if (line.isEmpty() || line.startsWith("#")) return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        val cpus = line.substring(eq + 1).trim()
        if (key.isEmpty() || cpus.isEmpty()) return null

        val open = key.indexOf('{')
        if (open < 0) {
            if (key.contains('}')) return null
            return Rule(key, null, cpus)
        }
        if (open == 0 || key.indexOf('{', open + 1) >= 0) return null
        val close = key.indexOf('}', open + 1)
        if (close != key.lastIndex) return null
        val owner = key.substring(0, open).trim()
        val thread = key.substring(open + 1, close).trim()
        if (owner.isEmpty() || thread.isEmpty()) return null
        return Rule(owner, thread, cpus)
    }

    private fun parseBlockHeader(code: String): BlockHeader? {
        if (code.isEmpty() || code.startsWith("#")) return null
        val open = code.indexOf('{')
        if (open <= 0 || code.substring(open + 1).trim().isNotEmpty()) return null
        val prefix = code.substring(0, open).trim()
        if (prefix.isEmpty() || prefix.contains('}') || prefix.indexOf('{') >= 0) return null

        val eq = prefix.indexOf('=')
        val owner = if (eq < 0) prefix else prefix.substring(0, eq).trim()
        val fallback = if (eq < 0) null else prefix.substring(eq + 1).trim()
        val valid = owner.isNotEmpty() && !owner.contains('=') &&
            (fallback == null || (fallback.isNotEmpty() && !fallback.contains('=')))
        return BlockHeader(owner, fallback, valid)
    }

    private fun parseBlockClose(code: String): BlockClose? {
        if (!code.startsWith('}')) return null
        val tail = code.substring(1).trim()
        if (tail.isEmpty()) return BlockClose(null, true)
        if (!tail.startsWith('=')) return BlockClose(null, false)
        val cpus = tail.substring(1).trim()
        return BlockClose(cpus.takeIf { it.isNotEmpty() }, cpus.isNotEmpty() && !cpus.contains('='))
    }

    private fun parseBlockBody(owner: String, code: String): Rule? {
        val eq = code.indexOf('=')
        if (eq <= 0) return null
        val name = code.substring(0, eq).trim()
        val cpus = code.substring(eq + 1).trim()
        if (name.isEmpty() || cpus.isEmpty() || name.any { it == '{' || it == '}' || it == '=' }) {
            return null
        }
        return when {
            name.startsWith(':') && name.length > 1 -> Rule(owner + name, null, cpus)
            name.startsWith("$owner:") && name.length > owner.length + 1 -> Rule(name, null, cpus)
            else -> Rule(owner, name, cpus)
        }
    }

    private fun codePart(rawLine: String): String = rawLine.substringBefore("//").trim()
}
