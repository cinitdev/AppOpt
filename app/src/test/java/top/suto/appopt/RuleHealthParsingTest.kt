package top.suto.appopt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleHealthParsingTest {

    @Test
    fun unescapePreservesEncodedLiteralSequences() {
        assertEquals("literal\\t", DaemonBridge.unescapeRuleHealthField("literal\\\\t"))
        assertEquals("literal\\n", DaemonBridge.unescapeRuleHealthField("literal\\\\n"))
        assertEquals("tab\tnewline\nslash\\", DaemonBridge.unescapeRuleHealthField("tab\\tnewline\\nslash\\\\"))
    }

    @Test
    fun parserBuildsTheSameKeyAsCurrentConfig() {
        val health = DaemonBridge.parseRuleHealth(
            "T\tcom.example.app\tliteral\\\\t\tmissed\t2\t1\t0\t2\tcom.example.app{literal\\\\t}=7\n"
        )
        val key = DaemonBridge.ruleHealthKey("T", "com.example.app", "literal\\t")
        assertEquals(DaemonBridge.RuleHealthStatus.MISSED, health[key]?.status)
    }

    @Test
    fun parserAcceptsLifecycleAwareAndLegacyRows() {
        val legacy = DaemonBridge.parseRuleHealth(
            "T\tcom.example.app\tRenderThread\tpending\t1\t1\t0\t2\tcom.example.app{RenderThread}=7\n"
        )
        val current = DaemonBridge.parseRuleHealth(
            "T\tcom.example.app\tRenderThread\tpending\t1\t1\t0\t2\tboot-id\t1234\tcom.example.app{RenderThread}=0-3,7\n"
        )
        val key = DaemonBridge.ruleHealthKey("T", "com.example.app", "RenderThread")
        assertEquals("com.example.app{RenderThread}=7", legacy[key]?.ruleLine)
        assertEquals("com.example.app{RenderThread}=0-3,7", current[key]?.ruleLine)
    }

    @Test
    fun configKeysIgnoreCpuOnlyChangesAndExcludeMainRules() {
        val first = ConfigReader.parsePackages(
            "com.example.app{RenderThread}=7\ncom.example.app:push=0-3\ncom.example.app=4-7\n"
        )
        val second = ConfigReader.parsePackages(
            "com.example.app{RenderThread}=4-7\ncom.example.app:push=2-3\ncom.example.app=0-7\n"
        )
        assertEquals(first.ruleHealthKeys, second.ruleHealthKeys)
        assertTrue(DaemonBridge.ruleHealthKey("T", "com.example.app", "RenderThread") in first.ruleHealthKeys)
        assertTrue(DaemonBridge.ruleHealthKey("P", "com.example.app:push", null) in first.ruleHealthKeys)
        assertFalse(DaemonBridge.ruleHealthKey("P", "com.example.app", null) in first.ruleHealthKeys)
    }
}
