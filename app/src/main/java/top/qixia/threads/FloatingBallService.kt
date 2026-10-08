package top.qixia.threads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import top.qixia.threads.databinding.OverlayResultBinding

/**
 * 悬浮球前台服务。
 *
 * 黄色胶囊 = 待机/帧率采样中，默认显示实时 FPS；
 * 点击 -> 红色胶囊 = 校准中, 向守护进程下发 start <前台包名>, 持续记录线程负载;
 * 再次点击 -> 下发 stop，生成待确认建议；返回 App 修改并保存后才写入规则。
 *
 * 胶囊可拖动; 区分点击与拖动靠移动阈值判定。
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var capsuleContainer: FrameLayout
    private lateinit var capsule: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var calibrating = false
    private var targetPkg: String? = null
    private var launchPkg: String? = null
    private var manualLaunchExpected = false
    private var autoStartCalibrationPending = false
    private var autoStartDelayMs = 0L
    private var autoStartScheduled = false
    private var autoStartDelayElapsed = false
    private var autoStartRunnable: Runnable? = null
    private var autoStartNoticePending = false
    private var autoStartNoticeRunnable: Runnable? = null
    // socket/FileObserver 回调线程写、主线程固定周期采样，需 @Volatile 保证可见性。
    // 同一份缓存用于每秒显示和记录，不再增加采集或刷新定时器。
    @Volatile private var latestFps = 0f
    @Volatile private var latestFpsUpdatedAtElapsed = 0L

    // 前台监测: 目标应用一旦退出/切后台, 自动移除悬浮球
    private var hasAppearedForeground = false   // 目标应用是否已经在前台出现过(拉起成功)
    private var absentCount = 0                  // 连续检测到不在前台的次数
    private var foregroundClosing = false        // 是否已因离开前台进入关闭流程
    private var launchProcessMissingCount = 0    // 启动阶段连续无法确认目标前台的次数

    private lateinit var fpsMonitor: FrameRateMonitor
    private lateinit var fpsSessionRecorder: FpsSessionRecorder
    private lateinit var historyMetricsSampler: HistoryMetricsSampler
    private var metricsCaptureId = ""

    private val fpsSampleTicker = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            updateCapsuleText()
            val pkg = targetPkg
            if (calibrating && !pkg.isNullOrBlank() && hasAppearedForeground && absentCount == 0 && !foregroundClosing) {
                historyMetricsSampler.tick(pkg, metricsCaptureId, if (::fpsMonitor.isInitialized) fpsMonitor.takeFrameMaximum() else null)
            } else if (calibrating) {
                historyMetricsSampler.pause()
            } else {
                historyMetricsSampler.stop()
            }
            if (
                !pkg.isNullOrBlank() && hasAppearedForeground && absentCount == 0 &&
                !foregroundClosing
            ) {
                val nowElapsed = SystemClock.elapsedRealtime()
                // 至少收到过一次守护进程回调后才落样本，避免 eBPF 启动失败被误记为 0 FPS。
                // 已建立通道后若短时失去新帧，则以 0 计入整段运行平均值。
                if (latestFpsUpdatedAtElapsed > 0L) {
                    val fps = if (
                        nowElapsed - latestFpsUpdatedAtElapsed in 0L..FPS_SAMPLE_FRESH_MS
                    ) {
                        latestFps
                    } else {
                        0f
                    }
                    fpsSessionRecorder.record(pkg, fps)
                }
            }
            mainHandler.postDelayed(this, FPS_SAMPLE_INTERVAL_MS)
        }
    }

    // 当前显示的提示横幅(用于 onDestroy 兜底清理, 避免 stopSelf 后泄漏)
    private var bannerView: View? = null

    // 当前显示的结果卡片(同上, onDestroy 兜底清理)
    private var resultView: View? = null
    private var capsuleAdded = false
    @Volatile private var serviceDestroyed = false
    private var foregroundTracker = ForegroundDetector.Tracker()
    private var foregroundCheckGeneration = -1L
    private var monitorGeneration = 0L
    private var pendingStopRunnable: Runnable? = null
    private var expectedStopReason: String? = null
    private var calibrationCommandGeneration = 0L
    private var calibrationStartPending = false

    private data class ForegroundCheckSnapshot(
        val generation: Long,
        val checkPkg: String,
        val foreground: Boolean,
        val processRunning: Boolean,
        val usageForeground: Boolean,
        val usagePackage: String?,
        val usageLastEventPackage: String?,
        val usageLastEventType: Int,
        val usageEventCount: Int,
        val usageResumedCount: Int,
        val focusedPkg: String?,
        val source: String,
        val detail: String
    )

    // 拖动状态
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var touchGesture = TouchGesture.IDLE

    private enum class TouchGesture {
        IDLE,
        PRESSED,
        DRAGGING
    }

    companion object {
        private const val CHANNEL_ID = "qixia_floating"
        private const val NOTIF_ID = 1001
        private const val DRAG_THRESHOLD_DP = 12f
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_LAUNCH_PKG = "launch_pkg"
        const val EXTRA_AUTO_START_CALIBRATION = "auto_start_calibration"
        const val EXTRA_AUTO_START_DELAY_MS = "auto_start_delay_ms"
        const val EXTRA_MANUAL_LAUNCH = "manual_launch"

        // 前台监测周期与离开阈值
        private const val FG_CHECK_INTERVAL = 3000L   // 每 3s 检查一次目标应用是否在前台
        private const val AUTO_START_FIRST_CHECK_DELAY = 1000L
        private const val AUTO_START_NOTICE_LEAD_MS = 600L
        private const val FG_ABSENT_LIMIT = 2         // 连续 2 次(约6s)不在前台才判定离开, 避免下拉通知栏等短暂切换误关
        private const val FG_APPEAR_GRACE = 15        // 启动后等待应用出现的宽限次数(约45s)
        private const val FG_LAUNCH_PROCESS_MISS_LIMIT = 3
        private const val FG_MANUAL_LAUNCH_PROCESS_MISS_LIMIT = 10
        private const val FG_MANUAL_LAUNCH_GRACE = 20
        private const val MANUAL_STOP_TIMEOUT_MS = 18_000L
        private const val MANUAL_STOP_CLOSE_DELAY_MS = 20_000L
        private const val MANUAL_WAIT_DONE_MS = 22_000L
        private const val BACKGROUND_WAIT_DONE_MS = 5_000L
        private const val FPS_SAMPLE_INTERVAL_MS = 1_000L
        private const val FPS_SAMPLE_FRESH_MS = 3_500L
        private val FPS_COMMAND_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QixiaThreadsFpsCommand").apply { isDaemon = true }
        }

        @Volatile private var runningInProcess = false

        fun isRunningInProcess(): Boolean = runningInProcess
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("QixiaThreads", "FloatingBallService onCreate")
        serviceDestroyed = false
        runningInProcess = true
        fpsSessionRecorder = FpsSessionRecorder(filesDir,
            retainedCalibrations = { top.qixia.threads.db.QixiaThreadsDbHelper.getInstance(this).calibrationWindows() })
        historyMetricsSampler = HistoryMetricsSampler(this)
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // fpsMonitor 和悬浮窗延迟到 onStartCommand 初始化, 需要明确的 targetPkg。
    }

    private fun postIfAlive(action: () -> Unit) {
        if (serviceDestroyed) return
        mainHandler.post {
            if (!serviceDestroyed) action()
        }
    }

    private fun markFpsSessionRunning() {
        val pkg = targetPkg?.takeIf { it.isNotBlank() } ?: return
        if (fpsSessionRecorder.markRunning(pkg)) updateCapsuleText()
    }

    private fun finishFpsSession(reason: String, packageName: String? = targetPkg) {
        if (::historyMetricsSampler.isInitialized) historyMetricsSampler.stop()
        if (!::fpsSessionRecorder.isInitialized) return
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return
        val result = fpsSessionRecorder.finish(pkg) ?: return
        android.util.Log.d(
            "QixiaThreads",
            "FPS session saved: pkg=${result.packageName} average=${result.averageFps} " +
                "samples=${result.sampleCount} duration=${result.durationMs}ms reason=$reason"
        )
    }

    private fun importCalibrationHistory(pkg: String, reason: String) {
        try {
            val result = DatabaseMigrator.migrateIfNeeded(applicationContext, pkg)
            android.util.Log.d(
                "QixiaThreads",
                "FloatingBallService history import: target=$pkg reason=$reason " +
                    "source=${result.sourceFound} imported=${result.importedSessions} " +
                    "claims=${result.processedClaims} invalid=${result.invalidClaim} " +
                    "cleanupFailed=${result.completionFailed}"
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "QixiaThreads",
                "FloatingBallService history import failed: target=$pkg reason=$reason",
                e
            )
        }
    }

    private fun cancelPendingStop() {
        pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    private fun cancelAutoStartDelay() {
        autoStartRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStartNoticeRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStartRunnable = null
        autoStartNoticeRunnable = null
        autoStartScheduled = false
        autoStartDelayElapsed = false
        autoStartNoticePending = false
    }

    private fun beginAutomaticCalibrationWithNotice(generation: Long) {
        if (!autoStartCalibrationPending || calibrating || autoStartNoticePending) return
        autoStartNoticePending = true
        val message = if (autoStartDelayMs > 0L) {
            "延时结束，目标应用仍在前台\n即将自动开始校准"
        } else {
            "已检测到目标应用进入前台\n即将自动开始校准"
        }
        showBanner(message, durationMs = 2200)
        android.util.Log.d(
            "QixiaThreads",
            "calibration auto start notice: pkg=${targetPkg.orEmpty()} lead=${AUTO_START_NOTICE_LEAD_MS}ms"
        )
        autoStartNoticeRunnable = Runnable {
            autoStartNoticeRunnable = null
            if (serviceDestroyed || generation != monitorGeneration || calibrating ||
                !autoStartCalibrationPending || foregroundClosing || !hasAppearedForeground
            ) {
                autoStartNoticePending = false
                return@Runnable
            }
            confirmAutomaticCalibrationForeground(generation)
        }.also { mainHandler.postDelayed(it, AUTO_START_NOTICE_LEAD_MS) }
    }

    /** 延时结束时重新读取当前前台，避免仅凭几秒前的 hasAppearedForeground 误启动。 */
    private fun confirmAutomaticCalibrationForeground(generation: Long) {
        val checkPkg = launchPkg ?: targetPkg ?: run {
            autoStartNoticePending = false
            return
        }
        thread(name = "QixiaThreadsAutoCalibrationForeground") {
            val foreground = runCatching {
                val taskState = DaemonBridge.readTaskForegroundState()
                if (taskState.available) {
                    taskState.packageName == checkPkg
                } else {
                    val focusedPackage = DaemonBridge.readFocusedPackage()
                    val usage = if (ForegroundDetector.hasUsageAccess(this)) {
                        ForegroundDetector.Tracker().queryState(this, checkPkg)
                    } else {
                        null
                    }
                    if (!focusedPackage.isNullOrBlank()) {
                        focusedPackage == checkPkg
                    } else {
                        usage?.foreground == true
                    }
                }
            }.getOrDefault(false)
            postIfAlive {
                if (generation != monitorGeneration || calibrating || foregroundClosing ||
                    !autoStartCalibrationPending || !autoStartNoticePending
                ) {
                    autoStartNoticePending = false
                    return@postIfAlive
                }
                autoStartNoticePending = false
                if (foreground) {
                    autoStartCalibrationPending = false
                    onCapsuleClick(automatic = true)
                } else {
                    autoStartDelayElapsed = false
                    showBanner("目标应用已离开前台\n自动校准已取消", durationMs = 2600)
                    mainHandler.removeCallbacks(foregroundWatcher)
                    mainHandler.post(foregroundWatcher)
                }
            }
        }
    }

    private fun scheduleAutoStartCalibration(generation: Long) {
        if (!autoStartCalibrationPending || calibrating || autoStartScheduled) return
        if (autoStartDelayMs <= 0L) {
            beginAutomaticCalibrationWithNotice(generation)
            return
        }
        autoStartScheduled = true
        val delayLabel = AutoStartCalibrationDelay.label(autoStartDelayMs)
        android.util.Log.d(
            "QixiaThreads",
            "calibration auto start scheduled: pkg=${targetPkg.orEmpty()} delay=${autoStartDelayMs}ms"
        )
        showBanner("已检测到目标应用\n将在 ${delayLabel}后自动开始校准", durationMs = 2600)
        autoStartRunnable = Runnable {
            autoStartRunnable = null
            autoStartScheduled = false
            if (serviceDestroyed || generation != monitorGeneration || calibrating ||
                !autoStartCalibrationPending || foregroundClosing || !hasAppearedForeground
            ) {
                return@Runnable
            }
            autoStartDelayElapsed = true
            mainHandler.removeCallbacks(foregroundWatcher)
            mainHandler.post(foregroundWatcher)
        }.also { mainHandler.postDelayed(it, autoStartDelayMs) }
    }

    private fun stopNormally(reason: String) {
        finishFpsSession(reason)
        mainHandler.removeCallbacks(fpsSampleTicker)
        if (expectedStopReason == null) expectedStopReason = reason
        FloatingBallSessionState.markExpectedStop(this, reason)
        android.util.Log.d("QixiaThreads", "FloatingBallService stopSelf: reason=$reason")
        stopSelf()
    }

    private fun scheduleStopSelf(
        delayMs: Long,
        generation: Long = monitorGeneration,
        reason: String
    ) {
        cancelPendingStop()
        val stop = Runnable {
            pendingStopRunnable = null
            if (!serviceDestroyed && generation == monitorGeneration) stopNormally(reason)
        }
        pendingStopRunnable = stop
        mainHandler.postDelayed(stop, delayMs)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.floating_channel_name, getString(R.string.app_name)),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("悬浮球运行中, 点击胶囊开始/结束校准")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addCapsule() {
        if (capsuleAdded) return
        capsule = TextView(this).apply {
            text = "0.0"
            setTextColor(resources.getColor(R.color.capsule_text, theme))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // BOLD 之上再给文字描一层细边, 实现比常规加粗更重的字重(系统无更重字体可选)
            paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = 2.0f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setBackgroundResource(R.drawable.capsule_yellow)
            // 整体再叠一层轻微透明, 让悬浮球在游戏画面上不喧宾夺主
            alpha = 0.92f
        }
        capsuleContainer = object : FrameLayout(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            isClickable = true
            contentDescription = getString(R.string.floating_description_idle, getString(R.string.app_name))
            setOnClickListener { onCapsuleClick() }
            addView(
                capsule,
                FrameLayout.LayoutParams(dp(45f), dp(30f), Gravity.CENTER)
            )
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // 固定尺寸胶囊，只显示当前阶段，不展示会持续跳动的实时 FPS。
        layoutParams = WindowManager.LayoutParams(
            dp(48f),
            dp(48f),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f)
            y = dp(120f)
        }
        clampCapsulePosition(layoutParams.x, layoutParams.y).also { bounded ->
            layoutParams.x = bounded.first
            layoutParams.y = bounded.second
        }

        capsuleContainer.setOnTouchListener { view, event -> handleTouch(view, event) }
        // 服务被系统重建或悬浮窗权限被撤销时, addView 会抛 BadTokenException。
        // 包裹后失败则直接 stopSelf, 避免崩溃 + 避免空跑一个看不见的服务。
        try {
            windowManager.addView(capsuleContainer, layoutParams)
            capsuleAdded = true
            android.util.Log.d("QixiaThreads", "FloatingBallService capsule added")
        } catch (e: Exception) {
            android.util.Log.e("QixiaThreads", "FloatingBallService add capsule failed: ${e.message}")
            stopNormally("add_capsule_failed")
        }
    }

    private fun updateCapsuleText() {
        if (serviceDestroyed) return
        if (!::capsule.isInitialized) return
        val fresh = latestFpsUpdatedAtElapsed > 0L &&
            SystemClock.elapsedRealtime() - latestFpsUpdatedAtElapsed in 0L..FPS_SAMPLE_FRESH_MS
        val value = if (fresh && latestFps.isFinite()) latestFps.coerceAtLeast(0f) else 0f
        val text = String.format(java.util.Locale.ROOT, "%.1f", value)
        if (capsule.text.toString() != text) capsule.text = text
        capsule.contentDescription = "$text FPS，${if (calibrating) "校准中" else "点击校准"}"
    }
    private fun handleTouch(clickView: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                touchX = event.rawX
                touchY = event.rawY
                touchGesture = TouchGesture.PRESSED
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                val dragThreshold = dp(DRAG_THRESHOLD_DP).toFloat()
                if (touchGesture == TouchGesture.PRESSED &&
                    (abs(dx) > dragThreshold || abs(dy) > dragThreshold)
                ) {
                    touchGesture = TouchGesture.DRAGGING
                }
                if (touchGesture == TouchGesture.DRAGGING) {
                    val bounded = clampCapsulePosition(
                        initialX + dx.toInt(),
                        initialY + dy.toInt()
                    )
                    layoutParams.x = bounded.first
                    layoutParams.y = bounded.second
                    try {
                        windowManager.updateViewLayout(capsuleContainer, layoutParams)
                    } catch (error: Exception) {
                        android.util.Log.e(
                            "QixiaThreads",
                            "FloatingBallService update capsule failed: ${error.message}",
                            error
                        )
                        stopNormally("update_capsule_failed")
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val shouldClick = touchGesture == TouchGesture.PRESSED
                touchGesture = TouchGesture.IDLE
                if (shouldClick) clickView.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchGesture = TouchGesture.IDLE
                return true
            }
        }
        return false
    }

    private fun clampCapsulePosition(x: Int, y: Int): Pair<Int, Int> {
        val width = layoutParams.width.coerceAtLeast(dp(48f))
        val height = layoutParams.height.coerceAtLeast(dp(48f))
        val screenWidth: Int
        val screenHeight: Int
        var insetLeft = 0
        var insetTop = 0
        var insetRight = 0
        var insetBottom = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetLeft = insets.left
            insetTop = insets.top
            insetRight = insets.right
            insetBottom = insets.bottom
        } else {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        val maxX = (screenWidth - insetRight - width).coerceAtLeast(insetLeft)
        val maxY = (screenHeight - insetBottom - height).coerceAtLeast(insetTop)
        return x.coerceIn(insetLeft, maxX) to y.coerceIn(insetTop, maxY)
    }
    private fun onCapsuleClick(automatic: Boolean = false) {
        if (calibrationStartPending) {
            showBanner("正在启动校准，请稍候…", durationMs = 1800)
            return
        }
        if (!calibrating) {
            // 黄 -> 红: 开始校准。目标包名由启动 App 时通过 Intent 指定。
            val pkg = targetPkg
            if (pkg.isNullOrBlank()) {
                android.util.Log.d("QixiaThreads", "calibration start ignored: target package empty")
                toast("未指定目标应用, 请从优化 App 内启动")
                return
            }
            cancelAutoStartDelay()
            autoStartCalibrationPending = false
            android.util.Log.d(
                "QixiaThreads",
                "calibration start: pkg=$pkg trigger=${if (automatic) "automatic" else "manual"}"
            )
            calibrationStartPending = true
            val commandGeneration = ++calibrationCommandGeneration
            calibrating = true
            metricsCaptureId = java.util.UUID.randomUUID().toString()
            fpsSessionRecorder.markCalibration(pkg)
            FloatingBallSessionState.setCalibrating(this, true)
            capsuleContainer.contentDescription = getString(R.string.floating_description_starting, getString(R.string.app_name))
            // 用户能点击悬浮球开始校准, 说明目标 App 会话已经成立。
            // 部分 ROM 的 UsageStats 会漏掉目标进入前台事件; 这里避免后续一直卡在"等待目标出现"阶段。
            hasAppearedForeground = true
            markFpsSessionRunning()
            absentCount = 0
            launchProcessMissingCount = 0
            capsule.setBackgroundResource(R.drawable.capsule_red)
            updateCapsuleText()
            val startMessage = if (automatic) {
                "● 已自动开始记录应用负载\n完成后点击胶囊结束校准"
            } else {
                "● 开始记录应用负载\n请正常操作游戏, 完成后再次点击胶囊结束"
            }
            showBanner("正在启动负载记录…", durationMs = 1800)
            CalibrationCommandDispatcher.execute {
                val result = DaemonBridge.startCalibration(pkg)
                android.util.Log.d(
                    "QixiaThreads",
                    "calibration start command result: pkg=$pkg status=${result.status} state=${result.state}"
                )
                if (!result.started && result.status != DaemonBridge.CalibrationStartStatus.BUSY) {
                    // start 可能已写入但确认超时。串行补发 stop，避免稍后才消费的旧命令继续采样。
                    DaemonBridge.stopCalibration(pkg)
                }
                postIfAlive {
                    if (commandGeneration != calibrationCommandGeneration || targetPkg != pkg) {
                        return@postIfAlive
                    }
                    calibrationStartPending = false
                    if (result.started) {
                        capsuleContainer.contentDescription =
                            getString(R.string.floating_description_calibrating, getString(R.string.app_name))
                        updateCapsuleText()
                        showBanner(startMessage, durationMs = 3500)
                        return@postIfAlive
                    }
                    val message = when (result.status) {
                        DaemonBridge.CalibrationStartStatus.BUSY ->
                            "另一个应用正在校准\n请先完成或停止当前校准"
                        DaemonBridge.CalibrationStartStatus.ROOT_TIMEOUT ->
                            "Root 命令执行超时, 请检查 Root 管理器"
                        DaemonBridge.CalibrationStartStatus.ROOT_COMMAND_FAILED ->
                            "无法写入校准命令, 请检查 Root 授权和模块状态"
                        DaemonBridge.CalibrationStartStatus.TARGET_PROCESS_NOT_READY ->
                            if (automatic) {
                                "目标应用仍在启动, 暂未自动校准\n进入稳定界面后可点击胶囊开始"
                            } else {
                                "未检测到稳定的目标进程\n请等待游戏进入主界面后重试"
                            }
                        DaemonBridge.CalibrationStartStatus.TARGET_PROCESS_EXITED ->
                            "目标进程启动后发生重建\n请等待游戏进入主界面后重试"
                        DaemonBridge.CalibrationStartStatus.DAEMON_NO_RESPONSE ->
                            "守护进程未确认校准\n请稍后重试或重启模块"
                        DaemonBridge.CalibrationStartStatus.INVALID_PACKAGE ->
                            "目标应用信息无效, 请重新选择"
                        DaemonBridge.CalibrationStartStatus.STARTED -> ""
                        DaemonBridge.CalibrationStartStatus.MODULE_UPDATE_REQUIRED -> "请更新模块并重启守护，当前模块不支持校准结果确认"
                        DaemonBridge.CalibrationStartStatus.STORAGE_UNAVAILABLE -> "采集存储暂不可用\n请解锁设备并打开柒夏线程后重试"
                    }
                    showBanner(message, durationMs = 3500)
                    revertToYellow()
                }
            }
        } else {
            // 红 -> 停止采样并生成规则; 移除胶囊, 弹出结果卡片
            val generation = monitorGeneration
            val pkg = targetPkg ?: ""
            ++calibrationCommandGeneration
            android.util.Log.d(
                "QixiaThreads",
                "FloatingBallService manual stop: reason=manual_stop target=$pkg launch=${launchPkg.orEmpty()} appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount"
            )
            calibrating = false
            FloatingBallSessionState.setCalibrating(this, false)
            // 用户主动停止: 关闭前台监测, 避免查看结果卡片时被自动关闭打断
            foregroundClosing = true
            finishFpsSession("manual_stop")
            mainHandler.removeCallbacks(foregroundWatcher)
            removeCapsule()
            showBanner("正在结束校准…", durationMs = 3500)
            var stopTimedOut = false
            var timeoutClose: Runnable? = null
            var timeoutStopSelf: Runnable? = null
            val stopTimeout = Runnable {
                if (generation != monitorGeneration) return@Runnable
                stopTimedOut = true
                showBanner("已请求停止，守护进程仍在生成规则\n完成后会自动显示结果", durationMs = 4200)
                val close = Runnable {
                    if (generation != monitorGeneration) return@Runnable
                    showBanner("校准收尾时间过长\n可稍后在历史记录或日志里查看结果", durationMs = 3200)
                    val stop = Runnable {
                        if (generation == monitorGeneration) stopNormally("manual_stop_timeout")
                    }
                    timeoutStopSelf = stop
                    mainHandler.postDelayed(stop, 3200)
                }
                timeoutClose = close
                mainHandler.postDelayed(close, MANUAL_STOP_CLOSE_DELAY_MS)
            }
            mainHandler.postDelayed(stopTimeout, MANUAL_STOP_TIMEOUT_MS)
            CalibrationCommandDispatcher.execute {
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) DaemonBridge.waitDone(pkg, timeoutMs = MANUAL_WAIT_DONE_MS) else null
                if (status != null) {
                    importCalibrationHistory(pkg, "manual_stop:$status")
                }
                val rules = if (status == "ok") DaemonBridge.readPkgRules(pkg) else emptyList()
                android.util.Log.d("QixiaThreads", "校准完成: ok=$ok, status=$status, rules.size=${rules.size}")
                postIfAlive {
                    if (generation != monitorGeneration) return@postIfAlive
                    mainHandler.removeCallbacks(stopTimeout)
                    timeoutClose?.let { mainHandler.removeCallbacks(it) }
                    timeoutStopSelf?.let { mainHandler.removeCallbacks(it) }
                    if (ok && status == null && !stopTimedOut) {
                        showBanner("等待守护进程响应超时\n规则可能未生成，请重试或检查日志", durationMs = 3200)
                        scheduleStopSelf(3000, generation, "manual_stop_response_timeout")
                    } else {
                        showResult(pkg, ok, status, rules)
                    }
                }
            }
        }
    }

    /** 校准结束后, 在悬浮窗里展示生成的规则结果; 3 秒后自动关闭, 用户也可点「完成」提前关闭。 */
    private fun showResult(pkg: String, ok: Boolean, status: String?, rules: List<String>) {
        if (serviceDestroyed) return
        val generation = monitorGeneration
        // Service 的 Context 没有 Theme, 需要包装一个带主题的 ContextThemeWrapper
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_QixiaThreads)
        val view = OverlayResultBinding.inflate(LayoutInflater.from(themedContext))

        if (!ok) {
            view.resultIcon.setImageResource(R.drawable.ic_error)
            view.resultTitle.text = "校准失败"
            view.resultSummary.text = "停止采样时出错，请确认已授予 root 权限"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "review") {
            view.resultIcon.setImageResource(R.drawable.ic_check_circle)
            view.resultTitle.text = "采集完成，等待确认"
            view.resultSummary.text = "返回 App 查看活跃线程和建议核心\n可调整后保存，当前配置尚未修改"
            view.rulesContainer.visibility = android.view.View.GONE
            view.resultOk.text = "返回 App 确认"
        } else if (status == "draft_fail") {
            view.resultIcon.setImageResource(R.drawable.ic_error)
            view.resultTitle.text = "待确认结果保存失败"
            view.resultSummary.text = "分核建议未能保存，当前配置未修改。已保存的采集数据可在历史记录中查看；请到设置导出诊断包，检查具体失败原因。"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "short") {
            view.resultIcon.setImageResource(R.drawable.ic_warning)
            view.resultTitle.text = "采样时长不足"
            view.resultSummary.text = "需要至少 30 秒采样才能生成准确规则\n建议重新进入游戏并多玩一会"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "no_load") {
            view.resultIcon.setImageResource(R.drawable.ic_info)
            view.resultTitle.text = "暂无可分配线程"
            view.resultSummary.text = "未检测到平均负载达到 5% 的活跃线程\n已保留的活跃线程可在历史记录中查看"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "write_fail") {
            view.resultIcon.setImageResource(R.drawable.ic_error)
            view.resultTitle.text = "写入失败"
            view.resultSummary.text = "规则生成成功但写回配置文件失败\n请检查模块权限或查看日志"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == null) {
            view.resultIcon.setImageResource(R.drawable.ic_warning)
            view.resultTitle.text = "等待超时"
            view.resultSummary.text = "已请求停止采样，但未收到守护进程完成状态\n请稍后查看历史记录或日志"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (rules.isEmpty()) {
            view.resultIcon.setImageResource(R.drawable.ic_info)
            view.resultTitle.text = "未生成规则"
            view.resultSummary.text = "本次未生成新规则\n请重试或检查日志获取详情"
            view.rulesContainer.visibility = android.view.View.GONE
        } else {
            view.resultIcon.setImageResource(R.drawable.ic_check_circle)
            view.resultTitle.text = "校准成功"
            view.resultSummary.text = "已为 $pkg 生成 ${rules.size} 条大小核分配规则"
            view.rulesContainer.visibility = android.view.View.VISIBLE
            view.resultRules.text = rules.joinToString("\n")
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            resultOverlayWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        // 手动点击与自动超时共用收口: 只会真正移除+stopSelf 一次
        var closed = false
        val close = object : Runnable {
            override fun run() {
                if (closed) return
                closed = true
                mainHandler.removeCallbacks(this)  // 取消未触发的自动关闭
                try { windowManager.removeView(view.root) } catch (_: Exception) {}
                if (resultView === view.root) resultView = null
                if (generation == monitorGeneration) stopNormally("result_closed")
            }
        }
        view.resultOk.setOnClickListener {
            if (status == "review") runCatching {
                startActivity(Intent(this, QixiaThreadsComposeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            close.run()
        }
        resultView = view.root
        // 同 addCapsule: 悬浮窗权限可能已撤销, addView 抛异常时静默放弃此结果浮层并关闭服务
        try {
            windowManager.addView(view.root, lp)
            // 3 秒后自动关闭(点击「完成」会提前触发并取消此定时)
            mainHandler.postDelayed(close, 6000)
        } catch (e: Exception) {
            if (resultView === view.root) resultView = null
            // addView 失败(权限撤销/窗口异常), 停止服务避免空跑
            if (generation == monitorGeneration) stopNormally("result_window_failed")
        }
    }

    private fun revertToYellow() {
        if (serviceDestroyed) return
        calibrating = false
        calibrationStartPending = false
        FloatingBallSessionState.setCalibrating(this, false)
        capsuleContainer.contentDescription = getString(R.string.floating_description_idle, getString(R.string.app_name))
        capsule.setBackgroundResource(R.drawable.capsule_yellow)
        updateCapsuleText()
    }

    private fun resultOverlayWidth(): Int {
        val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            metrics.bounds.width() - insets.left - insets.right
        } else {
            resources.displayMetrics.widthPixels
        }
        return minOf(dp(340f), (screenWidth - dp(24f)).coerceAtLeast(1))
    }

    private fun removeCapsule() {
        if (!capsuleAdded) return
        try {
            windowManager.removeView(capsuleContainer)
        } catch (_: Exception) {
        } finally {
            capsuleAdded = false
        }
    }

    private fun toast(msg: String) {
        AppToast.show(this, msg)
    }

    /**
     * 在屏幕上方显示一个自动消失的提示横幅(比系统 toast 更醒目, 游戏里不易被压制)。
     * durationMs 后自动移除。
     */
    private fun showBanner(msg: String, durationMs: Long = 3200) {
        if (serviceDestroyed) return
        val tv = TextView(this).apply {
            text = msg
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_banner)
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64f)
        }
        try {
            // 先移除上一条横幅, 避免叠加
            bannerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            bannerView = tv
            windowManager.addView(tv, lp)
            mainHandler.postDelayed({
                try { windowManager.removeView(tv) } catch (_: Exception) {}
                if (bannerView === tv) bannerView = null
            }, durationMs)
        } catch (_: Exception) {
        }
    }

    // ---- 前台监测: 目标应用退出/切后台时自动关闭悬浮球 ----

    private var appearGraceLeft = FG_APPEAR_GRACE

    private val foregroundWatcher = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            val generation = monitorGeneration
            val pkg = targetPkg
            if (pkg.isNullOrBlank() || foregroundClosing) return
            val checkPkg = launchPkg ?: pkg
            val needLaunchProcessCheck = !hasAppearedForeground
            if (foregroundCheckGeneration == generation) {
                android.util.Log.d(
                    "QixiaThreads",
                    "FloatingBallService foreground check: target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} check=$checkPkg source=previous_running action=skip foreground=false usage=false focused= appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount confirmed=true notConfirmed=$launchProcessMissingCount graceLeft=$appearGraceLeft"
                )
                mainHandler.postDelayed(this, FG_CHECK_INTERVAL)
                return
            }
            foregroundCheckGeneration = generation
            val tracker = foregroundTracker
            thread {
                var foreground = false
                var processRunning = true
                var usageForeground = false
                var usageState: ForegroundDetector.State? = null
                var focusedPkg: String? = null
                var checkKey = "unknown"
                var checkDetail = ""
                try {
                    val usageAccess = ForegroundDetector.hasUsageAccess(this@FloatingBallService)
                    if (usageAccess) {
                        usageState = tracker.queryState(this@FloatingBallService, checkPkg)
                        usageForeground = usageState.foreground
                    }
                    val taskState = DaemonBridge.readTaskForegroundState()
                    if (taskState.available) {
                        focusedPkg = taskState.packageName
                        foreground = focusedPkg == checkPkg
                        processRunning = if (needLaunchProcessCheck) foreground else true
                        checkKey = when {
                            foreground -> "activity-task"
                            focusedPkg == packageName -> "activity-task-self"
                            else -> "activity-task-other"
                        }
                        checkDetail = "ActivityTaskManager mode=${taskState.mode} age=${taskState.ageMs ?: -1}ms " +
                            "generation=${taskState.generation ?: 0} reason=${taskState.reason} " +
                            "selection=${taskState.selection} task=${taskState.taskId ?: 0} " +
                            "display=${taskState.displayId ?: -1} visible=${taskState.visiblePackages.joinToString("|")} " +
                            "usageAccess=$usageAccess"
                    } else {
                        val topState = DaemonBridge.readTopAppState(checkPkg)
                        focusedPkg = DaemonBridge.readFocusedPackage()
                        val focusMatchesTarget = focusedPkg == checkPkg
                        val focusShowsOther = !focusedPkg.isNullOrBlank() && !focusMatchesTarget
                        val topHasSignal = topState.targetTopApp || topState.scanned > 0 || topState.packages.isNotEmpty()
                        val taskFallback = "atm=${taskState.status}/${taskState.mode} " +
                            "age=${taskState.ageMs ?: -1}ms error=${taskState.error.ifBlank { "none" }}"

                        val usageSignal = when {
                            !usageAccess -> "none"
                            usageState?.error?.isNotBlank() == true -> "none"
                            usageForeground -> "target"
                            usageState?.currentPackage?.isNotBlank() == true -> "other"
                            else -> "none"
                        }
                        val cgroupSignal = when {
                            topState.targetTopApp -> "target"
                            topHasSignal -> "other"
                            else -> "none"
                        }
                        val focusSignal = when {
                            focusMatchesTarget -> "target"
                            focusShowsOther -> "other"
                            else -> "none"
                        }
                        val targetVotes = listOf(usageSignal, cgroupSignal, focusSignal).count { it == "target" }
                        val otherVotes = listOf(usageSignal, cgroupSignal, focusSignal).count { it == "other" }
                        val signalCount = targetVotes + otherVotes
                        val noSignal = signalCount == 0

                        foreground = when {
                            otherVotes >= 2 -> false
                            targetVotes >= 2 -> true
                            targetVotes == 1 && otherVotes == 0 -> true
                            otherVotes == 1 && targetVotes == 0 -> false
                            focusSignal == "target" -> true
                            focusSignal == "other" -> false
                            else -> false
                        }
                        processRunning = when {
                            foreground -> true
                            needLaunchProcessCheck && signalCount == 0 -> true
                            needLaunchProcessCheck -> false
                            else -> true
                        }
                        checkKey = when {
                            targetVotes >= 2 || otherVotes >= 2 -> "fallback-vote"
                            focusSignal == "target" -> "dumpsys"
                            focusSignal == "other" -> if (focusedPkg == packageName) "focus-self" else "focus-other"
                            cgroupSignal == "target" -> "cgroup-top"
                            cgroupSignal == "other" -> "cgroup-other"
                            usageSignal == "target" -> "usage"
                            usageSignal == "other" -> "usage-other"
                            noSignal && hasAppearedForeground -> "no-signal"
                            else -> "not-confirmed"
                        }
                        checkDetail = "fallback vote targetVotes=$targetVotes otherVotes=$otherVotes " +
                            "usage=$usageSignal usagePkg=${usageState?.currentPackage.orEmpty()} " +
                            "cgroup=$cgroupSignal focused=${focusedPkg.orEmpty()} " +
                            "backend=${topState.backend} pid=${topState.pid ?: 0} scanned=${topState.scanned} " +
                            "packages=${topState.packages.joinToString("|")}; $taskFallback"
                    }
                } catch (e: Exception) {
                    checkKey = "error"
                    checkDetail = e.message.orEmpty()
                } finally {
                    postIfAlive {
                        if (generation != monitorGeneration) return@postIfAlive
                        if (foregroundCheckGeneration == generation) {
                            foregroundCheckGeneration = -1L
                        }
                        onForegroundChecked(
                            ForegroundCheckSnapshot(
                                generation = generation,
                                checkPkg = checkPkg,
                                foreground = foreground,
                                processRunning = processRunning,
                                usageForeground = usageForeground,
                                usagePackage = usageState?.currentPackage,
                                usageLastEventPackage = usageState?.lastEventPackage,
                                usageLastEventType = usageState?.lastEventType ?: -1,
                                usageEventCount = usageState?.eventCount ?: 0,
                                usageResumedCount = usageState?.resumedCount ?: 0,
                                focusedPkg = focusedPkg,
                                source = checkKey,
                                detail = checkDetail
                            )
                        )
                    }
                }
            }
            mainHandler.postDelayed(this, FG_CHECK_INTERVAL)
        }
    }

    private fun onForegroundChecked(snapshot: ForegroundCheckSnapshot) {
        if (serviceDestroyed) return
        if (snapshot.generation != monitorGeneration) return
        if (snapshot.checkPkg != (launchPkg ?: targetPkg)) return
        if (foregroundClosing) return
        var action = "observe"
        if (snapshot.foreground) {
            hasAppearedForeground = true
            markFpsSessionRunning()
            absentCount = 0
            launchProcessMissingCount = 0
            val shouldAutoStart =
                autoStartCalibrationPending && !calibrating && !autoStartScheduled &&
                    !autoStartNoticePending
            logForegroundCheck(
                snapshot,
                action = if (shouldAutoStart) "foreground_auto_start" else "foreground"
            )
            if (shouldAutoStart) {
                if (autoStartDelayElapsed) {
                    autoStartDelayElapsed = false
                    beginAutomaticCalibrationWithNotice(snapshot.generation)
                } else {
                    scheduleAutoStartCalibration(snapshot.generation)
                }
            }
            return
        }
        // 不在前台
        if (autoStartScheduled || autoStartDelayElapsed || autoStartNoticePending) {
            cancelAutoStartDelay()
            android.util.Log.d(
                "QixiaThreads",
                "calibration auto start delay cancelled: pkg=${targetPkg.orEmpty()} reason=foreground_lost"
            )
        }
        if (!hasAppearedForeground) {
            // 目标 App 可能已经打开, 但部分 ROM/管控环境会漏掉 UsageStats 前台事件。
            // 启动阶段若新 C/dumpsys 都无法确认目标前台, 说明目标 App 基本没有成功切到前台。
            if (!snapshot.processRunning) {
                launchProcessMissingCount++
                action = "wait_process"
            } else {
                launchProcessMissingCount = 0
                action = "wait_foreground"
            }
            val processMissLimit = if (manualLaunchExpected) {
                FG_MANUAL_LAUNCH_PROCESS_MISS_LIMIT
            } else {
                FG_LAUNCH_PROCESS_MISS_LIMIT
            }
            if (!snapshot.processRunning && launchProcessMissingCount >= processMissLimit) {
                action = "close"
                logForegroundCheck(snapshot, action = action)
                closeByForeground(
                    appeared = false,
                    reason = "target_not_confirmed",
                    focusedPkg = snapshot.focusedPkg,
                    source = snapshot.source,
                    detail = snapshot.detail
                )
                return
            }
            if (--appearGraceLeft <= 0) {
                action = "grace_expired"
                logForegroundCheck(snapshot, action = action)
                if (!calibrating) {
                    closeByForeground(
                        appeared = false,
                        reason = "target_not_confirmed",
                        focusedPkg = snapshot.focusedPkg,
                        source = snapshot.source,
                        detail = snapshot.detail
                    )
                    return
                }
                appearGraceLeft = if (manualLaunchExpected) {
                    FG_MANUAL_LAUNCH_GRACE
                } else {
                    FG_APPEAR_GRACE
                }
            } else {
                logForegroundCheck(snapshot, action = action)
            }
            return
        }
        // 曾在前台, 现在离开: 累计到阈值即关闭
        absentCount++
        if (absentCount >= FG_ABSENT_LIMIT) {
            logForegroundCheck(snapshot, action = "close")
            closeByForeground(
                appeared = true,
                reason = "left_foreground",
                focusedPkg = snapshot.focusedPkg,
                source = snapshot.source,
                detail = snapshot.detail
            )
        } else {
            logForegroundCheck(snapshot, action = "absent_counting")
        }
    }

    private fun logForegroundCheck(snapshot: ForegroundCheckSnapshot, action: String) {
        val pkg = launchPkg ?: targetPkg ?: ""
        android.util.Log.d(
            "QixiaThreads",
            "FloatingBallService foreground check: target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} check=${snapshot.checkPkg} pkg=$pkg source=${snapshot.source} action=$action foreground=${snapshot.foreground} usage=${snapshot.usageForeground} usagePkg=${snapshot.usagePackage.orEmpty()} usageEvents=${snapshot.usageEventCount} usageResumed=${snapshot.usageResumedCount} usageLast=${snapshot.usageLastEventPackage.orEmpty()} usageLastType=${snapshot.usageLastEventType} focused=${snapshot.focusedPkg.orEmpty()} appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount confirmed=${snapshot.processRunning} notConfirmed=$launchProcessMissingCount graceLeft=$appearGraceLeft ${snapshot.detail}"
        )
    }

    /**
     * 因目标应用离开前台(或始终未出现)而自动关闭。
     * 校准中则先停止采样; 关闭前显示明确横幅, 待横幅可见后再真正 stopSelf。
     */
    private fun closeByForeground(
        appeared: Boolean,
        reason: String = if (appeared) "left_foreground" else "target_not_confirmed",
        focusedPkg: String? = null,
        source: String? = null,
        detail: String? = null
    ) {
        if (serviceDestroyed) return
        if (foregroundClosing) return
        android.util.Log.d(
            "QixiaThreads",
            "FloatingBallService auto close: reason=$reason appeared=$appeared calibrating=$calibrating target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} focused=${focusedPkg.orEmpty()} absent=$absentCount notConfirmed=$launchProcessMissingCount source=${source.orEmpty()} detail=${detail.orEmpty()}"
        )
        foregroundClosing = true
        val generation = monitorGeneration
        mainHandler.removeCallbacks(foregroundWatcher)
        removeCapsule()
        val pkg = targetPkg ?: ""
        finishFpsSession("foreground_close:$reason", pkg)

        val wasCalibrating = calibrating
        calibrating = false
        calibrationStartPending = false
        ++calibrationCommandGeneration
        FloatingBallSessionState.setCalibrating(this, false)

        if (wasCalibrating && appeared && pkg.isNotBlank()) {
            showBanner("正在结束校准…", durationMs = 3500)
            CalibrationCommandDispatcher.execute {
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) {
                    DaemonBridge.waitDone(pkg, timeoutMs = MANUAL_WAIT_DONE_MS)
                } else {
                    null
                }
                if (status != null) {
                    importCalibrationHistory(pkg, "foreground_close:$status")
                }
                val rules = if (status == "ok") DaemonBridge.readPkgRules(pkg) else emptyList()
                android.util.Log.d(
                    "QixiaThreads",
                    "FloatingBallService foreground stop calibration result: pkg=$pkg ok=$ok status=$status rules=${rules.size}"
                )

                postIfAlive {
                    if (generation != monitorGeneration) return@postIfAlive
                    showResult(pkg, ok, status, rules)
                }
            }
        } else {
            // 未校准或未出现: 直接显示提示并关闭
            val msg = when {
                appeared -> "已退出游戏，悬浮球已关闭"
                else     -> "未检测到目标应用启动\n悬浮球已自动关闭"
            }
            showBanner(msg, durationMs = 2600)
            scheduleStopSelf(2200, generation, "foreground_close:$reason")
        }
    }

    override fun onDestroy() {
        val wasCalibrating = calibrating
        val wasSessionActive = FloatingBallSessionState.isActive(this)
        val unexpectedStop = expectedStopReason == null && wasSessionActive
        android.util.Log.d(
            "QixiaThreads",
            "FloatingBallService onDestroy target=$targetPkg calibrating=$wasCalibrating " +
                "expected=${expectedStopReason.orEmpty()} unexpected=$unexpectedStop"
        )
        val pkgToStop = targetPkg?.takeIf { it.isNotBlank() && wasCalibrating }
        calibrating = false
        calibrationStartPending = false
        ++calibrationCommandGeneration
        autoStartCalibrationPending = false
        cancelAutoStartDelay()
        serviceDestroyed = true
        if (::historyMetricsSampler.isInitialized) historyMetricsSampler.close()
        runningInProcess = false
        if (unexpectedStop) {
            FloatingBallSessionState.reportUnexpectedStop(this, targetPkg, wasCalibrating)
        } else if (wasSessionActive) {
            FloatingBallSessionState.markExpectedStop(this, expectedStopReason ?: "service_destroyed")
        }
        monitorGeneration++
        cancelPendingStop()
        mainHandler.removeCallbacksAndMessages(null)
        finishFpsSession(expectedStopReason ?: "service_destroyed")
        // fpsMonitor 在 onStartCommand 才初始化; 服务若在那之前被回收, 直接访问会崩
        if (::fpsMonitor.isInitialized) fpsMonitor.stop()
        // 通知守护进程停止 FPS 监测(省电)。su 是独立进程,
        // 即使本进程随后退出, 已 fork 的命令仍会执行完。
        if (pkgToStop != null) {
            CalibrationCommandDispatcher.execute {
                val ok = DaemonBridge.stopCalibration(pkgToStop)
                val status = if (ok) {
                    DaemonBridge.waitDone(pkgToStop, timeoutMs = BACKGROUND_WAIT_DONE_MS)
                } else {
                    null
                }
                if (status != null) {
                    importCalibrationHistory(pkgToStop, "service_destroy:$status")
                }
            }
        }
        FPS_COMMAND_EXECUTOR.execute {
            DaemonBridge.stopFpsMonitor()
        }
        removeCapsule()
        bannerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bannerView = null
        resultView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        resultView = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedTarget = intent?.getStringExtra(EXTRA_TARGET_PKG)
            ?.takeIf { it.isNotBlank() } ?: targetPkg
        if (requestedTarget.isNullOrBlank()) {
            android.util.Log.d("QixiaThreads", "FloatingBallService stop: missing target package")
            stopNormally("missing_target_package")
            return START_NOT_STICKY
        }
        val requestedLaunch = intent?.getStringExtra(EXTRA_LAUNCH_PKG)
            ?.takeIf { it.isNotBlank() } ?: requestedTarget
        val requestedAutoStart = intent?.getBooleanExtra(EXTRA_AUTO_START_CALIBRATION, false) == true
        val requestedAutoStartDelay = AutoStartCalibrationDelay.normalize(
            intent?.getLongExtra(EXTRA_AUTO_START_DELAY_MS, 0L) ?: 0L
        )
        val requestedManualLaunch = intent?.getBooleanExtra(EXTRA_MANUAL_LAUNCH, false) == true
        val previousTarget = targetPkg
        val targetChanged = !previousTarget.isNullOrBlank() && previousTarget != requestedTarget
        val continuingCalibration = previousTarget == requestedTarget && calibrating
        val previousAppearedForeground = hasAppearedForeground
        val previousAbsentCount = absentCount
        val previousLaunchProcessMissingCount = launchProcessMissingCount
        if (targetChanged) finishFpsSession("target_changed", previousTarget)
        if (targetChanged && calibrating) {
            calibrating = false
            calibrationStartPending = false
            ++calibrationCommandGeneration
            CalibrationCommandDispatcher.execute {
                val pkg = previousTarget!!
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) {
                    DaemonBridge.waitDone(pkg, timeoutMs = BACKGROUND_WAIT_DONE_MS)
                } else {
                    null
                }
                if (status != null) {
                    importCalibrationHistory(pkg, "target_changed:$status")
                }
            }
        }

        monitorGeneration++
        val generation = monitorGeneration
        cancelPendingStop()
        cancelAutoStartDelay()
        mainHandler.removeCallbacks(foregroundWatcher)
        bannerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bannerView = null
        resultView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        resultView = null
        foregroundClosing = false
        targetPkg = requestedTarget
        launchPkg = requestedLaunch
        manualLaunchExpected = requestedManualLaunch
        autoStartCalibrationPending = requestedAutoStart && !calibrating && !calibrationStartPending
        autoStartDelayMs = requestedAutoStartDelay
        expectedStopReason = null
        FloatingBallSessionState.begin(this, requestedTarget, calibrating)
        fpsSessionRecorder.begin(requestedTarget)
        hasAppearedForeground = if (continuingCalibration) previousAppearedForeground else false
        absentCount = if (continuingCalibration) previousAbsentCount else 0
        launchProcessMissingCount = if (continuingCalibration) previousLaunchProcessMissingCount else 0
        foregroundCheckGeneration = -1L
        appearGraceLeft = if (manualLaunchExpected) FG_MANUAL_LAUNCH_GRACE else FG_APPEAR_GRACE
        foregroundTracker = ForegroundDetector.Tracker()
        if (previousTarget != requestedTarget) {
            latestFps = 0f
            latestFpsUpdatedAtElapsed = 0L
        }
        if (capsuleAdded && ::capsule.isInitialized) {
            capsule.setBackgroundResource(
                if (calibrating) R.drawable.capsule_red else R.drawable.capsule_yellow
            )
            updateCapsuleText()
        }

        addCapsule()
        if (::capsuleContainer.isInitialized) {
            capsuleContainer.contentDescription = if (calibrationStartPending) {
                getString(R.string.floating_description_starting, getString(R.string.app_name))
            } else if (calibrating) {
                getString(R.string.floating_description_calibrating, getString(R.string.app_name))
            } else if (autoStartCalibrationPending) {
                if (autoStartDelayMs > 0L) {
                    getString(R.string.floating_description_auto_delayed, getString(R.string.app_name), AutoStartCalibrationDelay.label(autoStartDelayMs))
                } else {
                    getString(R.string.floating_description_auto, getString(R.string.app_name))
                }
            } else {
                getString(R.string.floating_description_idle, getString(R.string.app_name))
            }
        }
        // 每个目标使用独立 socket 与实时快照，避免切换应用时沿用上一目标的 FPS。
        // 文件监听仅在 socket 连接失败时兜底。
        if (::fpsMonitor.isInitialized) fpsMonitor.stop()
        val fpsGeneration = generation
        fpsMonitor = FrameRateMonitor(this) fpsCallback@{ fps ->
            if (serviceDestroyed || fpsGeneration != monitorGeneration) return@fpsCallback
            latestFps = fps
            latestFpsUpdatedAtElapsed = SystemClock.elapsedRealtime()
        }
        fpsMonitor.start()
        mainHandler.removeCallbacks(fpsSampleTicker)
        mainHandler.postDelayed(fpsSampleTicker, FPS_SAMPLE_INTERVAL_MS)
        // 有目标应用时: 通知守护进程开始帧率监测(差分读 SF, 不清缓冲, 不干扰 Scene)
        // + 启动前台监测看门狗。
        if (!targetPkg.isNullOrBlank()) {
            val pkg = targetPkg!!
            val fpsSocketName = fpsMonitor.socketName
            val fpsSocketToken = fpsMonitor.socketToken
            android.util.Log.d(
                "QixiaThreads",
                "FloatingBallService start fps monitor: pkg=$pkg socket=${!fpsSocketName.isNullOrBlank()}"
            )
            FPS_COMMAND_EXECUTOR.execute {
                val helperOk = DaemonBridge.ensureTaskForegroundHelper()
                android.util.Log.d("QixiaThreads", "FloatingBallService foreground helper ensure: ok=$helperOk")
                val ok = DaemonBridge.startFpsMonitor(pkg, fpsSocketName, fpsSocketToken)
                android.util.Log.d("QixiaThreads", "FloatingBallService fps command result: pkg=$pkg ok=$ok")
                if (!ok) {
                    postIfAlive {
                        if (generation == monitorGeneration) {
                            toast("帧率监测下发失败, 请确认 root")
                        }
                    }
                }
            }
            val firstCheckDelay = if (autoStartCalibrationPending) {
                AUTO_START_FIRST_CHECK_DELAY
            } else {
                FG_CHECK_INTERVAL
            }
            mainHandler.postDelayed(foregroundWatcher, firstCheckDelay)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
