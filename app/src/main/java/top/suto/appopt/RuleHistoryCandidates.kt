package top.suto.appopt

import top.suto.appopt.db.RuleHistoryRecord

enum class RuleHistoryKind {
    CHILD_PROCESS,
    THREAD
}

data class RuleHistoryCandidate(
    val kind: RuleHistoryKind,
    val owner: String,
    val thread: String?,
    val avg: Float?,
    val max: Float?,
    val epoch: Long
)

data class ThreadWildcardSuggestion(
    val exactName: String,
    val pattern: String,
    val matchedNames: List<String>
)

object RuleHistoryCandidates {
    fun build(baseOwner: String, records: List<RuleHistoryRecord>): List<RuleHistoryCandidate> {
        if (baseOwner.isBlank()) return emptyList()
        val candidates = LinkedHashMap<String, RuleHistoryCandidate>()

        fun add(candidate: RuleHistoryCandidate) {
            val key = "${candidate.kind}|${candidate.owner}|${candidate.thread.orEmpty()}"
            candidates.putIfAbsent(key, candidate)
        }

        for (record in records) {
            val name = record.name.trim()
            if (name.isEmpty()) continue

            if (name.startsWith("$baseOwner:") && !name.contains('{')) {
                add(
                    RuleHistoryCandidate(
                        kind = RuleHistoryKind.CHILD_PROCESS,
                        owner = name,
                        thread = null,
                        avg = record.avg,
                        max = record.max,
                        epoch = record.epoch
                    )
                )
                parseChildThreads(record.details).forEach { detail ->
                    add(
                        RuleHistoryCandidate(
                            kind = RuleHistoryKind.THREAD,
                            owner = name,
                            thread = detail.name,
                            avg = detail.avg,
                            max = detail.max,
                            epoch = record.epoch
                        )
                    )
                }
                continue
            }

            val brace = name.indexOf('{')
            if (brace > 0 && name.endsWith('}')) {
                val owner = name.substring(0, brace).trim()
                val thread = name.substring(brace + 1, name.length - 1).trim()
                if ((owner == baseOwner || owner.startsWith("$baseOwner:")) && thread.isNotEmpty()) {
                    add(
                        RuleHistoryCandidate(
                            kind = RuleHistoryKind.THREAD,
                            owner = owner,
                            thread = thread,
                            avg = record.avg,
                            max = record.max,
                            epoch = record.epoch
                        )
                    )
                }
                continue
            }

            if (name != baseOwner) {
                add(
                    RuleHistoryCandidate(
                        kind = RuleHistoryKind.THREAD,
                        owner = baseOwner,
                        thread = name,
                        avg = record.avg,
                        max = record.max,
                        epoch = record.epoch
                    )
                )
            }
        }

        return candidates.values.sortedWith(
            compareByDescending<RuleHistoryCandidate> { it.epoch }
                .thenByDescending { it.avg ?: -1f }
                .thenBy { it.thread ?: it.owner }
        )
    }

    private data class ChildThreadDetail(
        val name: String,
        val avg: Float?,
        val max: Float?
    )

