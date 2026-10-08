package top.qixia.threads.compose

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryExportText {
    fun session(pkg: String, label: String, session: SessionSummary, threads: List<ThreadData>): String = buildString {
        appendSession(this, pkg, label, session, threads)
    }

    fun appendSession(output: Appendable, pkg: String, label: String,
        session: SessionSummary, threads: List<ThreadData>) = with(output) {
        appendLine("QixiaThreads ${HistoryPresentation.sourceLabel(session.source)}")
        appendLine("应用: $label")
        appendLine("包名: $pkg")
        appendLine("时间: ${session.epoch}")
        appendLine("会话 ID: ${session.id}")
        appendLine("记录类型: ${HistoryPresentation.sourceLabel(session.source)}")
        appendLine("采集时长 (ms): ${session.durationMs}")
        appendLine("采样轮数: ${session.rounds}")
        appendLine("负载记录数: ${threads.size}")
        appendLine("本节为 CPU 负载记录，不是实时 FPS。")
        for (thread in threads) {
            appendLine()
            appendLine("名称: ${thread.name}")
            appendLine(String.format(Locale.US, "AVG: %.1f%%   MAX: %.1f%%", thread.avg, thread.max))
            append("曲线: ").append(thread.series).appendLine()
            if (thread.details.isNotBlank()) append("子线程明细: ").append(thread.details).appendLine()
        }
    }
}

/** 创建用户请求的新导出文件；失败时只删除本次创建的待提交记录。 */
internal fun exportText(context: Context, prefix: String, text: String): String {
    return exportText(context, prefix) { output -> output.write(text) }
}

/** 大报告只保留当前解析区间和较小的 UTF-8 缓冲区，限制内存占用。 */
internal fun exportText(context: Context, prefix: String, write: (Writer) -> Unit): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    val fileName = "${safePrefix}_$stamp.txt"
    val directory = "${Environment.DIRECTORY_DOWNLOADS}/QixiaThreads"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, directory)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建导出文件")
    try {
        val stream = resolver.openOutputStream(uri) ?: error("无法写入导出文件")
        stream.writer(Charsets.UTF_8).buffered(32 * 1024).use(write)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0) {
            "无法完成导出文件"
        }
    } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
    return "$directory/$fileName"
}
