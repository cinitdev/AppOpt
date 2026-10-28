package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoStartAccessTest {
    @Test fun frameworkWarningsDoNotHideStructuredStatus() {
        assertEquals(AutoStartStatus.ALLOWED, AutoStartAccess.parse(true,
            "Vendor theme warning\nqixia_autostart=allowed\n"))
        assertEquals(AutoStartStatus.BLOCKED, AutoStartAccess.parse(true, "qixia_autostart=blocked"))
    }

    @Test fun MissingAmbiguousOrFailedResultsNeverClaimPermission() {
        for (output in listOf("", "mode=allow", "qixia_autostart=unknown",
            "qixia_autostart=allowed\nqixia_autostart=blocked")) {
            assertEquals(AutoStartStatus.UNKNOWN, AutoStartAccess.parse(true, output))
        }
        assertEquals(AutoStartStatus.UNKNOWN, AutoStartAccess.parse(false, "qixia_autostart=allowed"))
    }
}