    private fun parseChildThreads(details: String): List<ChildThreadDetail> {
        if (details.isBlank()) return emptyList()
        if (!details.startsWith("v2:")) {
            return details.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ChildThreadDetail(it, null, null) }
        }
        return details.removePrefix("v2:")
            .split(';')
            .mapNotNull { record ->
                val parts = record.split(',', limit = 3)
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                ChildThreadDetail(
                    name = name,
                    avg = parts.getOrNull(1)?.toFloatOrNull(),
                    max = parts.getOrNull(2)?.toFloatOrNull()
                )
            }
    }

    fun suggestThreadWildcard(
        selected: RuleHistoryCandidate,
        candidates: List<RuleHistoryCandidate>
    ): ThreadWildcardSuggestion? {
        if (selected.kind != RuleHistoryKind.THREAD) return null
        val exactName = selected.thread?.trim().orEmpty()
        if (!rawThreadNameSyntaxOk(exactName)) return null

        val sameOwnerNames = sequenceOf(exactName)
            .plus(
                candidates.asSequence()
                    .filter { it.kind == RuleHistoryKind.THREAD && it.owner == selected.owner }
                    .mapNotNull { it.thread?.trim()?.takeIf(String::isNotEmpty) }
            )
            .filter(::rawThreadNameSyntaxOk)
            .distinct()
            .toList()
        val choice = sameOwnerNames.asSequence()
            .mapNotNull { ownWildcardCandidate(it, sameOwnerNames) }
            .distinct()
            .mapNotNull { candidate ->
                val regex = generatedWildcardRegex(candidate)
                if (!regex.matches(exactName)) return@mapNotNull null
                PatternChoice(
                    pattern = candidate,
                    regex = regex,
                    coverage = sameOwnerNames.count(regex::matches),
                    requiredAtoms = wildcardRequiredAtoms(candidate),
                    codePointLength = candidate.codePointCount(0, candidate.length)
                )
            }
            .sortedWith { left, right -> comparePatternChoices(left, right) }
            .firstOrNull()
            ?: return null
        val pattern = choice.pattern

        val matchedNames = sameOwnerNames
            .filter(choice.regex::matches)
            .sortedWith(Comparator(::compareThreadNames))
        if (exactName !in matchedNames) {
            return null
        }

        return ThreadWildcardSuggestion(
            exactName = exactName,
            pattern = pattern,
            matchedNames = matchedNames
        )
    }

    private data class NumericShape(
        val literals: List<String>,
        val numbers: List<String>
    )

    private data class PatternChoice(
        val pattern: String,
        val regex: Regex,
        val coverage: Int,
        val requiredAtoms: Int,
        val codePointLength: Int
    )

    private fun rawThreadNameSyntaxOk(name: String): Boolean {
        return name.isNotEmpty() && name != "*" && name.none {
            it in "{}=/\\*?[]\n\r" || (it < ' ' && it != '\t')
        }
    }

    private fun ownWildcardCandidate(name: String, sameOwnerNames: List<String>): String? {
        val selected = numericShape(name) ?: return null
        val compatible = sameOwnerNames.asSequence()
            .filter { it != name }
            .mapNotNull(::numericShape)
            .filter { it.literals == selected.literals && it.numbers.size == selected.numbers.size }
            .toList()
        val varying = BooleanArray(selected.numbers.size)
        for (shape in compatible) {
            for (index in selected.numbers.indices) {
                if (shape.numbers[index] != selected.numbers[index]) varying[index] = true
            }
        }

        val direct = BooleanArray(selected.numbers.size) { index ->
            val previous = selected.literals[index].lastOrNull()
            val next = selected.literals[index + 1].firstOrNull()
            previous != null && isDirectNumberDelimiter(previous) &&
                (next == null || isDirectNumberDelimiter(next))
        }
        val dynamic = BooleanArray(selected.numbers.size) { direct[it] || varying[it] }
        if (dynamic.none { it } || stableAnchorCount(selected) < 2) return null

        val dynamicIndexes = dynamic.indices.filter { dynamic[it] }
        if (dynamicIndexes.size == 1) {
            val index = dynamicIndexes.single()
            if (direct[index] && index == selected.numbers.lastIndex && selected.literals.last().isEmpty()) {
                val prefix = buildString {
                    for (part in 0 until index) {
                        append(selected.literals[part])
                        append(selected.numbers[part])
                    }
                    append(selected.literals[index])
                }.trimEnd(' ', '\t')
                return validGeneratedPattern("$prefix*")
            }
        }

        val pattern = buildString {
            for (index in selected.numbers.indices) {
                append(selected.literals[index])
                append(if (dynamic[index]) "[0-9]*" else selected.numbers[index])
            }
            append(selected.literals.last())
        }
        return validGeneratedPattern(pattern)
    }

    private fun isDirectNumberDelimiter(char: Char): Boolean {
        return char == ' ' || char == '\t' || char == '-' || char == '_'
    }

    private fun stableAnchorCount(shape: NumericShape): Int {
        var count = 0
        for (literal in shape.literals) {
            var index = 0
            while (index < literal.length) {
                val codePoint = literal.codePointAt(index)
                if (codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code ||
                    codePoint >= 0x80
                ) {
                    count++
                }
                index += Character.charCount(codePoint)
            }
        }
        return count
    }

    private fun wildcardRequiredAtoms(pattern: String): Int {
        var required = 0
        var index = 0
        while (index < pattern.length) {
            val codePoint = pattern.codePointAt(index)
            when (codePoint) {
                '*'.code -> index++
                '['.code -> {
                    required++
                    val close = pattern.indexOf(']', index + 1)
                    index = if (close >= 0) close + 1 else index + 1
                }
                else -> {
                    required++
                    index += Character.charCount(codePoint)
                }
            }
        }
        return required
    }

    private fun comparePatternChoices(left: PatternChoice, right: PatternChoice): Int {
        if (left.coverage != right.coverage) return right.coverage.compareTo(left.coverage)
        if (left.requiredAtoms != right.requiredAtoms) {
            return left.requiredAtoms.compareTo(right.requiredAtoms)
        }
        if (left.codePointLength != right.codePointLength) {
            return left.codePointLength.compareTo(right.codePointLength)
        }
        val leftBytes = left.pattern.toByteArray(Charsets.UTF_8)
        val rightBytes = right.pattern.toByteArray(Charsets.UTF_8)
        val shared = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until shared) {
            val comparison = (leftBytes[index].toInt() and 0xff)
                .compareTo(rightBytes[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }

    private fun numericShape(name: String): NumericShape? {
        val literals = mutableListOf<String>()
        val numbers = mutableListOf<String>()
        var literalStart = 0
        var index = 0
        while (index < name.length) {
            if (name[index] !in '0'..'9') {
                index++
                continue
            }
            literals += name.substring(literalStart, index)
            val numberStart = index
            while (index < name.length && name[index] in '0'..'9') index++
            numbers += name.substring(numberStart, index)
            literalStart = index
        }
        if (numbers.isEmpty()) return null
        literals += name.substring(literalStart)
        return NumericShape(literals, numbers)
    }

    private fun validGeneratedPattern(pattern: String): String? {
        return pattern.takeIf {
            it != "*" && it.contains('*') && RuleConfigLogic.threadFitsNativeBuffer(it)
        }
    }

    private fun generatedWildcardRegex(pattern: String): Regex {
        val regex = buildString {
            append('^')
            var index = 0
            while (index < pattern.length) {
                when {
                    pattern.startsWith("[0-9]", index) -> {
                        append("[0-9]")
                        index += 5
                    }
                    pattern[index] == '*' -> {
                        append(".*")
                        index++
                    }
                    pattern[index] in "\\.^$|?+(){}[]" -> {
                        append('\\').append(pattern[index++])
                    }
                    else -> append(pattern[index++])
                }
            }
            append('$')
        }
        return Regex(regex)
    }

    private fun compareThreadNames(left: String, right: String): Int {
        val leftNumberStart = left.indexOfFirst { it in '0'..'9' }
        val rightNumberStart = right.indexOfFirst { it in '0'..'9' }
        if (leftNumberStart >= 0 && rightNumberStart >= 0) {
            val leftPrefix = left.substring(0, leftNumberStart)
            val rightPrefix = right.substring(0, rightNumberStart)
            val prefixComparison = leftPrefix.compareTo(rightPrefix, ignoreCase = true)
            if (prefixComparison != 0) return prefixComparison
            val leftNumber = left.substring(leftNumberStart).takeWhile { it in '0'..'9' }.toLongOrNull()
            val rightNumber = right.substring(rightNumberStart).takeWhile { it in '0'..'9' }.toLongOrNull()
            if (leftNumber != null && rightNumber != null && leftNumber != rightNumber) {
                return leftNumber.compareTo(rightNumber)
            }
        }
        return left.compareTo(right, ignoreCase = true)
    }
}
