package top.qixia.threads

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** 运行 App 实际生成的原子写入脚本，覆盖开关组合与失败保留；只写临时目录。 */
class AutomaticCustomStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runScript(script: String): Boolean {
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "C:/Program Files/Git/bin/bash.exe" else "/bin/bash"
        assumeTrue(File(shell).isFile)
        val file = folder.newFile()
        file.writeText(script)
        val output = folder.newFile()
        val process = ProcessBuilder(shell, "--noprofile", "--norc", file.path.replace('\\', '/'))
            .redirectErrorStream(true).redirectOutput(output).start()
        assertTrue("Shell 未按时结束", process.waitFor(15, TimeUnit.SECONDS))
        return process.exitValue() == 0
    }

    @Test fun switchesPreserveCustomRecordingFallbackAndUnrelatedRows() {
        val config = folder.newFile("modes.conf")
        val state = folder.newFile("modes.state")
        val other = "# 保留其他应用\ncom.other,fallback,record\n"
        config.writeText(other + "com.game\n")
        state.writeText("version=1\nfallback_version=1\nrecording_version=1\ncustom_version=1\n")
        fun change(auto: Boolean? = null, fallback: Boolean? = null, record: Boolean? = null, custom: String? = null) =
            runScript(DaemonBridge.applicationModesMutationScript("com.game", "custom-test", auto, fallback, record,
                config.path.replace('\\', '/'), state.path.replace('\\', '/'), custom))
        fun row(expected: String) = assertEquals(other + expected, config.readText())
        assertTrue(change(custom = "2:0-3+6|30:4-7")); row("com.game,custom=2:0-3+6|30:4-7\n")
        assertTrue(change(record = true)); row("com.game,custom=2:0-3+6|30:4-7,record\n")
        assertTrue(change(fallback = true)); row("com.game,fallback,custom=2:0-3+6|30:4-7,record\n")
        assertTrue(change(record = false)); row("com.game,fallback,custom=2:0-3+6|30:4-7\n")
        assertTrue(change(record = true))
        assertTrue(change(custom = "")); row("com.game,fallback,record\n")
        assertTrue(change(custom = "40:7|1:0"))
        assertTrue(change(fallback = false)); row("com.game,custom=40:7|1:0,record\n")
        assertTrue(change(auto = false)); row("com.game,record\n")
        assertFalse(change(custom = "2:0")); row("com.game,record\n")
        assertTrue(change(auto = true)); row("com.game,auto,record\n")
        state.writeText("version=1\nrecording_version=1\n")
        assertFalse(change(custom = "2:0")); row("com.game,auto,record\n")
    }

    @Test fun duplicateAndMalformedCustomRowsMatchTheAppParser() {
        val config = folder.newFile("duplicate.conf")
        val state = folder.newFile("duplicate.state")
        state.writeText("version=1\nrecording_version=1\ncustom_version=1\n")
        for (raw in listOf(
            "com.game,custom=2:0\ncom.game,record\ncom.game,custom=10:1\ncom.game,custom=broken",
            "com.game,custom=2:0,custom=5:1,record",
            "com.game,custom=0:0,record", "com.game,custom=5:64,record",
            "com.game,custom=5:3-1,record", "com.game,custom=5:0|5:1,record",
            "com.game,custom=1:0+63|100:63,record", "com.game,custom=5:01,record"
        )) {
            config.writeText(raw)
            val before = AutomaticAffinityConfig.entries(raw)["com.game"]
            assertTrue(runScript(DaemonBridge.applicationModesMutationScript("com.game", "custom-test", recording = true,
                configPath = config.path.replace('\\', '/'), statePath = state.path.replace('\\', '/'))))
            val after = AutomaticAffinityConfig.entries(config.readText()).getValue("com.game")
            assertEquals(raw, before?.custom.orEmpty(), after.custom)
            assertEquals(raw, before?.automatic == true, after.automatic)
            assertTrue(after.recording)
        }
    }
}
