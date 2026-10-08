package top.qixia.threads

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class SceneBlacklistFileTest {
    private lateinit var directory: File
    private lateinit var policy: File

    @Before fun prepare() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "scene-policy-${System.nanoTime()}").apply { mkdirs() }
        policy = File(directory, "cpuset.conf")
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun update(packages: Set<String>, prefix: String = ""): DaemonBridge.RootCommandResult =
        DaemonBridge.runRootCommand(prefix + SceneBlacklistSync.updateScript(packages, policy.path))

    private fun metadata(): String = DaemonBridge.runRootCommand(
        "stat -c '%u:%g:%a:%C' '${policy.path}'"
    ).output.trim()

    @Test fun configuredPackageReadPreservesSuccessfulMultilineOutput() {
        val rules = "com.game=0-3\ncom.game{RenderThread}=7\ncom.chat:push=0-3\n"
        policy.writeText(rules)
        val read = DaemonBridge.runRootCommandStreaming("cat '${policy.path}'") { }
        assertTrue(read.toString(), read.success)
        assertEquals(rules, read.output)
        assertEquals(setOf("com.game", "com.chat"), SceneBlacklistSync.collectPackages(read.output, ""))
    }

    @Test fun installedConfigurationCanBeReadForForegroundSync() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("checkInstalledConfiguration") == "true")
        val plain = DaemonBridge.runRootCommand("cat /data/adb/modules/QixiaThreads/config/applist.conf")
        val rules = DaemonBridge.readConfigRawOrNull()
        assertTrue(plain.toString(), plain.success)
        assertNotNull("Plain read=${plain.output}", rules)
        assertEquals(plain.output.trimEnd(), rules!!.trimEnd())
        assertTrue(SceneBlacklistSync.collectPackages(rules, "").isNotEmpty())
        val result = DaemonBridge.runRootCommand("id; test -f '${SceneBlacklistSync.SCENE_CONFIG}' || " +
            "test -f '/proc/1/root${SceneBlacklistSync.SCENE_CONFIG}'")
        assertTrue(result.toString(), result.success)
    }

    @Test fun firstUseAddsBlacklistAndPreservesOptionsAndFileAccess() {
        val original = "gold_first=0\nuse_presets=1\nin_apps=1\nin_games=1\n"
        policy.writeText(original)
        assertTrue(DaemonBridge.runRootCommand("chmod 0600 '${policy.path}'").success)
        val before = metadata()
        val result = update(setOf("com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd"))
        assertTrue(result.output, result.success)
        assertEquals("updated", result.output.trim())
        assertEquals(original + "blacklist=com.tencent.tmgp.pubgmhd,com.tencent.tmgp.sgame\n", policy.readText())
        assertEquals(before, metadata())
        assertEquals(listOf("cpuset.conf"), directory.list()!!.toList())
    }

    @Test fun keepsUserEntriesMergesDuplicateKeysAndDoesNotRewriteAgain() {
        policy.writeText("# blacklist=com.comment\r\nblacklist = com.user,com.same\r\nin_games=1\r\nblacklist=com.second, com.same\r\n")
        val apps = setOf("com.same", "com.new")
        assertEquals("updated", update(apps).output.trim())
        assertEquals("# blacklist=com.comment\r\nblacklist=com.user,com.same,com.second,com.new\r\nin_games=1\r\n", policy.readText())
        val inode = DaemonBridge.runRootCommand("stat -c %i '${policy.path}'").output
        assertEquals("unchanged", update(apps).output.trim())
        assertEquals(inode, DaemonBridge.runRootCommand("stat -c %i '${policy.path}'").output)
    }

    @Test fun missingFileIsNotCreatedAndEmptyConfigurationCanBeInitialized() {
        assertEquals("missing", update(setOf("com.game")).output.trim())
        assertFalse(policy.exists())
        policy.writeText("")
        assertEquals("updated", update(setOf("com.game")).output.trim())
        assertEquals("blacklist=com.game\n", policy.readText())
    }

    @Test fun emptyApplicationListDoesNotClearManualBlacklist() {
        val original = "blacklist=com.user\nin_games=1\n"
        policy.writeText(original)
        assertEquals("unchanged", update(emptySet()).output.trim())
        assertEquals(original, policy.readText())
    }

    @Test fun onlyInstalledConfiguredAppsAreMergedAndStaleConfiguredEntriesAreRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = context.packageName
        val absent = "top.qixia.scene.blacklist.not_installed"
        val configured = SceneBlacklistSync.collectPackages(
            "$installed=0-3\n$installed{RenderThread}=7\n$absent:worker=0-3\n",
            "$installed\n$absent\n"
        )
        val eligible = SceneBlacklistSync.installedPackages(configured, context.packageManager)
        assertEquals(setOf(installed), eligible)
        policy.writeText("in_games=1\nblacklist=com.user.manual,$absent,$installed,$installed\n")
        val script = SceneBlacklistSync.updateScript(eligible, policy.path, configured - eligible)
        assertEquals("updated", DaemonBridge.runRootCommand(script).output.trim())
        assertEquals("in_games=1\nblacklist=com.user.manual,$installed\n", policy.readText())
        assertEquals("unchanged", DaemonBridge.runRootCommand(script).output.trim())
    }

    @Test fun allConfiguredAppsUninstalledStillRemovesTheirPreviousEntries() {
        policy.writeText("blacklist=com.uninstalled\nin_games=1\n")
        val script = SceneBlacklistSync.updateScript(emptySet(), policy.path, setOf("com.uninstalled"))
        assertEquals("updated", DaemonBridge.runRootCommand(script).output.trim())
        assertEquals("blacklist=\nin_games=1\n", policy.readText())
        policy.writeText("in_games=1\n")
        assertEquals("unchanged", DaemonBridge.runRootCommand(script).output.trim())
        assertEquals("in_games=1\n", policy.readText())
    }

    @Test fun permissionLabelFailureLeavesOriginalUntouched() {
        val original = "blacklist=com.user\nin_games=1\n"
        policy.writeText(original)
        assertFalse(update(setOf("com.game"), "chcon() { return 1; };\n").success)
        assertEquals(original, policy.readText())
        assertEquals(listOf("cpuset.conf"), directory.list()!!.toList())
    }

    @Test fun concurrentSceneEditIsKeptAndRequestsRetry() {
        policy.writeText("in_games=1\n")
        val marker = File(directory, "read-once")
        val prefix = """
            sha256sum() {
                if [ -f '${marker.path}' ]; then printf 'in_apps=0\n' >> '${policy.path}'; fi
                touch '${marker.path}'
                /system/bin/sha256sum "${'$'}@"
            }
        """.trimIndent() + "\n"
        assertEquals("retry", update(setOf("com.game"), prefix).output.trim())
        assertEquals("in_games=1\nin_apps=0\n", policy.readText())
        assertFalse(directory.list()!!.any { it.endsWith(".tmp") })
    }
}
