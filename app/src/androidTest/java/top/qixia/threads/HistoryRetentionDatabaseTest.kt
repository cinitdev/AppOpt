package top.qixia.threads

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.qixia.threads.db.QixiaThreadsDbHelper
import top.qixia.threads.db.ThreadImport

/** 使用缓存目录下的独立数据库，不访问用户历史数据库。 */
@RunWith(AndroidJUnit4::class)
class HistoryRetentionDatabaseTest {
    private lateinit var folder: File
    private lateinit var database: QixiaThreadsDbHelper

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(target.cacheDir, "history-retention-test-${System.nanoTime()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(folder, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }
        database = QixiaThreadsDbHelper(isolated)
    }

    @After fun cleanup() { database.close(); folder.deleteRecursively() }

    private fun add(pkg: String, epoch: Long): Boolean = database.insertSessionWithThreadsIfAbsent(
        pkg, epoch, 2, listOf(ThreadImport("worker", 10f, 20f, "10,20")))

    @Test fun globalLimitKeepsNewestTenAndExpiredClaimCannotReimport() {
        assertTrue(add("app.1", 1))
        val oldId = database.getSessionSummariesByPackage("app.1").single().id
        (2L..16L).forEach { assertTrue(add("app.${it % 3}", it)) }
        val sessions = database.getPackagesWithHistory().flatMap { database.getSessionSummariesByPackage(it.pkg) }
        assertEquals((7L..16L).toList(), sessions.map { it.epoch }.sorted())
        assertTrue(database.getThreadsBySessionId(oldId).isEmpty())
        assertFalse(add("app.1", 1))
        assertEquals(0, database.pruneHistory())
    }

    @Test fun batchDeletionIsExactCascadesAndCannotReappearFromClaim() {
        (1L..6L).forEach { assertTrue(add("app.${it % 2}", it)) }
        val sessions = database.getPackagesWithHistory().flatMap { pkg ->
            database.getSessionSummariesByPackage(pkg.pkg).map { pkg.pkg to it }
        }
        val selected = sessions.filter { it.second.epoch % 2L == 0L }
        val ids = selected.map { it.second.id }
        assertEquals(selected.associate { it.second.id to it.first }, database.getSessionPackages(ids))
        assertEquals(3, database.deleteSessions(ids + ids + -1L))
        selected.forEach { (pkg, session) ->
            assertTrue(database.getThreadsBySessionId(session.id).isEmpty())
            assertFalse(add(pkg, session.epoch))
        }
        assertEquals(3, database.getPackagesWithHistory().sumOf { it.sessionCount })
        assertTrue(add("app.0", 7))
    }
}
