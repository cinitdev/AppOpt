package top.qixia.threads.compose.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshAppRuleEditTest {
    @Test fun openingAnUnconfiguredAppAndSavingWritesNothing() {
        assertEquals(AppRuleEdit.None, appRuleEdit(null, "-2", "-2"))
    }

    @Test fun savingWithoutChangesWritesNothing() {
        assertEquals(AppRuleEdit.None, appRuleEdit("60,120", "60", "120"))
    }

    @Test fun settingBothLevelsBackToInheritRemovesTheStoredRule() {
        assertEquals(AppRuleEdit.Clear, appRuleEdit("60,120", "-2", "-2"))
        assertEquals(AppRuleEdit.Clear, appRuleEdit("-2,-2", "-2", "-2"))
    }

    @Test fun changedValuesAreSaved() {
        assertEquals(AppRuleEdit.Save("90,120"), appRuleEdit(null, "90", "120"))
        assertEquals(AppRuleEdit.Save("60,90"), appRuleEdit("60,120", "60", "90"))
        assertEquals(AppRuleEdit.Save("120,120"), appRuleEdit(null, "120", "120"))
    }
}
