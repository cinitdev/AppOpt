package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutomaticCustomRulesTest {
    @Test fun parsesSortsAndRoundTripsDeviceIndependentCpuRanges() {
        val rules = listOf(AutomaticCustomRule(2, "0-3,6"), AutomaticCustomRule(40, "4-7"), AutomaticCustomRule(80, "63"))
        assertEquals(rules, AutomaticCustomRules.decode("80:63|2:0-3+6|40:4-7"))
        assertEquals("2:0-3+6|40:4-7|80:63", AutomaticCustomRules.encode(rules.reversed()))
        val eight = (1..8).joinToString("|") { "$it:0" }
        assertEquals(8, AutomaticCustomRules.decode(eight)?.size)
        assertNull(AutomaticCustomRules.decode("$eight|9:0"))
    }

    @Test fun rejectsAmbiguousThresholdsAndMalformedOrEmptyCoreRanges() {
        for (bad in listOf("", "0:0", "101:0", "5.5:0", "05:0", "5:64", "5:3-1", "5:0|5:1",
            "5:", "5:0+", "5:0:1", "5:01", "5:0|", "5:0,1", "5:0++1", "5:0-99999999999")) {
            assertNull(bad, AutomaticCustomRules.decode(bad))
            assertTrue(bad, AutomaticAffinityConfig.entries("com.game,custom=$bad,record").isEmpty())
        }
    }

    @Test fun customOptionsComposeWithoutChangingRecordingOnlyOrPackageNames() {
        val raw = "com.game,custom=2:0-3|30:4-7,record\ncom.game,fallback\ncom.video,record\n"
        val options = AutomaticAffinityConfig.entries(raw).getValue("com.game")
        assertTrue(options.automatic && options.fallback && options.recording)
        assertEquals(2, options.custom.first().threshold)
        assertEquals(setOf("com.game"), AutomaticAffinityConfig.modes(raw).keys)
        assertEquals(setOf("com.game", "com.video"), AutomaticAffinityConfig.recordingPackages(raw))
        assertEquals(setOf("com.game"), SceneBlacklistSync.collectPackages("", raw))
        assertTrue(AutomaticAffinityConfig.entries("com.game,custom=2:0,custom=5:1,record").isEmpty())
        assertEquals(listOf(AutomaticCustomRule(10, "1")), AutomaticAffinityConfig.entries(
            "com.game,custom=2:0\ncom.game,record\ncom.game,custom=10:1\ncom.game,custom=broken").getValue("com.game").custom)
    }

    @Test fun capabilityAndCustomRulesAreReportedEvenWhenDaemonIsStopped() {
        for (live in listOf("", "1")) {
            val state = DaemonBridge.parseAutomaticAffinity("version=1\ncustom_version=1\n__PACKAGES__\ncom.game,custom=2:0-3\n__LIVE__\n$live")
            assertTrue(state.customSupported)
            assertEquals(listOf(AutomaticCustomRule(2, "0-3")), state.customRules["com.game"])
        }
        assertFalse(DaemonBridge.parseAutomaticAffinity("version=1\n__PACKAGES__\ncom.game\n__LIVE__\n1").customSupported)
    }
}
