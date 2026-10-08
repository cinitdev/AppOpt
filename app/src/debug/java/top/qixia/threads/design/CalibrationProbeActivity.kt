package top.qixia.threads.design

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import top.qixia.threads.DaemonBridge

/** 需显式运行且有时限的测试负载，不包含在正式 APK 中。 */
class CalibrationProbeActivity : Activity() {
    private val running = AtomicBoolean(true)
    private val workers = mutableListOf<Thread>()
    private var allocationHistoryProbe = false
    @Volatile private var result = 1L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allocationHistoryProbe = intent.getBooleanExtra("allocation_history_probe", false)
        if (allocationHistoryProbe) {
            startAllocationHistoryProbe()
            return
        }
        if (intent.getBooleanExtra("bridge_check", false)) {
            val label = TextView(this).apply { setPadding(40,100,40,40) }
            setContentView(label)
            Thread {
                val output = "exit7=${DaemonBridge.runRootCommand("exit 7")}\n" +
                    "mode=${DaemonBridge.setAutomaticAffinity("top.qixia.threads", false)}\n"
                java.io.File(filesDir,"qa-bridge-result.txt").writeText(output)
                runOnUiThread { label.text = output }
            }.start()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.postDelayed({ window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, 90_000)
        val familyProbe = intent.getBooleanExtra("family_probe", false)
        setContentView(TextView(this).apply {
            text = "QixiaThreads 校准确认测试\n\n${if (familyProbe) "重线程、线程重建、通配组和休眠线程" else "2 个活跃线程 + 24 个休眠线程"}\n最长运行 90 秒，离开本页立即停止。"
            textSize = 20f; setPadding(50,150,50,50)
        })
        val loads = if (familyProbe) listOf("ReviewHeavy" to 28_000_000L, "ReviewMedium" to 18_000_000L,
            "RenderThread" to 6_000_000L, "thread-shared-1" to 5_000_000L, "thread-shared-2" to 4_000_000L,
            "thread-shared-3" to 1_000_000L, "Binder:${android.os.Process.myPid()}_1" to 3_000_000L,
            "Binder:${android.os.Process.myPid()}_2" to 2_000_000L, "ReviewLow" to 1_000_000L)
            else listOf("ReviewHeavy" to 25_000_000L, "ReviewLight" to 3_000_000L)
        loads.forEach { (name, busyNs) ->
            workers += Thread({
                val deadline = System.nanoTime() + 90_000_000_000L
                // 每八秒使用相同 Linux comm 名称创建新 TID。
                // 工作线程运行时所属线程休眠，不额外产生忙循环。
                if (familyProbe && name == "RenderThread") {
                    while (running.get() && System.nanoTime() < deadline) {
                        val child = Thread({ runLoad(busyNs, minOf(deadline, System.nanoTime() + 8_000_000_000L)) }, name)
                        child.start()
                        try { child.join() } catch (_: InterruptedException) { child.interrupt(); break }
                    }
                    return@Thread
                }
                runLoad(busyNs, deadline)
            }, if (familyProbe && name == "RenderThread") "ReviewChurn" else name).also { it.start() }
        }
        repeat(24) { i -> workers += Thread({ LockSupport.parkNanos(90_000_000_000L) }, "ReviewIdle$i").also { it.start() } }
    }

    private fun startAllocationHistoryProbe() {
        val label = TextView(this).apply {
            text = "核心轨迹验证 · 真实采样\n\nQAHeavy：持续约 30% CPU\n" +
                "QAAlternating：15 秒约 30% → 20 秒约 1% → 15 秒约 30% → 20 秒约 1%\n\n" +
                "负载持续 70 秒，离开本页立即停止。"
            textSize = 20f
            setPadding(50, 150, 50, 50)
        }
        setContentView(label)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.nanoTime()
        val deadline = started + 70_000_000_000L
        workers += Thread({ runLoad(15_000_000L, deadline) }, "QAHeavy").also { it.start() }
        workers += Thread({
            // 所有阶段边界共用最初的单调时钟起点；
            // 延迟启动的工作线程跳过已经结束的阶段，不延长整轮运行。
            for ((endNs, busyNs) in listOf(
                15_000_000_000L to 15_000_000L,
                35_000_000_000L to 500_000L,
                50_000_000_000L to 15_000_000L,
                70_000_000_000L to 500_000L
            )) {
                if (!running.get() || Thread.currentThread().isInterrupted) break
                runLoad(busyNs, started + endNs)
            }
        }, "QAAlternating").also { it.start() }
        repeat(2) { index ->
            workers += Thread({
                LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1L))
            }, "QAIdle$index").also { it.start() }
        }
        window.decorView.postDelayed({
            stopWorkers()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isFinishing && !isDestroyed) label.append("\n\n负载已结束。")
        }, 70_000)
    }

    private fun runLoad(busyNs: Long, deadline: Long) {
        val worker = Thread.currentThread()
        while (running.get() && !worker.isInterrupted && System.nanoTime() < deadline) {
            val start = System.nanoTime()
            val busyUntil = minOf(deadline, start + busyNs)
            var value = result
            while (running.get() && !worker.isInterrupted && System.nanoTime() < busyUntil) {
                value = value * 1664525 + 1013904223
            }
            result = value
            val remaining = minOf(start + 50_000_000L, deadline) - System.nanoTime()
            if (running.get() && !worker.isInterrupted && remaining > 0L) LockSupport.parkNanos(remaining)
        }
    }

    private fun stopWorkers() {
        running.set(false)
        workers.forEach { it.interrupt() }
    }

    override fun onPause() {
        if (allocationHistoryProbe) stopWorkers()
        super.onPause()
    }

    override fun onStop() {
        stopWorkers()
        super.onStop()
    }
}
