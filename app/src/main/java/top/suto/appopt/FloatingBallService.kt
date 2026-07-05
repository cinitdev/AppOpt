package top.suto.appopt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import top.suto.appopt.databinding.OverlayResultBinding

/**
 * 悬浮球前台服务。
 *
 * 黄色胶囊 = 待机, 中间显示实时帧率;
 * 点击 -> 红色胶囊 = 校准中, 向守护进程下发 start <前台包名>, 持续记录线程负载;
 * 再次点击 -> 胶囊消失, 下发 stop, 守护进程据采样生成大小核规则并回写配置。
 *
 * 胶囊可拖动; 区分点击与拖动靠移动阈值判定。
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var capsule: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var calibrating = false
    private var targetPkg: String? = null
    private var launchPkg: String? = null
    // FileObserver 回调线程写、主线程读, 需 @Volatile 保证可见性(否则主线程可能读到陈旧值)
    @Volatile private var currentFps = 0f

    // 前台监测: 目标应用一旦退出/切后台, 自动移除悬浮球
    private var hasAppearedForeground = false   // 目标应用是否已经在前台出现过(拉起成功)
    private var absentCount = 0                  // 连续检测到不在前台的次数
    private var foregroundClosing = false        // 是否已因离开前台进入关闭流程
    private var launchProcessMissingCount = 0    // 启动阶段连续无法确认目标前台的次数

    private lateinit var fpsMonitor: FrameRateMonitor

    // 当前显示的提示横幅(用于 onDestroy 兜底清理, 避免 stopSelf 后泄漏)
    private var bannerView: View? = null

    // 当前显示的结果卡片(同上, onDestroy 兜底清理)
    private var resultView: View? = null
    private var capsuleAdded = false
    @Volatile private var serviceDestroyed = false
    private var foregroundTracker = ForegroundDetector.Tracker()
    private var usageAccessMissingCount = 0
    private var foregroundCheckGeneration = -1L
    private var monitorGeneration = 0L
    private var pendingStopRunnable: Runnable? = null

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
    private var dragged = false

    companion object {
        private const val CHANNEL_ID = "appopt_floating"
        private const val NOTIF_ID = 1001
        private const val DRAG_THRESHOLD_DP = 12f
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_LAUNCH_PKG = "launch_pkg"

        // 前台监测周期与离开阈值
        private const val FG_CHECK_INTERVAL = 3000L   // 每 3s 检查一次目标应用是否在前台
        private const val FG_ABSENT_LIMIT = 2         // 连续 2 次(约6s)不在前台才判定离开, 避免下拉通知栏等短暂切换误关
        private const val FG_APPEAR_GRACE = 15        // 启动后等待应用出现的宽限次数(约45s)
        private const val FG_LAUNCH_PROCESS_MISS_LIMIT = 3
        private const val FG_USAGE_ACCESS_MISSING_LIMIT = 3
        private const val MANUAL_STOP_TIMEOUT_MS = 18_000L
        private const val MANUAL_STOP_CLOSE_DELAY_MS = 20_000L
        private const val MANUAL_WAIT_DONE_MS = 22_000L
        private const val BACKGROUND_WAIT_DONE_MS = 5_000L
        private val FPS_COMMAND_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AppOptFpsCommand").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("AppOpt", "FloatingBallService onCreate")
        serviceDestroyed = false
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

    private fun importCalibrationHistory(pkg: String, reason: String) {
        try {
            val result = DatabaseMigrator.migrateIfNeeded(applicationContext, pkg)
            android.util.Log.d(
                "AppOpt",
                "FloatingBallService history import: target=$pkg reason=$reason " +
                    "source=${result.sourceFound} imported=${result.importedSessions} " +
                    "claims=${result.processedClaims} invalid=${result.invalidClaim} " +
                    "cleanupFailed=${result.completionFailed}"
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "AppOpt",
                "FloatingBallService history import failed: target=$pkg reason=$reason",
                e
            )
        }
    }

    private fun cancelPendingStop() {
        pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStopRunnable = null
    }

    private fun scheduleStopSelf(delayMs: Long, generation: Long = monitorGeneration) {
        cancelPendingStop()
        val stop = Runnable {
            pendingStopRunnable = null
            if (!serviceDestroyed && generation == monitorGeneration) stopSelf()
        }
        pendingStopRunnable = stop
        mainHandler.postDelayed(stop, delayMs)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "AppOpt 悬浮球",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AppOpt 线程优化")
            .setContentText("悬浮球运行中, 点击胶囊开始/结束校准")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        ).toInt()

    private fun addCapsule() {
        if (capsuleAdded) return
        capsule = TextView(this).apply {
            text = "0.0"
            setTextColor(resources.getColor(R.color.capsule_text, theme))
            textSize = 13f
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

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // 固定尺寸: 扁胶囊, 按最宽内容("● 120.0")预留, 避免帧率变化导致忽大忽小
        layoutParams = WindowManager.LayoutParams(
            dp(45f),
            dp(30f),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f)
            y = dp(120f)
        }

        capsule.setOnTouchListener { _, event -> handleTouch(event) }
        // 服务被系统重建或悬浮窗权限被撤销时, addView 会抛 BadTokenException。
        // 包裹后失败则直接 stopSelf, 避免崩溃 + 避免空跑一个看不见的服务。
        try {
            windowManager.addView(capsule, layoutParams)
            capsuleAdded = true
            android.util.Log.d("AppOpt", "FloatingBallService capsule added")
        } catch (e: Exception) {
            android.util.Log.e("AppOpt", "FloatingBallService add capsule failed: ${e.message}")
            stopSelf()
        }
    }

    private fun updateCapsuleText() {
        if (serviceDestroyed) return
        if (!::capsule.isInitialized) return
        // 显示游戏真实渲染帧率(带 1 位小数, 由守护进程直连 binder 解析 SF 帧时间戳得出);
        // 校准中加前缀红点。<=0 表示尚未收到数据或未在监测。
        val label = if (currentFps > 0f) String.format(Locale.US, "%.1f", currentFps) else "0.0"
        capsule.text = if (calibrating) label else label
    }
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                touchX = event.rawX
                touchY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                val baseThreshold = dp(DRAG_THRESHOLD_DP).toFloat()
                val dragThreshold = if (calibrating) baseThreshold * 2f else baseThreshold
                if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) {
                    dragged = true
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(capsule, layoutParams)
                    } catch (_: Exception) {
                        stopSelf()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                val stopClickThreshold = dp(DRAG_THRESHOLD_DP).toFloat() * 2.5f
                val isStopTap = calibrating && abs(dx) <= stopClickThreshold && abs(dy) <= stopClickThreshold
                if (!dragged || isStopTap) onCapsuleClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragged = false
                return true
            }
        }
        return false
    }
    private fun onCapsuleClick() {
        if (!calibrating) {
            // 黄 -> 红: 开始校准。目标包名由启动 App 时通过 Intent 指定。
            val pkg = targetPkg
            if (pkg.isNullOrBlank()) {
                android.util.Log.d("AppOpt", "calibration start ignored: target package empty")
                toast("未指定目标应用, 请从优化 App 内启动")
                return
            }
            android.util.Log.d("AppOpt", "calibration start: pkg=$pkg")
            calibrating = true
            // 用户能点击悬浮球开始校准, 说明目标 App 会话已经成立。
            // 部分 ROM 的 UsageStats 会漏掉目标进入前台事件; 这里避免后续一直卡在"等待目标出现"阶段。
            hasAppearedForeground = true
            absentCount = 0
            launchProcessMissingCount = 0
            capsule.setBackgroundResource(R.drawable.capsule_red)
            updateCapsuleText()
            showBanner("● 开始记录应用负载\n请正常操作游戏, 完成后再次点击胶囊结束", durationMs = 3500)
            thread {
                val ok = DaemonBridge.startCalibration(pkg)
                android.util.Log.d("AppOpt", "calibration start command result: pkg=$pkg ok=$ok")
                if (!ok) postIfAlive {
                    showBanner("下发失败, 请确认已授予 root", durationMs = 3000)
                    revertToYellow()
                }
            }
        } else {
            // 红 -> 停止采样并生成规则; 移除胶囊, 弹出结果卡片
            val generation = monitorGeneration
            val pkg = targetPkg ?: ""
            android.util.Log.d(
                "AppOpt",
                "FloatingBallService manual stop: reason=manual_stop target=$pkg launch=${launchPkg.orEmpty()} appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount"
            )
            calibrating = false
            // 用户主动停止: 关闭前台监测, 避免查看结果卡片时被自动关闭打断
            foregroundClosing = true
            mainHandler.removeCallbacks(foregroundWatcher)
            removeCapsule()
            showBanner("正在分析负载并生成规则…", durationMs = 3500)
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
                        if (generation == monitorGeneration) stopSelf()
                    }
                    timeoutStopSelf = stop
                    mainHandler.postDelayed(stop, 3200)
                }
                timeoutClose = close
                mainHandler.postDelayed(close, MANUAL_STOP_CLOSE_DELAY_MS)
            }
            mainHandler.postDelayed(stopTimeout, MANUAL_STOP_TIMEOUT_MS)
            thread {
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) DaemonBridge.waitDone(pkg, timeoutMs = MANUAL_WAIT_DONE_MS) else null
                if (status != null) {
                    importCalibrationHistory(pkg, "manual_stop:$status")
                }
                val rules = if (status == "ok") DaemonBridge.readPkgRules(pkg) else emptyList()
                android.util.Log.d("AppOpt", "校准完成: ok=$ok, status=$status, rules.size=${rules.size}")
                postIfAlive {
                    if (generation != monitorGeneration) return@postIfAlive
                    mainHandler.removeCallbacks(stopTimeout)
                    timeoutClose?.let { mainHandler.removeCallbacks(it) }
                    timeoutStopSelf?.let { mainHandler.removeCallbacks(it) }
                    if (ok && status == null && !stopTimedOut) {
                        showBanner("等待守护进程响应超时\n规则可能未生成，请重试或检查日志", durationMs = 3200)
                        scheduleStopSelf(3000, generation)
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
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_AppOpt)
        val view = OverlayResultBinding.inflate(LayoutInflater.from(themedContext))

        if (!ok) {
            view.resultIcon.setImageResource(R.drawable.ic_error)
            view.resultTitle.text = "校准失败"
            view.resultSummary.text = "停止采样时出错，请确认已授予 root 权限"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "short") {
            view.resultIcon.setImageResource(R.drawable.ic_warning)
            view.resultTitle.text = "采样时长不足"
            view.resultSummary.text = "需要至少 30 秒采样才能生成准确规则\n建议重新进入游戏并多玩一会"
            view.rulesContainer.visibility = android.view.View.GONE
        } else if (status == "no_load") {
            view.resultIcon.setImageResource(R.drawable.ic_info)
            view.resultTitle.text = "负载过低"
            view.resultSummary.text = "未检测到明显的负载变化\n建议在游戏内正常游玩时采样"
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
            WindowManager.LayoutParams.WRAP_CONTENT,
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
                if (generation == monitorGeneration) stopSelf()
            }
        }
        view.resultOk.setOnClickListener { close.run() }
        resultView = view.root
        // 同 addCapsule: 悬浮窗权限可能已撤销, addView 抛异常时静默放弃此结果浮层并关闭服务
        try {
            windowManager.addView(view.root, lp)
            // 3 秒后自动关闭(点击「完成」会提前触发并取消此定时)
            mainHandler.postDelayed(close, 6000)
        } catch (e: Exception) {
            if (resultView === view.root) resultView = null
            // addView 失败(权限撤销/窗口异常), 停止服务避免空跑
            if (generation == monitorGeneration) stopSelf()
        }
    }

    private fun revertToYellow() {
        if (serviceDestroyed) return
        calibrating = false
        capsule.setBackgroundResource(R.drawable.capsule_yellow)
        updateCapsuleText()
    }

    private fun removeCapsule() {
        if (!capsuleAdded) return
        try {
            windowManager.removeView(capsule)
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
            // 没有使用情况访问权限时无法判断前台。短暂不可用先重试, 连续不可用则收尾关闭,
            // 避免悬浮球在权限被系统回收或用户关闭后长期残留。
            if (!ForegroundDetector.hasUsageAccess(this@FloatingBallService)) {
                usageAccessMissingCount++
                android.util.Log.d(
                    "AppOpt",
                    "FloatingBallService foreground check: target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} check=${pkg} source=usage_access action=wait_permission foreground=false usage=false focused= appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount confirmed=true notConfirmed=$launchProcessMissingCount graceLeft=$appearGraceLeft missing=$usageAccessMissingCount"
                )
                if (usageAccessMissingCount >= FG_USAGE_ACCESS_MISSING_LIMIT) {
                    closeByUsageAccessLost()
                } else {
                    mainHandler.postDelayed(this, FG_CHECK_INTERVAL)
                }
                return
            }
            usageAccessMissingCount = 0
            val checkPkg = launchPkg ?: pkg
            val needLaunchProcessCheck = !hasAppearedForeground
            if (foregroundCheckGeneration == generation) {
                android.util.Log.d(
                    "AppOpt",
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
                    usageState = tracker.queryState(this@FloatingBallService, checkPkg)
                    usageForeground = usageState.foreground
                    val topState = DaemonBridge.readTopAppState(checkPkg)
                    focusedPkg = DaemonBridge.readFocusedPackage()
                    val focusMatchesTarget = focusedPkg == checkPkg
                    val focusShowsOther = !focusedPkg.isNullOrBlank() && !focusMatchesTarget
                    val topHasSignal = topState.targetTopApp || topState.scanned > 0 || topState.packages.isNotEmpty()
                    val topShowsOther = topHasSignal && !topState.targetTopApp

                    if (usageForeground && !focusShowsOther && !topShowsOther) {
                        foreground = true
                        processRunning = true
                        checkKey = "usage"
                        checkDetail = "UsageStats 命中; cgroup=${topState.targetTopApp} focused=${focusedPkg.orEmpty()} backend=${topState.backend} pid=${topState.pid ?: 0} scanned=${topState.scanned} packages=${topState.packages.joinToString("|")}"
                    } else if (topState.targetTopApp) {
                        foreground = true
                        processRunning = true
                        checkKey = "cgroup-top"
                        checkDetail = "cgroup 前台组命中目标; usage=$usageForeground focused=${focusedPkg.orEmpty()} backend=${topState.backend} pid=${topState.pid ?: 0} scanned=${topState.scanned} packages=${topState.packages.joinToString("|")}"
                    } else {
                        foreground = focusMatchesTarget
                        processRunning = if (needLaunchProcessCheck) focusMatchesTarget else true
                        checkKey = when {
                            focusMatchesTarget -> "dumpsys"
                            focusShowsOther -> if (focusedPkg == packageName) "focus-self" else "focus-other"
                            topShowsOther -> "cgroup-other"
                            else -> "not-confirmed"
                        }
                        checkDetail = if (usageForeground && (focusShowsOther || topShowsOther)) {
                            "UsageStats 命中但前台纠偏为离开目标; focused=${focusedPkg.orEmpty()} backend=${topState.backend} pid=${topState.pid ?: 0} scanned=${topState.scanned} packages=${topState.packages.joinToString("|")}"
                        } else {
                            "cgroup 前台组未命中目标, 回退 dumpsys; focused=${focusedPkg.orEmpty()} backend=${topState.backend} pid=${topState.pid ?: 0} scanned=${topState.scanned} packages=${topState.packages.joinToString("|")}"
                        }
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
            absentCount = 0
            launchProcessMissingCount = 0
            logForegroundCheck(snapshot, action = "foreground")
            return
        }
        // 不在前台
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
            if (!snapshot.processRunning && launchProcessMissingCount >= FG_LAUNCH_PROCESS_MISS_LIMIT) {
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
                appearGraceLeft = FG_APPEAR_GRACE
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
            "AppOpt",
            "FloatingBallService foreground check: target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} check=${snapshot.checkPkg} pkg=$pkg source=${snapshot.source} action=$action foreground=${snapshot.foreground} usage=${snapshot.usageForeground} usagePkg=${snapshot.usagePackage.orEmpty()} usageEvents=${snapshot.usageEventCount} usageResumed=${snapshot.usageResumedCount} usageLast=${snapshot.usageLastEventPackage.orEmpty()} usageLastType=${snapshot.usageLastEventType} focused=${snapshot.focusedPkg.orEmpty()} appeared=$hasAppearedForeground calibrating=$calibrating absent=$absentCount confirmed=${snapshot.processRunning} notConfirmed=$launchProcessMissingCount graceLeft=$appearGraceLeft ${snapshot.detail}"
        )
    }

    private fun closeByUsageAccessLost() {
        if (serviceDestroyed) return
        if (foregroundClosing) return
        android.util.Log.d(
            "AppOpt",
            "FloatingBallService auto close: reason=usage_access_lost appeared=$hasAppearedForeground calibrating=$calibrating target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} focused= absent=$absentCount notConfirmed=$launchProcessMissingCount source=usage_access detail=UsageStats permission unavailable"
        )
        foregroundClosing = true
        val generation = monitorGeneration
        mainHandler.removeCallbacks(foregroundWatcher)
        removeCapsule()

        val pkg = targetPkg ?: ""
        val wasCalibrating = calibrating
        calibrating = false

        if (wasCalibrating && pkg.isNotBlank()) {
            thread {
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) {
                    DaemonBridge.waitDone(pkg, timeoutMs = BACKGROUND_WAIT_DONE_MS)
                } else {
                    null
                }
                if (status != null) {
                    importCalibrationHistory(pkg, "usage_access_lost:$status")
                }
                postIfAlive {
                    if (generation != monitorGeneration) return@postIfAlive
                    showBanner("使用情况访问权限不可用\n校准已停止，悬浮球已关闭", durationMs = 2800)
                    scheduleStopSelf(2600, generation)
                }
            }
        } else {
            showBanner("使用情况访问权限不可用\n悬浮球已关闭", durationMs = 2800)
            scheduleStopSelf(2600, generation)
        }
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
            "AppOpt",
            "FloatingBallService auto close: reason=$reason appeared=$appeared calibrating=$calibrating target=${targetPkg.orEmpty()} launch=${launchPkg.orEmpty()} focused=${focusedPkg.orEmpty()} absent=$absentCount notConfirmed=$launchProcessMissingCount source=${source.orEmpty()} detail=${detail.orEmpty()}"
        )
        foregroundClosing = true
        val generation = monitorGeneration
        mainHandler.removeCallbacks(foregroundWatcher)
        removeCapsule()
        val pkg = targetPkg ?: ""

        val wasCalibrating = calibrating
        calibrating = false

        if (wasCalibrating && appeared && pkg.isNotBlank()) {
            // 校准中退出游戏: 快速等待 C 端返回状态并显示具体原因
            thread {
                val ok = DaemonBridge.stopCalibration(pkg)
                val status = if (ok) {
                    DaemonBridge.waitDone(pkg, timeoutMs = BACKGROUND_WAIT_DONE_MS)
                } else {
                    null
                }
                if (status != null) {
                    importCalibrationHistory(pkg, "foreground_close:$status")
                }

                val msg = when (status) {
                    "ok" -> "校准完成，规则已生成"
                    "short" -> "采样时长不足 (需要 ≥30 秒)\n建议重新进入游戏多玩一会"
                    "no_load" -> "未检测到明显负载\n建议在游戏内正常游玩时采样"
                    "write_fail" -> "规则写回配置文件失败\n请检查模块权限"
                    else -> "已退出游戏，校准已停止\n(数据已保存到历史记录)"
                }

                postIfAlive {
                    if (generation != monitorGeneration) return@postIfAlive
                    showBanner(msg, durationMs = 2600)
                    scheduleStopSelf(2600, generation)
                }
            }
        } else {
            // 未校准或未出现: 直接显示提示并关闭
            val msg = when {
                appeared -> "已退出游戏，悬浮球已关闭"
                else     -> "未检测到目标应用启动\n悬浮球已自动关闭"
            }
            showBanner(msg, durationMs = 2600)
            scheduleStopSelf(2200, generation)
        }
    }

    override fun onDestroy() {
        android.util.Log.d("AppOpt", "FloatingBallService onDestroy target=$targetPkg calibrating=$calibrating")
        val pkgToStop = targetPkg?.takeIf { it.isNotBlank() && calibrating }
        calibrating = false
        serviceDestroyed = true
        monitorGeneration++
        cancelPendingStop()
        mainHandler.removeCallbacksAndMessages(null)
        // fpsMonitor 在 onStartCommand 才初始化; 服务若在那之前被回收, 直接访问会崩
        if (::fpsMonitor.isInitialized) fpsMonitor.stop()
        // 通知守护进程停止 FPS 监测(省电)。su 是独立进程,
        // 即使本进程随后退出, 已 fork 的命令仍会执行完。
        FPS_COMMAND_EXECUTOR.execute {
            if (pkgToStop != null) {
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
            android.util.Log.d("AppOpt", "FloatingBallService stop: missing target package")
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedLaunch = intent?.getStringExtra(EXTRA_LAUNCH_PKG)
            ?.takeIf { it.isNotBlank() } ?: requestedTarget
        val previousTarget = targetPkg
        val targetChanged = !previousTarget.isNullOrBlank() && previousTarget != requestedTarget
        if (targetChanged && calibrating) {
            calibrating = false
            FPS_COMMAND_EXECUTOR.execute {
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
        mainHandler.removeCallbacks(foregroundWatcher)
        bannerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bannerView = null
        resultView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        resultView = null
        foregroundClosing = false
        targetPkg = requestedTarget
        launchPkg = requestedLaunch
        hasAppearedForeground = false
        absentCount = 0
        usageAccessMissingCount = 0
        launchProcessMissingCount = 0
        foregroundCheckGeneration = -1L
        appearGraceLeft = FG_APPEAR_GRACE
        foregroundTracker = ForegroundDetector.Tracker()
        currentFps = 0f
        if (capsuleAdded && ::capsule.isInitialized) {
            capsule.setBackgroundResource(R.drawable.capsule_yellow)
            updateCapsuleText()
        }

        addCapsule()
        // 初始化真实帧率接收器: 优先本地 socket, 文件监听仅作兜底
        if (!::fpsMonitor.isInitialized) {
            fpsMonitor = FrameRateMonitor(this) { fps ->
                currentFps = fps
                postIfAlive { updateCapsuleText() }
            }
            fpsMonitor.start()
        }
        // 有目标应用时: 通知守护进程开始帧率监测(差分读 SF, 不清缓冲, 不干扰 Scene)
        // + 启动前台监测看门狗。
        if (!targetPkg.isNullOrBlank()) {
            val pkg = targetPkg!!
            val fpsSocketName = fpsMonitor.socketName
            val fpsSocketToken = fpsMonitor.socketToken
            android.util.Log.d(
                "AppOpt",
                "FloatingBallService start fps monitor: pkg=$pkg socket=${!fpsSocketName.isNullOrBlank()}"
            )
            FPS_COMMAND_EXECUTOR.execute {
                val ok = DaemonBridge.startFpsMonitor(pkg, fpsSocketName, fpsSocketToken)
                android.util.Log.d("AppOpt", "FloatingBallService fps command result: pkg=$pkg ok=$ok")
                if (!ok) {
                    postIfAlive {
                        if (generation == monitorGeneration) {
                            toast("帧率监测下发失败, 请确认 root")
                        }
                    }
                }
            }
            mainHandler.postDelayed(foregroundWatcher, FG_CHECK_INTERVAL)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
