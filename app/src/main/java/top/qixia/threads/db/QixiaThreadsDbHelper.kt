package top.qixia.threads.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import top.qixia.threads.HistoryRetention
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * QixiaThreads 数据库助手 (原生 SQLite)
 * 支持 Android 12-16 (API 31-36)
 */
class QixiaThreadsDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "qixia.db"
        private const val DATABASE_VERSION = 7
        private const val RULE_HISTORY_RECENT_SESSIONS = 3
        private const val RULE_HISTORY_ROW_LIMIT = 900
        private const val MAX_TOTAL_SESSIONS = HistoryRetention.CALIBRATION_LIMIT

        // 表名
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_THREADS = "threads"
        private const val TABLE_DELETED = "deleted_sessions"

        // sessions 表字段
        private const val COL_SESSION_ID = "id"
        private const val COL_PKG = "pkg"
        private const val COL_EPOCH = "epoch"
        private const val COL_ROUNDS = "rounds"
        private const val COL_CREATED_AT = "created_at"

        // threads 表字段
        private const val COL_THREAD_ID = "id"
        private const val COL_THREAD_SESSION_ID = "session_id"
        private const val COL_NAME = "name"
        private const val COL_AVG = "avg"
        private const val COL_MAX = "max"
        private const val COL_SERIES = "series"
        private const val COL_DETAILS = "details"

        @Volatile
        private var instance: QixiaThreadsDbHelper? = null

        /**
         * 获取数据库单例。
         *
         * 如果用户手动删除了数据库文件, 这里会关闭旧连接并重建, 避免继续拿着已经
         * 指向旧 inode 的 SQLite 句柄, 导致导入成功但历史页读不到新数据。
         */
        fun getInstance(context: Context): QixiaThreadsDbHelper {
            val appContext = context.applicationContext
            return synchronized(this) {
                val current = instance
                if (current != null && !appContext.getDatabasePath(DATABASE_NAME).exists()) {
                    try {
                        current.close()
                    } catch (_: Exception) {
                    }
                    instance = null
                }
                instance ?: QixiaThreadsDbHelper(appContext).also { instance = it }
            }
        }

        /**
         * 压缩 series 字符串 (Deflate + Base64)
         */
        fun compressSeries(series: String): String {
            val input = series.toByteArray(Charsets.UTF_8)
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }

        /**
         * 解压 series 字符串 (Base64 + Inflate)
         * 如果解压失败(数据损坏/格式错误)返回空字符串,避免崩溃
         */
        fun decompressSeries(compressed: String): String {
            val inflater = Inflater()
            return try {
                val input = Base64.decode(compressed, Base64.NO_WRAP)
                inflater.setInput(input)
                val output = ByteArrayOutputStream(input.size * 2)
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        throw DataFormatException("Inflater made no progress")
                    }
                    output.write(buffer, 0, count)
                }
                if (!inflater.finished()) "" else output.toString(Charsets.UTF_8.name())
            } catch (e: Exception) {
                android.util.Log.e("QixiaThreads", "decompress series failed: ${e.message}")
                ""
            } finally {
                inflater.end()
            }
        }
    }

    /** 开启外键约束, 让删除 session 时自动级联删除对应线程。 */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // sessions: 一次线程负载采样会话, pkg+epoch 后续通过唯一索引用于导入去重。
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PKG TEXT NOT NULL,
                $COL_EPOCH INTEGER NOT NULL,
                $COL_ROUNDS INTEGER NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // threads: 会话内的线程负载曲线, session 删除时通过外键级联清理。
        db.execSQL(
            """
            CREATE TABLE $TABLE_THREADS (
                $COL_THREAD_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_THREAD_SESSION_ID INTEGER NOT NULL,
                $COL_NAME TEXT NOT NULL,
                $COL_AVG REAL NOT NULL,
                $COL_MAX REAL NOT NULL,
                $COL_SERIES TEXT NOT NULL,
                $COL_DETAILS TEXT NOT NULL DEFAULT '',
                FOREIGN KEY($COL_THREAD_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        createIndexes(db)
        createDeletionMarkers(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw android.database.sqlite.SQLiteException("不支持的数据库版本：$oldVersion -> $newVersion")
    }
    /**
     * 原子导入一次会话及其线程数据。
     *
     * sessions 上有 (pkg, epoch) 唯一索引; 已导入过的会话会被忽略, 不再写入重复线程。
     * 任意线程插入失败都会回滚整次会话, 避免留下只有 session 没有完整线程的半条记录。
     */
    fun insertSessionWithThreadsIfAbsent(
        pkg: String,
        epoch: Long,
        rounds: Int,
        threads: List<ThreadImport>
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.query(TABLE_DELETED, arrayOf(COL_EPOCH), "$COL_PKG = ? AND $COL_EPOCH = ?",
                arrayOf(pkg, epoch.toString()), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) {
                    db.setTransactionSuccessful()
                    return false
                }
            }
            val sessionValues = ContentValues().apply {
                put(COL_PKG, pkg)
                put(COL_EPOCH, epoch)
                put(COL_ROUNDS, rounds)
                put(COL_CREATED_AT, System.currentTimeMillis())
            }
            val sessionId = db.insertWithOnConflict(
                TABLE_SESSIONS,
                null,
                sessionValues,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (sessionId == -1L) {
                db.setTransactionSuccessful()
                false
            } else {
                for (thread in threads) {
                    val threadValues = ContentValues().apply {
                        put(COL_THREAD_SESSION_ID, sessionId)
                        put(COL_NAME, thread.name)
                        put(COL_AVG, thread.avg)
                        put(COL_MAX, thread.max)
                        put(COL_SERIES, compressSeries(thread.series))
                        put(COL_DETAILS, thread.details)
                    }
                    val threadId = db.insert(TABLE_THREADS, null, threadValues)
                    check(threadId != -1L) { "insert thread failed: ${thread.name}" }
                }
                try {
                    pruneGlobalHistory(db)
                } catch (error: Exception) {
                    // 保留策略失败不能让本次完整校准记录随事务一起回滚。
                    android.util.Log.w("QixiaThreads", "history retention failed after insert: $pkg", error)
                }
                db.setTransactionSuccessful()
                true
            }
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 获取指定包名的完整会话及线程数据。
     *
     * 默认用于历史详情, 线程按 AVG 降序显示; 导出原版 .log 时可保留导入顺序,
     * 避免导出的文本和守护进程原始历史格式不一致。
     */
    fun getSessionsByPackage(pkg: String, preserveOriginalThreadOrder: Boolean = false): List<SessionWithThreads> {
        val db = readableDatabase
        val sessions = mutableListOf<SessionWithThreads>()
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_PKG = ?",
            arrayOf(pkg),
            null,
            null,
            "$COL_EPOCH DESC, $COL_SESSION_ID DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val sessionId = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_ID))
                sessions.add(
                    SessionWithThreads(
                        id = sessionId,
                        pkg = pkg,
                        epoch = it.getLong(it.getColumnIndexOrThrow(COL_EPOCH)),
                        rounds = it.getInt(it.getColumnIndexOrThrow(COL_ROUNDS)),
                        threads = getThreadsBySessionId(sessionId, preserveOriginalThreadOrder)
                    )
                )
            }
        }
        return sessions
    }

    /**
     * 获取指定包名的会话概要(不读取线程 series), 用于历史页首屏快速渲染。
     * 显示顺序按记录生成时间(epoch)倒序, 不受重新导入影响。
     */
    /** 用于保留曲线的轻量引用快照，不加载线程序列。 */
    fun calibrationWindows(): Map<String, List<Pair<Long, Long>>> {
        val windows = linkedMapOf<String, MutableList<Pair<Long, Long>>>()
        readableDatabase.rawQuery("SELECT $COL_PKG, $COL_EPOCH, $COL_ROUNDS FROM $TABLE_SESSIONS", null).use { cursor ->
            while (cursor.moveToNext()) {
                windows.getOrPut(cursor.getString(0)) { mutableListOf() }
                    .add(cursor.getLong(1) to cursor.getLong(2).coerceAtLeast(0L) * 500L)
            }
        }
        return windows
    }

    fun getSessionSummariesByPackage(pkg: String): List<SessionSummary> {
        val db = readableDatabase
        val sessions = mutableListOf<SessionSummary>()
        val cursor = db.rawQuery(
            """
            SELECT s.$COL_SESSION_ID, s.$COL_EPOCH, s.$COL_ROUNDS, COUNT(t.$COL_THREAD_ID) AS thread_count
            FROM $TABLE_SESSIONS s
            LEFT JOIN $TABLE_THREADS t ON t.$COL_THREAD_SESSION_ID = s.$COL_SESSION_ID
            WHERE s.$COL_PKG = ?
            GROUP BY s.$COL_SESSION_ID, s.$COL_EPOCH, s.$COL_ROUNDS
            ORDER BY s.$COL_EPOCH DESC, s.$COL_SESSION_ID DESC
            """.trimIndent(),
            arrayOf(pkg)
        )
        cursor.use {
            while (it.moveToNext()) {
                sessions.add(
                    SessionSummary(
                        id = it.getLong(0),
                        epoch = it.getLong(1),
                        rounds = it.getInt(2),
                        threadCount = it.getInt(3)
                    )
                )
            }
        }
        return sessions
    }

    /**
     * 获取指定会话的所有线程
     */
    fun getThreadsBySessionId(sessionId: Long, preserveOriginalOrder: Boolean = false): List<ThreadData> {
        val db = readableDatabase
        val threads = mutableListOf<ThreadData>()
        // 原版 .log 导出需要保持导入顺序; 历史详情 UI 则按平均负载降序显示。
        val orderBy = if (preserveOriginalOrder) "$COL_THREAD_ID ASC" else "$COL_AVG DESC"
        val cursor = db.query(
            TABLE_THREADS,
            null,
            "$COL_THREAD_SESSION_ID = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            orderBy
        )
        cursor.use {
            while (it.moveToNext()) {
                val compressedSeries = it.getString(it.getColumnIndexOrThrow(COL_SERIES))
                threads.add(
                    ThreadData(
                        name = it.getString(it.getColumnIndexOrThrow(COL_NAME)),
                        avg = it.getFloat(it.getColumnIndexOrThrow(COL_AVG)),
                        max = it.getFloat(it.getColumnIndexOrThrow(COL_MAX)),
                        series = decompressSeries(compressedSeries),
                        details = it.getString(it.getColumnIndexOrThrow(COL_DETAILS))
                    )
                )
            }
        }
        return threads
    }

    /**
     * 读取规则编辑器需要的历史负载摘要，不读取和解压折线序列。
     */
    fun getRuleHistoryRecordsByPackage(
        pkg: String,
        recentSessionLimit: Int = RULE_HISTORY_RECENT_SESSIONS,
        rowLimit: Int = RULE_HISTORY_ROW_LIMIT
    ): List<RuleHistoryRecord> {
        val safeSessionLimit = recentSessionLimit.coerceIn(1, 10)
        val safeRowLimit = rowLimit.coerceIn(1, 2_000)
        val db = readableDatabase
        val sessions = mutableListOf<Pair<Long, Long>>()
        db.query(
            TABLE_SESSIONS,
            arrayOf(COL_SESSION_ID, COL_EPOCH),
            "$COL_PKG = ?",
            arrayOf(pkg),
            null,
            null,
            "$COL_EPOCH DESC, $COL_SESSION_ID DESC",
            safeSessionLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) sessions += cursor.getLong(0) to cursor.getLong(1)
        }
        if (sessions.isEmpty()) return emptyList()
        val selectedSessions = sessions.take(safeRowLimit)

        val records = mutableListOf<RuleHistoryRecord>()
        val baseBudget = safeRowLimit / selectedSessions.size
        var extraBudget = safeRowLimit % selectedSessions.size
        selectedSessions.forEach { (sessionId, epoch) ->
            val budget = baseBudget + if (extraBudget > 0) 1 else 0
            if (extraBudget > 0) extraBudget--
            db.query(
                TABLE_THREADS,
                arrayOf(COL_NAME, COL_AVG, COL_MAX, COL_DETAILS),
                "$COL_THREAD_SESSION_ID = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "$COL_AVG DESC, $COL_MAX DESC",
                budget.coerceAtLeast(1).toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(
                        RuleHistoryRecord(
                            epoch = epoch,
                            name = cursor.getString(0),
                            avg = cursor.getFloat(1),
                            max = cursor.getFloat(2),
                            details = cursor.getString(3)
                        )
                    )
                }
            }
        }
        return records
    }

    /**
     * 删除指定会话(级联删除线程)
     */
    fun deleteSession(sessionId: Long): Int {
        return deleteSessions(listOf(sessionId))
    }

    /** 在单次事务内执行，与逐条删除使用相同的外键级联行为。 */
    fun deleteSessions(sessionIds: Collection<Long>): Int {
        val ids = sessionIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        return try {
            var deleted = 0
            for (chunk in ids.chunked(500)) {
                markDeleted(db, "$COL_SESSION_ID IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map(Long::toString).toTypedArray())
                deleted += db.delete(TABLE_SESSIONS,
                    "$COL_SESSION_ID IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map(Long::toString).toTypedArray())
            }
            db.setTransactionSuccessful()
            deleted
        } finally { db.endTransaction() }
    }

    fun getSessionPackages(sessionIds: Collection<Long>): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        for (chunk in sessionIds.filter { it > 0L }.distinct().chunked(500)) {
            readableDatabase.query(TABLE_SESSIONS, arrayOf(COL_SESSION_ID, COL_PKG),
                "$COL_SESSION_ID IN (${chunk.joinToString(",") { "?" }})",
                chunk.map(Long::toString).toTypedArray(), null, null, null).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getLong(0)] = cursor.getString(1)
            }
        }
        return result
    }

    /**
     * 删除指定包名的所有会话
     */
    fun deleteAllSessionsByPackage(pkg: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            markDeleted(db, "$COL_PKG = ?", arrayOf(pkg))
            val deleted = db.delete(TABLE_SESSIONS, "$COL_PKG = ?", arrayOf(pkg))
            db.setTransactionSuccessful()
            deleted
        } finally { db.endTransaction() }
    }

    /**
     * 对已有数据库执行一次保留策略。所有应用合计保留最近 10 次校准；
     * threads 通过外键级联删除，避免长期校准后数据库无限增长。
     */
    fun pruneHistory(): Int {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val deleted = pruneGlobalHistory(db)
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 获取指定包名所有会话的 epoch 集合(用于导入去重)
     */
    fun getEpochsByPackage(pkg: String): List<Long> {
        val db = readableDatabase
        val epochs = mutableListOf<Long>()
        val cursor = db.rawQuery(
            "SELECT $COL_EPOCH FROM $TABLE_SESSIONS WHERE $COL_PKG = ?",
            arrayOf(pkg)
        )
        cursor.use {
            while (it.moveToNext()) {
                epochs.add(it.getLong(0))
            }
        }
        return epochs
    }

    /**
     * 获取所有有历史记录的包名, 最近生成过记录的应用排在前面。
     */
    fun getPackagesWithHistory(): List<PackageInfo> {
        val db = readableDatabase
        val packages = mutableListOf<PackageInfo>()
        val cursor = db.rawQuery(
            """
            SELECT $COL_PKG, MAX($COL_EPOCH) AS last_time, COUNT(*) AS session_count
            FROM $TABLE_SESSIONS
            GROUP BY $COL_PKG
            ORDER BY last_time DESC
            """.trimIndent(),
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                packages.add(
                    PackageInfo(
                        pkg = it.getString(0),
                        lastTime = it.getLong(1),
                        sessionCount = it.getInt(2)
                    )
                )
            }
        }
        return packages
    }

    /** 创建查询索引和 pkg+epoch 去重索引。 */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pkg ON $TABLE_SESSIONS($COL_PKG)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_pkg_epoch ON $TABLE_SESSIONS($COL_PKG, $COL_EPOCH)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_id ON $TABLE_THREADS($COL_THREAD_SESSION_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_pkg_epoch_desc ON $TABLE_SESSIONS($COL_PKG, $COL_EPOCH DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_threads_session_avg ON $TABLE_THREADS($COL_THREAD_SESSION_ID, $COL_AVG DESC)")
    }

    private fun pruneGlobalHistory(db: SQLiteDatabase): Int {
        val where = """
            $COL_SESSION_ID NOT IN (
                SELECT $COL_SESSION_ID
                FROM $TABLE_SESSIONS
                ORDER BY $COL_EPOCH DESC, $COL_SESSION_ID DESC
                LIMIT $MAX_TOTAL_SESSIONS
            )
            """.trimIndent()
        markDeleted(db, where, emptyArray())
        return db.delete(TABLE_SESSIONS, where, null)
    }

    private fun createDeletionMarkers(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_DELETED (" +
            "$COL_PKG TEXT NOT NULL, $COL_EPOCH INTEGER NOT NULL, PRIMARY KEY ($COL_PKG, $COL_EPOCH))")
    }

    /** Root 确认失败不能让手动删除或已过期的记录重新出现。 */
    private fun markDeleted(db: SQLiteDatabase, where: String, arguments: Array<String>) {
        db.execSQL("INSERT OR IGNORE INTO $TABLE_DELETED ($COL_PKG, $COL_EPOCH) " +
            "SELECT $COL_PKG, $COL_EPOCH FROM $TABLE_SESSIONS WHERE $where", arguments)
    }


}

/**
 * 会话及其线程数据
 */
data class SessionWithThreads(
    val id: Long,
    val pkg: String,
    val epoch: Long,
    val rounds: Int,
    val threads: List<ThreadData>
)

/**
 * 历史列表卡片摘要, 不包含线程 series, 用于快速首屏渲染。
 */
enum class HistorySource { CALIBRATION, AUTO_ALLOCATION }

data class SessionSummary(
    val id: Long,
    val epoch: Long,
    val rounds: Int,
    val threadCount: Int,
    val source: HistorySource = HistorySource.CALIBRATION,
    val startedAtMs: Long = 0L,
    val recordedDurationMs: Long = 0L,
    val recordedEndedAtMs: Long = 0L
) {
    /** 现有校准行保留原来的 500 毫秒采样间隔。 */
    val durationMs: Long get() = recordedDurationMs.takeIf { it > 0L }
        ?: rounds.coerceAtLeast(0).toLong() * 500L
    val endedAtMs: Long get() = recordedEndedAtMs.takeIf { it > 0L }
        ?: if (startedAtMs > 0L && recordedDurationMs > 0L) startedAtMs + recordedDurationMs else epoch * 1000L
}

/**
 * 从守护进程原版 .log 解析出来、准备写入数据库的线程数据。
 */
data class ThreadImport(
    val name: String,
    val avg: Float,
    val max: Float,
    val series: String,
    val details: String = ""
)

/**
 * 线程数据
 */
data class ThreadData(
    val name: String,
    val avg: Float,
    val max: Float,
    val series: String,
    val details: String
)

/**
 * 规则编辑器使用的轻量历史记录，不包含折线序列。
 */
data class RuleHistoryRecord(
    val epoch: Long,
    val name: String,
    val avg: Float,
    val max: Float,
    val details: String
)

/**
 * 包名信息
 */
data class PackageInfo(
    val pkg: String,
    val lastTime: Long,
    val sessionCount: Int
)
