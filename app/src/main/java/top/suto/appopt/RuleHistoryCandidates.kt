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
        val pattern = wildcardCandidate(exactName) ?: return null

        val sameOwnerNames = candidates.asSequence()
            .filter { it.kind == RuleHistoryKind.THREAD && it.owner == selected.owner }
            .mapNotNull { it.thread?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        val groupedNames = sameOwnerNames.filter { wildcardCandidate(it) == pattern }
        if (groupedNames.size < 2) return null

        val matchedNames = sameOwnerNames
            .filter { matchesGeneratedWildcard(it, pattern) }
            .sortedWith(Comparator(::compareThreadNames))
        if (matchedNames.size < 2) return null

        return ThreadWildcardSuggestion(
            exactName = exactName,
            pattern = pattern,
            matchedNames = matchedNames
        )
    }

    private fun wildcardCandidate(name: String): String? {
        if (name.isEmpty() || name == "*" || name.any { it in "{}=/\\*\n\r" }) return null
        val digitPosition = name.indexOfFirst { it in '0'..'9' }
        if (digitPosition < 0) return null
        val prefix = name.substring(0, digitPosition).trimEnd()
        val asciiLetters = prefix.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (prefix.length < 4 || asciiLetters < 2) return null

        var firstDigitEnd = digitPosition
        while (firstDigitEnd < name.length && name[firstDigitEnd] in '0'..'9') {
            firstDigitEnd++
        }
        val hasStableSuffix = name.substring(firstDigitEnd).any {
            it in 'a'..'z' || it in 'A'..'Z'
        }
        if (hasStableSuffix) {
            val precisePattern = buildString {
                var index = 0
                while (index < name.length) {
                    if (name[index] in '0'..'9') {
                        append("[0-9]*")
                        while (index < name.length && name[index] in '0'..'9') index++
                    } else {
                        append(name[index++])
                    }
                }
            }
            if (precisePattern.length <= 31) return precisePattern
        }
        return "$prefix*".takeIf { it.length <= 31 }
    }

    private fun matchesGeneratedWildcard(name: String, pattern: String): Boolean {
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
        return Regex(regex).matches(name)
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
