package top.qixia.threads.compose

import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.CoreTimelineReport
import top.qixia.threads.ThreadIdentity

internal object CoreTimelinePresentation {
    data class Thread(val identity: ThreadIdentity, val name: String, val events: List<CoreEvent>) {
        val operations: Int = events.count(::isOperation)
        val observations: Int = events.count { it.kind == CoreEventKind.OBSERVE }
    }

    fun threads(report: CoreTimelineReport): List<Thread> = report.events.groupBy { it.identity }
        .map { (id, rows) -> Thread(id, if (id.tid == 0) "调度器记录" else rows.lastOrNull { it.name.isNotBlank() }?.name ?: "线程 ${id.tid}", rows.sortedBy { it.timestampMs }) }
        .sortedWith(compareByDescending<Thread> { it.operations }.thenByDescending { it.events.size }.thenBy { it.identity.tid })

    fun isOperation(event: CoreEvent) = event.kind == CoreEventKind.ASSIGN || event.kind == CoreEventKind.RELEASE

    fun cpus(cpus: List<Int>?): String {
        if (cpus.isNullOrEmpty()) return "范围未记录"
        val sorted = cpus.distinct().sorted()
        val spans = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (cpu in sorted.drop(1)) {
            if (cpu == end + 1) end = cpu else {
                spans += if (start == end) "$start" else "$start–$end"
                start = cpu; end = cpu
            }
        }
        spans += if (start == end) "$start" else "$start–$end"
        return "CPU ${spans.joinToString(", ")}"
    }

    fun title(event: CoreEvent): String = when (event.kind) {
        CoreEventKind.ASSIGN -> "QixiaThreads 自动分配"
        CoreEventKind.RELEASE -> "QixiaThreads 解除限制"
        CoreEventKind.OBSERVE -> "采样观察"
        CoreEventKind.EXTERNAL -> "非 QixiaThreads 调整"
        CoreEventKind.EXIT -> "线程结束"
        CoreEventKind.ERROR -> "操作未完成"
    }

    fun source(source: CoreEventSource): String = when (source) {
        CoreEventSource.QIXIA -> "自动分配"
        CoreEventSource.SYSTEM -> "系统调度"
        CoreEventSource.UNKNOWN -> "归属未知"
    }

    fun rangeUnchanged(event: CoreEvent): Boolean {
        val before = event.beforeCpus?.takeIf { it.isNotEmpty() } ?: return false
        val after = event.afterCpus?.takeIf { it.isNotEmpty() } ?: return false
        return before.toSet() == after.toSet()
    }

    fun reason(event: CoreEvent): String {
        if (event.legacy) return "旧版记录仅保留操作时间和核心范围，未保存负载与原因。"
        return when (event.reason) {
            "initial" -> if (event.kind == CoreEventKind.ASSIGN) "根据当前负载与核心余量开始分配。" else "本次首次观察到此线程，建立核心状态基线。"
            "low_average" -> "最近采样平均使用率低于 5%，解除 QixiaThreads 限制，交回系统调度。"
            "inactive" -> "最近连续低负载或不活跃，解除 QixiaThreads 限制；不代表线程已经结束。"
            "unrestricted" -> "恢复线程原本允许使用的核心范围。"
            "affinity_changed", "migration" -> if (event.kind == CoreEventKind.ASSIGN) "负载或核心余量变化，更新允许使用的核心。" else "采样发现允许核心范围发生变化。"
            "cpu_changed" -> "采样发现上次执行核心变化，不代表记录了期间的每次迁核。"
            "source_changed" -> "采样发现核心范围的管理归属变化。"
            "external_override" -> "允许范围被系统或其他调度策略改变，QixiaThreads 让出控制。"
            "cpuset_takeover" -> "已将线程移入 QixiaThreads 专属核心分组，并核对允许范围，完成接管。"
            "control_reasserted" -> "检测到核心范围或所属分组被改变，已重新接管并核对允许范围。"
            "cpuset_unavailable" -> "设备暂未提供可用的核心分组或核心信息，本次未完成接管，稍后重试。"
            "cpuset_failed" -> "创建或迁入 QixiaThreads 专属核心分组失败，尚未确认接管，稍后重试。"
            "cpuset_readback_failed" -> "迁入后未能确认线程所属核心分组，不能把本次操作当作接管成功。"
            "retry_pending" -> "正在等待下一次重试，本条记录不代表已完成接管。"
            "recovery_baseline_missing" -> "发现遗留的 QixiaThreads 核心分组，但缺少原分组的可靠恢复信息，暂不继续接管。"
            "capacity_pending" -> "已有线程正在等待恢复，暂缓接管新线程，待恢复完成后重试。"
            "identity_unavailable" -> "暂时无法读取线程身份，本次操作等待重试；不代表线程已经结束。"
            "inherited_release" -> "线程继承了 QixiaThreads 的核心限制，已按继承来源的恢复记录解除限制，交回系统调度。"
            "inherited_external" -> "线程已离开原来的 QixiaThreads 核心分组，停止后续恢复，不再覆盖当前核心设置。"
            "inherited_baseline_missing" -> "线程继承了 QixiaThreads 核心分组，但缺少继承来源的可靠恢复记录，暂不改写核心设置。"
            "foreground_left" -> "应用离开前台，结束本次自动分配。"
            "mode_disabled" -> "自动分配已关闭，恢复原允许范围。"
            "mode_changed" -> "分配模式发生变化，先恢复原允许范围。"
            "calibration" -> "开始手动校准，暂时停止自动分配。"
            "paused" -> "自动分配暂时回退，等待重新评估。"
            "shutdown" -> "守护进程退出，恢复原允许范围。"
            "thread_exit", "identity_changed" -> "原线程已经结束或身份发生变化，后续同名线程单独记录。"
            "sample_unavailable", "not_selected" -> "本轮不再满足分配条件，解除 QixiaThreads 限制。"
            "config_unavailable" -> "配置尚未就绪，暂停自动分配。"
            "restore_failed", "recovery_failed" -> "恢复尚未成功，不能把本次操作当作已交回系统。"
            "cpuset_cleanup_failed" -> "线程范围已恢复，专属核心分组清理暂未完成，稍后重试。"
            "write_failed", "readback_failed", "batch_failed" -> "写入或核对未完成，以实际读回状态为准。"
            "mask_filtered" -> "系统限制了可用核心，以实际读回范围为准。"
            else -> "本次未取得更详细的原因。"
        }
    }

    fun relativeTime(timestamp: Long, start: Long): String {
        val delta = timestamp - start
        val seconds = if (delta < 0) (-delta + 999) / 1000 else delta / 1000
        return "${if (delta < 0) "−" else "+"}${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
