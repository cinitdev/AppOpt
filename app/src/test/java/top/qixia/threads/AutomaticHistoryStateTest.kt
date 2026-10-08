package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutomaticHistoryStateTest {
    @Test fun missingOrUnsupportedBackendNeverLooksEnabled() {
        listOf("", "enabled=1", "auto_history_enabled=1",
            "auto_history_version=2\nauto_history_enabled=1",
            "auto_history_version=1\nauto_history_version=1\nauto_history_enabled=1").forEach {
            assertEquals(DaemonBridge.AutomaticHistoryState(), DaemonBridge.parseAutomaticHistory(it))
        }
    }

    @Test fun newInstallDefaultsOffAndOnlyExplicitOneEnablesRecording() {
        listOf("", "auto_history_enabled=0", "auto_history_enabled=true", "auto_history_enabled=garbage",
            "enabled=1", "auto_history_enabled=1\nauto_history_enabled=0",
            "auto_history_enabled=0\nauto_history_enabled=1", "auto_history_enabled=1\nbroken").forEach {
            assertEquals(DaemonBridge.AutomaticHistoryState(true, false),
                DaemonBridge.parseAutomaticHistory("auto_history_version=1\n$it"))
        }
    }

    @Test fun sharesPolicyWithoutConfusingUnrelatedVersionAndEnabledKeys() {
        val policy = "version=2\n# opt in\nauto_history_version = 1\nauto_history_enabled = 1 # record\n" +
            "cpuset_name=Custom\nenabled=0\ndetected_all=0-7\n"
        assertEquals(DaemonBridge.AutomaticHistoryState(true, true), DaemonBridge.parseAutomaticHistory(policy))
    }
}
