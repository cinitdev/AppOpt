package top.qixia.threads

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AutomaticHistoryPolicyTest {
    private lateinit var root: File
    private lateinit var active: File
    private lateinit var pending: File
    private lateinit var policy: File

    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "automatic-policy-${System.nanoTime()}").apply { mkdirs() }
        active = File(root, "active").apply { mkdirs() }
        pending = File(root, "pending").apply { mkdirs() }
        policy = File(active, "calib_policy.conf")
    }

    @After fun cleanup() { root.deleteRecursively() }

    private fun mutate(enabled: Boolean): Boolean {
        return runMutation(DaemonBridge.automaticHistoryMutationScript(enabled))
    }

    private fun saveSettings(snapshot: String): Boolean =
        runMutation(DaemonBridge.editablePolicyMutationScript(snapshot))

    private fun runMutation(command: String): Boolean {
        val script = command
            .replace("/data/adb/modules/QixiaThreads/config", active.path)
            .replace("/data/adb/modules_update/QixiaThreads/config", pending.path)
        return DaemonBridge.runRootCommand(script).success
    }

    private fun assertNoTemporaryFiles() {
        assertEquals(listOf("calib_policy.conf"), active.list()?.sorted())
    }

    @Test fun togglePreservesAllOtherPolicyLinesAndTopology() {
        val other = "version=2\ncpuset_name=KeepMe\nrule_output_format=legacy\n" +
            "auto_history_version=1\n# topology\ndetected_group_0=0-3\ndetected_group_1=4-7\n"
        policy.writeText(other + "auto_history_enabled=0\n")
        assertTrue(mutate(true))
        assertEquals(other + "auto_history_enabled=1\n", policy.readText())
        assertTrue(mutate(false))
        assertEquals(other + "auto_history_enabled=0\n", policy.readText())
        assertNoTemporaryFiles()
    }

    @Test fun toggleNormalizesOnlyDuplicateHistorySwitches() {
        policy.writeText("version=2\nauto_history_version=1\nauto_history_enabled=0\n" +
            "cpuset_name=Custom\nauto_history_enabled=1\n")
        assertTrue(mutate(true))
        assertEquals("version=2\nauto_history_version=1\nauto_history_enabled=1\ncpuset_name=Custom\n",
            policy.readText())
        assertNoTemporaryFiles()
    }

    @Test fun unknownOrDuplicateVersionDoesNotReplacePolicy() {
        for (version in listOf("", "auto_history_version=2\n",
            "auto_history_version=1\nauto_history_version=1\n")) {
            val original = "version=2\n${version}auto_history_enabled=0\ncpuset_name=KeepMe\n"
            policy.writeText(original)
            assertFalse(mutate(true))
            assertEquals(original, policy.readText())
            assertNoTemporaryFiles()
        }
    }

    @Test fun pendingModuleUpdateCannotChangeActivePolicy() {
        val original = "auto_history_version=1\nauto_history_enabled=0\n"
        policy.writeText(original)
        File(pending, "calib_policy.conf").writeText(original)
        assertFalse(mutate(true))
        assertEquals(original, policy.readText())
        assertEquals(original, File(pending, "calib_policy.conf").readText())
        assertNoTemporaryFiles()
    }

    @Test fun settingsSavePreservesRuntimeSwitchesUnknownKeysAndLatestTopology() {
        val original = "version=2\nrule_output_format=legacy\ncpuset_name=KeepMe\n" +
            "keep_all_cores_online=1 # user choice\nauto_history_version=1\nauto_history_enabled=0\n" +
            "# custom value\nfuture_key=preserve\n# QixiaThreads detected CPU topology begin\n" +
            "detected_group_0=0-3\n# QixiaThreads detected CPU topology end\n"
        policy.writeText(original)
        val snapshot = CalibPolicy.parse(original).copy(ruleOutputFormat = CalibPolicy.RuleOutputFormat.YAML,
            cpusetName = "GameThreads").toConfigText()
        // 设置页面加载后，另一写入方更新这些字段。
        assertTrue(mutate(true))
        assertTrue(DaemonBridge.runRootCommand(
            "sed 's/detected_group_0=0-3/detected_group_0=0-1/' '${policy.path}' > '${policy.path}.topology.tmp' && " +
                "mv '${policy.path}.topology.tmp' '${policy.path}'"
        ).success)
        val current = policy.readText()
        assertTrue(saveSettings(snapshot))
        assertEquals(current.replace("rule_output_format=legacy", "rule_output_format=yaml")
            .replace("cpuset_name=KeepMe", "cpuset_name=GameThreads"), policy.readText())
        assertEquals(DaemonBridge.AutomaticHistoryState(true, true), DaemonBridge.parseAutomaticHistory(policy.readText()))
        assertTrue(mutate(false))
        assertTrue(saveSettings(snapshot))
        assertEquals(DaemonBridge.AutomaticHistoryState(true, false), DaemonBridge.parseAutomaticHistory(policy.readText()))
        assertNoTemporaryFiles()
    }

    @Test fun restoringEditableDefaultsDoesNotResetRecordingOrRuntimeOptions() {
        val original = "version=2\ncpuset_name=Custom\nrule_output_format=yaml\n" +
            "keep_all_cores_online=1\nauto_history_version=1\nauto_history_enabled=1\n"
        policy.writeText(original)
        assertTrue(saveSettings(CalibPolicy.DEFAULT.toConfigText()))
        assertEquals(original.replace("cpuset_name=Custom", "cpuset_name=QiXiaRs")
            .replace("rule_output_format=yaml", "rule_output_format=legacy"), policy.readText())
        assertNoTemporaryFiles()
    }

    @Test fun settingsSaveNormalizesOnlyEditedKeysAndRemovesCompletedMigration() {
        policy.writeText("version=1\nrule_output_format=legacy # format\ncpuset_name=First\n" +
            "cpuset_name=Second\nrule_output_format_migration=extended_block\n" +
            "auto_history_version=2\nfuture_key=keep\n")
        assertTrue(saveSettings(CalibPolicy.DEFAULT.toConfigText()))
        assertEquals("version=2\nrule_output_format=legacy # format\ncpuset_name=QiXiaRs\n" +
            "auto_history_version=2\nfuture_key=keep\n", policy.readText())
        assertNoTemporaryFiles()
    }

    @Test fun pendingUpdateRejectsSettingsSaveInsideThePolicyLock() {
        val original = "version=2\ncpuset_name=KeepMe\nauto_history_version=1\nauto_history_enabled=1\n"
        policy.writeText(original)
        File(pending, "calib_policy.conf").writeText(original)
        assertFalse(saveSettings(CalibPolicy.DEFAULT.toConfigText()))
        assertEquals(original, policy.readText())
        assertEquals(original, File(pending, "calib_policy.conf").readText())
        assertNoTemporaryFiles()
    }
}
