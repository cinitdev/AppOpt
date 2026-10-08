package top.qixia.threads

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AutomaticAffinityCapabilityTest {
    // 夹具位于本应用缓存目录，直接运行同一份写入脚本即可验证，不依赖 Root 授权。
    private fun runFixtureScript(script: String): Boolean {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() == 0
    }
    @Test fun normalWritesDeduplicateWithoutLosingOtherApps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = File.createTempFile("affinity-mode-", ".conf", context.cacheDir)
        val state = File.createTempFile("affinity-mode-", ".state", context.cacheDir)
        try {
            config.writeText("# 保留注释\ncom.game\ncom.other\ncom.game # 重复项\n")
            state.writeText("version=1\n")
            fun write(enabled: Boolean): Boolean =
                runFixtureScript(DaemonBridge.automaticAffinityMutationScript(
                    "com.game", enabled, "mode-test", config.path, state.path))
            org.junit.Assert.assertTrue(write(true))
            assertEquals("# 保留注释\ncom.other\ncom.game\n", config.readText())
            val before = config.readText()
            org.junit.Assert.assertTrue(write(true))
            assertEquals(before, config.readText())
            state.writeText("version=2\n")
            org.junit.Assert.assertFalse(write(true))
            assertEquals(before, config.readText())
            org.junit.Assert.assertTrue(write(false))
            assertEquals("# 保留注释\ncom.other\n", config.readText())
            org.junit.Assert.assertFalse(write(true))
            assertEquals("# 保留注释\ncom.other\n", config.readText())
            state.writeText("version=1\n")
            org.junit.Assert.assertTrue(write(true))
            assertEquals("# 保留注释\ncom.other\ncom.game\n", config.readText())
        } finally {
            config.delete()
            state.delete()
        }
    }

    @Test fun inlineCapabilityCheckMatchesSnapshotValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File.createTempFile("automatic-capability-", ".state", context.cacheDir)
        try {
            for (state in listOf(
                "version=1\npid=123\n", "version=1\r\npid=123\r\n", "\nversion=1\n\n",
                "", "version=2\n", "version=1\nversion=1\n", "version=1\npid=1\npid=2\n",
                "version=1\ninvalid\n", "version=1\n=value\n", " version=1\n", "version=1=2\n"
            )) {
                fixture.writeText(state)
                val expected = DaemonBridge.parseAutomaticAffinity("$state\n__PACKAGES__\n\n__LIVE__\n").supported
                val result = runFixtureScript(DaemonBridge.automaticAffinitySupportCheck(fixture.path))
                assertEquals(state, expected, result)
            }
        } finally { fixture.delete() }
    }
}
