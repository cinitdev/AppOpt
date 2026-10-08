package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.CalibPolicy
import top.qixia.threads.HistoryFieldCodec
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData

class PorcelainUiLogicTest {
    private fun app(pkg: String, state: AppRuleState, installed: Boolean = true) =
        AppItemModel(pkg, pkg, installed, null, state = state)

    @Test fun pendingAndConfiguredAreDisjoint() {
        val items = listOf(app("pending", AppRuleState.PENDING), app("configured", AppRuleState.CONFIGURED), app("missing", AppRuleState.MISSING, false))
        val state = ApplicationsUiState(configured = items, loading = false)
        assertEquals(listOf("pending"), state.visibleItems.map { it.packageName })
        assertEquals(listOf("configured", "missing"), state.copy(selectedTab = ApplicationTab.CONFIGURED).visibleItems.map { it.packageName })
        assertEquals(listOf("configured"), state.copy(selectedTab = ApplicationTab.CONFIGURED, hideMissing = true).visibleItems.map { it.packageName })
    }

    @Test fun searchMatchesCaseInsensitivePackageAndKeepsTabBoundary() {
        val state = ApplicationsUiState(configured = listOf(app("com.Game.Test", AppRuleState.PENDING), app("com.Other", AppRuleState.CONFIGURED)),
            query = "  GAME  ")
        assertEquals("com.Game.Test", state.visibleItems.single().packageName)
        assertTrue(state.copy(selectedTab = ApplicationTab.CONFIGURED).visibleItems.isEmpty())
    }

    @Test fun pendingAndAddableShareOneListWithPendingFirst() {
        val state = ApplicationsUiState(
            configured = listOf(app("pending", AppRuleState.PENDING), app("configured", AppRuleState.CONFIGURED)),
            addable = listOf(app("new", AppRuleState.CONFIGURED))
        )
        assertEquals(listOf("pending", "new"), state.visibleItems.map { it.packageName })
        assertEquals(listOf("configured"), state.copy(selectedTab = ApplicationTab.CONFIGURED).visibleItems.map { it.packageName })
    }

    @Test fun addedApplicationCannotRemainAsDuplicateAddButtonInStaleInstalledList() {
        val installed = listOf(app("new", AppRuleState.CONFIGURED), app("configured", AppRuleState.CONFIGURED))
        val before = ApplicationsUiState(configured = listOf(installed[1]), addable = installed, query = "new")
        assertEquals(installed[0], before.visibleItems.single())
        val pending = installed[0].copy(state = AppRuleState.PENDING)
        val after = before.copy(configured = before.configured + pending)
        assertEquals(ApplicationTab.LIBRARY, after.selectedTab)
        assertEquals(pending, after.visibleItems.single())
        assertEquals(listOf(pending), after.copy(query = "").visibleItems)
        assertEquals(listOf(installed[1]), after.copy(query = "", selectedTab = ApplicationTab.CONFIGURED).visibleItems)
    }

    @Test fun sharedSearchMatchesNamesAndPackagesAcrossBothSources() {
        val pending = app("com.game.pending", AppRuleState.PENDING)
        val addable = app("com.new", AppRuleState.CONFIGURED).copy(label = "Game Player")
        val state = ApplicationsUiState(configured = listOf(pending), addable = listOf(addable), query = "  GaMe  ")
        assertEquals(listOf(pending, addable), state.visibleItems)
        assertEquals(listOf(addable), state.copy(query = "NEW").visibleItems)
        assertTrue(state.copy(query = "does.not.exist").visibleItems.isEmpty())
    }

    @Test fun hideMissingAppliesToTheMergedListWithoutHidingInstalledAddableApps() {
        val pending = app("pending", AppRuleState.PENDING, installed = false)
        val addable = app("new", AppRuleState.CONFIGURED)
        val state = ApplicationsUiState(configured = listOf(pending), addable = listOf(addable, addable))
        assertEquals(listOf(pending, addable), state.visibleItems)
        assertEquals(listOf(addable), state.copy(hideMissing = true).visibleItems)
        assertEquals(listOf(pending, addable), state.copy(hideMissing = true).copy(hideMissing = false).visibleItems)
    }

