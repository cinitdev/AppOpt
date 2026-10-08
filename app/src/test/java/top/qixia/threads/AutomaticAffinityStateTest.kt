package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutomaticAffinityStateTest {
    @Test fun onlyPlainPackagesAreSelectedAndDeduplicated() {
        val packages = DaemonBridge.parseAutomaticAffinityPackages("""
            com.game
            com.game # 重复项
            com.light
            com.bad,fast
            com.bad,unknown,extra
            bad..name
            com.game:worker
        """.trimIndent())
        assertEquals(setOf("com.game", "com.light"), packages)
        val state = DaemonBridge.parseAutomaticAffinity("version=1\n__PACKAGES__\ncom.game\ncom.light\n__LIVE__\n1")
        assertEquals(setOf("com.game", "com.light"), state.packages)
        assertTrue(state.supported)
        assertTrue(DaemonBridge.parseAutomaticAffinityPackages("com.game,unknown").isEmpty())
    }
    @Test fun unsupportedModulesCannotAdvertiseTheFeature() {
        assertFalse(DaemonBridge.parseAutomaticAffinity("__PACKAGES__\ncom.game\n__LIVE__\n").supported)
        assertFalse(DaemonBridge.parseAutomaticAffinity("version=2\n__PACKAGES__\n__LIVE__\n").supported)
    }

    @Test fun packageSelectionAndRuntimeStateStaySeparate() {
        val state = DaemonBridge.parseAutomaticAffinity("""
            version=1
            package=com.game
            state=cooldown
            detail=收益不足，已恢复系统调度
            __PACKAGES__
            com.game
            com.game # duplicate
            com.other
            invalid;command
            __LIVE__
            1
        """.trimIndent())
        assertTrue(state.supported)
        assertEquals(setOf("com.game", "com.other"), state.packages)
        assertEquals("com.game", state.activePackage)
        assertEquals("cooldown", state.state)
        assertEquals("收益不足，已恢复系统调度", state.detail)
    }

    @Test fun staleRuntimeKeepsCapabilityAndSelectionButCannotAdvertiseOptimization() {
        val state = DaemonBridge.parseAutomaticAffinity("version=1\nstate=active\npackage=com.game\n__PACKAGES__\ncom.game\n__LIVE__\n")
        assertTrue(state.supported)
        assertEquals(setOf("com.game"), state.packages)
        assertEquals("stopped", state.state)
        assertEquals("", state.activePackage)
        assertEquals("自动分配守护尚未运行", state.detail)
    }
    @Test fun duplicateSchemaIsRejected() {
        assertFalse(DaemonBridge.parseAutomaticAffinity("version=1\nversion=1\n__PACKAGES__\n__LIVE__\n1").supported)
    }
}
