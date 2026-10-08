package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.CoreEvent
import top.qixia.threads.AutoHistoryStore
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.CoreTimelineReport
import top.qixia.threads.FpsSessionRecorder
import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.ThreadIdentity
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import top.qixia.threads.db.ThreadData
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HistoryRunExporterTest {
    private val visit = SessionSummary(-5, 10, 30, 3, HistorySource.AUTO_ALLOCATION,
        startedAtMs = 1000, recordedDurationMs = 9000, recordedEndedAtMs = 10_000)
    private val windows = (0..2).map { HistoryReportWindow(it, 1000L + it * 3000, 4000L + it * 3000) }

    private fun detail(index: Int): QixiaThreadsRepository.HistorySessionDetail {
        val window = windows[index]
        val session = visit.copy(startedAtMs = window.startMs, recordedEndedAtMs = window.endMs,
            recordedDurationMs = window.endMs - window.startMs, rounds = 10, threadCount = 1)
        val thread = ThreadData("thread_$index", 1.1f, 2.2f, "0:1.1;1000:2.2", "child_$index")
        val fps = HistoryFpsStore.Report(60f, 59f, 61f, 200,
            listOf(HistoryFpsStore.Point(0f, 59f), HistoryFpsStore.Point(1f, 61f, true)), 58f, 2f)
        val series = HistoryMetrics.Series("cpu_mhz.0", 2000f, 1000f, 3000f, 2,
            listOf(HistoryMetrics.Point(0f, 1000f), HistoryMetrics.Point(1f, 3000f, true)))
        val metrics = HistoryMetrics.Report(mapOf(series.key to series), 2, 1, 3000L, 1f,
            mapOf("model" to "model_$index"),
            mapOf("cpu_mhz.0" to listOf(HistoryMetrics.FrequencyBucket(1000f, 3000f, 100f))))
        val event = CoreEvent(window.endMs, ThreadIdentity(1, 2, 3), "thread_$index",
            if (index == 2) CoreEventKind.RELEASE else CoreEventKind.ASSIGN,
            CoreEventSource.QIXIA, listOf(0, 1), listOf(1), 1, 1.1f, "reason_$index")
        return QixiaThreadsRepository.HistorySessionDetail(listOf(thread), mapOf(visit.id to fps),
            mapOf(visit.id to metrics), CoreTimelineReport(visit.id, window.startMs, window.endMs,
                listOf(event), 1), session, windows)
    }

    @Test fun exportsEveryWindowAndAllDataWithWholeVisitAndRealWindowTimes() {
        val output = StringBuilder()
        val loaded = mutableListOf<Int>()
        HistoryRunExporter.appendTo(output, "test.game", "游戏", listOf(visit)) { selected, index ->
            assertEquals(visit, selected)
            // 只有前一区间写入输出后才继续解析。
            if (index > 0) assertTrue(output.contains("reason_${index - 1}"))
            loaded += index
            detail(index)
        }
        val text = output.toString()
        assertEquals(listOf(0, 1, 2), loaded)
        assertTrue(text.contains("完整运行时长 (ms): 9000"))
        assertTrue(text.contains("完整运行活跃线程数: 3"))
        assertTrue(text.contains("数据窗口数: 3"))
        for (window in windows) {
            assertTrue(text.contains("数据窗口 ${window.index + 1}/3（索引 ${window.index}）"))
            assertTrue(text.contains("(${window.startMs} ms)"))
            assertTrue(text.contains("(${window.endMs} ms)"))
            assertTrue(text.contains("名称: thread_${window.index}"))
            assertTrue(text.contains("子线程明细: child_${window.index}"))
            assertTrue(text.contains("设备 model: model_${window.index}"))
            assertTrue(text.contains("reason_${window.index}"))
        }
        assertEquals(3, "FPS 采样点数: 200".toRegex().findAll(text).count())
        assertTrue(text.contains("5% LOW: 58.0 / JITTER: 2.0 %"))
        assertTrue(text.contains("FPS 曲线 (时段进度,FPS): 0.0,59.0;1.0,61.0"))
        assertTrue(text.contains("FPS 断点 (时段进度): 1.0"))
        assertTrue(text.contains("曲线 (进度,数值,断点): 0.0,1000.0,false;1.0,3000.0,true"))
        assertTrue(text.contains("分布 (最低MHz,最高MHz,有效样本%): 1000.0,3000.0,100.0"))
        assertTrue(text.contains("\trelease\tqixia\t"))
        assertFalse(text.contains("上次完成运行的平均 FPS"))
    }

    @Test fun calibrationRetainsItsOriginalThreadExportAndIndependentPreviousAverage() {
        val session = SessionSummary(7, 5, 10, 1)
        val thread = ThreadData("RenderThread", 10f, 40f, "0:10;1000:40", "worker")
        val output = StringBuilder()
        val previous = FpsSessionRecorder.AverageRecord("test.game", 59f, 5, 1, 101)
        HistoryRunExporter.appendTo(output, "test.game", "游戏", listOf(session), previous) { requested, index ->
            assertEquals(session, requested)
            assertEquals(0, index)
            QixiaThreadsRepository.HistorySessionDetail(listOf(thread), emptyMap(), emptyMap(), null, session)
        }
        assertTrue(output.startsWith("上次完成运行的平均 FPS: 59.0\n运行时长毫秒: 100\n"))
        assertTrue(output.contains("该值独立于下方校准会话，不代表每条历史会话的帧率。"))
        assertTrue(output.contains(HistoryExportText.session("test.game", "游戏", session, listOf(thread))))
        assertFalse(output.contains("完整运行"))
        assertFalse(output.contains("数据窗口"))
    }

    @Test fun timelineStreamsBoundedRowsAndKeepsTheOldTextApiEquivalent() {
        val report = detail(0).coreTimeline!!
        val streamed = StringBuilder()
        report.appendTo(streamed)
        assertEquals(report.exportText(), streamed.toString())
        val many = report.copy(events = List(20_000) { index -> report.events.single().copy(
            timestampMs = 1000L + index, name = "worker_$index") })
        val sink = object : Appendable {
            var newlines = 0
            var longestAppend = 0
            override fun append(value: CharSequence?): Appendable = append(value, 0, value?.length ?: 4)
            override fun append(value: CharSequence?, start: Int, end: Int): Appendable {
                val chars = value ?: "null"
                longestAppend = maxOf(longestAppend, end - start)
                check(end - start < 8192) { "A giant report string was allocated before writing" }
                for (index in start until end) if (chars[index] == '\n') newlines++
                return this
            }
            override fun append(value: Char): Appendable {
                if (value == '\n') newlines++
                return this
            }
        }
        many.appendTo(sink)
        assertEquals(20_003, sink.newlines)
        assertTrue(sink.longestAppend < 8192)
    }

    @Test fun failingWindowAbortsExportInsteadOfClaimingAPartialVisitIsComplete() {
        val loaded = mutableListOf<Int>()
        val failure = IOException("window unavailable")
        try {
            HistoryRunExporter.appendTo(StringBuilder(), "test.game", "游戏", listOf(visit)) { _, index ->
                loaded += index
                if (index == 1) throw failure
                detail(index)
            }
            fail("Expected the export to fail")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(listOf(0, 1), loaded)
    }

    @Test fun importCannotRepartitionWindowsUntilTheWholeVisitHasReachedOutput() {
        val output = StringBuilder()
        val firstWindowLoaded = CountDownLatch(1)
        val continueExport = CountDownLatch(1)
        val importStarted = CountDownLatch(1)
        val archiveRepartitioned = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val loaded = mutableListOf<Int>()
        val export = Thread({
            try {
                HistoryRunExporter.appendTo(output, "test.game", "游戏", listOf(visit)) { _, index ->
                    loaded += index
                    if (index == 0) {
                        firstWindowLoaded.countDown()
                        check(continueExport.await(5, TimeUnit.SECONDS)) { "Export was not released" }
                    }
                    // 补入前置分片可将旧区间 N 移到 N+1；此模拟归档
                    // 会在导入穿过锁保护时故意改变返回内容。
                    detail(if (archiveRepartitioned.get()) (index + 1) % windows.size else index)
                }
            } catch (error: Throwable) { failure.set(error) }
        }, "history-export-regression").apply { isDaemon = true }
        val importer = Thread({
            importStarted.countDown()
            synchronized(AutoHistoryStore) { archiveRepartitioned.set(true) }
        }, "history-import-regression").apply { isDaemon = true }
        try {
            export.start()
            assertTrue("Export must reach its first window", firstWindowLoaded.await(5, TimeUnit.SECONDS))
            importer.start()
            assertTrue(importStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (importer.state != Thread.State.BLOCKED && importer.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Import must wait for the complete export, not only one window read", Thread.State.BLOCKED, importer.state)
            assertFalse(archiveRepartitioned.get())
            continueExport.countDown()
            export.join(5000)
            importer.join(5000)
            assertFalse("Export must finish", export.isAlive)
            assertFalse("Import must resume after export", importer.isAlive)
            failure.get()?.let { throw AssertionError("Export failed", it) }
            assertTrue(archiveRepartitioned.get())
            assertEquals(listOf(0, 1, 2), loaded)
            windows.forEach { window ->
                assertEquals("Window ${window.index} must appear exactly once", 1,
                    "名称: thread_${window.index}\n".toRegex().findAll(output).count())
                assertTrue(output.contains("reason_${window.index}"))
            }
        } finally {
            continueExport.countDown()
            export.join(5000)
            if (importer.state != Thread.State.NEW) importer.join(5000)
        }
    }
}
