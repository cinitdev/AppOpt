package top.qixia.threads

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 胶囊冷启动的最后兜底。请求成功与否由服务回报，此入口不宣告显示成功。
 */
class BootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        if (target.matches(PACKAGE_PATTERN)) {
            val service = Intent(this, PerformanceOverlayService::class.java)
                .putExtras(intent)
            runCatching { startForegroundService(service) }.onFailure {
                CapsuleReply.from(intent)?.send("failed", "引导启动胶囊失败：${it.javaClass.simpleName}")
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_pkg"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
