package top.suto.appopt.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

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
        private const val DATABASE_VERSION = 4

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

        /**
         * 获取数据库单例。
         *
         * 如果用户手动删除了数据库文件, 这里会关闭旧连接并重建, 避免继续拿着已经
         * 指向旧 inode 的 SQLite 句柄, 导致导入成功但历史页读不到新数据。
         */
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
                android.util.Log.e("AppOpt", "decompress series failed: ${e.message}")
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
                FOREIGN KEY($COL_THREAD_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // V1 -> V2: 旧库里 series 是逗号分隔明文, 升级时压缩成 Deflate+Base64。
            android.util.Log.d("AppOpt", "upgrade db v$oldVersion -> v$newVersion: compress series")
            val cursor = db.query(TABLE_THREADS, arrayOf(COL_THREAD_ID, COL_SERIES), null, null, null, null, null)
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val oldSeries = it.getString(1)
                    // 旧格式包含逗号; 新格式是 Base64 压缩串。
                    if (oldSeries.contains(',')) {
                        val values = ContentValues().apply { put(COL_SERIES, compressSeries(oldSeries)) }
                        db.update(TABLE_THREADS, values, "$COL_THREAD_ID = ?", arrayOf(id.toString()))
                    }
                }
            }
            android.util.Log.d("AppOpt", "db upgrade complete")
        }
        if (oldVersion < 3) {
            // V3 移除了“第几次”的永久编号, 历史列表直接按生成时间排序。
            android.util.Log.d("AppOpt", "db v$oldVersion -> v$newVersion: session index removed, no migration needed")
        }
        if (oldVersion < 4) {
            // V4 增加 pkg+epoch 唯一约束, 升级前先清理旧库里可能存在的重复会话。
            android.util.Log.d("AppOpt", "upgrade db v$oldVersion -> v$newVersion: add session de-duplication")
            removeDuplicateSessions(db)
            createIndexes(db)
        }
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
                    check(threadId != -1L) { "insert thread failed: ${thread.name}" }
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
                        series = decompressSeries(compressedSeries)
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
     * 获取所有有历史记录的包名, 最近生成过记录的应用排在前面。
     */
    fun getPackagesWithHistory(): List<PackageInfo> {
        val db = readableDatabase
        val packages = mutableListOf<PackageInfo>()
        val cursor = db.rawQuery(
            """
            SELECT $COL_PKG, MAX($COL_EPOCH) AS last_time
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
                        lastTime = it.getLong(1)
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
    }

    /**
     * 清理升级前已经存在的重复会话。
     *
     * 同一个 pkg+epoch 只保留最后插入的一条, 再删除失去 session 的线程行,
     * 避免创建唯一索引时失败。
     */
    private fun removeDuplicateSessions(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM $TABLE_SESSIONS
            WHERE $COL_SESSION_ID NOT IN (
                SELECT keep_id FROM (
                    SELECT MAX($COL_SESSION_ID) AS keep_id
                    FROM $TABLE_SESSIONS
                    GROUP BY $COL_PKG, $COL_EPOCH
                )
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            DELETE FROM $TABLE_THREADS
            WHERE $COL_THREAD_SESSION_ID NOT IN (
                SELECT $COL_SESSION_ID FROM $TABLE_SESSIONS
            )
            """.trimIndent()
        )
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
data class SessionSummary(
    val id: Long,
    val epoch: Long,
    val rounds: Int,
    val threadCount: Int
)

/**
 * 从守护进程原版 .log 解析出来、准备写入数据库的线程数据。
 */
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
    val series: String
)

/**
 * 包名信息
 */
data class PackageInfo(
    val pkg: String,
    val lastTime: Long
)
