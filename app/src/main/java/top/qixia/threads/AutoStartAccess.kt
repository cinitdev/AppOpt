package top.qixia.threads

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

enum class AutoStartStatus { ALLOWED, BLOCKED, UNKNOWN }

/** 厂商没有统一自启动 API；仅报告实际读取结果，不把未知状态当成已授权。 */
object AutoStartAccess {
    fun read(): AutoStartStatus {
        val user = Process.myUid() / 100000
        val result = DaemonBridge.runRootCommand(
            "CLASSPATH=/data/adb/modules/QixiaThreads/config/tools/qixia_foreground_helper.jar " +
                "app_process /system/bin qixia.foreground.ForegroundHelper --autostart-status $user", 4L)
        return parse(result.success, result.output)
    }

    internal fun parse(success: Boolean, output: String): AutoStartStatus {
        if (!success) return AutoStartStatus.UNKNOWN
        return when (output.lineSequence().filter { it.startsWith("qixia_autostart=") }
            .singleOrNull()?.substringAfter('=')?.trim()) {
            "allowed" -> AutoStartStatus.ALLOWED
            "blocked" -> AutoStartStatus.BLOCKED
            else -> AutoStartStatus.UNKNOWN
        }
    }

    /** 尝试厂商自启动管理页；入口不存在时落到本应用详情，避免打开错误品牌的页面。 */
    fun openSettings(context: Context) {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(java.util.Locale.ROOT)
        val components = when {
            listOf("xiaomi", "redmi", "poco").any(brand::contains) -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            listOf("oppo", "realme", "oneplus").any(brand::contains) -> listOf(
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity")
            listOf("vivo", "iqoo").any(brand::contains) -> listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            brand.contains("huawei") -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            brand.contains("honor") -> listOf(
                "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            else -> emptyList()
        }
        val candidates = components.mapNotNull { name -> ComponentName.unflattenFromString(name)?.let {
            Intent().setComponent(it).putExtra("package_name", context.packageName)
                .putExtra("packageName", context.packageName)
        } } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        AppToast.show(context, "请在系统设置中检查柒夏线程的自启动权限")
    }
}
