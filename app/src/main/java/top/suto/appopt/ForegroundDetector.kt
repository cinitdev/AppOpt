package top.suto.appopt

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 前台应用检测 —— 基于系统 UsageStatsManager, 不依赖 root / dumpsys 文本解析。
 *
 * 替代原先的 `dumpsys activity activities | grep ...` 方案: 那套靠匹配
 * mResumedActivity/topResumedActivity 等字段名, 在不同品牌深度 ROM(MIUI/
 * ColorOS/One UI)与不同 Android 版本上字段名/格式会变, 属脆弱解析。
 * UsageStatsManager.queryEvents 是 Google 官方接口, 跨品牌、Android 12-16
 * 行为一致, 且省去一次 su+dumpsys 的开销。
 *
 * 代价: 需要用户授予「使用情况访问」(PACKAGE_USAGE_STATS) 特殊权限,
 * 跟悬浮窗权限一样需到系统设置页手动开启。未授予时 hasUsageAccess() 返回 false。
 */
object ForegroundDetector {

    /** 是否已被授予「使用情况访问」权限 */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 跨调用维护"最近一次进入前台的包名"。
     *
     * ACTIVITY_RESUMED/PAUSED 是**状态变化**事件: 应用稳定停留前台时系统不会反复
     * 产生新事件。若每次只查最近 N 秒窗口找"窗口内最后一次 RESUMED", 进入游戏那次
     * RESUMED 会在 N 秒后滑出窗口 -> 窗口内再无事件 -> 误判"不在前台"(游戏内约 10s
     * 后把悬浮球错误关闭的真因)。
     * 改为增量查询: 每次只读「上次查询点到现在」的新事件, 有新的 RESUMED 就更新当前
     * 前台包; 没有新事件则保持上次结论(没切换 = 前台没变)。
     */
    @Volatile private var lastQueryEnd = 0L
    @Volatile private var lastForegroundPkg: String? = null

    /** 重置增量跟踪状态。每次开始一轮前台监测前调用, 避免上一轮残留干扰判定。 */
    fun reset() {
        lastQueryEnd = 0L
        lastForegroundPkg = null
    }

    /**
     * 判断指定包名当前是否为前台应用。
     *
     * 基于 UsageStatsManager 事件流, 采用「增量查询 + 状态保持」语义:
     * 首次(或 reset 后)回溯 initialLookbackMs 抓取进入前台的事件作为初值, 之后每次
     * 只查询自上次以来的新事件。前台未切换时窗口内没有事件, 维持上次判定的前台包不变
     * —— 正确反映"用户一直停在游戏里"的情形, 不会因事件滑出窗口而误判离开。
     *
     * 未授权或查询失败返回 false(调用方据此走宽限/关闭逻辑)。
     */
    fun isAppForeground(context: Context, pkg: String, initialLookbackMs: Long = 60_000L): Boolean {
        if (pkg.isBlank()) return false
        if (!hasUsageAccess(context)) return false
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // 首次回溯一段时间抓进入前台的事件做初值; 之后从上次查询点增量查, 不漏不重扫
            val start = if (lastQueryEnd == 0L) now - initialLookbackMs else lastQueryEnd
            val events = usm.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED(=1, 旧名 MOVE_TO_FOREGROUND): 某 activity 进入前台
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForegroundPkg = event.packageName
                }
            }
            lastQueryEnd = now
            lastForegroundPkg == pkg
        } catch (_: Exception) {
            false
        }
    }
}
