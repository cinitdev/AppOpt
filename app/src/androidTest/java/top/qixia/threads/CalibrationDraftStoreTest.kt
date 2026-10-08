package top.qixia.threads

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用隔离的私有文件和模拟传输，不读写设备规则或真实草稿。 */
@RunWith(AndroidJUnit4::class)
class CalibrationDraftStoreTest {
    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var prefs: SharedPreferences
    private lateinit var preferencesName: String
    private lateinit var store: CalibrationDraftStore
    private val remote = linkedMapOf<String, CalibrationDraft>()
    private var readSucceeds = true
    private var deleteSucceeds = true

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "draft-store-test-${System.nanoTime()}"
        preferencesName = token
        sandbox = File(target.cacheDir, token).apply { check(mkdirs()) }
        prefs = target.getSharedPreferences(token, Context.MODE_PRIVATE)
        context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = sandbox
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        store = newStore()
    }

    @After fun cleanup() {
        sandbox.deleteRecursively()
        context.deleteSharedPreferences(preferencesName)
    }

    private fun newStore() = CalibrationDraftStore(context) { command ->
        if (command.startsWith("rm -f ")) {
            if (deleteSucceeds) {
                Regex("'[^']*/([^/']+\\.draft)'").findAll(command).forEach { remote.remove(it.groupValues[1]) }
            }
            DaemonBridge.RootCommandResult(if (deleteSucceeds) "acknowledged" else "", deleteSucceeds)
        } else {
            DaemonBridge.RootCommandResult(remote.values.joinToString("") { "\n__QIXIA_DRAFT__\n${it.wire}" }, readSucceeds)
        }
    }

    private fun draft(stamp: Long, pkg: String = "com.test", name: String = "RenderThread"): CalibrationDraft {
        fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it) }
        return requireNotNull(CalibrationDraft.parse(
            "QIXIA_CALIBRATION_DRAFT\t1\nmeta\t$stamp-123\t$pkg\t$stamp\t60000\n" +
                "cpu\t0\t300\ncpu\t7\t1024\n" +
                "thread\t${hex(pkg)}\t${hex(name)}\t35.0\t90.0\t85.0\t7\t${hex("推荐")}\n"
        ))
    }

    private fun publish(vararg drafts: CalibrationDraft) = drafts.forEach { remote[it.fileName] = it }
    private fun localFile(draft: CalibrationDraft) = File(sandbox, "calibration_review/${draft.fileName}.json")
    private fun writeLegacy(review: CalibrationReview, backup: Boolean = false) {
        val file = localFile(review.draft).let { if (backup) File(it.path + ".bak") else it }
        file.parentFile!!.mkdirs()
        file.writeText(JSONObject().put("wire", review.draft.wire)
            .put("selections", JSONArray(review.selections.map { JSONArray(it.toList()) })).toString())
    }

    @Test fun sameAppIsReplacedAndNewSuggestionsDoNotInheritOldEdits() {
        val old = draft(1000)
        val other = draft(1100, "com.other")
        publish(old, other)
        val edited = store.load().first { it.draft.packageName == old.packageName }.copy(
            selections = listOf(setOf(0)), original = listOf("com.test=0"),
            processSelections = mapOf("com.test" to setOf(0)))
        store.persist(edited)
        val latest = draft(2000, name = "NewRenderer")
        publish(latest)
        val pending = store.load()
        assertEquals(2, pending.size)
        val replacement = pending.first { it.draft.packageName == old.packageName }
        assertEquals(latest.fileName, replacement.draft.fileName)
        assertEquals(listOf(setOf(7)), replacement.selections)
        assertNull(replacement.original)
        assertTrue(replacement.processSelections.isEmpty())
        assertFalse(localFile(old).exists())
        assertFalse(remote.containsKey(old.fileName))
        assertTrue(localFile(other).exists())
    }

    @Test fun sameSessionRefreshAndRecreationPreserveEdits() {
        publish(draft(1000))
        val edited = store.load().single().copy(selections = listOf(setOf(0)))
        store.persist(edited)
        assertEquals(edited.selections, store.load().single().selections)
        assertEquals(edited.selections, newStore().load().single().selections)
    }

    @Test fun legacyDuplicatesAndBackupFilesMigrateToNewestPerExactPackage() {
        val old = draft(1000)
        val newest = draft(3000)
        val childNamedApp = draft(500, "com.test.other")
        writeLegacy(CalibrationReview(old))
        writeLegacy(CalibrationReview(newest, listOf(setOf(0))), backup = true)
        writeLegacy(CalibrationReview(childNamedApp))
        publish(draft(2000), old)
        val pending = store.load()
        assertEquals(setOf(newest.fileName, childNamedApp.fileName), pending.map { it.draft.fileName }.toSet())
        assertEquals(listOf(setOf(0)), pending.first().selections)
        assertFalse(localFile(old).exists())
        assertEquals(2, localFile(old).parentFile!!.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test fun staleEditsOpenAndSaveCannotRestoreReplacedSession() {
        publish(draft(1000))
        val old = store.load().single()
        val latest = draft(2000)
        publish(latest)
        store.load()
        assertThrows(SupersededCalibrationDraftException::class.java) { store.persist(old) }
        assertThrows(SupersededCalibrationDraftException::class.java) { store.open(old) }
        assertThrows(SupersededCalibrationDraftException::class.java) { store.save(old) }
        assertFalse(localFile(old.draft).exists())
        assertEquals(latest.fileName, store.load().single().draft.fileName)
    }

    @Test fun discardChecksForNewRemoteSessionBeforeDeleting() {
        publish(draft(1000))
        val old = store.load().single().draft
        val latest = draft(2000)
        publish(latest)
        assertThrows(SupersededCalibrationDraftException::class.java) { store.discard(old) }
        assertTrue(remote.containsKey(latest.fileName))
        assertEquals(latest.fileName, store.load().single().draft.fileName)
    }

    @Test fun acknowledgedOldSnapshotsDoNotReappearAfterDiscardOrAppRestart() {
        val old = draft(1000)
        val latest = draft(2000)
        publish(old, latest)
        store.discard(store.load().single().draft)
        assertTrue(remote.isEmpty())
        // 模拟模块升级时恢复之前暂存的快照。
        publish(old, latest)
        assertTrue(newStore().load().isEmpty())
        assertTrue(remote.isEmpty())
        val next = draft(3000)
        publish(next)
        assertEquals(next.fileName, newStore().load().single().draft.fileName)
    }

    @Test fun failedRootAcknowledgementNeverResurrectsAnOldPendingRecord() {
        val old = draft(1000)
        publish(old)
        store.load()
        deleteSucceeds = false
        val latest = draft(2000)
        publish(latest)
        assertEquals(latest.fileName, store.load().single().draft.fileName)
        store.discard(latest)
        assertTrue(newStore().load().isEmpty())
        deleteSucceeds = true
        assertTrue(newStore().load().isEmpty())
        assertTrue(remote.isEmpty())
    }

    @Test fun writeFailurePreservesOldDraftAndDoesNotAcknowledgeIt() {
        val old = draft(1000)
        publish(old)
        store.load()
        val latest = draft(2000)
        val blocker = File(localFile(latest).path + ".new").apply { check(mkdir()) }
        publish(latest)
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(localFile(old).isFile)
        assertTrue(remote.containsKey(old.fileName))
        check(blocker.delete())
        assertEquals(latest.fileName, store.load().single().draft.fileName)
    }

    @Test fun fullQueueStillAcceptsReplacementForAnExistingApp() {
        (0 until 64).forEach { publish(draft(1000L + it, "com.app$it")) }
        assertEquals(64, store.load().size)
        val newest = draft(3000, "com.app0")
        publish(newest)
        val pending = store.load()
        assertEquals(64, pending.size)
        assertEquals(newest.fileName, pending.first { it.draft.packageName == "com.app0" }.draft.fileName)
        assertEquals(64, localFile(newest).parentFile!!.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test fun failedRemoteReadKeepsCachedEditsButBlocksDiscard() {
        publish(draft(1000))
        val review = store.load().single()
        readSucceeds = false
        assertEquals(review.draft.fileName, store.load().single().draft.fileName)
        assertTrue(runCatching { store.discard(review.draft) }.isFailure)
        assertTrue(localFile(review.draft).exists())
    }
}
