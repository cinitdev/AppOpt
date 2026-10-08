package top.qixia.threads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.qixia.threads.compose.QixiaThreadsViewModel
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.QixiaThreadsApp
import top.qixia.threads.compose.ui.QixiaLaunchHost

class QixiaThreadsComposeActivity : ComponentActivity() {
    private val viewModel: QixiaThreadsViewModel by viewModels()
    private val moduleUpdateViewModel: ModuleUpdateDownloadViewModel by viewModels()
    private var moduleDownloadState by mutableStateOf(ModuleUpdateDownloadViewModel.State())
    private var showBrandLaunch by mutableStateOf(false)
    private var nativeSplashDismissed by mutableStateOf(false)
    private var pendingLaunchPackage: String? = null
    private var firstResume = true
    private var recovering by mutableStateOf(false)
    private var recoveryNotice by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pkg = pendingLaunchPackage
        pendingLaunchPackage = null
        if (granted && pkg != null) launchAppWithFloatingBall(pkg)
        if (!granted) AppToast.show(this, "需要通知权限才能稳定显示前台悬浮服务")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // 仅在新 Activity 中播放一次，从后台返回或恢复配置时不重播。
        showBrandLaunch = savedInstanceState == null
        // 使用系统默认的首帧交接，不额外等待数据或延长透明启动窗口。
        splashScreen.setOnExitAnimationListener { provider ->
            provider.remove()
            nativeSplashDismissed = true
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            QixiaThreadsTheme {
                QixiaLaunchHost(showLaunch = showBrandLaunch, started = nativeSplashDismissed,
                    onFinished = { showBrandLaunch = false }) { launchBlocked ->
                    QixiaThreadsApp(
                        viewModel = viewModel,
                        onLaunchApp = ::requestLaunchApp,
                        onOpenOverlayPermission = ::openOverlayPermission,
                        onOpenUsagePermission = ::openUsagePermission,
                        moduleDownloadState = moduleDownloadState,
                        onBeginModuleUpdate = { moduleUpdateViewModel.start(applicationContext, it) },
                        onRetryModuleUpdate = { moduleUpdateViewModel.retry(applicationContext) },
                        onCancelModuleUpdate = { moduleUpdateViewModel.cancelSession() },
                        onInstallModuleUpdate = ::installDownloadedModule,
                        startupBlocked = launchBlocked || recovering || recoveryNotice != null
                    )
                }
                if (!showBrandLaunch) recoveryNotice?.let { message ->
                    AlertDialog(onDismissRequest = { recoveryNotice = null },
                        title = { Text("上次悬浮会话意外中断") }, text = { Text(message) },
                        confirmButton = { TextButton({ recoveryNotice = null }) { Text("知道了") } },
                        dismissButton = { TextButton({
                            recoveryNotice = null
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")))
                        }) { Text("后台运行设置") } })
                }
            }
        }
        moduleUpdateViewModel.state.observe(this) { moduleDownloadState = it }
    }

    override fun onResume() {
        super.onResume()
        resumeReadyUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 热启动或新建 Activity 时可能没有系统启动画面，退出监听器也就不会触发。
        // 窗口已可见且获得焦点时，同样可以安全开始品牌动画。
        if (hasFocus) nativeSplashDismissed = true
    }

    private fun resumeReadyUi() {
        recoverInterruptedSession()
        if (firstResume) {
            firstResume = false
        } else {
            viewModel.refreshDashboard()
        }
        moduleUpdateViewModel.resumeAuthorizedSession(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        super.onStop()
    }

    private fun requestLaunchApp(pkg: String) {
        if (recovering) { AppToast.show(this, "正在结束上次中断的采集，请稍候"); return }
        if (!viewModel.state.value.environment.featuresAvailable) {
            AppToast.show(this, "Root、模块或守护进程尚未就绪")
            return
        }
        if (viewModel.state.value.applications.configured.any {
                it.packageName == pkg && it.automaticAffinityEnabled
            }) {
            val intent = packageManager.getLaunchIntentForPackage(pkg.substringBefore(':'))
            if (intent == null) AppToast.show(this, "请手动进入 $pkg，自动分配将在前台生效")
            else runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { AppToast.show(this, "启动游戏失败") }
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AppToast.show(this, "请先授予悬浮窗权限")
            openOverlayPermission()
            return
        }
        if (!ForegroundDetector.hasUsageAccess(this)) {
            AppToast.show(this, "请先授予使用情况访问权限")
            openUsagePermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingLaunchPackage = pkg
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchAppWithFloatingBall(pkg)
    }

    private fun launchAppWithFloatingBall(pkg: String) {
        if (recovering) return
        val launchPkg = pkg.substringBefore(':')
        val launchIntent = packageManager.getLaunchIntentForPackage(launchPkg)
        val prefs = getSharedPreferences(QixiaThreadsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val serviceIntent = Intent(this, FloatingBallService::class.java)
            .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
            .putExtra(FloatingBallService.EXTRA_LAUNCH_PKG, launchPkg)
            .putExtra(
                FloatingBallService.EXTRA_AUTO_START_CALIBRATION,
                prefs.getBoolean(QixiaThreadsViewModel.PREF_AUTO_START, false)
            )
            .putExtra(
                FloatingBallService.EXTRA_AUTO_START_DELAY_MS,
                prefs.getLong(QixiaThreadsViewModel.PREF_AUTO_DELAY, 0L)
            )
            .putExtra(FloatingBallService.EXTRA_MANUAL_LAUNCH, launchIntent == null)
        runCatching { startForegroundService(serviceIntent) }
            .onFailure {
                AppToast.show(this, "悬浮球启动失败，请检查后台运行权限")
                return
            }
        if (launchIntent == null) {
            AppToast.show(this, "悬浮球已开启，请手动进入 $launchPkg")
            return
        }
        runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }.onFailure {
            FloatingBallSessionState.markExpectedStop(this, "launch_failed")
            stopService(Intent(this, FloatingBallService::class.java))
            AppToast.show(this, "启动 $launchPkg 失败")
        }
    }

    private fun openOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun recoverInterruptedSession() {
        if (recovering || FloatingBallService.isRunningInProcess()) return
        val incident = FloatingBallSessionState.consumeIncident(this, false, consume = false) ?: return
        recovering = true
        CalibrationCommandDispatcher.execute {
            // 新校准命令也使用同一队列；恢复旧会话时不能停止
            // 正在运行的服务，也不能清除已完成的草稿。
            val result = runCatching {
                if (FloatingBallService.isRunningInProcess()) return@runCatching false
                val calibrationStopped = !incident.calibrating || incident.targetPkg.isBlank() ||
                    DaemonBridge.stopCalibration(incident.targetPkg)
                val fpsStopped = DaemonBridge.stopFpsMonitor()
                check(calibrationStopped && fpsStopped)
                FloatingBallSessionState.consumeIncident(applicationContext, false)
                DaemonBridge.updateFloatingBallLease(false, null, false)
                true
            }
            runOnUiThread {
                recovering = false
                if (result.getOrDefault(false) || result.isFailure) {
                    recoveryNotice = if (result.isSuccess)
                        "已补发停止命令。已保存的历史和待确认建议会保留；未完成的采集请重新开始。请允许 ${getString(R.string.app_name)} 后台运行，避免再次被系统停止。"
                    else "停止旧采集的命令未成功发送，异常标记已保留，下次返回会重试。请检查 Root 和守护进程状态。"
                    viewModel.refreshDashboard()
                }
            }
        }
    }

    private fun openUsagePermission() {
        runCatching {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun installDownloadedModule() {
        val state = moduleDownloadState
        val update = state.update ?: return
        val zipPath = state.zipPath ?: return
        if (!moduleUpdateViewModel.claimForInstall(zipPath)) {
            AppToast.show(this, "模块更新状态已变化，请重试")
            return
        }
        runCatching {
            startActivity(UpdateInstallActivity.intent(this, update, zipPath))
        }.onFailure {
            moduleUpdateViewModel.handoffFailed(applicationContext, "无法打开模块刷入页面")
            AppToast.show(this, "无法打开模块刷入页面")
        }
    }
}
