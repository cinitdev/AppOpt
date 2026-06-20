package top.suto.appopt.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.DataFormatException

/**
 * AppOpt 数据库助手 (原生 SQLite)
 * 支持 Android 12-16 (API 31-36)
 */
class AppOptDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "appopt.db"
        private const val DATABASE_VERSION = 4  // v4: 会话按 pkg+epoch 去重, 导入事务化

        // 表名
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_THREADS = "threads"

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

        @Volatile
        private var instance: AppOptDbHelper? = null

        fun getInstance(context: Context): AppOptDbHelper {
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
                instance ?: AppOptDbHelper(appContext).also { instance = it }
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
            val bos = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                bos.write(buffer, 0, count)
            }
            deflater.end()
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
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
                val bos = ByteArrayOutputStream(input.size * 2)
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        throw DataFormatException("Inflater 无进展")
                    }
                    bos.write(buffer, 0, count)
                }
                if (!inflater.finished()) "" else bos.toString(Charsets.UTF_8.name())
            } catch (e: Exception) {
                android.util.Log.e("AppOpt", "解压 series 失败(数据可能损坏): ${e.message}")
                ""  // 返回空字符串,避免崩溃
            } finally {
                inflater.end()
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 创建 sessions 表
        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PKG TEXT NOT NULL,
                $COL_EPOCH INTEGER NOT NULL,
                $COL_ROUNDS INTEGER NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """)

        // 创建 threads 表
        db.execSQL("""
            CREATE TABLE $TABLE_THREADS (
                $COL_THREAD_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_THREAD_SESSION_ID INTEGER NOT NULL,
                $COL_NAME TEXT NOT NULL,
                $COL_AVG REAL NOT NULL,
                $COL_MAX REAL NOT NULL,
                $COL_SERIES TEXT NOT NULL,
                FOREIGN KEY($COL_THREAD_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE
            )
        """)

        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // V1 -> V2: 压缩所有 series 字段
            android.util.Log.d("AppOpt", "升级数据库 v$oldVersion -> v$newVersion: 压缩 series 字段")
            val cursor = db.query(TABLE_THREADS, arrayOf(COL_THREAD_ID, COL_SERIES), null, null, null, null, null)
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val oldSeries = it.getString(1)
                    // 如果不是 Base64 格式(含 =+/ 或纯字母数字)则视为未压缩,执行压缩
                    if (oldSeries.contains(',')) {  // 旧格式含逗号
                        val compressed = compressSeries(oldSeries)
                        val cv = ContentValues().apply { put(COL_SERIES, compressed) }
                        db.update(TABLE_THREADS, cv, "$COL_THREAD_ID = ?", arrayOf(id.toString()))
                    }
                }
            }
            android.util.Log.d("AppOpt", "数据库升级完成")
        }
        if (oldVersion < 3) {
            android.util.Log.d("AppOpt", "数据库版本 v$oldVersion -> v$newVersion: 历史序号功能已移除, 无需迁移")
        }
        if (oldVersion < 4) {
            android.util.Log.d("AppOpt", "升级数据库 v$oldVersion -> v$newVersion: 增加会话去重约束")
            removeDuplicateSessions(db)
            createIndexes(db)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * 插入会话并返回 ID
     */
    fun insertSession(pkg: String, epoch: Long, rounds: Int): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PKG, pkg)
            put(COL_EPOCH, epoch)
            put(COL_ROUNDS, rounds)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_SESSIONS, null, values)
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
                    }
                    val threadId = db.insert(TABLE_THREADS, null, threadValues)
                    check(threadId != -1L) { "插入线程数据失败: ${thread.name}" }
                }
                db.setTransactionSuccessful()
                true
            }
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 插入线程数据 (series 自动压缩)
     */
    fun insertThread(sessionId: Long, name: String, avg: Float, max: Float, series: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_THREAD_SESSION_ID, sessionId)
            put(COL_NAME, name)
            put(COL_AVG, avg)
            put(COL_MAX, max)
            put(COL_SERIES, compressSeries(series))  // 压缩后存储
        }
        db.insert(TABLE_THREADS, null, values)
    }

    /**
     * 获取指定包名的所有会话(按记录生成时间降序)。
     */
    fun getSessionsByPackage(pkg: String, preserveOriginalThreadOrder: Boolean = false): List<SessionWithThreads> {
        val db = readableDatabase
        val sessions = mutableListOf<SessionWithThreads>()

        // 查询 sessions
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_PKG = ?",
            arrayOf(pkg),
            null, null,
            "$COL_EPOCH DESC, $COL_SESSION_ID DESC"  // 按记录生成时间降序
        )

        cursor.use {
            while (it.moveToNext()) {
                val sessionId = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_ID))
                val epoch = it.getLong(it.getColumnIndexOrThrow(COL_EPOCH))
                val rounds = it.getInt(it.getColumnIndexOrThrow(COL_ROUNDS))

                // 查询该 session 的所有线程
                val threads = getThreadsBySessionId(sessionId, preserveOriginalThreadOrder)

                sessions.add(SessionWithThreads(sessionId, pkg, epoch, rounds, threads))
            }
        }

        return sessions
    }

    /**
     * 获取指定包名的会话概要(不读取线程 series), 用于历史页首屏快速渲染。
     * 显示顺序按记录生成时间(epoch)倒序, 不受重新导入影响。
     */
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
        val orderBy = if (preserveOriginalOrder) {
            "$COL_THREAD_ID ASC"  // 原版 .log 导出需要保持导入时的线程顺序
        } else {
            "$COL_AVG DESC"       // 历史详情 UI 按平均负载降序显示
        }

        val cursor = db.query(
            TABLE_THREADS,
            null,
            "$COL_THREAD_SESSION_ID = ?",
            arrayOf(sessionId.toString()),
            null, null,
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
                        series = decompressSeries(compressedSeries)  // 解压后返回
                    )
                )
            }
        }

        return threads
    }

    /**
     * 删除指定会话(级联删除线程)
     */
    fun deleteSession(sessionId: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_SESSIONS, "$COL_SESSION_ID = ?", arrayOf(sessionId.toString()))
    }

    /**
     * 删除指定包名的所有会话
     */
    fun deleteAllSessionsByPackage(pkg: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_SESSIONS, "$COL_PKG = ?", arrayOf(pkg))
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
     * 检查是否有指定包名的记录
     */
    fun hasSessionsForPackage(pkg: String): Boolean {        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SESSIONS WHERE $COL_PKG = ?",
            arrayOf(pkg)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0) > 0
            }
        }
        return false
    }

    /**
     * 获取所有有记录的包名, 最近生成过记录的应用排在前面。
     */
    fun getPackagesWithHistory(): List<PackageInfo> {
        val db = readableDatabase
        val packages = mutableListOf<PackageInfo>()

        val cursor = db.rawQuery("""
            SELECT $COL_PKG, MAX($COL_EPOCH) as last_time
            FROM $TABLE_SESSIONS
            GROUP BY $COL_PKG
            ORDER BY last_time DESC
        """, null)

        cursor.use {
            while (it.moveToNext()) {
                packages.add(
                    PackageInfo(
                        pkg = it.getString(0),
                        lastTime = it.getLong(1)
                    )
                )
            }
        }

        return packages
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pkg ON $TABLE_SESSIONS($COL_PKG)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_pkg_epoch ON $TABLE_SESSIONS($COL_PKG, $COL_EPOCH)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_id ON $TABLE_THREADS($COL_THREAD_SESSION_ID)")
    }

    private fun removeDuplicateSessions(db: SQLiteDatabase) {
        db.execSQL("""
            DELETE FROM $TABLE_SESSIONS
            WHERE $COL_SESSION_ID NOT IN (
                SELECT keep_id FROM (
                    SELECT MAX($COL_SESSION_ID) AS keep_id
                    FROM $TABLE_SESSIONS
                    GROUP BY $COL_PKG, $COL_EPOCH
                )
            )
        """.trimIndent())
        db.execSQL("""
            DELETE FROM $TABLE_THREADS
            WHERE $COL_THREAD_SESSION_ID NOT IN (
                SELECT $COL_SESSION_ID FROM $TABLE_SESSIONS
            )
        """.trimIndent())
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

data class SessionSummary(
    val id: Long,
    val epoch: Long,
    val rounds: Int,
    val threadCount: Int
)

data class ThreadImport(
    val name: String,
    val avg: Float,
    val max: Float,
    val series: String
)

/**
 * 线程数据
 */
data class ThreadData(
    val name: String,
    val avg: Float,
    val max: Float,
    val series: String  // 逗号分隔
)

/**
 * 包名信息
 */
data class PackageInfo(
    val pkg: String,
    val lastTime: Long
)
