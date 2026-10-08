package top.qixia.threads

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

internal object BoundedInstallLog {
    const val DEFAULT_MAX_CHARS = 64 * 1024
    private const val TRUNCATION_MARKER = "[较早的安装日志已省略]\n"
    private const val MAX_LINE_BOUNDARY_SEARCH = 4 * 1024

    fun append(target: StringBuilder, text: String, maxChars: Int = DEFAULT_MAX_CHARS) {
        if (text.isEmpty()) return
        require(maxChars > TRUNCATION_MARKER.length)
        target.append(text)
        if (target.length <= maxChars) return

        val tailCapacity = maxChars - TRUNCATION_MARKER.length
        val minimumStart = (target.length - tailCapacity).coerceAtLeast(0)
        val newline = target.indexOf("\n", minimumStart)
        val tailStart = if (newline in minimumStart until
            minOf(target.length, minimumStart + MAX_LINE_BOUNDARY_SEARCH)
        ) {
            newline + 1
        } else {
            minimumStart
        }
        val tail = target.substring(tailStart).takeLast(tailCapacity)
        target.clear()
        target.append(TRUNCATION_MARKER).append(tail)
    }
}

class UpdateInstallViewModel : ViewModel() {

    data class State(
        val statusTitle: String = "准备刷入模块",
        val statusDetail: String = "正在检测模块管理器",
        val log: String = "",
        val running: Boolean = true,
        val success: Boolean? = null,
        val rebootRequired: Boolean = false
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
        logBuffer.append("检查 QixiaThreads 模块刷入状态\n")
        logBuffer.append("当前版本：${update.localVersion} (${update.localVersionCode})\n")
        logBuffer.append("目标版本：${update.remoteVersion} (${update.remoteVersionCode})\n")
        logBuffer.append("模块 zip：$zipPath\n\n")
        mutableState.value = State(log = logBuffer.toString())

        ModuleUpdater.installDownloadedModule(
            zipPath = zipPath,
            inAppUpdate = true,
            context = context.applicationContext,
            expectedVersionCode = update.remoteVersionCode,
            callback = object : ModuleUpdater.InstallCallback {
                override fun onProgress(message: String, percent: Int?) {
                    updateState { copy(statusTitle = "正在刷入模块", statusDetail = message) }
                }

                override fun onLog(text: String) {
                    appendLog(text)
                }

                override fun onSuccess(message: String, rebootRequired: Boolean) {
                    ModuleUpdater.clearPersistedDownload(context, update)
                    appendResult(
                        true,
                        if (rebootRequired) listOf("模块已刷入，重启后生效", "App 将在重启后自动更新")
                        else listOf(message)
                    )
                    flushLogPublish()
                    updateState {
                        copy(
                            statusTitle = if (rebootRequired) "等待重启" else "更新已完成",
                            statusDetail = if (rebootRequired) "模块已刷入，重启后生效" else message,
                            log = logSnapshot(),
                            running = false,
                            success = true,
                            rebootRequired = rebootRequired
                        )
                    }
                }

                override fun onFailure(message: String, retainedZipPath: String?) {
                    ModuleUpdater.clearPersistedDownload(context, update)
                    appendResult(false, listOf(message))
                    flushLogPublish()
                    updateState {
                        copy(
                            statusTitle = "刷入失败",
                            statusDetail = if (retainedZipPath != null) {
                                "模块 zip 已保留，可手动刷入"
                            } else {
                                message.lineSequence().firstOrNull().orEmpty().ifBlank { "无法完成刷入" }
                            },
                            log = logSnapshot(),
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
        BoundedInstallLog.append(logBuffer, text)
        if (!logPublishScheduled) {
            logPublishScheduled = true
            mainHandler.postDelayed(publishLogRunnable, LOG_PUBLISH_INTERVAL_MS)
        }
    }

    private fun publishLog() {
        updateState { copy(log = logSnapshot()) }
    }

    private fun flushLogPublish() {
        mainHandler.removeCallbacks(publishLogRunnable)
        logPublishScheduled = false
    }

    private fun appendResult(success: Boolean, lines: List<String>) {
        val result = buildString {
            append("\n********************************************\n")
            append(if (success) "- Done\n" else "- Failed\n")
            lines.forEach { append("- ").append(it).append('\n') }
            append("********************************************\n")
        }
        BoundedInstallLog.append(logBuffer, result)
    }

    private fun logSnapshot(): String = logBuffer.toString()

    override fun onCleared() {
        mainHandler.removeCallbacks(publishLogRunnable)
        super.onCleared()
    }

    private companion object {
        // TextView 每次赋值都会重新测量整段文本；2 FPS 足以表达刷入进度，又不会频繁重排。
        const val LOG_PUBLISH_INTERVAL_MS = 500L
    }
}
