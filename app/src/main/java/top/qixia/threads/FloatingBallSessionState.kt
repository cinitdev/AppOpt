package top.qixia.threads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 记录悬浮球会话是否正常结束，并在被系统或后台管控异常停止时提醒用户。
 */
object FloatingBallSessionState {
    private val leaseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QixiaThreadsFloatingLease").apply { isDaemon = true }
    }
    data class Incident(
        val targetPkg: String,
        val calibrating: Boolean,
        val stoppedAt: Long,
        val detectedAfterRestart: Boolean
    )

    data class ActiveSession(
        val targetPkg: String,
        val calibrating: Boolean
    )

    private const val PREFS_NAME = "floating_ball_session_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TARGET_PKG = "target_pkg"
    private const val KEY_CALIBRATING = "calibrating"
    private const val KEY_PENDING_INCIDENT = "pending_incident"
    private const val KEY_STOPPED_AT = "stopped_at"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_STARTED_ELAPSED = "started_elapsed"

    // 跨重启、跨版本安装或遗留过久的会话不属于后台管控异常。
    private const val MAX_INCIDENT_AGE_MS = 24L * 60L * 60L * 1000L

    private const val ALERT_CHANNEL_ID = "qixia_floating_alerts"
    private const val ALERT_NOTIFICATION_ID = 1002

    fun begin(context: Context, targetPkg: String, calibrating: Boolean) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TARGET_PKG, targetPkg)
            .putBoolean(KEY_CALIBRATING, calibrating)
            .putBoolean(KEY_PENDING_INCIDENT, false)
            .putString(KEY_BOOT_ID, currentBootId())
            .putLong(KEY_VERSION_CODE, currentVersionCode(context))
            .putLong(KEY_STARTED_AT, now)
            .putLong(KEY_STARTED_ELAPSED, SystemClock.elapsedRealtime())
            .remove(KEY_STOPPED_AT)
            .remove(KEY_LAST_STOP_REASON)
            .commit()
        updateRootLease(active = true, targetPkg = targetPkg, calibrating = calibrating)
    }

    fun setCalibrating(context: Context, calibrating: Boolean) {
        val state = prefs(context)
        state.edit()
            .putBoolean(KEY_CALIBRATING, calibrating)
            .commit()
        updateRootLease(
            active = state.getBoolean(KEY_ACTIVE, false),
            targetPkg = state.getString(KEY_TARGET_PKG, ""),
            calibrating = calibrating
        )
    }

    fun markExpectedStop(context: Context, reason: String) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_CALIBRATING, false)
            .putString(KEY_LAST_STOP_REASON, reason)
            .commit()
        updateRootLease(active = false, targetPkg = null, calibrating = false)
    }

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun activeSession(context: Context): ActiveSession? {
        val state = prefs(context)
        if (!state.getBoolean(KEY_ACTIVE, false)) return null
        val targetPkg = state.getString(KEY_TARGET_PKG, "").orEmpty()
        if (targetPkg.isBlank()) return null
        return ActiveSession(
            targetPkg = targetPkg,
            calibrating = state.getBoolean(KEY_CALIBRATING, false)
        )
    }

    fun reportUnexpectedStop(context: Context, targetPkg: String?, calibrating: Boolean) {
        val stoppedAt = System.currentTimeMillis()
        val pkg = targetPkg.orEmpty()
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_TARGET_PKG, pkg)
            .putBoolean(KEY_CALIBRATING, calibrating)
            .putBoolean(KEY_PENDING_INCIDENT, true)
            .putLong(KEY_STOPPED_AT, stoppedAt)
            .putString(KEY_LAST_STOP_REASON, "unexpected_destroy")
            .commit()
        updateRootLease(active = false, targetPkg = null, calibrating = false)
        postUnexpectedStopNotification(context, calibrating)
    }

    private fun updateRootLease(active: Boolean, targetPkg: String?, calibrating: Boolean) {
        leaseExecutor.execute {
            runCatching { DaemonBridge.updateFloatingBallLease(active, targetPkg, calibrating) }
                .onFailure { Log.w("QixiaThreads", "更新悬浮球 Root 租约失败", it) }
        }
    }

    fun consumeIncident(context: Context, serviceRunning: Boolean, consume: Boolean = true): Incident? {
        if (serviceRunning) return null
        val state = prefs(context)
        val pending = state.getBoolean(KEY_PENDING_INCIDENT, false)
        val staleActiveSession = state.getBoolean(KEY_ACTIVE, false) && !serviceRunning
        if (!pending && !staleActiveSession) return null

        val now = System.currentTimeMillis()
        val storedBootId = state.getString(KEY_BOOT_ID, "").orEmpty()
        val storedVersionCode = state.getLong(KEY_VERSION_CODE, -1L)
        val startedAt = state.getLong(KEY_STARTED_AT, 0L)
        val startedElapsed = state.getLong(KEY_STARTED_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val sameRuntime = storedBootId.isNotBlank() && storedBootId == currentBootId() &&
            storedVersionCode >= 0L && storedVersionCode == currentVersionCode(context)
        val plausibleAge = startedElapsed in 1..nowElapsed &&
            nowElapsed - startedElapsed <= MAX_INCIDENT_AGE_MS
        if (!sameRuntime || !plausibleAge) {
            clearIncidentState(state)
            notificationManager(context).cancel(ALERT_NOTIFICATION_ID)
            Log.d(
                "QixiaThreads",
                "FloatingBall stale session ignored: sameRuntime=$sameRuntime " +
                    "wallAge=${now - startedAt}ms elapsedAge=${nowElapsed - startedElapsed}ms"
            )
            return null
        }

        val incident = Incident(
            targetPkg = state.getString(KEY_TARGET_PKG, "").orEmpty(),
            calibrating = state.getBoolean(KEY_CALIBRATING, false),
            stoppedAt = state.getLong(KEY_STOPPED_AT, now),
            detectedAfterRestart = staleActiveSession && !pending
        )
        if (consume) {
            clearIncidentState(state)
            notificationManager(context).cancel(ALERT_NOTIFICATION_ID)
        }
        return incident
    }

    private fun clearIncidentState(state: android.content.SharedPreferences) {
        state.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_PENDING_INCIDENT, false)
            .putBoolean(KEY_CALIBRATING, false)
            .remove(KEY_BOOT_ID)
            .remove(KEY_VERSION_CODE)
            .remove(KEY_STARTED_AT)
            .remove(KEY_STARTED_ELAPSED)
            .apply()
    }

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(-1L)

    private fun postUnexpectedStopNotification(context: Context, calibrating: Boolean) {
        val manager = notificationManager(context)
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.floating_alert_channel_name, context.getString(R.string.app_name)),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val openApp = Intent(context, QixiaThreadsComposeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ALERT_NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = if (calibrating) {
            "悬浮球可能被系统或后台管控停止，本次校准已中断。"
        } else {
            context.getString(R.string.floating_background_permission_hint, context.getString(R.string.app_name))
        }
        val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("校准悬浮球被异常关闭")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        try {
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w("QixiaThreads", "FloatingBallService abnormal stop notification denied", error)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
