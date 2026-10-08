package top.qixia.threads

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.security.MessageDigest

/** 用一个 Rust 进程读取传感器和 PMU，su 仅授予权限并执行二进制文件。 */
internal class HistoryRustProbe {
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false
    @Volatile private var aborted = false
    private var reader: BufferedReader? = null
    private var failures = 0
    private var nextAttempt = 0L

    fun read(context: Context): Map<String, String> {
        if (stopped || failures >= 3 || SystemClock.elapsedRealtime() < nextAttempt) return emptyMap()
        return try {
            aborted = false
            if (process == null) {
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it in listOf("arm64-v8a", "x86_64") } ?: return emptyMap()
                val bytes = context.assets.open("history_rust/$abi").use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
                val file = File(context.codeCacheDir, "history-rust-$hash")
                if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
                    file.writeBytes(bytes)
                    check(file.setExecutable(true, true))
                }
                val child = ProcessBuilder("su", "-c", "exec '${file.absolutePath.replace("'", "'\\''")}'")
                    .redirectErrorStream(true).start()
                process = child
                if (stopped || aborted) { abortRead(); return emptyMap() }
                reader = child.inputStream.bufferedReader()
                var ready = false
                for (i in 0 until 16) {
                    val line = reader?.readLine() ?: break
                    check(line.length <= 256)
                    if (line == "QIXIA_METRICS 1") { ready = true; break }
                }
                check(ready)
            }
            val child = process ?: return emptyMap()
            child.outputStream.write("sample\n".toByteArray())
            child.outputStream.flush()
            check(reader?.readLine() == "BEGIN")
            val values = linkedMapOf<String, String>()
            repeat(512) {
                val line = reader?.readLine() ?: error("Probe closed")
                if (line == "END") return values
                check(line.length <= 256 && '|' in line)
                val key = line.substringBefore('|')
                check(key !in values)
                values[key] = line.substringAfter('|')
            }
            error("Probe overflow")
        } catch (_: Exception) {
            abortRead()
            failures++
            nextAttempt = SystemClock.elapsedRealtime() + 60_000L
            emptyMap()
        }
    }
    fun abortRead() {
        aborted = true
        val child = process
        process = null
        runCatching { child?.outputStream?.close() }
        child?.destroy()
        reader = null
    }
    fun stop() { stopped = true; abortRead() }
}
