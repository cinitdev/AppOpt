package top.qixia.threads

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import qixia.refresh.RefreshPolicy
import top.qixia.threads.compose.RefreshRateSettingsController

/** 在独立临时目录验证真实 Root 写入，不更改设备的刷新率策略。 */
class RefreshRateStorageTest {
    @Test fun atomicSingleKeyUpdatesPreserveOtherAppsAndProduceRealNewlines() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("emulator"))
        val folder = "${InstrumentationRegistry.getInstrumentation().targetContext.cacheDir}/refresh-storage-test"
        fun root(command: String): String {
            val result = DaemonBridge.runRootCommand(command, 5)
            check(result.success) { result.output }
            return result.output
        }
        root("[ ! -e '$folder' ] && mkdir '$folder'")
        try {
            fun change(key: String, value: String?) = root(RefreshRateSettingsController.mutation(key, value)
                .replace("/data/adb/modules/QixiaThreads/config", folder))
            fun read() = RefreshPolicy.parse(root("cat '$folder/refresh_rate.conf'"))
            change("app.com.example.one", "60,120,0")
            change("app.com.example.two", "-2,-1,1")
            change("active_hz", "165")
            change("active_hz", "144")
            change("app.com.example.one", null)
            assertEquals(mapOf("app.com.example.two" to "-2,-1,1", "active_hz" to "144"), read().values)
            assertFalse(root("cat '$folder/refresh_rate.conf'").contains("\\n"))
            change("display_modes","1 2 -1")
            assertEquals("1 2 -1",read().get("display_modes"))
            assertEquals("1",read().displayMode(165f,floatArrayOf(60f,120f,165f)))
            change("display_modes",null)
            assertEquals("-1 -1 -1",read().get("display_modes"))
            root("mkdir '$folder/refresh_rate.conf.lock'")
            val blocked = DaemonBridge.runRootCommand(RefreshRateSettingsController.mutation("enabled", "1")
                .replace("/data/adb/modules/QixiaThreads/config", folder), 5)
            assertFalse(blocked.success)
            assertFalse(read().on("enabled"))
            root("rmdir '$folder/refresh_rate.conf.lock'")
        } finally {
            root("rm -f '$folder/refresh_rate.conf'; rmdir '$folder/refresh_rate.conf.lock' 2>/dev/null; rmdir '$folder'")
        }
    }
}
