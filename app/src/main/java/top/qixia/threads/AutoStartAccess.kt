package top.qixia.threads

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Locale

enum class AutoStartStatus { CHECKING, ALLOWED, BLOCKED, NOT_REQUIRED, DISABLED, UNSUPPORTED, UNREADABLE }

data class AutoStartState(
    val status: AutoStartStatus = AutoStartStatus.CHECKING,
    val detail: String = "正在检查系统启动管理",
    val associate: String = "",
    val manager: String = "",
    val source: String = ""
) {
    val label: String get() = when (status) {
        AutoStartStatus.CHECKING -> "检查中"
        AutoStartStatus.ALLOWED -> "已允许"
        AutoStartStatus.BLOCKED -> "未允许"
        AutoStartStatus.NOT_REQUIRED -> "无需设置"
        AutoStartStatus.DISABLED -> "组件已禁用"
        AutoStartStatus.UNSUPPORTED -> "暂不支持"
        AutoStartStatus.UNREADABLE -> "无法读取"
    }
    // 检测细节保留在 detail，界面说明用途和下一步，不直接展示厂商接口名称。
    val description: String get() = when (status) {
        AutoStartStatus.CHECKING -> "正在读取系统自启动设置"
        AutoStartStatus.ALLOWED -> "允许柒夏线程在后台按需启动"
        AutoStartStatus.BLOCKED -> "建议允许自启动，便于使用性能记录"
        AutoStartStatus.NOT_REQUIRED -> "此系统不需要单独设置自启动"
        AutoStartStatus.DISABLED -> "系统启动管理组件已停用，恢复后可检查"
        AutoStartStatus.UNSUPPORTED -> "此系统暂不支持检测，请在系统设置中确认"
        AutoStartStatus.UNREADABLE -> "暂时无法读取，请在系统设置中确认"
    } + if (associate == "blocked") "；关联启动未开启" else ""
}

/** 仅报告可验证的状态，检测不替用户授权，也不阻塞首页加载。 */
object AutoStartAccess {
    fun read(hasRoot: Boolean): AutoStartState {
        if (!hasRoot) return AutoStartState(AutoStartStatus.UNREADABLE, "需要 Root 才能读取厂商启动管理")
        val user = Process.myUid() / 100000
        val result = DaemonBridge.runRootCommand(
            "CLASSPATH=/data/adb/modules/QixiaThreads/config/tools/qixia_foreground_helper.jar " +
                "app_process /system/bin qixia.foreground.ForegroundHelper --autostart-status $user", 5L)
        return parse(result.success, result.output)
    }

    internal fun parse(success: Boolean, output: String): AutoStartState {
        if (!success) return AutoStartState(AutoStartStatus.UNREADABLE, "启动管理查询失败或超时，请稍后刷新")
        val fields = output.lineSequence().filter { it.startsWith("qixia_autostart") && '=' in it }
            .groupBy({ it.substringBefore('=') }, { it.substringAfter('=').trim() })
        fun field(key: String) = fields[key]?.singleOrNull().orEmpty()
        val status = when (field("qixia_autostart")) {
            "allowed" -> AutoStartStatus.ALLOWED
            "blocked" -> AutoStartStatus.BLOCKED
            "not_required" -> AutoStartStatus.NOT_REQUIRED
            "disabled" -> AutoStartStatus.DISABLED
            "unsupported" -> AutoStartStatus.UNSUPPORTED
            else -> AutoStartStatus.UNREADABLE
        }
        return AutoStartState(status,
            field("qixia_autostart_detail").take(240).ifBlank { "模块未返回有效检测结果，请检查模块版本" },
            field("qixia_autostart_associate"),
            field("qixia_autostart_manager").takeIf { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) }.orEmpty(),
            field("qixia_autostart_source").takeIf { it in setOf("miui", "hyperos", "oplus", "vendor", "aosp") }.orEmpty())
    }

    /** 冻结状态只引导至详情，不擅自恢复系统应用。 */
    fun openSettings(context: Context, state: AutoStartState) {
        if (state.status == AutoStartStatus.NOT_REQUIRED) return
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        val components = when {
            state.manager.startsWith("com.oplus") || state.manager.startsWith("com.coloros") ||
                listOf("oppo", "realme", "oneplus").any(brand::contains) -> listOf(
                "com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity",
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity")
            state.source in setOf("miui", "hyperos") || state.manager == "com.miui.securitycenter" ||
                listOf("xiaomi", "redmi", "poco").any(brand::contains) -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            listOf("vivo", "iqoo").any(brand::contains) -> listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            brand.contains("huawei") -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            brand.contains("honor") -> listOf(
                "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            else -> emptyList()
        }
        val candidates = if (state.status == AutoStartStatus.DISABLED) emptyList() else components.mapNotNull { name ->
            ComponentName.unflattenFromString(name)?.let {
                Intent().setComponent(it).putExtra("package_name", context.packageName).putExtra("packageName", context.packageName)
            }
        }
        val detailsPackage = if (state.status == AutoStartStatus.DISABLED) state.manager.ifBlank { context.packageName } else context.packageName
        for (intent in candidates + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$detailsPackage"))) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        AppToast.show(context, "请在系统设置中检查柒夏线程的启动管理")
    }
}
