package top.qixia.threads.compose

import top.qixia.threads.OwnedThreadWildcardSuggestion
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.RuleHistoryCandidate
import top.qixia.threads.RuleHistoryCandidates
import top.qixia.threads.RuleHistoryKind
import top.qixia.threads.RuleSyntax
import top.qixia.threads.DaemonBridge

/** 主进程兜底规则没有需要守护进程校验的线程模式。 */
internal fun ruleBindingStatus(rule: RuleSyntax.Rule, base: String, installed: Boolean,
    dirty: Boolean, suspended: Boolean, health: DaemonBridge.RuleHealthStatus?, systemComponent: Boolean = false): String = when {
    dirty -> "未保存"
    suspended -> "已暂停"
    !installed && !systemComponent -> "应用未安装"
    rule.owner == base && rule.thread == null && systemComponent -> "系统进程已识别"
    rule.owner == base && rule.thread == null -> "应用已安装"
    health == DaemonBridge.RuleHealthStatus.MISSED -> "待复检"
    health == DaemonBridge.RuleHealthStatus.VALID -> "已匹配"
    else -> "待检查"
}

internal enum class RuleListFilter(val label: String) {
    ALL("全部"), MAIN("主进程"), CHILD("子进程"), THREAD("线程")
}

internal sealed interface RuleListEntry {
    val key: String
    data class Binding(val index: Int, val rule: RuleSyntax.Rule, val nested: Boolean = false) : RuleListEntry {
        override val key = "rule:$index"
    }
    data class Process(val owner: String, val count: Int, val expanded: Boolean) : RuleListEntry {
        override val key = "process:$owner"
    }
}

/** 索引始终对应源列表，包括重复规则和筛选后的子行。 */
internal fun ruleListEntries(
    rules: List<RuleSyntax.Rule>, base: String, query: String,
    filter: RuleListFilter, expanded: Set<String>
): List<RuleListEntry> {
    val search = query.trim()
    fun matches(rule: RuleSyntax.Rule): Boolean = search.isEmpty() ||
        rule.owner.contains(search, true) || rule.thread.orEmpty().contains(search, true) ||
        rule.cpus.contains(search, true) ||
        search.removePrefix("CPU ").removePrefix("cpu ").toIntOrNull()?.let {
            it in RuleConfigLogic.parseCpuRangeList(rule.cpus).orEmpty()
        } == true
    val indexed = rules.withIndex().toList()
    return buildList {
        indexed.filter { it.value.owner == base }.forEach { (index, rule) ->
            val inFilter = when (filter) {
                RuleListFilter.ALL -> true
                RuleListFilter.MAIN -> rule.thread == null
                RuleListFilter.CHILD -> false
                RuleListFilter.THREAD -> rule.thread != null
            }
            if (inFilter && matches(rule)) add(RuleListEntry.Binding(index, rule))
        }
        if (filter != RuleListFilter.MAIN) {
            indexed.filter { it.value.owner != base }.groupBy { it.value.owner }.forEach { (owner, group) ->
                val visible = group.filter { (_, rule) ->
                    (filter != RuleListFilter.THREAD || rule.thread != null) && matches(rule)
                }
                if (visible.isNotEmpty()) {
                    val open = owner in expanded || search.isNotEmpty() || filter == RuleListFilter.THREAD
                    add(RuleListEntry.Process(owner, group.size, open))
                    if (open) visible.forEach { (index, rule) -> add(RuleListEntry.Binding(index, rule, nested = true)) }
                }
            }
        }
    }
}

internal data class RuleEditTarget(val owner: String, val name: String = "", val cpus: String = "")

/** 从历史中选择会替换目标列表，未变目标仍保留各自的核心选择。 */
internal fun replaceRuleTargets(current: List<RuleEditTarget>, targets: List<Pair<String, String>>): List<RuleEditTarget> {
    val previous = current.associateBy { it.owner to it.name }
    return targets.distinct().map { (owner, name) ->
        previous[owner to name] ?: RuleEditTarget(owner, name, current.firstOrNull()?.cpus.orEmpty())
    }
}

internal fun applyRuleWildcards(
    current: List<RuleEditTarget>, suggestions: List<OwnedThreadWildcardSuggestion>
): List<RuleEditTarget> {
    val candidates = current.filter { it.name.isNotBlank() }.map {
        RuleHistoryCandidate(RuleHistoryKind.THREAD, it.owner, it.name, null, null, 0)
    }
    return RuleHistoryCandidates.resolveThreadTargets(candidates, suggestions).map { (owner, name) ->
        current.firstOrNull { it.owner == owner && it.name == name } ?: run {
            val matched = suggestions.firstOrNull { it.owner == owner && it.suggestion.pattern == name }
                ?.suggestion?.matchedNames.orEmpty().toSet()
            val cpus = current.filter { it.owner == owner && it.name in matched }
                .flatMap { RuleConfigLogic.parseCpuRangeList(it.cpus).orEmpty() }.toSet()
            RuleEditTarget(owner, name, RuleConfigLogic.formatCpuRangeList(cpus))
        }
    }
}

internal fun hasDuplicateRuleTargets(
    existing: List<RuleSyntax.Rule>, replacements: List<RuleSyntax.Rule>, editingIndex: Int?
): Boolean {
    val targets = replacements.map { it.owner to it.thread }
    return targets.distinct().size != targets.size || existing.withIndex().any { (index, rule) ->
        index != editingIndex && (rule.owner to rule.thread) in targets
    }
}
