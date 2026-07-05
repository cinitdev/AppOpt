package top.suto.appopt

import android.content.Context
import top.suto.appopt.db.AppOptDbHelper
import top.suto.appopt.db.ThreadImport

/**
 * 数据同步: 把守护进程写入的 .log 增量导入到数据库。
 *
 * 架构:
 *  - 守护进程(C)持续往 history/<pkg>.log 追加新校准会话
 *  - App 每次进入历史页时调用本类,把 .log 的会话按 epoch 去重导入数据库
 *  - 导入成功后删除 .log 文件，数据库成为唯一数据源
 *  - 数据库主键 id 仅作为内部记录标识; 历史列表按记录生成时间倒序显示
 */
object DatabaseMigrator {
    internal data class ParsedHistorySession(
        val epoch: Long,
        val rounds: Int,
        val threads: List<ThreadImport>
    )

    internal sealed class HistoryParseResult {
        data class Valid(val sessions: List<ParsedHistorySession>) : HistoryParseResult()
        data class Invalid(val reason: String) : HistoryParseResult()
    }

    data class MigrationResult(
        val sourceFound: Boolean,
        val importedSessions: Int,
        val processedClaims: Int,
        val invalidClaim: Boolean,
        val completionFailed: Boolean
    )

    // 按包名加锁,防止同一个包并发导入导致重复
    private val locks = mutableMapOf<String, Any>()

    private fun getLock(pkg: String): Any {
        return synchronized(locks) {
            locks.getOrPut(pkg) { Any() }
        }
    }

    /**
     * 把 .log 的新会话增量导入数据库，导入成功后删除 .log 文件
     */
    fun migrateIfNeeded(context: Context, pkg: String): MigrationResult {
        return synchronized(getLock(pkg)) {  // 同一包名串行执行
            val db = AppOptDbHelper.getInstance(context)

            // 已存在的 epoch 集合(去重用)
            val existingEpochs = db.getEpochsByPackage(pkg).toHashSet()
            android.util.Log.d("AppOpt", "migrate: $pkg 开始,已有 ${existingEpochs.size} 个 epoch")

            var totalImported = 0
            var processedClaims = 0
            var sourceFound = false
            var invalidClaim = false
            var completionFailed = false
            while (processedClaims < MAX_IMPORT_CLAIMS) {
                val raw = DaemonBridge.claimHistoryImport(pkg)
                if (raw.isBlank()) break
                sourceFound = true

                val parsed = parseHistory(raw)
                if (parsed is HistoryParseResult.Invalid) {
                    android.util.Log.w(
                        "AppOpt",
                        "migrate: $pkg 认领文件格式无效，保留文件等待排查: ${parsed.reason}"
                    )
                    invalidClaim = true
                    break
                }

                var imported = 0
                val sessions = (parsed as HistoryParseResult.Valid).sessions
                for (session in sessions) {
                    if (session.epoch in existingEpochs) continue
                    if (db.insertSessionWithThreadsIfAbsent(
                            pkg,
                            session.epoch,
                            session.rounds,
                            session.threads
                        )
                    ) {
                        imported++
                    }
                    existingEpochs.add(session.epoch)
                }
                totalImported += imported
                val completed = DaemonBridge.completeHistoryImport(pkg)
                android.util.Log.d(
                    "AppOpt",
                    "migrate: $pkg 已处理认领文件,新导入 $imported 个会话,删除认领文件=$completed"
                )
                if (!completed) {
                    completionFailed = true
                    break
                }
                processedClaims++
            }
            val result = MigrationResult(
                sourceFound = sourceFound,
                importedSessions = totalImported,
                processedClaims = processedClaims,
                invalidClaim = invalidClaim,
                completionFailed = completionFailed
            )
            android.util.Log.d(
                "AppOpt",
                "migrate: $pkg 完成,找到文件=${result.sourceFound},新导入=${result.importedSessions}," +
                    "已处理文件=${result.processedClaims},无效文件=${result.invalidClaim},清理失败=${result.completionFailed}"
            )
            result
        }  // synchronized 结束
    }

    /**
     * 解析负载记录行: "<AVG> <MAX> <名称>|<p1,p2,...>[|摘要]"
     * 旧日志没有摘要字段时保持兼容。
     */
    internal fun parseHistory(raw: String): HistoryParseResult {
        val sessions = mutableListOf<ParsedHistorySession>()
        val seenEpochs = mutableSetOf<Long>()
        var epoch: Long? = null
        var rounds = 0
        var threads = mutableListOf<ThreadImport>()

        fun flush(): String? {
            val currentEpoch = epoch ?: return null
            if (threads.isEmpty()) return "会话 $currentEpoch 不含负载记录"
            if (!seenEpochs.add(currentEpoch)) return "会话时间戳 $currentEpoch 重复"
            sessions.add(ParsedHistorySession(currentEpoch, rounds, threads.toList()))
            threads = mutableListOf()
            return null
        }

        raw.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                flush()?.let { return HistoryParseResult.Invalid(it) }
                val parts = line.removePrefix("#").trim().split(Regex("\\s+"))
                val parsedEpoch = parts.getOrNull(0)?.toLongOrNull()
                val parsedRounds = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || parsedEpoch == null || parsedEpoch <= 0L ||
                    parsedRounds == null || parsedRounds <= 0
                ) {
                    return HistoryParseResult.Invalid("第 ${index + 1} 行会话头无效")
                }
                epoch = parsedEpoch
                rounds = parsedRounds
            } else {
                if (epoch == null) {
                    return HistoryParseResult.Invalid("第 ${index + 1} 行负载记录缺少会话头")
                }
                val parsed = parseThreadLine(line)
                    ?: return HistoryParseResult.Invalid("第 ${index + 1} 行负载记录无效")
                threads.add(parsed)
            }
        }
        flush()?.let { return HistoryParseResult.Invalid(it) }
        if (sessions.isEmpty()) return HistoryParseResult.Invalid("文件不含会话")
        return HistoryParseResult.Valid(sessions)
    }

    internal fun parseThreadLine(line: String): ThreadImport? {
        val bar = line.indexOf('|')
        if (bar < 0) return null
        val head = line.substring(0, bar)
        val detailsBar = line.indexOf('|', bar + 1)
        val seriesStr = if (detailsBar >= 0) {
            line.substring(bar + 1, detailsBar)
        } else {
            line.substring(bar + 1)
        }
        val details = if (detailsBar >= 0) line.substring(detailsBar + 1).trim() else ""
        val sp1 = head.indexOf(' ')
        if (sp1 <= 0) return null
        val sp2 = head.indexOf(' ', sp1 + 1)
        if (sp2 <= 0) return null
        val avg = head.substring(0, sp1).toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            ?: return null
        val max = head.substring(sp1 + 1, sp2).toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            ?: return null
        val name = head.substring(sp2 + 1).trim()
        if (name.isEmpty()) return null
        val samples = seriesStr.split(',')
        if (samples.isEmpty() || samples.any { sample ->
                sample.trim().toFloatOrNull()?.let { it.isFinite() && it >= 0f } != true
            }
        ) return null
        return ThreadImport(name, avg, max, seriesStr, details)
    }

    private const val MAX_IMPORT_CLAIMS = 8
}
