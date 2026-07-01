package top.suto.appopt

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 前台应用检测 —— 基于系统 UsageStatsManager, 不依赖 root / dumpsys 文本解析。
 *
 * 使用 UsageStatsManager 的事件流维护“最近进入前台的包名”，避免解析 dumpsys 文本。
 * ACTIVITY_RESUMED 是状态变化事件，应用稳定停留前台时不会反复产生新事件，所以这里使用
 * “首次回看 + 后续增量”的方式保存上一次前台状态。
 */
object ForegroundDetector {

    data class State(
        val foreground: Boolean,
        val currentPackage: String?,
        val lastEventPackage: String?,
        val lastEventType: Int,
        val eventCount: Int,
        val resumedCount: Int,
        val queryStart: Long,
        val queryEnd: Long,
        val error: String? = null
    )

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

    class Tracker {
        @Volatile private var lastQueryEnd = 0L
        @Volatile private var lastForegroundPkg: String? = null
        @Volatile private var lastEventPkg: String? = null
        @Volatile private var lastEventType: Int = -1

        private companion object {
            const val QUERY_OVERLAP_MS = 500L
        }

        /** 重置增量跟踪状态。每次开始一轮前台监测前调用, 避免上一轮残留干扰判定。 */
        fun reset() {
            lastQueryEnd = 0L
            lastForegroundPkg = null
            lastEventPkg = null
            lastEventType = -1
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
        fun queryState(context: Context, pkg: String, initialLookbackMs: Long = 60_000L): State {
            if (pkg.isBlank()) {
                return State(false, lastForegroundPkg, lastEventPkg, lastEventType, 0, 0, 0L, 0L, "empty_pkg")
            }
            if (!ForegroundDetector.hasUsageAccess(context)) {
                return State(false, lastForegroundPkg, lastEventPkg, lastEventType, 0, 0, 0L, 0L, "usage_access_denied")
            }

            return try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                // 首次回溯一段时间抓进入前台的事件做初值; 之后增量查。
                // 这里保留 500ms 重叠, 避免部分 ROM 在时间边界上漏掉 UsageEvents。
                val start = if (lastQueryEnd == 0L) {
                    now - initialLookbackMs
                } else {
                    (lastQueryEnd - QUERY_OVERLAP_MS).coerceAtLeast(0L)
                }
                val events = usm.queryEvents(start, now)
                val event = UsageEvents.Event()
                var eventCount = 0
                var resumedCount = 0

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    eventCount++
                    lastEventPkg = event.packageName
                    lastEventType = event.eventType
                    // ACTIVITY_RESUMED(=1, 旧名 MOVE_TO_FOREGROUND): 某 activity 进入前台
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        resumedCount++
                        val eventPkg = event.packageName
                        if (!eventPkg.isNullOrBlank()) {
                            lastForegroundPkg = eventPkg
                        }
                    }
                }

                lastQueryEnd = now
                State(
                    foreground = lastForegroundPkg == pkg,
                    currentPackage = lastForegroundPkg,
                    lastEventPackage = lastEventPkg,
                    lastEventType = lastEventType,
                    eventCount = eventCount,
                    resumedCount = resumedCount,
                    queryStart = start,
                    queryEnd = now
                )
            } catch (e: Exception) {
                State(false, lastForegroundPkg, lastEventPkg, lastEventType, 0, 0, 0L, 0L, e.message)
            }
        }
    }
}
