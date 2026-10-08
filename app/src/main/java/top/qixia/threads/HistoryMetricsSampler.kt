package top.qixia.threads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 硬件读取、单位与差值计算均由 Rust 负责，此任务只记录标准化样本。
 * 由现有悬浮球刷新周期驱动，不新增定时器、队列或后台守护进程。 */
internal class HistoryMetricsSampler(private val context: Context) {
    private class Run(val pkg: String, val captureId: String) {
        val probe = HistoryRustProbe()
        var writer: HistoryMetricsStore.Writer? = null
        var full = false
        @Volatile var generation = 0L
    }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "HistoryMetrics").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var run: Run? = null
    private var nextSample = 0L
    private var closed = false
    private var pendingFrameMax: Float? = null

    fun tick(pkg: String, captureId: String, frameMax: Float? = null) {
        if (closed) return
        if (run?.pkg != pkg || run?.captureId != captureId) { stop(); run = Run(pkg, captureId); nextSample = 0L }
        val current = run ?: return
        if (frameMax != null) pendingFrameMax = maxOf(pendingFrameMax ?: frameMax, frameMax)
        val elapsed = SystemClock.elapsedRealtime()
        if (current.full || elapsed < nextSample || !busy.compareAndSet(false, true)) return
        nextSample = elapsed + 2000L
        val frames = pendingFrameMax
        val generation = current.generation
        pendingFrameMax = null
        worker.execute {
            try {
                if (run !== current || generation != current.generation) return@execute
                val timeout = Runnable { current.probe.abortRead() }
                handler.postDelayed(timeout, 5000)
                val raw = try { current.probe.read(context) } finally { handler.removeCallbacks(timeout) }
                val decoded = HistoryMetrics.decode(raw, System.currentTimeMillis())
                val sample = if (frames != null) decoded.copy(values = decoded.values + ("frame_max_ms" to frames)) else decoded
                if (run !== current || generation != current.generation) return@execute
                if (current.writer == null) current.writer = HistoryMetricsStore.Writer(context.filesDir, pkg,
                    sample.timestampMs, deviceInfo(), current.captureId)
                current.full = current.writer?.append(sample) != true
                if (current.full) { current.probe.stop(); current.writer?.close() }
            } catch (e: Exception) {
                current.full = true
                current.probe.stop()
                current.writer?.close()
                android.util.Log.w("QixiaThreads", "History metrics stopped: ${e.javaClass.simpleName}")
            } finally { busy.set(false) }
        }
    }
    /** 短暂无法确认前台时暂停 IO，不将同一次校准拆成多段记录。
     * 前台监测确认应用已离开后，才关闭本轮记录。 */
    fun pause() {
        run?.let { it.generation++ }
        pendingFrameMax = null
    }
    fun stop() {
        val old = run
        run = null
        pendingFrameMax = null
        old?.probe?.stop()
        // 等待在途样本创建或追加写入器后再关闭。
        if (old != null) worker.execute { old.writer?.close() }
    }
    fun close() { closed = true; stop(); worker.shutdown() }

    private fun deviceInfo(): Map<String, String> {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(0)
        return mapOf("platform" to android.os.Build.SOC_MODEL, "model" to android.os.Build.MODEL,
            "os" to "Android ${android.os.Build.VERSION.RELEASE}",
            "resolution" to (display?.mode?.let { "${it.physicalWidth} × ${it.physicalHeight}" } ?: "未读取"),
            "refresh" to (display?.refreshRate?.toInt()?.let { "$it Hz" } ?: "未读取"))
    }
}
