package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun parserKeepsChildProcessOwnerForChildThreadRules() {
        val health = DaemonBridge.parseRuleHealth(
            "T\tcom.tencent.mobileqq:MSF\tThread_*\tmissed\t2\t1\t0\t2\tcom.tencent.mobileqq:MSF{Thread_*}=0-7\n"
        )
        val key = DaemonBridge.ruleHealthKey(
            "T",
            "com.tencent.mobileqq:MSF",
            "Thread_*"
        )

        assertEquals(DaemonBridge.RuleHealthStatus.MISSED, health[key]?.status)
        assertEquals("com.tencent.mobileqq:MSF", health[key]?.owner)
        assertEquals("Thread_*", health[key]?.target)
        assertEquals(
            "com.tencent.mobileqq:MSF{Thread_*}=0-7",
            health[key]?.ruleLine
        )
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

    @Test
    fun resetPackageNormalizationKeepsOnlyASafeBasePackage() {
        assertEquals(
            "com.example.app",
            DaemonBridge.normalizeRuleHealthResetPackage("  com.example.app:push  ")
        )
        assertNull(DaemonBridge.normalizeRuleHealthResetPackage("com.example.app;touch /data/x"))
        assertEquals(
            "surfaceflinger",
            DaemonBridge.normalizeRuleHealthResetPackage("surfaceflinger")
        )
        assertNull(DaemonBridge.normalizeRuleHealthResetPackage("com..example"))
    }

    @Test
    fun resetOptimisticallyReturnsOnlyMatchingMissedRulesToPending() {
        fun health(
            owner: String,
            target: String,
            status: DaemonBridge.RuleHealthStatus
        ) = DaemonBridge.RuleHealth(
            kind = "T",
            owner = owner,
            target = target,
            status = status,
            missCount = 2,
            firstObservedAt = 11L,
            lastMatchedAt = 12L,
            lastCheckedAt = 13L,
            ruleLine = "$owner{$target}=0-3"
        )

        val matchingKey = DaemonBridge.ruleHealthKey("T", "com.example.app", "RenderThread")
        val pendingReviewKey = DaemonBridge.ruleHealthKey("T", "com.example.app", "Worker-1")
        val validKey = DaemonBridge.ruleHealthKey("T", "com.example.app", "Worker")
        val otherKey = DaemonBridge.ruleHealthKey("T", "com.other.app", "RenderThread")
        val source = linkedMapOf(
            matchingKey to health(
                "com.example.app",
                "RenderThread",
                DaemonBridge.RuleHealthStatus.MISSED
            ),
            pendingReviewKey to health(
                "com.example.app",
                "Worker-1",
                DaemonBridge.RuleHealthStatus.PENDING
            ).copy(missCount = 1),
            validKey to health(
                "com.example.app",
                "Worker",
                DaemonBridge.RuleHealthStatus.VALID
            ),
            otherKey to health(
                "com.other.app",
                "RenderThread",
                DaemonBridge.RuleHealthStatus.MISSED
            )
        )

        val updated = DaemonBridge.markRuleHealthResetPending(source, "com.example.app:push")

        assertEquals(DaemonBridge.RuleHealthStatus.PENDING, updated[matchingKey]?.status)
        assertEquals(0, updated[matchingKey]?.missCount)
        assertEquals(0L, updated[matchingKey]?.firstObservedAt)
        assertEquals(0L, updated[matchingKey]?.lastMatchedAt)
        assertEquals(0L, updated[matchingKey]?.lastCheckedAt)
        assertEquals(DaemonBridge.RuleHealthStatus.PENDING, updated[pendingReviewKey]?.status)
        assertEquals(0, updated[pendingReviewKey]?.missCount)
        assertEquals(DaemonBridge.RuleHealthStatus.VALID, updated[validKey]?.status)
        assertEquals(DaemonBridge.RuleHealthStatus.MISSED, updated[otherKey]?.status)
    }

    @Test
    fun resetRequestUsesSiblingTempAndAtomicRenameWithoutWritingHealthTable() {
        val command = DaemonBridge.buildRuleHealthResetWriteCommand("Y29tLmV4YW1wbGUK", "unit")

        assertTrue(
            command.contains(
                "target='/data/adb/modules/QixiaThreads/config/state/rule_health.reset'"
            )
        )
        assertTrue(
            command.contains(
                "tmp='/data/adb/modules/QixiaThreads/config/state/rule_health.reset.app-unit.tmp'"
            )
        )
        assertTrue(command.contains("Y29tLmV4YW1wbGUK"))
        assertTrue(command.contains("mv -f \"\$tmp\" \"\$target\""))
        assertFalse(command.contains("rule_health.tsv"))
    }
}
