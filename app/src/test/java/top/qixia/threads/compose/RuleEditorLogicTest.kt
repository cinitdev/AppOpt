package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.OwnedThreadWildcardSuggestion
import top.qixia.threads.RuleSyntax.Rule
import top.qixia.threads.ThreadWildcardSuggestion
import top.qixia.threads.DaemonBridge.RuleHealthStatus

class RuleEditorLogicTest {
    @Test fun mainFallbackUsesInstallationInsteadOfNonexistentHealthEntry() {
        val main = Rule(base, null, "0-3")
        assertEquals("应用已安装", ruleBindingStatus(main, base, true, false, false, null))
        assertEquals("应用未安装", ruleBindingStatus(main, base, false, false, false, null))
        assertEquals("未保存", ruleBindingStatus(main, base, true, true, false, null))
        assertEquals("已暂停", ruleBindingStatus(main, base, true, false, true, null))
    }

    @Test fun installedPackageDoesNotProveChildOrThreadMatch() {
        for (rule in listOf(Rule(base, "RenderThread", "7"), Rule("$base:worker", null, "4-6"))) {
            assertEquals("待检查", ruleBindingStatus(rule, base, true, false, false, null))
            assertEquals("待复检", ruleBindingStatus(rule, base, true, false, false, RuleHealthStatus.MISSED))
            assertEquals("已匹配", ruleBindingStatus(rule, base, true, false, false, RuleHealthStatus.VALID))
            assertEquals("已暂停", ruleBindingStatus(rule, base, true, false, true, RuleHealthStatus.MISSED))
        }
    }
    private val base = "com.test.game"
    private val rules = listOf(
        Rule(base, "RenderThread", "7"), Rule("$base:worker", "Decode", "4-6"),
        Rule(base, null, "0-3"), Rule("$base:worker", null, "4-7"),
        Rule(base, "RenderThread", "6"), Rule("$base:push", "Binder:*", "0-2")
    )

    @Test fun filteringPreservesSourceIndicesAndDuplicateRules() {
        val visible = ruleListEntries(rules, base, "Render", RuleListFilter.ALL, emptySet())
            .filterIsInstance<RuleListEntry.Binding>()
        assertEquals(listOf(0, 4), visible.map { it.index })
        assertEquals(listOf("7", "6"), visible.map { it.rule.cpus })
    }

    @Test fun collapsedChildrenHaveOneHeaderAndKeepIndependentOwners() {
        val entries = ruleListEntries(rules, base, "", RuleListFilter.ALL, emptySet())
        assertEquals(listOf(0, 2, 4), entries.filterIsInstance<RuleListEntry.Binding>().map { it.index })
        assertEquals(listOf("$base:worker", "$base:push"), entries.filterIsInstance<RuleListEntry.Process>().map { it.owner })
        assertEquals(listOf(2, 1), entries.filterIsInstance<RuleListEntry.Process>().map { it.count })
    }

    @Test fun searchFindsInteriorCpuAndOpensMatchingChild() {
        val entries = ruleListEntries(rules, base, "CPU 5", RuleListFilter.ALL, emptySet())
        assertEquals(listOf(1, 3), entries.filterIsInstance<RuleListEntry.Binding>().map { it.index })
        assertTrue(entries.filterIsInstance<RuleListEntry.Process>().single().expanded)
    }

    @Test fun typeFiltersKeepChildThreadsAndDoNotTreatThemAsMainRules() {
        assertEquals(listOf(2), ruleListEntries(rules, base, "", RuleListFilter.MAIN, emptySet())
            .filterIsInstance<RuleListEntry.Binding>().map { it.index })
        val threads = ruleListEntries(rules, base, "", RuleListFilter.THREAD, emptySet())
        assertEquals(setOf(0, 1, 4, 5), threads.filterIsInstance<RuleListEntry.Binding>().map { it.index }.toSet())
        val child = ruleListEntries(rules, base, "", RuleListFilter.CHILD, setOf("$base:worker"))
        assertEquals(listOf(1, 3), child.filterIsInstance<RuleListEntry.Binding>().map { it.index })
    }

    @Test fun historyReplacementRetainsEachTargetsCoreSelection() {
        val old = listOf(RuleEditTarget(base, "Render", "7"), RuleEditTarget(base, "Worker", "4-6"))
        val result = replaceRuleTargets(old, listOf(base to "Worker", base to "Audio", base to "Worker"))
        assertEquals(listOf(RuleEditTarget(base, "Worker", "4-6"), RuleEditTarget(base, "Audio", "7")), result)
        assertEquals("7", old.first().cpus)
    }

    @Test fun wildcardMergePreservesCoreUnionAndOtherOwners() {
        val old = listOf(RuleEditTarget(base, "worker-1", "4"), RuleEditTarget(base, "worker-2", "6-7"),
            RuleEditTarget("$base:child", "worker-1", "0-3"), RuleEditTarget(base, "Render", "7"))
        val selected = listOf(OwnedThreadWildcardSuggestion(base, ThreadWildcardSuggestion("worker-1", "worker-*", listOf("worker-1", "worker-2"))))
        val merged = applyRuleWildcards(old, selected)
        assertEquals("4,6-7", merged.single { it.owner == base && it.name == "worker-*" }.cpus)
        assertEquals("0-3", merged.single { it.owner == "$base:child" }.cpus)
        assertEquals("7", merged.single { it.name == "Render" }.cpus)
    }

    @Test fun duplicateDetectionExcludesOnlyTheEditedSourceRow() {
        assertTrue(hasDuplicateRuleTargets(rules, listOf(Rule(base, "RenderThread", "4")), 0))
        assertFalse(hasDuplicateRuleTargets(rules, listOf(Rule("$base:worker", "Decode", "7")), 1))
        assertTrue(hasDuplicateRuleTargets(rules, listOf(Rule(base, "New", "4"), Rule(base, "New", "7")), null))
        assertFalse(hasDuplicateRuleTargets(rules, listOf(Rule(base, "Decode", "7")), null))
    }
}
