package top.qixia.threads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoHistoryCompressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val folder: File get() = File(temp.root, "auto_history").apply { mkdirs() }
    private fun payload(start: Long = 1000, end: Long = 201000, capture: String? = null) =
        "QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\ncore_events\t1\n" +
            (capture?.let { "capture_id\t$it\nminimum_usage_ms\t180000\nforeground_ms\t9000000\n" } ?: "") +
            "T\t${start + 1000}\t1\t2\t3\t776f726b6572\t0.5\n" +
            "F\t${start + 1000}\t60\t16.7\nM\t${start + 1000}\t0\tpower_w=3.5\tbattery_ma=900\n" +
            "E\t${start + 1000}\t1\t2\t3\t776f726b6572\tassign\tqixia\t0,1\t1\t1\t12\tdemand\n" +
            "end_ms\t$end\nduration_ms\t${end - start}\nsamples\t1\ncore_events_dropped\t0\n"
    private fun plain(name: String = "auto_1000_12_0.log", text: String = payload()): File =
        File(folder, name).apply { writeText(text) }
    private fun gzip(file: File, bytes: ByteArray, append: Boolean = false) {
        GZIPOutputStream(FileOutputStream(file, append)).use { it.write(bytes) }
    }

    @Test fun gzipKeepsIdentityEveryCurveAndCoreEventAndUsesItsPhysicalMemberName() {
        val source = plain()
        val original = AutoHistoryRecord.read(source)!!
        val raw = source.readBytes()
        val compressed = AutoHistoryIo.compress(source) {}
        val restored = AutoHistoryRecord.read(compressed)!!
        assertEquals(original.entry.fileName, restored.entry.fileName)
        assertEquals(original.entry.session, restored.entry.session)
        assertEquals(original.threads, restored.threads)
        assertEquals(original.fps, restored.fps)
        assertEquals(original.metrics, restored.metrics)
        assertEquals(original.coreTimeline, restored.coreTimeline)
        assertEquals(listOf(compressed.name), restored.entry.memberFileNames)
        assertEquals(raw.size.toLong(), restored.entry.expandedBytes)
        assertEquals(original.entry.session.id, AutoHistoryRecord.sessionId(compressed.name))
        assertEquals(raw.toString(Charsets.UTF_8), AutoHistoryIo.reader(compressed).use { it.readText() })
    }

    @Test fun releaseGraceKeepsRealDurationAndDistinctTailProgressInSummaryAndReport() {
        val source = plain(text = payload(capture = "auto_1000_12_0")
            .replace("foreground_ms\t9000000", "foreground_ms\t180001")
            .replace("end_ms\t201000", "F\t195000\t45\t22\nF\t200000\t50\t20\nend_ms\t201000"))
        val direct = AutoHistoryRecord.read(source)!!
        assertEquals(200000L, direct.entry.session.durationMs)
        assertEquals(direct.entry, AutoHistoryRecord.summary(source))
        val selected = AutoHistoryStore.entries(temp.root).single()
        assertEquals(200000L, selected.session.durationMs)
        val record = AutoHistoryStore.record(temp.root, selected.session.id)!!
        assertEquals(200000L, record.entry.session.durationMs)
        val tail = record.fps!!.points.takeLast(2)
        assertTrue(tail[0].progress < tail[1].progress)
        assertTrue(tail.last().progress < 1f)
        assertEquals(180001L, record.entry.foregroundMs)
    }

    @Test fun streamingFpsStatisticsMatchExistingLowFivePercentAndJitter() {
        val values = List(12345) { index -> ((index * 193) % 120001) / 1000f }
        val source = plain(text = payload().replace("F\t2000\t60\t16.7\n", values.mapIndexed { index, fps ->
            "F\t${2000 + index}\t$fps\t16.7\n"
        }.joinToString("")))
        val full = AutoHistoryRecord.read(source)!!
        val summary = AutoHistoryRecord.summary(source)!!
        assertEquals(full.entry, summary)
        val expected = HistoryFpsStore.summary(values, emptyList())
        val actual = summary.fps!!
        assertEquals(expected.samples, actual.samples)
        assertEquals(expected.minimum, actual.minimum, 0f)
        assertEquals(expected.maximum, actual.maximum, 0f)
        assertEquals(expected.average, actual.average, .00001f)
        assertEquals(expected.low5!!, actual.low5!!, .00001f)
        assertEquals(expected.jitter!!, actual.jitter!!, .00001f)
    }

    @Test fun failedMetadataPublishKeepsOriginalAndRetryReusesTheVerifiedArchive() {
        val source = plain()
        val raw = source.readBytes()
        assertTrue(runCatching { AutoHistoryIo.compress(source) { throw IOException("metadata sync failed") } }.isFailure)
        assertArrayEquals(raw, source.readBytes())
        val archive = File(folder, "${source.name}.gz")
        val published = archive.readBytes()
        assertFalse(File(folder, "${source.name}.gz.part").exists())
        assertEquals(1, AutoHistoryStore.entries(temp.root).size)
        assertTrue(AutoHistoryStore.compactHistory(temp.root) {})
        assertFalse(source.exists())
        assertArrayEquals(published, archive.readBytes())
        assertEquals(1, AutoHistoryStore.entries(temp.root).size)
    }

    @Test fun corruptArchiveCannotReplaceAnIntactOriginalAndCrcFailuresAreRejected() {
        val source = plain()
        val original = source.readBytes()
        val archive = File(folder, "${source.name}.gz")
        gzip(archive, original)
        val bytes = archive.readBytes()
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 0x80).toByte()
        archive.writeBytes(bytes)
        assertNull(AutoHistoryRecord.read(archive))
        assertNull(AutoHistoryRecord.summary(archive))
        assertTrue(runCatching { AutoHistoryIo.expandedSize(archive) }.isFailure)
        assertFalse(AutoHistoryStore.compactHistory(temp.root) {})
        assertArrayEquals(original, source.readBytes())
        assertEquals(listOf(source.name), AutoHistoryStore.entries(temp.root).single().memberFileNames)
    }

    @Test fun concatenatedGzipUsesActualExpandedSizeAndRejectsOutputBeyondSixteenMiB() {
        val archive = File(folder, "auto_1000_12_0.log.gz")
        val bytes = payload().toByteArray()
        val split = bytes.size / 2
        gzip(archive, bytes.copyOfRange(0, split))
        gzip(archive, bytes.copyOfRange(split, bytes.size), append = true)
        assertEquals(bytes.size.toLong(), AutoHistoryIo.expandedSize(archive))
        assertNotNull(AutoHistoryRecord.read(archive))
        gzip(archive, ByteArray(9 * 1024 * 1024) { 65 })
        gzip(archive, ByteArray(9 * 1024 * 1024) { 66 }, append = true)
        assertTrue(archive.length() < AutoHistoryRecord.MAX_FILE_BYTES)
        assertTrue(runCatching { AutoHistoryIo.expandedSize(archive) }.isFailure)
        assertNull(AutoHistoryRecord.summary(archive))
    }

    @Test fun mixedPlainAndCompressedCopiesAreOneSegmentAndDeletionRemovesBoth() {
        val source = plain()
        val archive = File(folder, "${source.name}.gz")
        gzip(archive, source.readBytes())
        val entry = AutoHistoryStore.entries(temp.root).single()
        assertEquals(1, AutoHistoryStore.record(temp.root, entry.session.id)!!.coreTimeline.events.size)
        assertEquals(setOf(entry.session.id), AutoHistoryStore.deleteSessions(temp.root, listOf(entry.session.id)) { false })
        assertFalse(source.exists()); assertFalse(archive.exists())
        assertTrue(File(folder, "${source.name}.deleted").exists())
        gzip(archive, payload().toByteArray())
        assertTrue(AutoHistoryStore.entries(temp.root).isEmpty())
    }

    @Test fun compressedRetentionKeepsSevenVisitsAndTheirStableIds() {
        repeat(8) { index ->
            val start = 1000L + index * 300000L
            plain("auto_${start}_12_0.log", payload(start, start + 200000))
        }
        val before = AutoHistoryStore.entries(temp.root).map { it.session.id }
        assertTrue(AutoHistoryStore.compactHistory(temp.root) {})
        assertEquals(before, AutoHistoryStore.entries(temp.root).map { it.session.id })
        AutoHistoryStore.pruneHistory(temp.root) { false }
        val retained = AutoHistoryStore.entries(temp.root)
        assertEquals(before.take(7), retained.map { it.session.id })
        assertTrue(retained.all { it.memberFileNames.single().endsWith(".log.gz") })
    }

    @Test fun longVisitsUseWholeSegmentWindowsWithoutDroppingOrRepeatingAnyData() {
        val capture = "auto_1000_12_0"
        val padding = "X\t" + "x".repeat(8188) + "\n"
        val names = mutableListOf<String>()
        repeat(5) { index ->
            val start = 1000L + index * 200000L
            val file = File(folder, "auto_${start}_12_0.log.gz")
            names += file.name
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { output ->
                // 完整有效的物理分片，每片略大于 14 MiB。
                val body = payload(start, start + 200000, capture)
                output.write(body.substringBefore("end_ms\t"))
                repeat(1793) { output.write(padding) }
                output.write("end_ms\t${start + 200000}\nduration_ms\t200000\nsamples\t1\n")
            }
        }
        val entry = AutoHistoryStore.entries(temp.root).single()
        assertEquals(5, entry.fps!!.samples)
        assertTrue(entry.expandedBytes > 64L * 1024 * 1024)
        assertNull(AutoHistoryRecord.read(names.map { File(folder, it) }, "$capture.log"))
        val windows = AutoHistoryStore.reportWindows(temp.root, entry.session.id)
        assertEquals(5, windows.size)
        assertEquals(names, windows.flatMap { it.memberFileNames })
        windows.forEach { window ->
            assertTrue(window.memberFileNames.sumOf { AutoHistoryIo.expandedSize(File(folder, it)) } <= 16L * 1024 * 1024)
            val record = AutoHistoryStore.record(temp.root, entry.session.id, window.index)!!
            assertEquals(entry.session.id, record.entry.session.id)
            assertEquals(window.startMs, record.entry.session.startedAtMs)
            assertEquals(window.endMs - window.startMs, record.entry.session.durationMs)
            assertEquals(window.memberFileNames.size, record.coreTimeline.events.size)
            assertEquals(window.memberFileNames.size, record.fps!!.samples)
            assertTrue(record.coreTimeline.events.first().timestampMs > window.startMs)
        }
        assertNull(AutoHistoryStore.record(temp.root, entry.session.id, windows.size))
    }

    @Test fun measuredK70VolumePartitionsFromMetadataWithoutInflatingArchives() {
        // 仅用元数据复现实测的 48539867 字节记录及其 16 个物理成员；
        // 解码内容已由上面的测试覆盖。
        val total = 48_539_867L
        val members = List(16) { index ->
            val start = 1000L + index * 180000L
            val name = "auto_${start}_12_0.log.gz"
            AutoHistoryRecord.Entry(AutoHistoryIo.canonicalName(name), "app.one",
                SessionSummary(AutoHistoryRecord.sessionId(name), (start + 180000) / 1000, 1, 1,
                    HistorySource.AUTO_ALLOCATION, start, 180000, start + 180000), null,
                memberFileNames = listOf(name),
                expandedBytes = total / 16 + if (index < total % 16) 1 else 0)
        }
        assertEquals(total, members.sumOf { it.expandedBytes })
        val sizes = members.associate { it.memberFileNames.single() to it.expandedBytes }
        val windows = AutoHistoryStore.reportWindowsForMembers(members)
        assertEquals(4, windows.size)
        assertEquals(members.flatMap { it.memberFileNames }, windows.flatMap { it.memberFileNames })
        windows.forEach { window ->
            assertTrue(window.memberFileNames.sumOf { sizes.getValue(it) } <= 16L * 1024 * 1024)
        }
    }

    @Test fun summaryAcceptsFourHundredSegmentsWithoutDecodingCoreOrMetricArrays() {
        val capture = "auto_1000_12_0"
        val files = (0 until 400).map { index ->
            val start = 1000L + index * 2000
            plain("auto_${start}_12_0.log", payload(start, start + 2000, capture))
        }
        val summary = AutoHistoryRecord.summary(files, "$capture.log")!!
        assertEquals(400, summary.memberFileNames.size)
        assertEquals(400, summary.fps!!.samples)
        assertEquals(60f, summary.fps!!.average, 0f)
        assertEquals(1, summary.session.threadCount)
    }
}
