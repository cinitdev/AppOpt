package top.qixia.threads

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ModuleHotUpdateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var library: String

    @Before fun prepare() {
        root = File(instrumentation.targetContext.cacheDir, "hot-update-${System.nanoTime()}").apply { mkdirs() }
        library = instrumentation.context.assets.open("config/tools/qixia_hot_update.sh")
            .bufferedReader().use { it.readText() }.substringBeforeLast("\ncase \"${'$'}1\" in\n")
            .replace("/data/adb/modules/QixiaThreads", "${root.path}/active")
            .replace("/data/adb/modules_update/QixiaThreads", "${root.path}/staged")
            .replace("/data/adb/qixia_hot_update", "${root.path}/transaction")
        module("active", "old")
        module("staged", "new")
        file("active/config/applist.conf", "com.example.user=0-3\n")
        file("active/config/auto_affinity.conf", "com.example.game\n")
        file("active/config/calib_policy.conf", "cpuset_name=MyCpus\nauto_history_enabled=1\n")
        file("staged/config/calib_policy.conf", "cpuset_name=OldName\nauto_history_enabled=0\nnew_option=1\n")
        file("active/history/keep.log", "user history")
        file("active/update", "")
    }

    @After fun cleanup() {
        require(root.parentFile!!.canonicalFile == instrumentation.targetContext.cacheDir.canonicalFile)
        DaemonBridge.runRootCommand("rm -rf '${root.path}'")
    }

    private fun file(path: String, text: String) = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun module(path: String, build: String) {
        file("$path/module.prop", "id=QixiaThreads\nversionCode=205\n")
        listOf("service.sh", "config/bin/QiXiaRs", "config/ebpf/queuebuffer_probe.bpf.o",
            "config/ebpf/queuebuffer_probe_perf.bpf.o", "config/ebpf/queuebuffer_probe_stats.bpf.o",
            "config/tools/qixia_foreground_helper.sh", "config/tools/qixia_foreground_helper.jar",
            "config/tools/qixia_pkg_helper.sh", "config/tools/qixia_pkg_helper.jar",
            "config/tools/qixia_hot_update.sh")
            .forEach { file("$path/$it", "$build:$it\n").setExecutable(true) }
        file("$path/system.prop", "example.value=1\n")
    }

    private fun run(body: String, healthy: Boolean = true): String {
        val script = library + """

            stop_service() { return 0; }
            start_service() { printf started >> '${root.path}/starts'; }
            healthy() { ${if (healthy) "return 0" else "return 1"}; }
            old_healthy() { return 0; }
            REQUEST=test
            $body
            result=${'$'}?
            chmod -R 0777 '${root.path}'
            exit ${'$'}result
        """.trimIndent()
        val result = DaemonBridge.runRootCommand(script, timeoutSeconds = 20L)
        assertTrue(result.output, result.success)
        return result.output
    }

    private fun phase() = File(root, "active/config/state/module_update.state")
        .readLines().first { it.startsWith("phase=") }

    private fun captureToasts(failDelivery: Boolean = false): String {
        // 用实际脚本入口记录调用，验证助手尚未删除、状态已提交、参数为 Toast 文案。
        val helper = """
            #!/system/bin/sh
            state='${root.path}/active/config/state/module_update.state'
            printf '%s|%s\n' "${'$'}(sed -n 's/^phase=//p' "${'$'}state")" "${'$'}*" >> '${root.path}/toasts'
            test -s "${'$'}{0%/*}/qixia_pkg_helper.jar" || exit 2
            exit ${if (failDelivery) 1 else 0}
        """.trimIndent()
        file("staged/config/tools/qixia_pkg_helper.sh", helper)
        file("active/config/tools/qixia_pkg_helper.sh", helper)
        return "SHOW_RESULT=1"
    }

    @Test fun packageHelperLaunchesFromStableDirectoryAfterInstallerCleanup() {
        val launcher = instrumentation.context.assets.open("config/tools/qixia_pkg_helper.sh")
            .bufferedReader().use { it.readText() }
            .replace("/system/bin/app_process", "${root.path}/fake-app-process")
        file("launcher/qixia_pkg_helper.sh", launcher)
        file("launcher/qixia_pkg_helper.jar", "test dex")
        file("fake-app-process", "#!/system/bin/sh\npwd > '${root.path}/helper-cwd'\n").setExecutable(true)
        run("""
            mkdir '${root.path}/installer-temp'
            cd '${root.path}/installer-temp'
            rmdir '${root.path}/installer-temp'
            sh '${root.path}/launcher/qixia_pkg_helper.sh' toast test
        """.trimIndent())
        assertEquals("/\n", File(root, "helper-cwd").readText())
    }

    @Test fun resolvesMixedControllerMountsWithoutHardcodedPaths() {
        file("init.cgroup", "3:cpu,cpuacct:/\n2:memory:/apps\n1:cpuset:/\n0::/\n")
        file("caller.cgroup", "3:cpu,cpuacct:/background\n2:memory:/\n1:cpuset:/background\n0::/uid_10001/pid_123\n")
        file("mountinfo", """
            21 1 0:21 / /dev/cpu-test rw - cgroup none rw,cpu,cpuacct
            22 1 0:22 / /dev/memory-test rw - cgroup none rw,memory
            23 1 0:23 / /dev/cpuset-test rw - cgroup none rw,cpuset
            24 1 0:24 / /sys/fs/cgroup-test rw - cgroup2 none rw
        """.trimIndent())
        run("runtime_cgroup_targets '${root.path}/init.cgroup' '${root.path}/caller.cgroup' '${root.path}/mountinfo' > '${root.path}/targets'")
        assertEquals(setOf("/dev/cpu-test/cgroup.procs", "/dev/memory-test/apps/cgroup.procs",
            "/dev/cpuset-test/cgroup.procs", "/sys/fs/cgroup-test/cgroup.procs"),
            File(root, "targets").readLines().toSet())
    }

    @Test fun handlesSubtreeMountAndAlreadyDetachedController() {
        file("init.cgroup", "1:freezer:/services\n0::/\n")
        file("caller.cgroup", "1:freezer:/services/manager\n0::/\n")
        file("mountinfo", "21 1 0:21 /services /dev/freezer-test rw - cgroup none rw,freezer\n")
        run("runtime_cgroup_targets '${root.path}/init.cgroup' '${root.path}/caller.cgroup' '${root.path}/mountinfo' > '${root.path}/targets'")
        assertEquals("/dev/freezer-test/cgroup.procs\n", File(root, "targets").readText())
    }

    @Test fun inaccessibleInitGroupCannotPretendToBeDetached() {
        file("init.cgroup", "0::/\n")
        file("caller.cgroup", "0::/uid_10001/pid_123\n")
        file("mountinfo", "21 1 0:21 /uid_10001 /sys/fs/cgroup-test rw - cgroup2 none rw\n")
        run("! runtime_cgroup_targets '${root.path}/init.cgroup' '${root.path}/caller.cgroup' '${root.path}/mountinfo'")
    }

    @Test fun isolationFailureLeavesCurrentServiceAndStagingUntouched() {
        run("detach_runtime() { return 1; }; ! apply_update ''")
        assertFalse(File(root, "starts").exists())
        assertFalse(File(root, "transaction/status").exists())
        assertEquals("old:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
        assertTrue(File(root, "staged/config/bin/QiXiaRs").exists())
    }

    @Test fun successToastIsShownBeforeHelperCleanupAfterCommittedState() {
        run(captureToasts() + "\napply_update ''")
        val notices = File(root, "toasts").readLines()
        assertEquals(1, notices.size)
        assertTrue(notices.single().startsWith("applied|toast "))
        assertTrue(notices.single().contains("无需重启"))
        assertFalse(File(root, "staged").exists())
        assertFalse(File(root, "active/config/tools/qixia_pkg_helper.jar").exists())
        assertFalse(File(root, "active/config/tools/qixia_pkg_helper.sh").exists())
    }

    @Test fun failedHealthOnlyShowsRebootToastAfterRollback() {
        run(captureToasts() + "\napply_update ''", healthy = false)
        val notices = File(root, "toasts").readLines()
        assertEquals(1, notices.size)
        assertTrue(notices.single().startsWith("deferred|"))
        assertTrue(notices.single().contains("需要重启"))
        assertFalse(notices.single().contains("无需重启"))
        assertEquals("old:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
        assertTrue(File(root, "active/config/tools/qixia_pkg_helper.jar").exists())
    }

    @Test fun blockedToastDoesNotRollbackSuccessfulUpdate() {
        run(captureToasts(failDelivery = true) + "\napply_update ''")
        assertEquals("phase=applied", phase())
        assertFalse(File(root, "staged").exists())
        assertFalse(File(root, "transaction/backup").exists())
    }

    @Test fun firstInstallRequiresRebootWithoutStartingService() {
        File(root, "active/service.sh").delete()
        run(captureToasts() + "\napply_update ''")
        assertEquals("phase=deferred", phase())
        assertFalse(File(root, "starts").exists())
        assertTrue(File(root, "toasts").readText().contains("需要重启"))
    }

    @Test fun appliesRuntimePreservesLatestConfigurationAndHistory() {
        run("apply_update ''")
        assertEquals("phase=applied", phase())
        assertEquals("new:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
        assertEquals("com.example.user=0-3\n", File(root, "active/config/applist.conf").readText())
        assertEquals("com.example.game\n", File(root, "active/config/auto_affinity.conf").readText())
        assertEquals("cpuset_name=MyCpus\nauto_history_enabled=1\nnew_option=1\n",
            File(root, "active/config/calib_policy.conf").readText())
        assertEquals("user history", File(root, "active/history/keep.log").readText())
        assertFalse(File(root, "staged").exists())
        assertFalse(File(root, "active/update").exists())
        assertFalse(File(root, "transaction").exists())
        assertFalse(File(root, "active/config/tools/qixia_hot_update.sh").exists())
    }

    @Test fun pendingApkRetainsWorkerUntilInstallationCompletes() {
        file("staged/config/app/app.prop", "versionCode=205\n")
        file("staged/config/app/test.apk", "pending apk")
        file("transaction/worker.sh", "recovery worker")
        run("apply_update ''")
        assertEquals("phase=applied", phase())
        assertTrue(File(root, "transaction/worker.sh").exists())
        assertTrue(File(root, "active/config/tools/qixia_hot_update.sh").exists())
        assertTrue(File(root, "active/config/tools/qixia_pkg_helper.jar").exists())
        run("take_lock && rm -rf \"${'$'}ACTIVE/config/app\" && cleanup_completed_update")
        assertFalse(File(root, "transaction").exists())
        assertFalse(File(root, "active/config/tools/qixia_hot_update.sh").exists())
        assertFalse(File(root, "active/config/tools/qixia_pkg_helper.jar").exists())
        assertEquals("phase=applied", phase())
    }

    @Test fun cleanupPreservesIncompleteApkAndNewStaging() {
        // 缺少元数据也属于未完成的 APK 交接，不能把重试入口删掉。
        file("staged/config/app/test.apk", "pending apk")
        run("apply_update ''")
        assertTrue(File(root, "transaction").exists())
        module("staged", "next")
        run("take_lock && rm -rf \"${'$'}ACTIVE/config/app\" && cleanup_completed_update")
        assertTrue(File(root, "transaction").exists())
        assertTrue(File(root, "active/config/tools/qixia_hot_update.sh").exists())
        assertEquals("next:config/bin/QiXiaRs\n", File(root, "staged/config/bin/QiXiaRs").readText())
    }

    @Test fun failedOrUnownedTransactionCannotBeCleaned() {
        file("transaction/worker.sh", "recovery worker")
        run("apply_update ''; cleanup_completed_update", healthy = false)
        assertEquals("phase=deferred", phase())
        assertTrue(File(root, "transaction/worker.sh").exists())
        run("OWNER=; ! cleanup_completed_update")
        assertTrue(File(root, "transaction/worker.sh").exists())
        assertTrue(File(root, "active/config/tools/qixia_hot_update.sh").exists())
    }

    @Test fun laterUpdateWorksAfterPreviousScriptWasRemoved() {
        run("apply_update ''")
        assertFalse(File(root, "transaction").exists())
        module("staged", "next")
        run("REQUEST=next; apply_update ''")
        assertEquals("phase=applied", phase())
        assertEquals("next:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
        assertTrue(File(root, "active/config/state/module_update.state").readText().contains("request=next\n"))
        assertFalse(File(root, "transaction").exists())
    }

    @Test fun completedUpdateArchivesLogBeforeRemovingTransaction() {
        file("transaction/update.log", "runtime update log\n")
        file("transaction/app-update.log", "apk update log\n")
        run("apply_update ''")
        assertFalse(File(root, "transaction").exists())
        assertEquals("runtime update log\napk update log\n", File(root, "active/logs/HotUpdate.log").readText())
    }

    @Test fun rebootOnlyCleansAfterManagerAppliedExpectedBuild() {
        file("staged/system.prop", "example.value=2\n")
        run("apply_update ''")
        assertEquals("phase=deferred", phase())
        run("take_lock; BOOT=test-next-boot; recover; cleanup_completed_update")
        assertEquals("phase=deferred", phase())
        assertTrue(File(root, "transaction").exists())
        run("""
            take_lock
            VERSION=${'$'}(prop version "${'$'}TX/status")
            BUILD=${'$'}(prop build "${'$'}TX/status")
            cp "${'$'}STAGED/config/bin/QiXiaRs" "${'$'}ACTIVE/config/bin/QiXiaRs"
            rm -rf "${'$'}STAGED"
            rm -f "${'$'}ACTIVE/update"
            BOOT=test-next-boot
            recover && cleanup_completed_update
        """.trimIndent())
        assertEquals("phase=rebooted", phase())
        assertFalse(File(root, "transaction").exists())
        assertFalse(File(root, "active/config/tools/qixia_hot_update.sh").exists())
    }

    @Test fun failedHealthRestoresOldBuildAndRetainsStaging() {
        run("apply_update ''", healthy = false)
        assertEquals("phase=deferred", phase())
        assertEquals("old:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
        assertTrue(File(root, "staged/config/bin/QiXiaRs").exists())
        assertEquals("user history", File(root, "active/history/keep.log").readText())
    }

    @Test fun propertyChangesRequireRebootWithoutStoppingService() {
        file("staged/system.prop", "example.value=2\n")
        run("apply_update ''")
        assertEquals("phase=deferred", phase())
        assertFalse(File(root, "starts").exists())
    }

    @Test fun mountSymlinkChangesRequireReboot() {
        run("mkdir -p '${root.path}/active/system' '${root.path}/staged/system'; " +
            "ln -s one '${root.path}/active/system/link'; ln -s two '${root.path}/staged/system/link'; apply_update ''")
        assertEquals("phase=deferred", phase())
        assertFalse(File(root, "starts").exists())
    }

    @Test fun calibrationIsNotInterrupted() {
        file("active/config/calibrate.state", "sampling com.example.game\n")
        run("apply_update ''")
        assertEquals("phase=deferred", phase())
        assertFalse(File(root, "starts").exists())
    }

    @Test fun disabledModuleIsNotStarted() {
        file("active/disable", "")
        run("apply_update ''")
        assertEquals("phase=deferred", phase())
        assertFalse(File(root, "starts").exists())
    }

    @Test fun incompleteStagingDoesNotTouchCurrentBuild() {
        File(root, "staged/config/ebpf/queuebuffer_probe_perf.bpf.o").delete()
        run("apply_update ''")
        assertEquals("phase=deferred", phase())
        assertEquals("old:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
    }

    @Test fun interruptedCommitRestoresStagingAndOriginalRuntime() {
        run("""
            mkdir -p "${'$'}TX"
            backup_runtime
            copy_file "${'$'}STAGED/config/bin/QiXiaRs" "${'$'}ACTIVE/config/bin/QiXiaRs"
            publish committing interrupted
            mv "${'$'}STAGED" "${'$'}TX/consumed"
            recover
        """.trimIndent())
        assertEquals("phase=deferred", phase())
        assertTrue(File(root, "staged").exists())
        assertEquals("old:config/bin/QiXiaRs\n", File(root, "active/config/bin/QiXiaRs").readText())
    }

    @Test fun concurrentWorkerDoesNotStealLiveLock() {
        run("mkdir -p \"${'$'}TX\"; ln -s \"${'$'}${'$'}:${'$'}(starttime ${'$'}${'$'})\" \"${'$'}LOCK\"; apply_update ''")
        assertFalse(File(root, "transaction/status").exists())
        assertFalse(File(root, "starts").exists())
    }
}
