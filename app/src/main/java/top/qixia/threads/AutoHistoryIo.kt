package top.qixia.threads

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 仅改变存储容器，带版本的记录内容逐字节保持不变。 */
internal object AutoHistoryIo {
    private const val BUFFER_SIZE = 64 * 1024
    const val MAX_ARCHIVE_BYTES = AutoHistoryRecord.MAX_FILE_BYTES + BUFFER_SIZE
    fun canonicalName(name: String): String = name.removeSuffix(".gz")

    private fun input(file: File, compressed: Boolean = file.name.endsWith(".gz")): LimitedInput {
        require(file.isFile && file.length() in 1..MAX_ARCHIVE_BYTES)
        val raw = file.inputStream().buffered(BUFFER_SIZE)
        val decoded = try { if (compressed) GZIPInputStream(raw, BUFFER_SIZE) else raw }
            catch (error: Exception) { raw.close(); throw error }
        return LimitedInput(decoded, AutoHistoryRecord.MAX_FILE_BYTES)
    }

    fun reader(file: File): BufferedReader = input(file).reader(Charsets.UTF_8).buffered(BUFFER_SIZE)

    fun scan(file: File, consume: (String) -> Unit): Long {
        val stream = input(file)
        stream.reader(Charsets.UTF_8).buffered(BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                consume(line)
            }
        }
        return stream.count
    }

    /** ISIZE 只描述最后一个 gzip 成员，需读至 EOF 并验证 CRC 来统计实际输出。 */
    fun expandedSize(file: File): Long = input(file).use { stream ->
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) { val read = stream.read(buffer); if (read < 0) break; size += read }
        size.also { require(it > 0) }
    }

    private class LimitedInput(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        var count = 0L
            private set
        override fun read(): Int = `in`.read().also { if (it >= 0) checkSize(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt()).also {
                if (it > 0) checkSize(it)
            }
        private fun checkSize(amount: Int) { count += amount; require(count <= limit) { "记录展开后超过单段限制" } }
    }

    private data class Digest(val size: Long, val bytes: ByteArray)
    private fun digest(file: File, compressed: Boolean = file.name.endsWith(".gz")): Digest =
        input(file, compressed).use { stream ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var size = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                size += read; hash.update(buffer, 0, read)
            }
            Digest(size, hash.digest())
        }

    /** 只有全部解码字节一致，才可复用中断的转换结果。 */
    fun sameContent(first: File, second: File): Boolean {
        val a = digest(first); val b = digest(second)
        return a.size == b.size && a.bytes.contentEquals(b.bytes)
    }

    /** 删除原文件前，需在回调中发布元数据并同步目录。 */
    fun compress(source: File, beforeRemoveOriginal: (File) -> Unit): File {
        require(source.name.endsWith(".log") && source.length() in 1..AutoHistoryRecord.MAX_FILE_BYTES)
        val target = File(source.parentFile, "${source.name}.gz")
        val pending = File(source.parentFile, "${source.name}.gz.part")
        try {
            if (target.exists()) {
                check(sameContent(source, target)) { "压缩记录与原文不一致，保留原文" }
            } else {
                val original = digest(source)
                FileOutputStream(pending).use { output ->
                    val gzip = object : GZIPOutputStream(output, BUFFER_SIZE) { init { def.setLevel(1) } }
                    try {
                        input(source).use { it.copyTo(gzip, BUFFER_SIZE) }
                        gzip.finish(); gzip.flush(); output.fd.sync()
                    } finally { gzip.close() }
                }
                val restored = digest(pending, compressed = true)
                val unchanged = digest(source)
                check(original.size == restored.size && original.bytes.contentEquals(restored.bytes) &&
                    original.size == unchanged.size && original.bytes.contentEquals(unchanged.bytes))
                check(pending.renameTo(target)) { "无法发布压缩记录" }
            }
            beforeRemoveOriginal(target)
            check(source.delete()) { "压缩完成，原始副本待清理" }
            return target
        } finally { pending.delete() }
    }
}
