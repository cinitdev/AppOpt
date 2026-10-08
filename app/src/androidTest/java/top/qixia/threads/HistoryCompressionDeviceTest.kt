package top.qixia.threads

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 需显式启用：无损转换已完成的归档，不清理或删除记录。 */
class HistoryCompressionDeviceTest {
    private fun payloadHash(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val raw = file.inputStream().buffered(64 * 1024)
        (if (file.name.endsWith(".gz")) GZIPInputStream(raw, 64 * 1024) else raw).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun archiveMigrationKeepsEveryRecordedByteAndVisit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compress_history") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filesDir = instrumentation.targetContext.filesDir
        val directory = File(filesDir, "auto_history")
        fun files() = directory.listFiles().orEmpty().filter { AutoHistoryRecord.fileName.matches(it.name) }
        fun hashes() = files().associate { AutoHistoryIo.canonicalName(it.name) to payloadHash(it) }
        val original = files()
        assumeTrue(original.isNotEmpty())
        val beforeHashes = hashes()
        val beforeSummaries = AutoHistoryStore.entries(filesDir).associate { it.session.id to (it.session to it.fps) }
        val beforeBytes = original.sumOf { it.length() }
        val started = SystemClock.elapsedRealtime()
        assertTrue("Incomplete migration must preserve its source", AutoHistoryStore.compactHistory(filesDir))
        val elapsed = SystemClock.elapsedRealtime() - started
        assertEquals("Every original payload must survive byte-for-byte", beforeHashes, hashes())
        assertEquals(beforeSummaries, AutoHistoryStore.entries(filesDir).associate { it.session.id to (it.session to it.fps) })
        assertTrue(files().all { it.name.endsWith(".log.gz") })
        val afterBytes = files().sumOf { it.length() }
        if (original.any { it.name.endsWith(".log") }) assertTrue(afterBytes < beforeBytes)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "COMPRESSED files=${files().size} before=$beforeBytes after=$afterBytes ms=$elapsed hashes=${beforeHashes.size} unchanged\n")
        })
    }
}
