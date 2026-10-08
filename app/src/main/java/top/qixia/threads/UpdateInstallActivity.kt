package top.qixia.threads

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.UpdateInstallScreen

class UpdateInstallActivity : ComponentActivity() {
    private val viewModel: UpdateInstallViewModel by viewModels()
    private var installState by mutableStateOf(UpdateInstallViewModel.State())
    private var rebooting by mutableStateOf(false)
    private var rebootStatus by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestClose()
            }
        })

        val update = readUpdateInfo()
        val zipPath = intent.getStringExtra(EXTRA_ZIP_PATH)
        if (update == null || zipPath.isNullOrBlank()) {
            AppToast.show(this, "更新信息无效")
            finish()
            return
        }

        viewModel.state.observe(this) { installState = it }
        setContent {
            QixiaThreadsTheme {
                UpdateInstallScreen(
                    state = installState,
                    subtitle = "当前 ${update.localVersion} (${update.localVersionCode}) → ${update.remoteVersion} (${update.remoteVersionCode})",
                    rebooting = rebooting,
                    rebootStatus = rebootStatus,
                    onBack = ::requestClose,
                    onPrimaryAction = {
                        if (installState.rebootRequired) rebootSystem() else finish()
                    }
                )
            }
        }
        viewModel.start(applicationContext, zipPath, update)
    }

    private fun requestClose() {
        if (installState.running) {
            AppToast.show(this, "正在刷入模块，请等待完成")
        } else {
            finish()
        }
    }

    private fun rebootSystem() {
        if (rebooting) return
        rebooting = true
        rebootStatus = "正在请求重启" to "正在等待 Root 确认 reboot 命令"
        thread(name = "QixiaThreadsReboot") {
            val result = DaemonBridge.runRootCommand("reboot", timeoutSeconds = 5L)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.success) {
                    rebootStatus = "正在重启系统" to "Root 已接受 reboot 命令"
                    window.decorView.postDelayed({
                        if (!isFinishing && !isDestroyed && rebooting) {
                            rebooting = false
                            rebootStatus = "系统尚未重启" to "可再次尝试；已刷入的模块不会丢失"
                        }
                    }, 8_000L)
                } else {
                    rebooting = false
                    rebootStatus = "重启请求失败" to if (result.timedOut) {
                        "Root 命令等待超时，系统仍未重启时可再次尝试"
                    } else {
                        "Root 拒绝或无法执行 reboot，请检查 Root 授权"
                    }
                    AppToast.show(this@UpdateInstallActivity, "重启失败，设备尚未确认重启")
                }
            }
        }
    }

    private fun readUpdateInfo(): ModuleUpdater.UpdateInfo? {
        val localVersion = intent.getStringExtra(EXTRA_LOCAL_VERSION) ?: return null
        val localCode = intent.getIntExtra(EXTRA_LOCAL_CODE, -1).takeIf { it > 0 } ?: return null
        val remoteVersion = intent.getStringExtra(EXTRA_REMOTE_VERSION) ?: return null
        val remoteCode = intent.getIntExtra(EXTRA_REMOTE_CODE, -1).takeIf { it > 0 } ?: return null
        val zipUrl = intent.getStringExtra(EXTRA_ZIP_URL) ?: return null
        return ModuleUpdater.UpdateInfo(
            localVersion = localVersion,
            localVersionCode = localCode,
            remoteVersion = remoteVersion,
            remoteVersionCode = remoteCode,
            zipUrl = zipUrl,
            changelogUrl = null,
            changelogText = "",
            changelogLoadFailed = false
        )
    }

    companion object {
        private const val EXTRA_LOCAL_VERSION = "local_version"
        private const val EXTRA_LOCAL_CODE = "local_code"
        private const val EXTRA_REMOTE_VERSION = "remote_version"
        private const val EXTRA_REMOTE_CODE = "remote_code"
        private const val EXTRA_ZIP_URL = "zip_url"
        private const val EXTRA_ZIP_PATH = "zip_path"

        fun intent(context: Context, update: ModuleUpdater.UpdateInfo, zipPath: String): Intent {
            return Intent(context, UpdateInstallActivity::class.java)
                .putExtra(EXTRA_LOCAL_VERSION, update.localVersion)
                .putExtra(EXTRA_LOCAL_CODE, update.localVersionCode)
                .putExtra(EXTRA_REMOTE_VERSION, update.remoteVersion)
                .putExtra(EXTRA_REMOTE_CODE, update.remoteVersionCode)
                .putExtra(EXTRA_ZIP_URL, update.zipUrl)
                .putExtra(EXTRA_ZIP_PATH, zipPath)
        }
    }
}
