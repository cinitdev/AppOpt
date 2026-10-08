package top.qixia.threads

import android.content.Context

/**
 * 聚光灯引导使用稳定步骤 ID 记录完成状态。
 *
 * 新增功能时只追加新的 ID；不要修改旧 ID。需要让用户重新阅读某一步时，
 * 为该步骤使用新的 ID 后缀。新用户会看到全部步骤，老用户只看到新增步骤。
 */
object UsageGuide {
    private const val PREFS_NAME = "qixia_usage_guide"
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
        CPUSET_RUNTIME,
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
            description = "底栏“应用”打开应用管理。应用列表将待校准和可添加应用放在一起，共用搜索；已配置应用单独管理。先添加应用，再通过悬浮球采集主进程和子进程的活跃线程，生成待确认建议，保存后才生效。",
            target = Target.APP_TABS
        ),
        Step(
            id = "add_app_v1",
            title = "先从这里添加应用",
            description = "在“应用列表”搜索应用名称或包名，点击右侧“添加”。添加成功后直接变为待校准状态，无需切换列表。点击已添加的应用可打开管理并开启自动分配，不需要手动编写包名=auto。",
            target = Target.ADD_APP
        ),
        Step(
            id = "calibration_review_v2",
            title = "启动按钮会打开悬浮校准",
            description = "在应用列表中点击待校准应用右侧“启动”。进入需要优化的场景后点击黄色胶囊开始记录，充分操作后再次点击红色胶囊，系统会生成待确认的核心建议。返回 App 可调整，点击保存后才生效。",
            target = Target.START_CALIBRATION
        ),
        Step(
            id = "rule_management_v1",
            title = "点击应用进入完整管理页",
            description = "“已配置应用”中的整行都可以点击。管理页可查看和编辑规则、从全部历史候选选择线程或子进程，并可进行线程配置管理；手动新增只是高级微调入口。",
            target = Target.CONFIGURED_APP
        ),
        Step(
            id = "environment_tools_v1",
            title = "从首页检查权限与服务",
            description = "点击首页的权限与服务卡片，可检查 Root、模块、守护进程、前台监听和必要权限。模块更新在设置页，反馈问题时可从设置页“帮助与维护”导出诊断包。",
            target = Target.ENVIRONMENT_TOOLS
        ),
        Step(
            id = "history_logs_diagnostics_v1",
            title = "历史与日志各有用途",
            description = "历史记录保存每次校准的线程负载，也为规则编辑器提供去重候选；日志页会整理 Rust 守护进程和前台助手输出，并可按提醒或错误筛选。",
            target = Target.HISTORY_AND_LOGS
        ),
        Step(
            id = "rule_generation_settings_v1",
            title = "规则写入决定保存格式",
            description = "生成格式会转换现有规则，并决定 App 保存确认后的写入外观；不同格式只影响规则展示和编辑方式，不改变最终绑核效果。",
            target = Target.RULE_GENERATION
        ),
        Step(
            id = "cpuset_runtime_v1",
            title = "运行组属于 Rust 守护全局设置",
            description = "默认使用 /dev/cpuset/QiXiaRs。自定义名称只改变 Rust 守护创建的 cpuset 根目录，不改变规则中的核心范围，也不是单个应用设置；保存后会安全重启 Rust 守护。",
            target = Target.CPUSET_RUNTIME
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
