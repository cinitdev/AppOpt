package top.qixia.threads.compose

/** 启动卡片只包含同一轮守护进程中已知的启动诊断。 */
internal class LogStartupSession {
    private data class Session(val id: Long, val processId: String, val sequence: Long, val time: Long)
    private var session: Session? = null

    fun groupId(id: Long, processId: String, sequence: Long, time: Long,
        tag: String, level: LogLevel, message: String): Long? {
        if (tag == "RS" && level == LogLevel.INFO &&
            message.startsWith("启动 QixiaThreads Rust 守护 ")) {
            session = Session(id, processId, sequence, time)
            return id
        }
        val start = session ?: return null
        if (processId != start.processId) return null
        if (time < start.time || time - start.time > 10_000 ||
            sequence <= start.sequence || sequence - start.sequence > 256) {
            session = null
            return null
        }
        // 警告、错误和无关运行事件仍独立显示。
        if (level != LogLevel.INFO && level != LogLevel.SUCCESS) return null
        val belongs = when (tag) {
            "RS" -> servicePrefixes.any(message::startsWith)
            "校准", "CALIB" -> message.startsWith("活跃线程采集 →") ||
                message.startsWith("平均负载达到 5% 的活跃线程参与建议") ||
                capacity.matches(message) || message == "CPU 拓扑已写入校准策略"
            "CTRL" -> message.startsWith("守护验证 socket 已监听:")
            "auto" -> message.lineSequence().firstOrNull() == "状态更新: pkg= state=idle"
            else -> false
        }
        if (tag == "RS" && message.startsWith("扫描计划:")) session = null
        return start.id.takeIf { belongs }
    }

    private companion object {
        val capacity = Regex("CPU \\d+ capacity=(?:Some\\(\\d+\\)|None)")
        val servicePrefixes = listOf(
            "作者:", "配置文件:", "包名 UID 映射:", "检查间隔:", "cpuset 运行组:", "目标范围:",
            "Android 版本:", "设备品牌:", "内核版本:", "QixiaThreads 版本 ", "配置文件监控模式:",
            "启用守护进程验证 socket", "启用自动校准线程", "启用真实帧率监测线程",
            "已续接上次守护进程的线程恢复基线:", "规则加载完成:", "扫描计划:"
        )
    }
}

internal data class LogCardModel(val key: String, val entries: List<LogEntryModel>, val startup: Boolean) {
    val timestampMs: Long? get() = entries.last().timestampMs
}

/** 输入保留现有的最新优先筛选顺序，分组详情按从旧到新阅读。 */
internal fun logCards(entries: List<LogEntryModel>): List<LogCardModel> =
    entries.groupBy { entry -> entry.startupId?.let { "startup:$it" } ?: "event:${entry.id}" }
        .map { (key, members) -> LogCardModel(key, members.asReversed(), members.first().startupId != null) }
