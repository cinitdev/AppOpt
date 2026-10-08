package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class ConfigFileMutationScriptTest {
    @Test fun guardedWriteDoesNotDependOnIndentedHeredocTerminators() {
        val payload = "Y29tLnRlc3R7QmluZGVyOip9PTcNCg=="
        val source = "Y29tLnRlc3Q9YXV0bw=="
        val script = DaemonBridge.configFileMutationScript(payload, "test-token", source)
        val lines = script.lineSequence().map(String::trim).toList()

        // 多行保护条件包含无缩进的续行，此时外层 trimIndent()
        // 无法保证 heredoc 结束标记位于安全位置。
        assertFalse(script.contains("<<"))
        assertFalse(script.contains("EOF_BASE64"))
        assertEquals(1, lines.count {
            it == "if ! printf '%s' '$payload' | base64 -d > \"\$tmp\"; then"
        })
        assertEquals(1, lines.count {
            it == "printf '%s' '$source' | base64 -d | cmp -s - \"\$target\" || exit 1"
        })
        val compare = script.indexOf("cmp -s")
        val commit = script.indexOf("mv -f")
        assertTrue(compare >= 0 && compare < commit)
        assertEquals(2, lines.count { it.contains("modules_update/QixiaThreads/config/calib_policy.conf") })
        assertEquals(2, lines.count { it.contains("cat \"\$lock/owner\"") })
    }

    @Test fun rollbackRetainsSourceComparisonWhileSkippingOnlyPendingUpdateGuard() {
        val script = DaemonBridge.configFileMutationScript("b2xk", "test-token", "bmV3", true)
        assertTrue(script.contains("printf '%s' 'bmV3' | base64 -d | cmp -s - \"\$target\" || exit 1"))
        assertFalse(script.contains("modules_update"))
        assertFalse(script.contains("EOF_BASE64"))
        assertTrue(script.indexOf("cmp -s") < script.indexOf("mv -f"))
    }

    @Test fun emptyExpectedFileStillUsesComparisonAndMissingFileCheck() {
        val script = DaemonBridge.configFileMutationScript("", "test-token", "")
        assertTrue(script.contains("if ! printf '%s' '' | base64 -d > \"\$tmp\"; then"))
        assertTrue(script.contains("printf '%s' '' | base64 -d | cmp -s - \"\$target\" || exit 1"))
        assertTrue(script.contains("[ -L \"\$target\" ] || [ -n '' ]"))
        assertTrue(script.contains("modules_update"))
    }

    @Test fun existingUnguardedCallersUseTheSameSingleLinePayloadWrite() {
        val script = DaemonBridge.configFileMutationScript("YQ==", "test-token")
        assertTrue(script.contains("if ! printf '%s' 'YQ==' | base64 -d > \"\$tmp\"; then"))
        assertFalse(script.contains("cmp -s"))
        assertFalse(script.contains("modules_update"))
        assertFalse(script.contains("<<"))
    }
}
