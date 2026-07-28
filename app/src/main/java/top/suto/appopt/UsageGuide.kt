package top.suto.appopt

import android.content.Context

/**
 * 聚光灯引导使用稳定步骤 ID 记录完成状态。
 *
 * 新增功能时只追加新的 ID；不要修改旧 ID。需要让用户重新阅读某一步时，
 * 为该步骤使用新的 ID 后缀。新用户会看到全部步骤，老用户只看到新增步骤。
 */
object UsageGuide {
    private const val PREFS_NAME = "appopt_usage_guide"
    private const val KEY_COMPLETED_PAGE_IDS = "completed_page_ids"

    enum class Target {
        APP_TABS,
        ADD_APP,
        START_CALIBRATION,
        CONFIGURED_APP,
        ENVIRONMENT_TOOLS,
        HISTORY_AND_LOGS,
        RULE_GENERATION,
        RULE_GENERATION_LIMIT,
        SIMILAR_THREADS,
        PERFORMANCE_TIERS,
        PROCESS_FALLBACK,
        HELP_BUTTON
    }

    data class Step(
        val id: String,
        val title: String,
        val description: String,
        val target: Target
    )

    val steps: List<Step> = listOf(
        Step(
            id = "core_workflow_v1",
            title = "不用逐个添加线程",
            description = "应用页分为待校准、添加应用和已配置应用。推荐流程是先选择应用，再通过悬浮球记录负载；AppOpt 会一次扫描主进程线程和子进程并批量生成规则。",
            target = Target.APP_TABS
        ),
        Step(
            id = "add_app_v1",
            title = "先从这里添加应用",
            description = "切到“添加应用”，在亮起的列表中选择一个应用并点击右侧加号。完成添加后教程会自动进入下一步；不需要提前知道线程名，也不需要手动编写包名=auto。",
            target = Target.ADD_APP
        ),
        Step(
            id = "add_and_calibrate_v1",
            title = "播放按钮会启动悬浮校准",
            description = "切回“待校准”，点击应用右侧播放按钮。进入需要优化的场景后点击黄色胶囊开始记录，充分操作后再次点击红色胶囊，系统会自动生成多条规则。",
            target = Target.START_CALIBRATION
        ),
        Step(
            id = "rule_management_v1",
            title = "点击应用进入完整管理页",
            description = "“已配置应用”中的整行都可以点击。管理页可查看和编辑规则、从全部历史候选选择线程或子进程，并可为单个应用开启掉帧动态调度；手动新增只是高级微调入口。",
            target = Target.CONFIGURED_APP
        ),
        Step(
            id = "environment_tools_v1",
            title = "运行环境负责检查与维护",
            description = "这里可检查 Root、模块、守护进程、前台监听和必要权限，也能检查更新。反馈问题时使用“导出诊断包”，会收集日志、规则和系统状态。",
            target = Target.ENVIRONMENT_TOOLS
        ),
        Step(
            id = "history_logs_diagnostics_v1",
            title = "历史与日志各有用途",
            description = "历史记录保存每次校准的线程负载，也为规则编辑器提供去重候选；日志页会整理 C/Rust 守护进程和前台助手输出，并可按提醒或错误筛选。",
            target = Target.HISTORY_AND_LOGS
        ),
        Step(
            id = "rule_generation_settings_v1",
            title = "规则写入决定保存格式",
            description = "生成格式会转换现有规则，并决定 C / Rust 校准结束后的写入外观；不同格式只影响规则展示和编辑方式，不改变最终绑核效果。",
            target = Target.RULE_GENERATION
        ),
        Step(
            id = "rule_generation_limit_v1",
            title = "生成限制控制规则数量",
            description = "最大线程规则数只限制自动生成的线程级规则，最后的包名兜底不计入数量。数值越小规则越精简，数值越大可保留更多达到阈值的线程。",
            target = Target.RULE_GENERATION_LIMIT
        ),
        Step(
            id = "similar_threads_v1",
            title = "相似线程会合并计算",
            description = "带动态编号的同类线程会归并为通配规则。默认按组内最忙的单个线程判断档位，避免大量低负载线程累加后被错误提升。",
            target = Target.SIMILAR_THREADS
        ),
        Step(
            id = "performance_tiers_v1",
            title = "性能档位决定负载如何分级",
            description = "最高、较重和中等线程都要同时达到平均与峰值阈值才会生成规则，每档可选择对应的 CPU 核心。这些设置只影响后续校准。",
            target = Target.PERFORMANCE_TIERS
        ),
        Step(
            id = "process_fallback_v1",
            title = "进程兜底承接其余线程",
            description = "主进程中没有命中单独线程规则的线程会使用这里选择的核心。它不是额外的负载档位，也不会覆盖已经命中的线程规则；修改后影响后续校准生成的兜底规则。",
            target = Target.PROCESS_FALLBACK
        ),
        Step(
            id = "reopen_guide_v1",
            title = "以后可以从这里重新查看",
            description = "完成后不会重复弹出已经看过的步骤。以后新增功能只显示新增指引；需要重新查看完整教程时，点击设置页右上角的问号。",
            target = Target.HELP_BUTTON
        )
    )

    fun pendingSteps(context: Context): List<Step> {
        val completed = completedStepIds(context)
        return steps.filterNot { it.id in completed }
    }

    fun markCompleted(context: Context, completedSteps: Collection<Step>) {
        val next = completedStepIds(context).toMutableSet()
        next.addAll(completedSteps.map(Step::id))
        prefs(context).edit().putStringSet(KEY_COMPLETED_PAGE_IDS, next).apply()
    }

    private fun completedStepIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COMPLETED_PAGE_IDS, emptySet()).orEmpty().toSet()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
}
