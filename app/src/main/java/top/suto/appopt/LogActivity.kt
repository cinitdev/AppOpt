package top.suto.appopt

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityLogBinding

/**
 * 守护进程日志查看器。
 *
 * 守护进程 (service.sh 启动时) 把 stdout/stderr 重定向到模块目录下的 AppOpt.log,
 * 每次开机覆盖写, 故只含本次开机以来的运行日志。该文件位于 /data/adb/modules,
 * 普通应用无权限直读, 经 DaemonBridge 用 root (su) 读取最近若干行。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var loadGeneration = 0
    private var loadInFlight = false
    private var reloadPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.applyEdgeToEdge(this, binding.root, binding.logHeader)

        binding.logBack.setOnClickListener { finish() }
        binding.btnRefreshLog.setOnClickListener { loadLog() }
        loadLog()
    }

    private fun loadLog() {
        if (loadInFlight) {
            reloadPending = true
            return
        }
        loadInFlight = true
        val generation = ++loadGeneration
        binding.logEmpty.visibility = View.GONE
        thread {
            val log = runCatching { DaemonBridge.readDaemonLog() }
                .onFailure { android.util.Log.e("AppOpt", "读取守护进程日志失败", it) }
                .getOrDefault("")
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                loadInFlight = false
                render(log)
                if (reloadPending) {
                    reloadPending = false
                    loadLog()
                }
            }
        }
    }

    override fun onDestroy() {
        loadGeneration++
        loadInFlight = false
        reloadPending = false
        super.onDestroy()
    }

    private fun render(log: String) {
        val text = log.trim()
        if (text.isEmpty()) {
            binding.logText.text = ""
            binding.logScroll.visibility = View.GONE
            binding.logEmpty.visibility = View.VISIBLE
            return
        }
        binding.logEmpty.visibility = View.GONE
        binding.logScroll.visibility = View.VISIBLE
        binding.logText.text = text
        // 加载后滚动到底部, 方便看最新输出
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
