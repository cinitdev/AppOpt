package top.qixia.threads

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 显式传入本地模块包才执行真实刷入，普通测试运行不会操作设备模块。 */
class ModuleHotUpdateDeviceTest {
    @Test fun verifyHotUpdatedDaemonConnection() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyOnly") == "true")
        repeat(3) {
            val runtime = DaemonBridge.readDaemonRuntime()
            assertTrue("App 反向验证失败：$runtime", runtime.running)
        }
        if (args.getString("expectCleanup") == "true") {
            assertTrue(ModuleHotUpdate.isApplied(requireNotNull(DaemonBridge.readModuleVersion()).versionCode))
            assertFalse(ModuleHotUpdate.hasPendingApp())
            val result = DaemonBridge.runRootCommand("""
                test ! -e /data/adb/qixia_hot_update &&
                test ! -e /data/adb/qixia_hot_update.lock &&
                test ! -e /data/adb/modules/QixiaThreads/config/tools/qixia_hot_update.sh
            """.trimIndent())
            assertTrue("完成更新后仍有临时资料：${result.output}", result.success)
        }
    }

    @Test fun installThroughAppAndRestoreCompletedPage() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("moduleZip")
        assumeTrue("未指定真实模块包，跳过设备刷入", !path.isNullOrBlank())
        val code = args.getString("moduleCode")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val boot = DaemonBridge.runRootCommand("cat /proc/sys/kernel/random/boot_id").output
        val config = DaemonBridge.runRootCommand("sha256sum /data/adb/modules/QixiaThreads/config/applist.conf /data/adb/modules/QixiaThreads/config/auto_affinity.conf").output
        assertTrue(File(path!!).isFile)
        val done = CountDownLatch(1)
        var failure: String? = null
        var reboot = true
        ModuleUpdater.installDownloadedModule(path, object : ModuleUpdater.InstallCallback {
            override fun onProgress(message: String, percent: Int?) = Unit
            override fun onLog(text: String) { android.util.Log.i("QixiaHotUpdateTest", text) }
            override fun onSuccess(message: String, rebootRequired: Boolean) { reboot = rebootRequired; done.countDown() }
            override fun onFailure(message: String, retainedZipPath: String?) { failure = message; done.countDown() }
        }, context = context, expectedVersionCode = code)
        assertTrue("安装等待超时", done.await(5, TimeUnit.MINUTES))
        assertNull(failure, failure)
        assertFalse("本次普通更新应当热生效", reboot)
        assertTrue(ModuleHotUpdate.isApplied(code))
        val absent = DaemonBridge.runRootCommand(
            "/data/adb/modules/QixiaThreads/config/bin/QiXiaRs --find-pid qixia_absent_test_${System.nanoTime()}"
        )
        assertTrue("完整进程查询的空集合不能触发 shell 降级：${absent.output}", absent.success)
        assertTrue(absent.output.isBlank())
        assertEquals(boot, DaemonBridge.runRootCommand("cat /proc/sys/kernel/random/boot_id").output)
        assertEquals(config, DaemonBridge.runRootCommand("sha256sum /data/adb/modules/QixiaThreads/config/applist.conf /data/adb/modules/QixiaThreads/config/auto_affinity.conf").output)
        val update = ModuleUpdater.UpdateInfo("测试前版本", 204, "v2.0.5", code,
            "https://example.invalid/local-module.zip", null, "", false)
        if (args.getString("showResult") == "true") {
            context.startActivity(UpdateInstallActivity.intent(context, update, path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
