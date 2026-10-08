package top.qixia.threads

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离的测试收件目录中验证 Root 集成，不修改真实历史或模块配置。 */
@RunWith(AndroidJUnit4::class)
class PrivateCaptureStorageTest {
    private lateinit var actual: Context
    private lateinit var isolated: Context
    private lateinit var folder: File

    @Before fun setup() {
        actual = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(actual.cacheDir, "capture-storage-test-${System.nanoTime()}").apply { check(mkdirs()) }
        isolated = object : ContextWrapper(actual) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
        }
        PrivateCaptureStorage.initialize(isolated)
        assertTrue("Root required on test emulator", DaemonBridge.runRootCommand("id -u").output.trim() == "0")
    }

    @After fun cleanup() {
        PrivateCaptureStorage.initialize(actual)
        // 只删除本测试专用的缓存目录。
        folder.deleteRecursively()
    }

    private fun recording(name: String = "auto_1000_12_0.log"): File = File(PrivateCaptureStorage.automatic, name).apply {
        parentFile!!.mkdirs()
        writeText("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tcom.storage.test\n" +
            "start_ms\t1000\nT\t2000\t1\t2\t3\t776f726b6572\t12.5\nF\t2000\t60\t16.7\n" +
            "end_ms\t201000\nduration_ms\t200000\nsamples\t1\n")
    }

    @Test fun emptyReadersDoNotCreateInboxes() {
        AutoHistoryStore.importCompleted(isolated)
        assertTrue(DaemonBridge.listHistoryEntries().isEmpty())
        assertEquals("", DaemonBridge.claimHistoryImport("com.storage.test"))
        assertFalse(PrivateCaptureStorage.root.exists())
        assertFalse(File(folder, "auto_history").exists())
    }

    @Test fun completeAutomaticRecordingMovesWithoutCopyAndRemovesEmptyInbox() {
        val source = recording()
        val inode = Os.stat(source.path).st_ino
        val content = source.readText()
        AutoHistoryStore.importCompleted(isolated)
        val archive = File(folder, "auto_history/${source.name}")
        assertTrue(archive.isFile)
        assertEquals(content, archive.readText())
        assertEquals(inode, Os.stat(archive.path).st_ino)
        assertFalse(PrivateCaptureStorage.automatic.exists())
        assertEquals(1, AutoHistoryStore.entries(folder).size)
        AutoHistoryStore.importCompleted(isolated)
        assertEquals(1, AutoHistoryStore.entries(folder).size)
    }

    @Test fun activeAndInvalidFilesSurviveImport() {
        val completed = recording()
        val active = recording("auto_2000_12_0.tmp")
        val invalid = File(PrivateCaptureStorage.automatic, "auto_3000_12_0.log").apply { writeText("invalid\n") }
        AutoHistoryStore.importCompleted(isolated)
        assertFalse(completed.exists())
        assertTrue(active.exists())
        assertEquals("invalid\n", invalid.readText())
        assertTrue(PrivateCaptureStorage.automatic.isDirectory)
    }

    @Test fun acknowledgementKeepsNewCalibrationAndCleansDirectoryOnlyAfterFinalClaim() {
        val history = PrivateCaptureStorage.history.apply { mkdirs() }
        val source = File(history, "com.storage.test.log")
        source.writeText("first")
        assertEquals("first", DaemonBridge.claimHistoryImport("com.storage.test"))
        source.writeText("next")
        assertTrue(DaemonBridge.completeHistoryImport("com.storage.test"))
        assertEquals("next", source.readText())
        assertEquals("next", DaemonBridge.claimHistoryImport("com.storage.test"))
        assertTrue(DaemonBridge.completeHistoryImport("com.storage.test"))
        assertFalse(history.exists())
        assertFalse(File(PrivateCaptureStorage.root, ".history.lock").exists())
    }
}
