package top.qixia.threads.compose

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 一次有界读取原子替换的 Rust 摘要，不执行 Root 命令或导入历史。 */
internal object RecentUsageReader {
    const val MAX_BYTES = 64 * 1024
    const val MAX_PACKAGES = 24
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+(?:@[0-9]+)?")
    data class Entry(val packageName: String, val mode: HomeRecordMode, val startedAtMs: Long,
        val endedAtMs: Long, val fps: HomeFpsSummary?, val samples: Long)

    fun read(filesDir: File, nowMs: Long = System.currentTimeMillis()): List<Entry> = runCatching {
        val file = File(filesDir, "capture/recent_usage.tsv")
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        // 不规范的写入方可能原地扩展文件，因此仅检查大小不够。
        // 读取本身也限制长度，并拒绝被截断的 UTF-8 内容。
        val bytes = ByteArray(MAX_BYTES + 1)
        val size = file.inputStream().use { input ->
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                if (count == 0) continue
                size += count
            }
            size
        }
        if (size > MAX_BYTES) return emptyList()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        parse(text, nowMs)
    }.getOrDefault(emptyList())

    internal fun parse(text: String, nowMs: Long): List<Entry> {
        if (text.length > MAX_BYTES || !text.endsWith('\n')) return emptyList()
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull() != "# qixia_recent_usage=1") return emptyList()
        val rows = lines.drop(1).filter { it.isNotEmpty() }
        if (rows.size > MAX_PACKAGES) return emptyList()
        val latest = linkedMapOf<String, Entry>()
        for (row in rows) {
            if (row.length > 1024) continue
            val fields = row.split('\t')
            if (fields.size != 7 || fields[0].length > 255 || !packagePattern.matches(fields[0])) continue
            val mode = when (fields[1]) { "auto" -> HomeRecordMode.AUTOMATIC; "rules" -> HomeRecordMode.RULES; else -> continue }
            val start = fields[2].toLongOrNull() ?: continue
            val end = fields[3].toLongOrNull() ?: continue
            if (start <= 0L || end < start || end > nowMs.coerceAtMost(Long.MAX_VALUE - 300000L) + 300000L) continue
            // 在按包名去重前排除短会话，避免覆盖同包上一次有效记录。
            if (!HomeRecordSelector.hasRecordableDuration(start, end)) continue
            val samples = fields[6].toLongOrNull()?.takeIf { it >= 0 } ?: continue
            val fps = if (samples == 0L) {
                if (fields[4] != "-" || fields[5] != "-") continue
                null
            } else {
                val average = fields[4].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1000f } ?: continue
                val peak = fields[5].toFloatOrNull()?.takeIf { it.isFinite() && it in average..1000f } ?: continue
                HomeFpsSummary(average, start, end, peak)
            }
            val entry = Entry(fields[0], mode, start, end, fps, samples)
            val old = latest[entry.packageName]
            if (old == null || end > old.endedAtMs || (end == old.endedAtMs && start >= old.startedAtMs)) {
                latest[entry.packageName] = entry
            }
        }
        return latest.values.sortedWith(compareByDescending<Entry> { it.endedAtMs }.thenByDescending { it.startedAtMs })
    }
}
