package top.suto.appopt

import android.app.Service
import android.content.Intent
import android.os.FileObserver
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 文件监听服务: 监听 .log 文件变化,自动增量导入数据库
 */
class FileWatcherService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var observer: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        android.util.Log.d("AppOpt", "FileWatcherService started")
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.stopWatching()
        scope.coroutineContext[Job]?.cancel()  // 取消所有协程,防止泄漏
        super.onDestroy()
        android.util.Log.d("AppOpt", "FileWatcherService stopped")
    }

    private fun startWatching() {
        val historyDir = File("/data/adb/modules/AppOpt/history")

        // 目录不存在时静默失败(模块未安装/权限不足)
        if (!historyDir.exists() || !historyDir.isDirectory) {
            android.util.Log.w("AppOpt", "History 目录不存在,跳过监听: ${historyDir.absolutePath}")
            return
        }

        observer = object : FileObserver(historyDir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !path.endsWith(".log")) return

                when (event) {
                    CLOSE_WRITE, MOVED_TO -> {
                        val pkg = path.removeSuffix(".log")
                        android.util.Log.d("AppOpt", "File changed: $path")

                        scope.launch {
                            try {
                                DatabaseMigrator.migrateIfNeeded(this@FileWatcherService, pkg)
                            } catch (e: Exception) {
                                android.util.Log.e("AppOpt", "FileObserver 导入 $pkg 失败: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

        observer?.startWatching()
        android.util.Log.d("AppOpt", "Watching: ${historyDir.absolutePath}")
    }
}
