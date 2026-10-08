package top.qixia.threads

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoHistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun homeUsesOnlyVerifiedMetadataAndNeverParsesOrWritesAnUncachedArchive() {
        val file = run("auto_1000_12_0.log")
        assertTrue(AutoHistoryStore.cachedEntriesForHome(temp.root).isEmpty())
        assertFalse(File(file.parentFile, "${file.name}.meta").exists())
        val verified = AutoHistoryStore.entries(temp.root)
        assertEquals(verified, AutoHistoryStore.cachedEntriesForHome(temp.root))
        val metadata = File(file.parentFile, "${file.name}.meta")
        val original = metadata.readBytes()
        file.appendText("\n")
        assertTrue(AutoHistoryStore.cachedEntriesForHome(temp.root).isEmpty())
        assertArrayEquals(original, metadata.readBytes())
    }

    @Test fun homeDoesNotWaitForTheLargeArchiveImportOrExportLock() {
        run("auto_1000_12_0.log")
        val expected = AutoHistoryStore.entries(temp.root)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            synchronized(AutoHistoryStore) {
                val result = executor.submit<List<AutoHistoryRecord.Entry>> { AutoHistoryStore.cachedEntriesForHome(temp.root) }
                assertEquals(expected, result.get(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun homePreviewRoundTripsAcrossSegmentsWithRealTimesAndMissingSamples() {
        val capture = "auto_1000_12_0"
        val first = segment(capture, "$capture.log", 1000, 201000, 400000)
        val second = segment(capture, "auto_201000_12_0.log", 201000, 401000, 400000)
        val source = linkedMapOf<Long, Float>()
        fun addSamples(file: File, start: Long, count: Int) {
            val lines = (0 until count).map { index ->
                val time = start + index * 1000L
                val fps = 50f + index % 10
                source[time] = fps
                "F\t$time\t$fps\t16.7"
            }
            file.writeText(file.readLines().filterNot { it.startsWith("F\t") }.joinToString("\n") +
                "\n" + lines.joinToString("\n") + "\n")
        }
        addSamples(first, 1000, 100)
        addSamples(second, 201000, 200)
        val entry = AutoHistoryStore.entries(temp.root).single()
        val points = entry.fps!!.points
        assertTrue(points.size in 1..80)
        assertEquals(300, entry.fps!!.samples)
        assertEquals(1000L, points.first().timestampMs)
        assertEquals(400000L, points.last().timestampMs)
        assertTrue(points.all { source[it.timestampMs] == it.fps })
        assertTrue(points.first { it.timestampMs!! >= 201000L }.breakBefore)
        assertEquals(entry, AutoHistoryStore.cachedEntriesForHome(temp.root).single())
        assertEquals(entry, AutoHistoryStore.entries(temp.root).single())
    }

    @Test fun statsOnlyAutoMetadataWaitsForBackgroundUpgradeInsteadOfHomeParsingArchive() {
        val file = run("auto_1000_12_0.log")
        AutoHistoryStore.entries(temp.root)
        val metadata = File(file.parentFile, "${file.name}.meta")
        java.io.RandomAccessFile(metadata, "rw").use { it.writeInt(5) }
        val legacy = metadata.readBytes()
        assertTrue(AutoHistoryStore.cachedEntriesForHome(temp.root).isEmpty())
        assertArrayEquals(legacy, metadata.readBytes())
        val rebuilt = AutoHistoryStore.entries(temp.root).single()
        assertEquals(2000L, rebuilt.fps!!.points.single().timestampMs)
        assertEquals(rebuilt, AutoHistoryStore.cachedEntriesForHome(temp.root).single())
    }

    @Test fun groupedStatsOnlyMetadataIsAlsoReplacedAfterBackgroundUpgrade() {
        val capture = "auto_1000_12_0"
        segment(capture, "$capture.log", 1000, 201000, 400000)
        segment(capture, "auto_201000_12_0.log", 201000, 401000, 400000)
        val expected = AutoHistoryStore.entries(temp.root).single()
        val metadata = File(temp.root, "auto_history").listFiles()!!.filter { it.extension == "meta" }
        assertEquals(3, metadata.size)
        metadata.forEach { file -> java.io.RandomAccessFile(file, "rw").use { it.writeInt(5) } }
        val oldContents = metadata.associateWith { it.readBytes() }
        assertTrue(AutoHistoryStore.cachedEntriesForHome(temp.root).isEmpty())
        oldContents.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(expected, AutoHistoryStore.entries(temp.root).single())
        assertEquals(expected, AutoHistoryStore.cachedEntriesForHome(temp.root).single())
        assertTrue(expected.fps!!.points.any { it.breakBefore })
    }

    @Test fun staleMemberCannotMasqueradeAsACompleteSinglePartHomeReport() {
        val capture = "auto_1000_12_0"
        segment(capture, "$capture.log", 1000, 201000, 400000)
        val second = segment(capture, "auto_201000_12_0.log", 201000, 401000, 400000)
        assertEquals(1, AutoHistoryStore.entries(temp.root).size)
        assertEquals(1, AutoHistoryStore.cachedEntriesForHome(temp.root).size)
        second.appendText("\n")
        assertTrue(AutoHistoryStore.cachedEntriesForHome(temp.root).isEmpty())
    }

    @Test fun identityHeavySmallVisitStaysVisibleAndAllThreadsCanBeOpenedByWindow() {
        val directory = File(temp.root, "auto_history").apply { mkdirs() }
        repeat(3) { index ->
            val start = 1000L + index * 200000L
            File(directory, "auto_${start}_12_0.log").writeText(
                "QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\n" +
                    "minimum_usage_ms\t180000\ncapture_id\tauto_1000_12_0\nforeground_ms\t900000\n" +
                    (1..3500).joinToString("\n") { tid ->
                        "T\t${start + 1000}\t12\t$tid\t${index + 1}\t776f726b6572\t0.5"
                    } + "\nend_ms\t${start + 190000}\nduration_ms\t190000\n")
        }
        val visit = AutoHistoryStore.entries(temp.root).single()
        assertEquals(10500, visit.session.threadCount)
        val windows = AutoHistoryStore.reportWindows(temp.root, visit.session.id)
        assertEquals(2, windows.size)
        assertEquals(10500, windows.sumOf { AutoHistoryStore.record(temp.root, visit.session.id, it.index)!!.threads.size })
        // 重新加载后，元数据缓存仍需保留额外的身份数量预算。
        assertEquals(windows, AutoHistoryStore.reportWindows(temp.root, visit.session.id))
        assertEquals(3, directory.listFiles()!!.count { it.extension == "log" })
    }
    private fun run(name: String, end: Long = 11_000, pkg: String = "app.one"): File {
        val folder = File(temp.root, "auto_history").apply { mkdirs() }
        return File(folder, name).apply { writeText("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\t$pkg\n" +
            "start_ms\t1000\nT\t2000\t1\t2\t3\t776f726b6572\t0.1\nF\t2000\t60\t16.7\n" +
            "end_ms\t$end\nduration_ms\t${end - 1000}\nsamples\t1\n") }
    }

    @Test fun archivesHaveStableIdsAndListSummariesWithoutDroppingSameSecondRuns() {
        run("auto_1000_12_0.log", 10_100)
        run("auto_1001_12_0.log", 10_900)
        val entries = AutoHistoryStore.entries(temp.root)
        assertEquals(2, entries.size)
        assertEquals(10_900L, entries.first().session.endedAtMs)
        assertNotEquals(entries.first().session.id, entries.last().session.id)
        assertTrue(entries.all { it.fps!!.points.size in 1..80 && it.fps!!.average == 60f })
        assertEquals(2, File(temp.root, "auto_history").listFiles()!!.count { it.extension == "meta" })
        assertEquals(1, AutoHistoryStore.record(temp.root, entries.first().session.id)!!.threads.size)
        assertTrue(AutoHistoryStore.record(temp.root, entries.first().session.id)!!.fps!!.points.isNotEmpty())
    }

    @Test fun unfinishedAndDeletedArchivesCannotAppearOrLoadEvenIfRawCopyRemains() {
        val file = run("auto_1000_12_0.log")
        run("auto_1001_12_0.tmp")
        val entry = AutoHistoryStore.entries(temp.root).single()
        File(file.parentFile, "${file.name}.deleted").writeText("1")
        assertTrue(AutoHistoryStore.entries(temp.root).isEmpty())
        assertNull(AutoHistoryStore.record(temp.root, entry.session.id))
    }

    @Test fun packageDeletionWatermarkRetainsRunsCompletedLaterWithinSameSecondAndOtherPackages() {
        run("auto_1000_12_0.log", 10_100)
        run("auto_1001_12_0.log", 10_900)
        run("auto_1002_12_0.log", 10_100, "app.two")
        // 在 10.200 秒删除不能误删 10.900 秒才完成的记录。
        val key = MessageDigest.getInstance("SHA-256").digest("app.one".toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(File(temp.root, "auto_history"), "package_$key.deleted").writeText("10200")
        val entries = AutoHistoryStore.entries(temp.root)
        assertEquals(2, entries.size)
        assertEquals(10_900L, entries.single { it.pkg == "app.one" }.session.endedAtMs)
        assertEquals("app.two", entries.last().pkg)
        assertNull(AutoHistoryStore.record(temp.root, AutoHistoryRecord.sessionId("auto_1000_12_0.log")))
    }

    @Test fun retentionKeepsSevenNewestAcrossPackagesAndPreservesActiveFiles() {
        (0..11).forEach { index -> run("auto_${1000 + index}_12_0.log", 20_000L + index, "app.${index % 3}") }
        val active = run("auto_2000_12_0.tmp", 30_000L)
        val acknowledged = mutableListOf<String>()
        AutoHistoryStore.pruneHistory(temp.root) { names -> acknowledged += names; false }
        val entries = AutoHistoryStore.entries(temp.root)
        assertEquals(7, entries.size)
        assertEquals((20_005L..20_011L).toList().reversed(), entries.map { it.session.endedAtMs })
        assertEquals(5, acknowledged.size)
        assertTrue(active.exists())
        acknowledged.forEach { name ->
            val folder = File(temp.root, "auto_history")
            assertTrue(File(folder, "$name.deleted").isFile)
            assertFalse(File(folder, "$name.meta").exists())
            // 删除失败后，已复制但尚未确认的 Root 文件仍必须隐藏。
            run(name)
        }
        assertEquals(7, AutoHistoryStore.entries(temp.root).size)
    }

    @Test fun batchDeletionOnlyRemovesSelectedRecordsAndSurvivesRootFailure() {
        (0..3).forEach { run("auto_${1000 + it}_12_0.log", 20_000L + it, "app.$it") }
        val entries = AutoHistoryStore.entries(temp.root)
        val selected = setOf(entries[0].session.id, entries[2].session.id)
        val result = AutoHistoryStore.deleteSessions(temp.root, selected + 999L) { false }
        assertEquals(selected, result)
        assertEquals(entries.filterNot { it.session.id in selected }.map { it.session.id },
            AutoHistoryStore.entries(temp.root).map { it.session.id })
        entries.filter { it.session.id in selected }.forEach { entry ->
            run(entry.fileName)
            assertNull(AutoHistoryStore.record(temp.root, entry.session.id))
        }
        assertEquals(2, AutoHistoryStore.entries(temp.root).size)
    }

    private fun segment(capture: String, name: String, start: Long, end: Long, foreground: Long): File {
        val folder = File(temp.root, "auto_history").apply { mkdirs() }
        return File(folder, name).apply {
            writeText("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\n" +
                "core_events\t1\nminimum_usage_ms\t180000\ncapture_id\t$capture\nforeground_ms\t$foreground\n" +
                "T\t${start + 1}\t1\t2\t3\t776f726b6572\t12.0\nF\t${start + 1}\t60\t16.7\n" +
                "E\t${start + 1}\t1\t2\t3\t776f726b6572\tassign\tqixia\t0,1\t1\t1\t12.0\tdemand\n" +
                "end_ms\t$end\nduration_ms\t${end - start}\nsamples\t1\nforeground_ms\t$foreground\n")
        }
    }

    @Test fun segmentsAreOneVisitWithStableIdAndCompleteCoreEvents() {
        val capture = "auto_1000_12_0"
        segment(capture, "$capture.log", 1_000, 201_000, 214_000)
        segment(capture, "auto_201000_12_0.log", 201_000, 215_000, 214_000)
        val entry = AutoHistoryStore.entries(temp.root).single()
        assertEquals(AutoHistoryRecord.sessionId("$capture.log"), entry.session.id)
        assertEquals(214_000L, entry.session.durationMs)
        assertEquals(listOf("$capture.log", "auto_201000_12_0.log"), entry.memberFileNames)
        val record = AutoHistoryStore.record(temp.root, entry.session.id)!!
        assertEquals(2, record.coreTimeline.events.size)
        assertEquals(1, record.threads.size)
        assertEquals(2, record.fps!!.samples)
        assertFalse(record.coreTimeline.incomplete)
    }

    @Test fun aLateSegmentInvalidatesOnlyItsVisitSummaryAndRecordCache() {
        val capture = "auto_1000_12_0"
        segment(capture, "$capture.log", 1_000, 201_000, 214_000)
        segment(capture, "auto_201000_12_0.log", 201_000, 215_000, 214_000)
        val original = AutoHistoryStore.entries(temp.root).single()
        assertEquals(2, AutoHistoryStore.record(temp.root, original.session.id)!!.coreTimeline.events.size)
        // 即使原始记录的 LRU 缓存被挤出，重复加载列表仍复用 v3 分组元数据。
        repeat(3) { run("auto_${500000 + it}_12_0.log", 600_000L + it) }
        AutoHistoryStore.entries(temp.root)
        segment(capture, "auto_215000_12_0.log", 215_000, 225_000, 224_000)
        val updated = AutoHistoryStore.entries(temp.root).single { it.captureId == capture }
        assertEquals(original.session.id, updated.session.id)
        assertEquals(224_000L, updated.session.durationMs)
        assertEquals(3, AutoHistoryStore.record(temp.root, updated.session.id)!!.coreTimeline.events.size)
    }

    @Test fun deletingAVisitAcknowledgesAllMembersAndRejectsLaterSegmentsAfterSuccessfulAck() {
        val capture = "auto_1000_12_0"
        // 稳定的首分片名称不一定仍对应剩余物理文件之一。
        segment(capture, "auto_201000_12_0.log", 201_000, 215_000, 214_000)
        segment(capture, "auto_215000_12_0.log", 215_000, 225_000, 224_000)
        val entry = AutoHistoryStore.entries(temp.root).single()
        val acknowledged = mutableListOf<String>()
        assertEquals(setOf(entry.session.id), AutoHistoryStore.deleteSessions(temp.root, listOf(entry.session.id)) {
            acknowledged += it; true
        })
        assertEquals(entry.memberFileNames.toSet(), acknowledged.toSet())
        assertFalse(File(File(temp.root, "auto_history"), "${entry.memberFileNames.first()}.deleted").exists())
        segment(capture, "auto_225000_12_0.log", 225_000, 235_000, 234_000)
        assertTrue(AutoHistoryStore.entries(temp.root).isEmpty())
        assertNull(AutoHistoryStore.record(temp.root, entry.session.id))
    }

    @Test fun retentionCountsVisitsAndDeletesTheirSegmentsTogether() {
        (0..7).forEach { index ->
            val start = 1_000L + index * 300_000L
            val capture = "auto_${start}_12_0"
            segment(capture, "$capture.log", start, start + 200_000, 214_000)
            segment(capture, "auto_${start + 200_000}_12_0.log", start + 200_000, start + 214_000, 214_000)
        }
        val acknowledged = mutableListOf<String>()
        AutoHistoryStore.pruneHistory(temp.root) { acknowledged += it; false }
        val retained = AutoHistoryStore.entries(temp.root)
        assertEquals(7, retained.size)
        assertTrue(retained.all { it.memberFileNames.size == 2 })
        assertEquals(setOf("auto_1000_12_0.log", "auto_201000_12_0.log"), acknowledged.toSet())
        assertEquals(14, File(temp.root, "auto_history").listFiles()!!.count { it.extension == "log" })
    }
}
