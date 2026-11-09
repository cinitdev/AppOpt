package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test

class ApplicationListUpdatesTest {
    private fun app(pkg: String, state: AppRuleState = AppRuleState.CONFIGURED) =
        AppItemModel(pkg, pkg, true, null, state = state)

    @Test fun sortReadsInstallationTimeOnceAndPreservesOrdering() {
        val calls = mutableMapOf<String, Int>()
        val items = listOf(app("z"), app("a"), app("new"), app("gone").copy(installed = false))
        val sorted = sortAppsByInstallTime(items) { pkg ->
            calls[pkg] = (calls[pkg] ?: 0) + 1
            if (pkg == "new") 2L else 1L
        }
        assertEquals(listOf("new", "a", "z", "gone"), sorted.map { it.packageName })
        assertEquals(mapOf("z" to 1, "a" to 1, "new" to 1), calls)
    }

    @Test fun switchingAutomaticModeKeepsAndRestoresManualRules() {
        val manual = AppItemModel("game", "Game", true, null, ruleCount = 6,
            cpuSummary = "CPU 4-7", unhealthyRuleCount = 2)
        val enabled = manual.withAutomaticAffinity(true)
        assertEquals("CPU 自动分配", enabled.cpuSummary)
        assertEquals(0, enabled.unhealthyRuleCount)
        assertEquals(6, enabled.ruleCount)
        assertTrue(enabled.automaticAffinityEnabled)
        assertEquals(enabled, enabled.withAutomaticAffinity(true))
        assertEquals(manual, enabled.withAutomaticAffinity(false))
    }

    @Test fun togglingPendingAppDoesNotLoseItsPlaceWhenReturningToCalibration() {
        val added = app("pending", AppRuleState.PENDING)
        val enabled = added.withAutomaticAffinity(true)
        assertEquals(AppRuleState.CONFIGURED, enabled.state)
        assertEquals(added, enabled.withAutomaticAffinity(false))
    }

    @Test fun automaticOnlyAppMovesBetweenCatalogAndConfiguredWithoutLosingRecording() {
        val catalogApp = app("game").copy(performanceRecordingEnabled = true)
        val initial = ApplicationsUiState(loading = false, addable = listOf(catalogApp))

        val enabled = initial.automaticModeChanged("game", true, catalogApp)
        assertEquals("game", enabled.configured.single().packageName)
        assertTrue(enabled.configured.single().automaticAffinityEnabled)
        assertTrue(enabled.configured.single().automaticOnly)
        assertTrue(enabled.configured.single().performanceRecordingEnabled)

        val disabled = enabled.automaticModeChanged("game", false)
        assertTrue(disabled.configured.isEmpty())
        assertEquals("game", disabled.visibleItems.single().packageName)
        assertFalse(disabled.addable.single().automaticAffinityEnabled)
        assertFalse(disabled.addable.single().automaticOnly)
        assertTrue(disabled.addable.single().performanceRecordingEnabled)
    }

    @Test fun automaticOnlyEntryWithoutCatalogSnapshotReturnsToAddableOnDisable() {
        val configuredOnly = ApplicationsUiState(configured = listOf(
            app("game").withAutomaticAffinity(true).copy(automaticOnly = true)
        ))

        val disabled = configuredOnly.automaticModeChanged("game", false)
        assertTrue(disabled.configured.isEmpty())
        assertEquals("game", disabled.addable.single().packageName)
        assertTrue(disabled.visibleItems.single().packageName == "game")
    }

    @Test fun addDeleteAndReaddKeepCatalogueAndOtherPendingChanges() {
        val a = app("a")
        val b = app("b")
        val initial = ApplicationsUiState(loading = false, addable = listOf(a, b))
        val both = initial.applicationAdded(a).applicationAdded(b).applicationAdded(a)
        assertEquals(2, both.configured.size)
        val after = both.automaticModeChanged("b", true).applicationDeleted("a")
        assertEquals(listOf("a"), after.visibleItems.map { it.packageName })
        assertTrue(after.configured.single().automaticAffinityEnabled)
        val readded = after.applicationAdded(a)
        assertEquals(1, readded.visibleItems.size)
        assertEquals(AppRuleState.PENDING, readded.visibleItems.single().state)
    }
}
