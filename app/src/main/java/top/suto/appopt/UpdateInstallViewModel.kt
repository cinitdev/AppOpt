package top.suto.appopt

import android.content.Context
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

    fun start(context: Context, zipPath: String, update: ModuleUpdater.UpdateInfo) {
        if (startedZipPath != null) return
        startedZipPath = zipPath
        mutableState.value = State(
            log = buildString {
                append("开始刷入 AppOpt 模块更新\n")
                append("当前版本：${update.localVersion} (${update.localVersionCode})\n")
                append("目标版本：${update.remoteVersion} (${update.remoteVersionCode})\n")
                append("模块 zip：$zipPath\n\n")
            }
        )

        ModuleUpdater.installDownloadedModule(
            zipPath = zipPath,
            inAppUpdate = true,
            context = context.applicationContext,
            callback = object : ModuleUpdater.InstallCallback {
                override fun onProgress(message: String, percent: Int?) {
                    updateState { copy(statusTitle = "正在刷入模块", statusDetail = message) }
                }

                override fun onLog(text: String) {
                    updateState { copy(log = log + text) }
                }

                override fun onSuccess(message: String) {
                    updateState {
                        copy(
                            statusTitle = "等待重启",
                            statusDetail = "模块已刷入，重启后生效",
                            log = appendResult(
                                log,
                                true,
                                listOf("模块已刷入，重启后生效", "App 将在重启后自动更新")
                            ),
                            running = false,
                            success = true
                        )
                    }
                }

                override fun onFailure(message: String) {
                    updateState {
                        copy(
                            statusTitle = "刷入失败",
                            statusDetail = "模块 zip 已保留，可手动刷入",
                            log = appendResult(log, false, listOf(message)),
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

    private fun appendResult(log: String, success: Boolean, lines: List<String>): String {
        return buildString {
            append(log)
            append("\n********************************************\n")
            append(if (success) "- Done\n" else "- Failed\n")
            lines.forEach { append("- ").append(it).append('\n') }
            append("********************************************\n")
        }
    }
}
