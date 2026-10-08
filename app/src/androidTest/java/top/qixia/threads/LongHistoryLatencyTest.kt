package top.qixia.threads

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import top.qixia.threads.compose.QixiaThreadsRepository
import top.qixia.threads.compose.HistoryCurvePoint
import top.qixia.threads.compose.HistoryPlotMath

/** 需显式启用，仅检查已有记录，不导入、清理或删除采集文件。 */
class LongHistoryLatencyTest {
    private fun report(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "$message\n") })

    private fun <T> measure(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return block().also { report("PERF $name=${SystemClock.elapsedRealtime() - start} ms") }
    }

    @Test fun longVisitSummaryAndStartupRemainIndependentOfFullDetail() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("long_history") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val folder = File(app.filesDir, "auto_history")
        val before = folder.listFiles().orEmpty().associate { it.name to (it.length() to it.lastModified()) }
        val repository = QixiaThreadsRepository(app)
        measure("dashboard") { assertFalse(repository.loadDashboard().environment.loading) }
        val entries = measure("cached_history_entries") { AutoHistoryStore.entries(app.filesDir) }
        val selected = entries.maxByOrNull { it.session.durationMs }
        assumeTrue(selected != null && selected.session.durationMs > 30 * 60_000)
        selected!!
        val files = selected.memberFileNames.map { File(folder, it) }
        report("VISIT segments=${files.size} bytes=${files.sumOf { it.length() }} duration=${selected.session.durationMs}")
        val summary = measure("cold_summary") { AutoHistoryRecord.summary(files, selected.fileName) }
        assertEquals(selected, summary)
        val windows = AutoHistoryStore.reportWindows(app.filesDir, selected.session.id)
        val full = measure("first_report_window") { AutoHistoryStore.record(app.filesDir, selected.session.id) }!!
        assertEquals(selected.session.id, full.entry.session.id)
        report("WINDOWS count=${windows.size} first_duration=${full.entry.session.durationMs}")
        listOf("power_w", "battery_ma", "battery_pct").forEach { key ->
            val points = full.metrics!!.series.getValue(key).points.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }
            val duration = full.entry.session.durationMs
            report("EDGE $key first_ms=${points.first().progress * duration} tail_ms=${(1f - points.last().progress) * duration}")
            assertNotNull("Start $key", HistoryPlotMath.at(points, 0f, duration))
            assertNotNull("End $key", HistoryPlotMath.at(points, 1f, duration))
        }
        val last = measure("last_report_window") {
            AutoHistoryStore.record(app.filesDir, selected.session.id, windows.lastIndex)
        }!!
        listOf("power_w", "battery_ma", "battery_pct").forEach { key ->
            val points = last.metrics!!.series.getValue(key).points.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }
            assertNotNull("Final end $key", HistoryPlotMath.at(points, 1f, last.entry.session.durationMs))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "history-lock-test") {
            synchronized(AutoHistoryStore) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = SystemClock.elapsedRealtime()
            measure("home_while_archive_busy") { assertFalse(repository.loadHome(emptyList()).loading) }
            assertTrue("Home waited on archive parsing", SystemClock.elapsedRealtime() - start < 3000)
        } finally { release.countDown(); holder.join(2000) }
        measure("history_list") { repository.loadHistory() }
        assertEquals("Original archives must stay untouched", before,
            folder.listFiles().orEmpty().associate { it.name to (it.length() to it.lastModified()) })
    }
}
