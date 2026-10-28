package top.qixia.threads

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** 胶囊独立于主界面和校准服务；它只发送用户指令，不自行采样或改变调度。 */
class PerformanceOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var windows: WindowManager
    private var capsule: TextView? = null
    private var capsuleContainer: FrameLayout? = null
    private var fpsMonitor: FrameRateMonitor? = null
    private var fpsGeneration = 0
    private var latestFps = Float.NaN
    private var latestFpsUpdatedAt = 0L
    private var capsuleBackground = 0
    private var packageNameTarget = ""
    private var desiredPackage = ""
    private var token: String? = null
    private var state = "idle"
    private var requested = 0L
    private var closing = false
    private var destroyed = false
    private var disablePending = false
    private var lastCompletionAt = 0L
    private var generation = 0
    private val pulse = object : Runnable {
        override fun run() {
            if (destroyed) return
            consumeState()
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("performance_recording", "性能记录胶囊", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, QixiaThreadsComposeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2406, Notification.Builder(this, "performance_recording")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("性能记录胶囊")
            .setContentText("点击胶囊开始或结束，长按关闭当前应用的性能记录")
            .setContentIntent(open).setOngoing(true).build())
        main.post(pulse)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra("target_pkg").orEmpty()
        if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { stopSelf(); return START_NOT_STICKY }
        if (intent?.getStringExtra("operation") == "leave") {
            // 原服务已经退出时，迟到的离开通知可能冷启动一个新实例。
            if (packageNameTarget.isEmpty() && desiredPackage.isEmpty()) {
                stopSelf(); return START_NOT_STICKY
            }
            if (pkg == desiredPackage || pkg == packageNameTarget) {
                desiredPackage = ""
                leave()
            }
            return START_NOT_STICKY
        }
        desiredPackage = pkg
        if (pkg == packageNameTarget && !closing) return START_NOT_STICKY
        if (token != null) { leave(); return START_NOT_STICKY }
        showPackage(pkg)
        return START_NOT_STICKY
    }

    private fun showPackage(pkg: String) {
        val revision = ++generation
        worker.execute {
            val enabled = runCatching { pkg in DaemonBridge.performanceRecordingApps() }.getOrDefault(false)
            main.post {
                if (destroyed || revision != generation || desiredPackage != pkg) return@post
                if (!enabled || !Settings.canDrawOverlays(this)) {
                    if (enabled) toast("请先在柒夏线程授予悬浮窗权限")
                    stopSelf(); return@post
                }
                packageNameTarget = pkg
                closing = false
                state = "idle"
                startFpsMonitor(pkg)
                attachCapsule()
                toast("点击开始记录，长按关闭此应用的性能记录")
            }
        }
    }

    private fun attachCapsule() {
        if (capsule != null) { render(); return }
        val text = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.capsule_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = 2f
            includeFontPadding = false
            alpha = 0.92f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 可见尺寸、配色和校准胶囊一致，外围保留 48dp 的点击区域。
        val view = FrameLayout(this).apply {
            addView(text, FrameLayout.LayoutParams(dp(45), dp(30), Gravity.CENTER))
            setOnClickListener { if (state == "idle") startRecording() else if (state == "recording") stopRecording() }
            setOnLongClickListener { disable(); true }
        }
        val params = WindowManager.LayoutParams(dp(48), dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(120) }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                        dragging = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        view.onTouchEvent(cancel); cancel.recycle()
                    }
                    if (dragging) {
                        val bounds = windows.currentWindowMetrics.bounds
                        params.x = (originX + event.rawX - downX).toInt().coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
                        params.y = (originY + event.rawY - downY).toInt().coerceIn(0, (bounds.height() - view.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
        try { windows.addView(view, params); capsule = text; capsuleContainer = view; capsuleBackground = 0; render() }
        catch (_: Exception) { toast("胶囊显示失败，请检查悬浮窗权限"); stopSelf() }
    }

    private fun startFpsMonitor(pkg: String) {
        stopFpsMonitor()
        latestFps = Float.NaN
        latestFpsUpdatedAt = 0L
        val revision = ++fpsGeneration
        val monitor = FrameRateMonitor(this) { fps ->
            main.post {
                if (!destroyed && !closing && fpsGeneration == revision && packageNameTarget == pkg) {
                    latestFps = fps
                    latestFpsUpdatedAt = SystemClock.elapsedRealtime()
                }
            }
        }
        fpsMonitor = monitor
        monitor.start()
        val socket = monitor.socketName
        val secret = monitor.socketToken
        worker.execute {
            val success = runCatching { DaemonBridge.startFpsMonitor(pkg, socket, secret) }.getOrDefault(false)
            main.post {
                if (!destroyed && fpsMonitor === monitor && !success) toast("帧率监测启动失败，请检查 Root 和模块状态")
            }
        }
    }

    private fun stopFpsMonitor() {
        fpsGeneration++
        val monitor = fpsMonitor ?: return
        fpsMonitor = null
        val socket = monitor.socketName
        val secret = monitor.socketToken
        monitor.stop()
        // 只结束本胶囊的实时输出，不停止已接管通道的校准或后台历史采样。
        if (socket != null && secret != null) worker.execute {
            runCatching { DaemonBridge.stopFpsMonitor(socket, secret) }
        }
    }

    private fun startRecording() {
        token = UUID.randomUUID().toString()
        state = "starting"
        requested = SystemClock.elapsedRealtime()
        render()
        command(true)
    }

    private fun stopRecording() {
        if (token == null || state == "stopping") return
        state = "stopping"
        requested = SystemClock.elapsedRealtime()
        render()
        command(false)
    }

    private fun command(start: Boolean) {
        val pkg = packageNameTarget
        val request = token ?: return
        worker.execute {
            val success = runCatching { DaemonBridge.performanceRecordingCommand(pkg, request, start) }.getOrDefault(false)
            main.post {
                if (!destroyed && token == request && !success) {
                    toast("指令发送失败，请检查 Root 和模块状态")
                    // 停止失败时保留会话身份，允许重试；不虚报保存完成。
                    if (start) token = null
                    state = if (token == null) "idle" else "recording"
                    render()
                }
            }
        }
    }

    private fun consumeState() {
        val request = token ?: return
        val file = File(PrivateCaptureStorage.root, "performance_recording.state")
        val values = runCatching {
            require(file.length() in 1..4096)
            file.readLines().mapNotNull { if ('=' in it) it.substringBefore('=') to it.substringAfter('=') else null }.toMap()
        }.getOrDefault(emptyMap())
        if (state == "recording" && values["state"] == "idle") {
            // 守护重启只恢复已落盘数据，不自动重放手动开始指令。
            toast("采集服务已重启，请在 App 内查看记录")
            token = null; state = "idle"
            if (closing) { continueOrStop(); return }
            render(); return
        }
        if (values["token"] == request && values["package"] == packageNameTarget) {
            when (values["state"]) {
                "recording" -> {
                    if (state == "starting") state = "recording"
                    if (state == "recording" && System.currentTimeMillis() - (values["updated_ms"]?.toLongOrNull() ?: 0) > 30_000) {
                        toast("采集服务已中断，请在 App 内查看记录")
                        stopRecording()
                    }
                }
                "done", "error" -> {
                    toast(values["message"].orEmpty().ifBlank { "记录完成，请在 App 内查看" })
                    lastCompletionAt = SystemClock.elapsedRealtime()
                    token = null; state = "idle"
                    if (closing) { continueOrStop(); return }
                }
            }
        }
        if (state in setOf("starting", "stopping") && SystemClock.elapsedRealtime() - requested > 15_000) {
            toast("守护未响应，请检查模块；离开应用会结束采集")
            if (state == "starting") stopRecording()
            else {
                // 未收到保存确认时允许重试结束，不把仍可能进行的记录显示为空闲。
                state = "recording"
                if (closing) { stopSelf(); return }
            }
        }
        render()
    }

    private fun disable() {
        if (disablePending) return
        disablePending = true
        val pkg = packageNameTarget
        worker.execute {
            val success = runCatching { DaemonBridge.setPerformanceRecording(pkg, false) }.getOrDefault(false)
            main.post {
                disablePending = false
                if (destroyed) return@post
                if (!success) { toast("关闭失败，请在 App 内重试"); return@post }
                toast("已关闭此应用的性能记录")
                desiredPackage = ""
                leave(notifyDeparture = false)
            }
        }
    }

    private fun leave(notifyDeparture: Boolean = true) {
        if (closing) return
        closing = true
        if (notifyDeparture && capsule != null &&
            (lastCompletionAt == 0L || SystemClock.elapsedRealtime()-lastCompletionAt > 5000)) {
            toast(if (token != null) "已离开应用，正在保存性能记录"
                else "已离开应用，胶囊已收起；下次进入会再次显示")
        }
        removeCapsule()
        stopFpsMonitor()
        if (token != null) stopRecording() else continueOrStop()
    }

    private fun continueOrStop() {
        if (desiredPackage.isEmpty()) stopSelf() else showPackage(desiredPackage)
    }

    private fun render() {
        val view = capsule ?: return
        val fresh = latestFpsUpdatedAt > 0 && SystemClock.elapsedRealtime() - latestFpsUpdatedAt in 0L..3500L
        val fps = if (fresh && latestFps.isFinite()) latestFps.coerceAtLeast(0f) else 0f
        val text = String.format(java.util.Locale.ROOT, "%.1f", fps)
        if (view.text.toString() != text) view.text = text
        val background = if (state == "idle") R.drawable.capsule_yellow else R.drawable.capsule_red
        if (capsuleBackground != background) {
            view.setBackgroundResource(background)
            capsuleBackground = background
        }
        val action = when (state) {
            "starting" -> "正在开始"
            "stopping" -> "正在保存"
            "recording" -> "记录中，点击结束"
            else -> "点击开始"
        }
        capsuleContainer?.contentDescription = "$text FPS，性能记录，$action，长按关闭"
    }

    private fun removeCapsule() {
        capsuleContainer?.let { runCatching { windows.removeView(it) } }
        capsuleContainer = null
        capsule = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        destroyed = true
        generation++
        main.removeCallbacksAndMessages(null)
        removeCapsule()
        stopFpsMonitor()
        // 系统回收时尽力提交结束指令；Rust 也会在前台退出或开关关闭后收尾。
        token?.let { request -> val pkg = packageNameTarget; worker.execute { runCatching { DaemonBridge.performanceRecordingCommand(pkg, request, false) } } }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
