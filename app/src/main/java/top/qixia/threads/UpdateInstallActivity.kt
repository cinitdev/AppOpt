package top.qixia.threads

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
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
    private var exitForAppUpdate = false

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
        val appOnly = intent.getBooleanExtra(EXTRA_APP_ONLY, false)
        if (!appOnly && (update == null || zipPath.isNullOrBlank())) {
            AppToast.show(this, "更新信息无效")
            finish()
            return
        }

        if (appOnly) {
            installState = UpdateInstallViewModel.State(statusTitle = "检查 App 更新",
                statusDetail = "正在读取模块随附的安装包")
            thread(name = "QixiaThreadsPendingApk") {
                val pending = ModuleHotUpdate.hasPendingApp()
                runOnUiThread {
                    installState = UpdateInstallViewModel.State(statusTitle = "模块已生效",
                        statusDetail = if (pending) "可单独安装模块随附的 App" else "没有待安装的 App 更新",
                        log = "模块与 App 分开更新，点击完成可直接返回。", running = false,
                        success = true, appUpdatePending = pending)
                }
            }
        } else viewModel.state.observe(this) { installState = it }
        setContent {
            QixiaThreadsTheme {
                UpdateInstallScreen(
                    state = installState,
                    subtitle = if (update == null) "安装模块随附的 App" else
                        "当前 ${update.localVersion} (${update.localVersionCode}) → ${update.remoteVersion} (${update.remoteVersionCode})",
                    rebooting = rebooting,
                    rebootStatus = rebootStatus,
                    onBack = ::requestClose,
                    onPrimaryAction = {
                        if (installState.rebootRequired) rebootSystem() else requestClose()
                    },
                    onUpdateApp = ::completeAppUpdate
                )
            }
        }
        if (!appOnly) viewModel.start(applicationContext, requireNotNull(zipPath), requireNotNull(update))
    }

    private fun requestClose() {
        if (rebooting) return
        if (installState.running) {
            AppToast.show(this, "正在刷入模块，请等待完成")
        } else {
            finish()
        }
    }

    private fun completeAppUpdate() {
        if (rebooting) return
        rebooting = true
        rebootStatus = "正在准备 App 更新" to "即将返回桌面，安装完成后会显示提示"
        thread(name = "QixiaThreadsAppUpdate") {
            val queued = ModuleHotUpdate.queueAppInstall(Process.myPid())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (queued) {
                    // 先显示桌面并走完 Activity 退出，再让独立安装器替换本进程。
                    // 安装器会核对 PID 与启动时间，交接失败时保留安装包而不强制覆盖。
                    runCatching {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        exitForAppUpdate = true
                        finishAffinity()
                        finishAndRemoveTask()
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            Process.killProcess(Process.myPid())
                        }
                    }.onFailure {
                        rebooting = false
                        rebootStatus = "无法返回桌面" to "App 尚未更新，请稍后重试"
                    }
                } else {
                    rebooting = false
                    rebootStatus = "App 更新未能启动" to "模块已生效，可再次点击更新 App 重试"
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (exitForAppUpdate) Process.killProcess(Process.myPid())
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
        private const val EXTRA_APP_ONLY = "app_update_only"

        fun pendingAppIntent(context: Context) = Intent(context, UpdateInstallActivity::class.java)
            .putExtra(EXTRA_APP_ONLY, true)

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
