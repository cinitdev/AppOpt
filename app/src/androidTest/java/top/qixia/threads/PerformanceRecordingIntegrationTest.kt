package top.qixia.threads

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import top.qixia.threads.db.HistorySource
import java.io.File

/** 仅在模拟器运行完整胶囊流程；保留产生的报告，方便人工复核。 */
class PerformanceRecordingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val pkg = "com.google.android.deskclock"
    private fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
    }
    private fun waitUntil(label: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        fail("等待超时：$label")
    }
    private fun status(): Map<String, String> = runCatching {
        File(PrivateCaptureStorage.root, "performance_recording.state").readLines()
            .mapNotNull { if ('=' in it) it.substringBefore('=') to it.substringAfter('=') else null }.toMap()
    }.getOrDefault(emptyMap())
    private fun capsule(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString()?.contains(text) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return automation.windows.firstNotNullOfOrNull { find(it.root) }
    }
    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun assertLiveFps(action: String) {
        // 让画面持续发生变化，确认两种颜色都使用实时帧源，而非时长或占位文字。
        waitUntil("$action 胶囊显示实时 FPS", 25_000) {
            shell("input swipe 500 1400 500 500 600")
            val description = capsule(action)?.contentDescription?.toString().orEmpty()
            val fps = description.substringBefore(" FPS").toFloatOrNull()
            fps != null && fps > 0f
        }
    }

    @Test fun capsuleIsManualPersistentAndIndependentFromScheduling() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("emulator"))
        assertTrue("模拟器中的柒夏线程需要 Root 授权", DaemonBridge.hasRoot())
        val config = "/data/adb/modules/QixiaThreads/config"
        fun scheduling() = DaemonBridge.runRootCommand("sha256sum '$config/applist.conf' '$config/auto_affinity.conf'").output
        val before = scheduling()
        val enabledBefore = pkg in DaemonBridge.performanceRecordingApps()
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            assertTrue(DaemonBridge.setPerformanceRecording(pkg, true))
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(2800)
            shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            waitUntil("空闲胶囊") { capsule("性能记录，点击开始") != null }
            assertLiveFps("性能记录，点击开始")
            assertNotEquals("recording", status()["state"])
            screenshot("performance-capsule-idle.png")
            assertTrue(capsule("性能记录，点击开始")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("开始采集") { status()["state"] == "recording" }
            val firstToken = status()["token"]
            shell("input swipe 500 1600 500 550 600")
            SystemClock.sleep(3500)
            assertLiveFps("记录中，点击结束")
            screenshot("performance-capsule-recording.png")
            waitUntil("结束按钮") { capsule("结束") != null }
            assertTrue(capsule("结束")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("保存成功") { status()["state"] == "done" && status()["token"] == firstToken }
            waitUntil("保存后恢复黄胶囊") { capsule("性能记录，点击开始") != null }
            assertLiveFps("性能记录，点击开始")
            assertTrue(pkg in DaemonBridge.performanceRecordingApps())
            AutoHistoryStore.importCompleted(context)
            val report = AutoHistoryStore.entries(context.filesDir).filter { it.pkg == pkg && it.session.source == HistorySource.PERFORMANCE }.maxByOrNull { it.session.endedAtMs }
            assertNotNull(report)
            assertTrue(report!!.session.durationMs in 1..179999)
            assertNotNull(AutoHistoryStore.record(context.filesDir, report.session.id)?.metrics)
            assertEquals(before, scheduling())
            shell("input keyevent KEYCODE_HOME")
            waitUntil("离开后胶囊关闭") { capsule("性能记录，点击开始") == null }
            shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            waitUntil("再次进入仍显示胶囊") { capsule("性能记录，点击开始") != null }
            assertNotEquals("recording", status()["state"])
            assertTrue(capsule("性能记录，点击开始")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("第二次采集") { status()["state"] == "recording" && status()["token"] != firstToken }
            SystemClock.sleep(2500)
            waitUntil("长按结束按钮") { capsule("结束") != null }
            assertTrue(capsule("结束")!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            waitUntil("长按关闭持久开关") { pkg !in DaemonBridge.performanceRecordingApps() }
            waitUntil("长按先保存记录") { status()["state"] == "done" }
            waitUntil("长按移除胶囊") { capsule("性能记录，点击开始") == null }
            assertEquals(before, scheduling())
            assertTrue(DaemonBridge.setPerformanceRecording(pkg, true))
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(2800)
            shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            waitUntil("离开测试的空闲胶囊") { capsule("性能记录，点击开始") != null }
            assertTrue(capsule("性能记录，点击开始")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("第三次采集") { status()["state"] == "recording" }
            SystemClock.sleep(2500)
            shell("input keyevent KEYCODE_HOME")
            waitUntil("离开前台自动保存") { status()["state"] == "done" }
            assertTrue(pkg in DaemonBridge.performanceRecordingApps())
        } finally {
            assertTrue(DaemonBridge.setPerformanceRecording(pkg, enabledBefore))
            shell("input keyevent KEYCODE_HOME")
        }
    }

    @Test fun manualStopPreservesTheOverlappingAutomaticHistory() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("emulator"))
        assertTrue(DaemonBridge.hasRoot())
        val before = DaemonBridge.readAutomaticHistory().enabled
        val automaticBefore = pkg in DaemonBridge.readAutomaticAffinity().packages
        val performanceBefore = pkg in DaemonBridge.performanceRecordingApps()
        val began = System.currentTimeMillis()
        try {
            assertTrue(DaemonBridge.setPerformanceRecording(pkg, true))
            assertTrue(DaemonBridge.setAutomaticAffinity(pkg, true))
            assertTrue(DaemonBridge.setAutomaticHistory(true))
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(2800)
            shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            waitUntil("自动记录启动") { DaemonBridge.readRootFile("/data/adb/modules/QixiaThreads/config/auto_history.state")?.contains("package=$pkg") == true }
            val token = java.util.UUID.randomUUID().toString()
            assertTrue(DaemonBridge.performanceRecordingCommand(pkg, token, true))
            waitUntil("共享采集启动") { status()["token"] == token && status()["state"] == "recording" }
            SystemClock.sleep(3500)
            assertTrue(DaemonBridge.performanceRecordingCommand(pkg, token, false))
            waitUntil("手动记录保存") { status()["token"] == token && status()["state"] == "done" }
            assertTrue(DaemonBridge.readRootFile("/data/adb/modules/QixiaThreads/config/auto_history.state")!!.contains("active=1"))
            // 自动记录仍遵守原来的三分钟准入条件，不修改阈值加速测试。
            while (System.currentTimeMillis() - began < 190_000) SystemClock.sleep(500)
            shell("input keyevent KEYCODE_HOME")
            waitUntil("自动记录结束") { DaemonBridge.readRootFile("/data/adb/modules/QixiaThreads/config/auto_history.state")?.contains("active=0") == true }
            waitUntil("两种历史均可导入") {
                AutoHistoryStore.importCompleted(context)
                val entries = AutoHistoryStore.entries(context.filesDir).filter { it.pkg == pkg && it.session.startedAtMs >= began }
                entries.any { it.session.source == HistorySource.AUTO_ALLOCATION && it.session.durationMs > 180_000 } &&
                    entries.any { it.session.source == HistorySource.PERFORMANCE && it.session.durationMs < 180_000 }
            }
        } finally {
            DaemonBridge.setPerformanceRecording(pkg, performanceBefore)
            DaemonBridge.setAutomaticAffinity(pkg, automaticBefore)
            DaemonBridge.setAutomaticHistory(before)
            shell("input keyevent KEYCODE_HOME")
        }
    }
}
