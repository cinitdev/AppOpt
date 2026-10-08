package top.qixia.threads.compose

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentUsageReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val header = "# qixia_recent_usage=1\n"
    private fun file() = File(temp.root, "capture/recent_usage.tsv").also { it.parentFile!!.mkdirs() }

    @Test fun readsNativeModesAndKeepsLatestPerPackageWithoutInventingMissingFps() {
        file().writeText(header + "app.one\tauto\t1000\t203000\t50\t60\t2\n" +
            "app.two\trules\t4000\t205000\t-\t-\t0\n" + "app.one\tauto\t6000\t207000\t0\t0\t1\n")
        val entries = RecentUsageReader.read(temp.root, 300000)
        assertEquals(listOf("app.one", "app.two"), entries.map { it.packageName })
        assertEquals(0f, entries.first().fps!!.average, .001f)
        assertNull(entries.last().fps)
        assertEquals(HomeRecordMode.RULES, entries.last().mode)
    }

    @Test fun rejectsInvalidRowsWithoutBorrowingOrClampingTheirValues() {
        val invalid = listOf(
            "app.one\tcalibration\t1000\t203000\t50\t60\t1",
            "../escape\tauto\t1000\t203000\t50\t60\t1",
            "app.one\tauto\t204000\t203000\t50\t60\t1",
            "app.one\tauto\t1000\t203000\tNaN\t60\t1",
            "app.one\tauto\t1000\t203000\t50\tInfinity\t1",
            "app.one\tauto\t1000\t203000\t80\t60\t1",
            "app.one\tauto\t1000\t203000\t50\t60\t0",
            "app.one\tauto\t1000\t203000\t-\t60\t1",
            "app.one\tauto\t1000\t203000\t-\t-\t-1",
            "app.one\tauto\t1000\t9223372036854775807\t-\t-\t0")
        file().writeText(header + invalid.joinToString("\n", postfix = "\n") + "app.good\trules\t1000\t203000\t-\t-\t0\n")
        assertEquals(listOf("app.good"), RecentUsageReader.read(temp.root, 300000).map { it.packageName })
    }

    @Test fun boundedReaderRejectsOversizedInvalidUtf8PartialAndForeignFiles() {
        assertTrue(RecentUsageReader.read(temp.root).isEmpty())
        val target = file()
        for (text in listOf("# other=1\n", header + "x".repeat(RecentUsageReader.MAX_BYTES),
            header + "app.one\tauto\t1000\t203000\t50\t60\t1")) {
            target.writeText(text)
            assertTrue(RecentUsageReader.read(temp.root, 300000).isEmpty())
            assertEquals(text, target.readText())
        }
        target.writeBytes(header.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28, 0x0a))
        assertTrue(RecentUsageReader.read(temp.root, 300000).isEmpty())
    }

    @Test fun packageAndFileLimitsRemainBoundedAcrossAtomicReplacement() {
        val target = file()
        val rows = (1..24).joinToString("\n", postfix = "\n") { "app.$it\trules\t1000\t203000\t-\t-\t0" }
        target.writeText(header + rows)
        assertEquals(24, RecentUsageReader.read(temp.root, 300000).size)
        val replacement = File(target.parentFile, "recent_usage.tmp")
        replacement.writeText(header + "app.next\tauto\t4000\t205000\t55\t60\t1\n")
        java.nio.file.Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals("app.next", RecentUsageReader.read(temp.root, 300000).single().packageName)
        target.writeText(header + rows + "app.25\trules\t1000\t203000\t-\t-\t0\n")
        assertTrue(RecentUsageReader.read(temp.root, 300000).isEmpty())
    }

    @Test fun threeMinuteBoundaryIsAppliedBeforePackageDeduplicationWithoutChangingTheFile() {
        val target = file()
        val text = header +
            "app.one\tauto\t1000\t201000\t55\t60\t2\n" +
            "app.one\tauto\t300000\t480000\t100\t120\t2\n" +
            "app.short\trules\t1000\t180999\t-\t-\t0\n" +
            "app.exact\trules\t1000\t181000\t-\t-\t0\n" +
            "app.long\trules\t1000\t181001\t-\t-\t0\n"
        target.writeText(text)
        val entries = RecentUsageReader.read(temp.root, 500000)
        assertEquals(listOf("app.one", "app.long"), entries.map { it.packageName })
        assertEquals(201000L, entries.first().endedAtMs)
        assertEquals(55f, entries.first().fps!!.average, .001f)
        assertEquals(text, target.readText())
    }
}
