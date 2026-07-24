package top.suto.appopt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 记录悬浮球会话是否正常结束，并在被系统或后台管控异常停止时提醒用户。
 */
object FloatingBallSessionState {
    data class Incident(
        val targetPkg: String,
        val calibrating: Boolean,
        val stoppedAt: Long,
        val detectedAfterRestart: Boolean
    )

    private const val PREFS_NAME = "floating_ball_session_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TARGET_PKG = "target_pkg"
    private const val KEY_CALIBRATING = "calibrating"
    private const val KEY_PENDING_INCIDENT = "pending_incident"
    private const val KEY_STOPPED_AT = "stopped_at"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"

    private const val ALERT_CHANNEL_ID = "appopt_floating_alerts"
    private const val ALERT_NOTIFICATION_ID = 1002

    fun begin(context: Context, targetPkg: String, calibrating: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TARGET_PKG, targetPkg)
            .putBoolean(KEY_CALIBRATING, calibrating)
            .putBoolean(KEY_PENDING_INCIDENT, false)
            .remove(KEY_STOPPED_AT)
            .remove(KEY_LAST_STOP_REASON)
            .commit()
    }

    fun setCalibrating(context: Context, calibrating: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CALIBRATING, calibrating)
            .commit()
    }

    fun markExpectedStop(context: Context, reason: String) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_CALIBRATING, false)
            .putString(KEY_LAST_STOP_REASON, reason)
            .commit()
    }

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

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
        postUnexpectedStopNotification(context, calibrating)
    }

    fun consumeIncident(context: Context, serviceRunning: Boolean): Incident? {
        val state = prefs(context)
        val pending = state.getBoolean(KEY_PENDING_INCIDENT, false)
        val staleActiveSession = state.getBoolean(KEY_ACTIVE, false) && !serviceRunning
        if (!pending && !staleActiveSession) return null

        val incident = Incident(
            targetPkg = state.getString(KEY_TARGET_PKG, "").orEmpty(),
            calibrating = state.getBoolean(KEY_CALIBRATING, false),
            stoppedAt = state.getLong(KEY_STOPPED_AT, System.currentTimeMillis()),
            detectedAfterRestart = staleActiveSession && !pending
        )
        state.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_PENDING_INCIDENT, false)
            .putBoolean(KEY_CALIBRATING, false)
            .apply()
        notificationManager(context).cancel(ALERT_NOTIFICATION_ID)
        return incident
    }

    private fun postUnexpectedStopNotification(context: Context, calibrating: Boolean) {
        val manager = notificationManager(context)
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "AppOpt 异常提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val openApp = Intent(context, MainActivity::class.java).apply {
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
            "悬浮球可能被系统或后台管控停止，请允许 AppOpt 后台运行后重试。"
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
            Log.w("AppOpt", "FloatingBallService abnormal stop notification denied", error)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
