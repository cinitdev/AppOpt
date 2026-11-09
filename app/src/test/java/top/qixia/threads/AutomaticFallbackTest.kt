package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutomaticFallbackTest {
    @Test fun recordingOnlyNeverEnablesAutomaticRulesOrSceneBlacklist() {
        val raw = "com.video,record\ncom.game,auto,record\ncom.full,fallback,record\ncom.plain\n"
        assertEquals(setOf("com.video", "com.game", "com.full"), AutomaticAffinityConfig.recordingPackages(raw))
        assertEquals(mapOf("com.game" to false, "com.full" to true, "com.plain" to false), AutomaticAffinityConfig.modes(raw))
        assertEquals(setOf("com.game", "com.full", "com.plain"), SceneBlacklistSync.collectPackages("", raw))
        val state = DaemonBridge.parseAutomaticAffinity("version=1\nfallback_version=1\nrecording_version=1\n__PACKAGES__\n$raw\n__LIVE__\n1")
        assertEquals(setOf("com.game", "com.full", "com.plain"), state.packages)
        assertEquals(setOf("com.full"), state.fallbackPackages)
    }

    @Test fun combinedFlagsMergeWithoutDependingOnOrderAndRejectUnknownOptions() {
        for (raw in listOf("com.game\ncom.game,record", "com.game,record\ncom.game", "com.game,record,auto")) {
            assertEquals(AutomaticAffinityConfig.Options(true, false, true), AutomaticAffinityConfig.entries(raw)["com.game"])
        }
        assertEquals(AutomaticAffinityConfig.Options(true, true, true),
            AutomaticAffinityConfig.entries("com.game , record , fallback # 组合")["com.game"])
        for (raw in listOf("com.game,record,unknown", "com.game,record,", "com.game:child,record", "bad..name,record")) {
            assertTrue(raw, AutomaticAffinityConfig.entries(raw).isEmpty())
        }
    }

    @Test fun optionNeverLeaksIntoPackageNamesAndDuplicatesMerge() {
        val raw = "com.game,fallback\ncom.other\ncom.game # 重复\ncom.child:remote,fallback\ncom.bad,eco\n"
        assertEquals(mapOf("com.game" to true, "com.other" to false), AutomaticAffinityConfig.modes(raw))
        assertEquals(setOf("com.game", "com.other"), DaemonBridge.parseAutomaticAffinityPackages(raw))
        val state = DaemonBridge.parseAutomaticAffinity("version=1\nfallback_version=1\n__PACKAGES__\n$raw\n__LIVE__\n1")
        assertTrue(state.fallbackSupported)
        assertEquals(setOf("com.game"), state.fallbackPackages)
        assertFalse(DaemonBridge.parseAutomaticAffinity("version=1\n__PACKAGES__\ncom.game\n__LIVE__\n1").fallbackSupported)
    }

    @Test fun fallbackMatchesMainProcessOnlyAcrossRuleFormatsAndDuplicatePriority() {
        val present = (0..7).toSet()
        for (raw in listOf(
            "com.game=0-3\ncom.game:worker=4-6\ncom.game{RenderThread}=7",
            "app(com.game, 0-3) {\n thread(RenderThread, 7)\n}",
        )) {
            assertEquals("0-3", AutomaticAffinityConfig.fallbackCpus(RuleSyntax.parse(raw).rules, "com.game", present))
        }
        assertNull(AutomaticAffinityConfig.fallbackCpus(RuleSyntax.parse("com.game=auto\ncom.game:worker=0-3\ncom.game{main}=7").rules, "com.game", present))
        val duplicates = RuleSyntax.parse("com.game=2-4\ncom.game=0,3-4\ncom.game=6-10").rules
        assertEquals("0,3-4", AutomaticAffinityConfig.fallbackCpus(duplicates, "com.game", present))
    }
}
