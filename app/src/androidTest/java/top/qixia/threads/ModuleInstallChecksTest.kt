package top.qixia.threads

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ModuleInstallChecksTest {
    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private lateinit var preferencesName: String

    @Before fun prepare() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "module-checks-${System.nanoTime()}").apply { mkdirs() }
        preferencesName = root.name
        isolatedContext = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(preferencesName, mode)
        }
    }

    @After fun cleanup() {
        root.deleteRecursively()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences(preferencesName)
    }

    private fun file(path: String, text: String = ""): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun detect(version: String): String {
        val script = "su() { printf '%s' '$version'; };\n" +
            ModuleUpdater.rootManagerDetectionScript().replace("/data/adb", root.path)
        val result = DaemonBridge.runRootCommand(script)
        assertTrue(result.output, result.success)
        return result.output.trim()
    }

    @Test fun activeProviderWinsOverOtherManagersLeftoverFiles() {
        listOf("apd", "magisk/magisk", "ksud").forEach { file(it).setExecutable(true) }
        assertEquals("apatch", detect("11142:APatch"))
        assertEquals("magisk", detect("28.0:MAGISK"))
        assertEquals("kernelsu", detect("KernelSU"))
    }

    @Test fun kernelSuAlternatePathAndUnknownProviderFallback() {
        file("ksu/bin/ksud").setExecutable(true)
        assertEquals("kernelsu-bin", detect("KernelSU"))
        assertEquals("kernelsu-bin", detect(""))
    }

    private fun completeModule(path: String) {
        file("$path/module.prop", "id=QixiaThreads\nversionCode=186\n")
        listOf("service.sh", "config/bin/QiXiaRs", "config/ebpf/queuebuffer_probe.bpf.o",
            "config/ebpf/queuebuffer_probe_stats.bpf.o", "config/ebpf/queuebuffer_probe_perf.bpf.o")
            .forEach { file("$path/$it") }
    }

    private fun pending(): Boolean {
        val script = ModuleUpdater.pendingModuleCheckScript(186)
            .replace("/data/adb/modules_update/QixiaThreads", File(root, "pending").path)
            .replace("/data/adb/modules/QixiaThreads", File(root, "active").path)
        val result = DaemonBridge.runRootCommand(script)
        assertTrue(result.output, result.success)
        return result.output.trim() == "1"
    }

    @Test fun installedModuleDoesNotRequireInstallerScript() {
        completeModule("pending")
        assertFalse(File(root, "pending/customize.sh").exists())
        assertTrue(pending())
    }

    @Test fun rejectsIncompleteOrWrongVersionStaging() {
        completeModule("pending")
        file("pending/module.prop", "id=QixiaThreads\nversionCode=185\n")
        assertFalse(pending())
        file("pending/module.prop", "id=QixiaThreads\nversionCode=186\n")
        File(root, "pending/config/ebpf/queuebuffer_probe_perf.bpf.o").delete()
        assertFalse(pending())
    }

    @Test fun activeModuleNeedsUpdateMarker() {
        completeModule("active")
        assertFalse(pending())
        file("active/update")
        assertTrue(pending())
    }

    @Test fun completedJournalSurvivesDownloadCleanupAndNewContext() {
        val zip = file("download.zip", "temporary payload")
        ModuleUpdater.rememberCompletedInstall(isolatedContext, zip, 186, "boot-one", "- APatch\n- Done\n")
        assertTrue(zip.delete())
        val restored = ModuleUpdater.readCompletedInstall(ContextWrapper(isolatedContext), zip, 186)
        assertNotNull(restored)
        assertEquals("boot-one", restored!!.bootId)
        assertEquals("- APatch\n- Done\n", restored.log)
        assertNull(ModuleUpdater.readCompletedInstall(isolatedContext, zip, 187))
        assertNull(ModuleUpdater.readCompletedInstall(isolatedContext, File(root, "another.zip"), 186))
    }

    @Test fun completedLogIsBoundedAndFailedJournalIsNotRestored() {
        val zip = file("download.zip")
        ModuleUpdater.rememberCompletedInstall(isolatedContext, zip, 186, "boot-one", "line\n".repeat(30000))
        val restored = ModuleUpdater.readCompletedInstall(isolatedContext, zip, 186)!!
        assertTrue(restored.log.length <= BoundedInstallLog.DEFAULT_MAX_CHARS)
        assertTrue(restored.log.endsWith("line\n"))
        isolatedContext.getSharedPreferences("unused", Context.MODE_PRIVATE).edit()
            .putString("install.state", "failed").commit()
        assertNull(ModuleUpdater.readCompletedInstall(isolatedContext, zip, 186))
    }

    @Test fun sameVersionAndPathDoNotRestoreDifferentZip() {
        val zip = file("download.zip", "build-one")
        val timestamp = zip.lastModified()
        ModuleUpdater.rememberCompletedInstall(isolatedContext, zip, 186, "boot-one", "done")
        zip.writeText("build-two")
        zip.setLastModified(timestamp)
        assertNull(ModuleUpdater.readCompletedInstall(isolatedContext, zip, 186))
    }

    @Test fun cancelledWorkersCannotClearOrPublishOverRetry() {
        val update = ModuleUpdater.UpdateInfo("2.0.0", 200, "2.0.1", 201,
            "https://example.invalid/module.zip", null, "", false)
        val old = ModuleUpdater.beginDownloadAttempt(isolatedContext, update)
        ModuleUpdater.persistDownloadPhase(isolatedContext, update,
            ModuleUpdater.DOWNLOAD_PHASE_DOWNLOADING, null, 10, old)
        ModuleUpdater.cancelPersistedDownload(isolatedContext, update, old)
        val current = ModuleUpdater.beginDownloadAttempt(isolatedContext, update)
        ModuleUpdater.persistDownloadPhase(isolatedContext, update,
            ModuleUpdater.DOWNLOAD_PHASE_READY, file("retry.zip", "new").absolutePath, 100, current)

        // 用户已用相同更新标识重试后，旧任务才开始退出清理。
        val staleWorker = Thread {
            ModuleUpdater.cancelPersistedDownload(isolatedContext, update, old)
            ModuleUpdater.clearPersistedDownloadTask(isolatedContext, update, old)
            ModuleUpdater.clearPersistedDownload(isolatedContext, update, old)
            ModuleUpdater.persistDownloadPhase(isolatedContext, update,
                ModuleUpdater.DOWNLOAD_PHASE_FAILED, null, null, old)
        }
        staleWorker.start()
        staleWorker.join()
        val restored = ModuleUpdater.readPersistedDownloadSession(isolatedContext)!!
        assertEquals(ModuleUpdater.DOWNLOAD_PHASE_READY, restored.phase)
        assertEquals(100, restored.percent)
        assertEquals("new", File(restored.targetPath!!).readText())
    }

    private fun applied(): Boolean {
        val script = ModuleUpdater.appliedModuleCheckScript(186)
            .replace("/data/adb/modules_update/QixiaThreads", File(root, "pending").path)
            .replace("/data/adb/modules/QixiaThreads", File(root, "active").path)
        val result = DaemonBridge.runRootCommand(script)
        assertTrue(result.output, result.success)
        return result.output.trim() == "1"
    }

    @Test fun appliedResultRequiresCompleteEnabledModuleAndNoPendingUpdate() {
        completeModule("active")
        File(root, "active/config/bin/QiXiaRs").setExecutable(true)
        assertTrue(applied())
        val marker = file("active/update")
        assertFalse(applied())
        marker.delete()
        val disabled = file("active/disable")
        assertFalse(applied())
        disabled.delete()
        val pending = File(root, "pending").apply { mkdirs() }
        assertFalse(applied())
        pending.delete()
        File(root, "active/config/bin/QiXiaRs").delete()
        assertFalse(applied())
    }
}
