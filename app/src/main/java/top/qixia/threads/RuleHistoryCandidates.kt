package top.qixia.threads

import top.qixia.threads.db.RuleHistoryRecord

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

data class OwnedThreadWildcardSuggestion(
    val owner: String,
    val suggestion: ThreadWildcardSuggestion
)

object RuleHistoryCandidates {
    internal const val MAX_EDITOR_CANDIDATES = 300
    private const val MAX_CHILD_THREADS_PER_PROCESS = 64

    fun build(
        baseOwner: String,
        records: List<RuleHistoryRecord>,
        maxCandidates: Int = MAX_EDITOR_CANDIDATES
    ): List<RuleHistoryCandidate> {
        if (baseOwner.isBlank() || maxCandidates <= 0) return emptyList()
        val candidates = LinkedHashMap<String, RuleHistoryCandidate>()
        val candidatesPerEpoch = mutableMapOf<Long, Int>()
        val perEpochBuildLimit = maxCandidates.coerceAtMost(2_000).times(2).coerceAtLeast(64)

        fun add(candidate: RuleHistoryCandidate) {
            val key = "${candidate.kind}|${candidate.owner}|${candidate.thread.orEmpty()}"
            if (key in candidates || (candidatesPerEpoch[candidate.epoch] ?: 0) >= perEpochBuildLimit) {
                return
            }
            candidates[key] = candidate
            candidatesPerEpoch[candidate.epoch] = (candidatesPerEpoch[candidate.epoch] ?: 0) + 1
        }

        for (record in records) {
            val name = record.name.trim()
            if (name.isEmpty()) continue

            if (HistoryFieldCodec.isProcessAggregateRecord(baseOwner, name, record.details)) {
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

        val sorted = candidates.values.sortedWith(CANDIDATE_ORDER)
        return takeRecentSessionsFairly(sorted, maxCandidates.coerceAtMost(2_000))
    }

    private data class ChildThreadDetail(
        val name: String,
        val avg: Float?,
        val max: Float?
    )

    private fun parseChildThreads(details: String): List<ChildThreadDetail> {
        if (details.isBlank()) return emptyList()
        val payload = HistoryFieldCodec.parseChildDetails(details)
        if (payload == null) {
            return details.split(',')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_CHILD_THREADS_PER_PROCESS)
                .map { ChildThreadDetail(it, null, null) }
                .toList()
        }
        return payload.body
            .split(';')
            .asSequence()
            .take(MAX_CHILD_THREADS_PER_PROCESS)
            .mapNotNull { record ->
                val parts = record.split(',', limit = 3)
                val rawName = parts.getOrNull(0)?.trim().orEmpty()
                val name = if (payload.encodedNames) {
                    HistoryFieldCodec.decodeName(rawName)
                } else {
                    rawName
                }
                if (name.isEmpty()) return@mapNotNull null
                ChildThreadDetail(
                    name = name,
                    avg = parts.getOrNull(1)?.toFloatOrNull(),
                    max = parts.getOrNull(2)?.toFloatOrNull()
                )
            }
            .toList()
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
        return buildSuggestion(exactName, sameOwnerNames, buildPatternChoices(sameOwnerNames))
    }

    fun collectThreadWildcardSuggestions(
        selected: List<RuleHistoryCandidate>,
        candidates: List<RuleHistoryCandidate>
    ): List<OwnedThreadWildcardSuggestion> {
        val suggestions = linkedMapOf<String, OwnedThreadWildcardSuggestion>()
        val ownerIndexes = mutableMapOf<String, OwnerWildcardIndex>()
        selected.asSequence()
            .filter { it.kind == RuleHistoryKind.THREAD }
            .forEach { candidate ->
                val exactName = candidate.thread?.trim().orEmpty()
                if (!rawThreadNameSyntaxOk(exactName)) return@forEach
                val index = ownerIndexes.getOrPut(candidate.owner) {
                    val names = candidates.asSequence()
                        .filter { it.kind == RuleHistoryKind.THREAD && it.owner == candidate.owner }
                        .mapNotNull { it.thread?.trim()?.takeIf(String::isNotEmpty) }
                        .plus(
                            selected.asSequence()
                                .filter {
                                    it.kind == RuleHistoryKind.THREAD && it.owner == candidate.owner
                                }
                                .mapNotNull { it.thread?.trim()?.takeIf(String::isNotEmpty) }
                        )
                        .filter(::rawThreadNameSyntaxOk)
                        .distinct()
                        .toList()
                    OwnerWildcardIndex(names, buildPatternChoices(names))
                }
                val suggestion = buildSuggestion(exactName, index.names, index.choices)
                    ?: return@forEach
                val key = "${candidate.owner}\u0000${suggestion.pattern}"
                suggestions.putIfAbsent(
                    key,
                    OwnedThreadWildcardSuggestion(candidate.owner, suggestion)
                )
            }
        return suggestions.values.toList()
    }

    fun resolveThreadTargets(
        selected: List<RuleHistoryCandidate>,
        appliedSuggestions: Collection<OwnedThreadWildcardSuggestion>
    ): List<Pair<String, String>> {
        val resolved = linkedMapOf<String, Pair<String, String>>()
        selected.forEach { candidate ->
            val name = candidate.thread?.trim().orEmpty()
            if (candidate.kind == RuleHistoryKind.THREAD && name.isNotEmpty()) {
                resolved["${candidate.owner}\u0000$name"] = candidate.owner to name
            }
        }
        appliedSuggestions.forEach { owned ->
            val matched = owned.suggestion.matchedNames.toHashSet()
            resolved.entries.removeAll { (_, target) ->
                target.first == owned.owner && target.second in matched
            }
            val pattern = owned.suggestion.pattern
            resolved["${owned.owner}\u0000$pattern"] = owned.owner to pattern
        }
        return resolved.values.toList()
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

    private data class OwnerWildcardIndex(
        val names: List<String>,
        val choices: List<PatternChoice>
    )

    private fun buildPatternChoices(names: List<String>): List<PatternChoice> {
        return names.asSequence()
            .mapNotNull { ownWildcardCandidate(it, names) }
            .distinct()
            .map { pattern ->
                val regex = generatedWildcardRegex(pattern)
                PatternChoice(
                    pattern = pattern,
                    regex = regex,
                    coverage = names.count(regex::matches),
                    requiredAtoms = wildcardRequiredAtoms(pattern),
                    codePointLength = pattern.codePointCount(0, pattern.length)
                )
            }
            .toList()
    }

    private fun buildSuggestion(
        exactName: String,
        names: List<String>,
        choices: List<PatternChoice>
    ): ThreadWildcardSuggestion? {
        val choice = choices.asSequence()
            .filter { it.regex.matches(exactName) }
            .minWithOrNull(Comparator(::comparePatternChoices))
            ?: return null
        val matchedNames = names
            .filter(choice.regex::matches)
            .sortedWith(Comparator(::compareThreadNames))
        if (exactName !in matchedNames) return null
        return ThreadWildcardSuggestion(exactName, choice.pattern, matchedNames)
    }

    private fun takeRecentSessionsFairly(
        sorted: List<RuleHistoryCandidate>,
        limit: Int
    ): List<RuleHistoryCandidate> {
        if (sorted.size <= limit) return sorted
        val epochs = sorted.asSequence().map(RuleHistoryCandidate::epoch).distinct().toList()
        // 正常来源只有最近三个会话。大量不同 epoch 通常来自调用方测试或导入数据，
        // 此时仍按全局顺序截断，避免为了稀疏数据制造数千个小桶。
        if (epochs.size !in 2..10) return sorted.take(limit)

        val buckets = sorted.groupBy(RuleHistoryCandidate::epoch)
        val selected = LinkedHashMap<String, RuleHistoryCandidate>(limit)
        val perSession = (limit / epochs.size).coerceAtLeast(1)
        epochs.forEach { epoch ->
            buckets[epoch].orEmpty().take(perSession).forEach { candidate ->
                selected[candidateKey(candidate)] = candidate
            }
        }
        if (selected.size < limit) {
            sorted.forEach { candidate ->
                if (selected.size >= limit) return@forEach
                selected.putIfAbsent(candidateKey(candidate), candidate)
            }
        }
        return selected.values.take(limit)
    }

    private fun candidateKey(candidate: RuleHistoryCandidate): String =
        "${candidate.kind}\u0000${candidate.owner}\u0000${candidate.thread.orEmpty()}"

    private val CANDIDATE_ORDER =
        compareByDescending<RuleHistoryCandidate> { it.epoch }
            .thenByDescending { it.avg ?: -1f }
            .thenByDescending { it.max ?: -1f }
            .thenBy { it.thread ?: it.owner }

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