    @Test fun lastAverageIsNotClearedByNavigationOrSearch() {
        val item = app("game", AppRuleState.CONFIGURED).copy(averageFps = 58.6f, fpsSessionDurationMs = 3_600_000)
        val state = ApplicationsUiState(configured = listOf(item), selectedTab = ApplicationTab.CONFIGURED)
        assertEquals(58.6f, state.copy(query = "gam").visibleItems.single().averageFps!!, .001f)
        assertEquals(3_600_000L, state.visibleItems.single().fpsSessionDurationMs)
    }

    @Test fun readonlyAndUpdateLocksArePreserved() {
        assertFalse(SettingsUiState(hasRoot = false, readSuccess = true).editable)
        assertFalse(SettingsUiState(hasRoot = true, readSuccess = false).editable)
        assertFalse(SettingsUiState(hasRoot = true, readSuccess = true, lockedByPendingUpdate = true).editable)
    }

    @Test fun defaultPolicyValidForEightCoreDevice() {
        assertNull(PolicyEditorLogic.validate(CalibPolicy.DEFAULT, (0..7).toSet()))
    }

    @Test fun retiredTierValuesDoNotBlockCurrentSettings() {
        assertNull(PolicyEditorLogic.validate(CalibPolicy.DEFAULT.copy(bestCores = "999", bestAvg = Double.NaN, maxThreadRules = 100), emptySet()))
        assertNotNull(PolicyEditorLogic.validate(CalibPolicy.DEFAULT.copy(cpusetName = "../bad"), emptySet()))
        val output = CalibPolicy.DEFAULT.toConfigText()
        assertFalse(output.contains("best_thread="))
        assertFalse(output.contains("fallback="))
        assertFalse(output.contains("max_thread_rules="))
        assertTrue(output.contains("rule_output_format="))
    }

    @Test fun ruleDirtyStateComparesWithOriginalSnapshot() {
        val editor = RuleEditorUiState(app("a", AppRuleState.CONFIGURED), listOf("a=0-3"), "a=0-3", setOf(0, 1, 2, 3))
        assertFalse(editor.dirty)
        assertTrue(editor.copy(draft = "a=0-1").dirty)
        assertFalse(editor.copy(draft = "a=0-3\n").dirty)
    }

    @Test fun logAttentionIncludesWarningsAndErrors() {
        val logs = LogsUiState(entries = LogLevel.entries.mapIndexed { i, level -> LogEntryModel(i.toLong(), i, "tag", level, "body", "raw") }, filter = LogFilter.ATTENTION)
        assertEquals(setOf(LogLevel.WARNING, LogLevel.ERROR), logs.visibleEntries.map { it.level }.toSet())
        assertEquals(1, logs.copy(filter = LogFilter.ERROR).visibleEntries.size)
    }

    @Test fun exportKeepsNamesStatsSeriesAndChildPayload() {
        val encoded = "v3p:${HistoryFieldCodec.encodeName("render,thread")},12.3,45.6"
        val result = HistoryExportText.session("com.game", "游戏", SessionSummary(9, 123, 120, 1),
            listOf(ThreadData("com.game:render", 12.3f, 45.6f, "1,2,3", encoded)))
        assertTrue(result.contains("会话 ID: 9"))
        assertTrue(result.contains("12.3%"))
        assertTrue(result.contains("45.6%"))
        assertTrue(result.contains("曲线: 1,2,3"))
        assertTrue(result.contains(encoded))
        assertTrue(result.contains("不是实时 FPS"))
    }

    @Test fun encodedChildDetailsAreReadableAndKeepStats() {
        val payload = "v3p:${HistoryFieldCodec.encodeName("render,thread")},12.3,45.6"
        val child = HistoryPresentation.children(payload).single()
        assertEquals("render,thread", child.name)
        assertEquals(12.3f, child.avg!!, .001f)
        assertEquals(45.6f, child.max!!, .001f)
    }

    @Test fun legacyChildDetailsKeepAllNames() {
        assertEquals(listOf("one", "two"), HistoryPresentation.children("one,two").map { it.name })
        assertTrue(HistoryPresentation.children("").isEmpty())
    }
}
