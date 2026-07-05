package top.suto.appopt

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class UpdateInstallViewModel : ViewModel() {

    data class State(
        val statusTitle: String = "准备刷入模块",
        val statusDetail: String = "正在检测模块管理器",
        val log: String = "",
        val running: Boolean = true,
        val success: Boolean? = null
    )

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private var startedZipPath: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()
    private var logPublishScheduled = false
    private val publishLogRunnable = Runnable {
        logPublishScheduled = false
        publishLog()
    }

    fun start(context: Context, zipPath: String, update: ModuleUpdater.UpdateInfo) {
        if (startedZipPath != null) return
        startedZipPath = zipPath
        logBuffer.clear()
        logBuffer.append("开始刷入 AppOpt 模块更新\n")
        logBuffer.append("当前版本：${update.localVersion} (${update.localVersionCode})\n")
        logBuffer.append("目标版本：${update.remoteVersion} (${update.remoteVersionCode})\n")
        logBuffer.append("模块 zip：$zipPath\n\n")
        mutableState.value = State(log = logBuffer.toString())

        ModuleUpdater.installDownloadedModule(
            zipPath = zipPath,
            inAppUpdate = true,
            context = context.applicationContext,
            callback = object : ModuleUpdater.InstallCallback {
                override fun onProgress(message: String, percent: Int?) {
                    updateState { copy(statusTitle = "正在刷入模块", statusDetail = message) }
                }

                override fun onLog(text: String) {
                    appendLog(text)
                }

                override fun onSuccess(message: String) {
                    appendResult(
                        true,
                        listOf("模块已刷入，重启后生效", "App 将在重启后自动更新")
                    )
                    flushLogPublish()
                    updateState {
                        copy(
                            statusTitle = "等待重启",
                            statusDetail = "模块已刷入，重启后生效",
                            log = logBuffer.toString(),
                            running = false,
                            success = true
                        )
                    }
                }

                override fun onFailure(message: String) {
                    appendResult(false, listOf(message))
                    flushLogPublish()
                    updateState {
                        copy(
                            statusTitle = "刷入失败",
                            statusDetail = "模块 zip 已保留，可手动刷入",
                            log = logBuffer.toString(),
                            running = false,
                            success = false
                        )
                    }
                }
            }
        )
    }

    private fun updateState(update: State.() -> State) {
        mutableState.value = (mutableState.value ?: State()).update()
    }

    private fun appendLog(text: String) {
        logBuffer.append(text)
        if (!logPublishScheduled) {
            logPublishScheduled = true
            mainHandler.postDelayed(publishLogRunnable, LOG_PUBLISH_INTERVAL_MS)
        }
    }

    private fun publishLog() {
        updateState { copy(log = logBuffer.toString()) }
    }

    private fun flushLogPublish() {
        mainHandler.removeCallbacks(publishLogRunnable)
        logPublishScheduled = false
    }

    private fun appendResult(success: Boolean, lines: List<String>) {
        logBuffer.append("\n********************************************\n")
        logBuffer.append(if (success) "- Done\n" else "- Failed\n")
        lines.forEach { logBuffer.append("- ").append(it).append('\n') }
        logBuffer.append("********************************************\n")
    }

    override fun onCleared() {
        mainHandler.removeCallbacks(publishLogRunnable)
        super.onCleared()
    }

    private companion object {
        const val LOG_PUBLISH_INTERVAL_MS = 100L
    }
}
