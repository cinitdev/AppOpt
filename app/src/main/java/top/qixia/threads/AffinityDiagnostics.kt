package top.qixia.threads

import top.qixia.threads.compose.CoreTimelinePresentation

data class AffinityDiagnosticRow(val identity: ThreadIdentity, val name: String, val average: Float,
    val desired: List<Int>?, val actual: List<Int>?, val group: String, val expectedGroup: String,
    val operationMs: Long, val operation: String, val reason: String, val life: String, val error: String = "") {
    val matched: Boolean get() = life == "alive" && desired != null && desired == actual && group.isNotBlank() && group == expectedGroup
    fun status(runtime: String): String = when {
        life == "exited" -> "线程已退出"
        life == "reused" -> "原线程已结束，TID 已复用"
        life != "alive" -> "线程身份暂不可读"
        runtime == "idle" || runtime == "empty" || runtime == "stopped" -> "自动分配当前未运行"
        matched -> "接管已核对"
        operation == "error" -> "最近操作失败"
        actual == null || group.isBlank() -> "实际状态暂不可读"
        desired != null -> "当前范围或 cpuset 与目标不一致"
        average < 5f -> "采样均值未达到 5%"
        else -> "负载达标，尚无分配目标"
    }
    fun explanation(): String = if (operation.isBlank()) "守护启动后尚未记录此线程的写入结果。" else
        CoreTimelinePresentation.reason(CoreEvent(operationMs, identity, name,
            when (operation) { "assign" -> CoreEventKind.ASSIGN; "release" -> CoreEventKind.RELEASE; "error" -> CoreEventKind.ERROR; else -> CoreEventKind.EXTERNAL },
            CoreEventSource.UNKNOWN, null, actual, null, average, reason))
}
data class AffinityDiagnosticReport(val packageName: String, val sampledMs: Long, val readMs: Long,
    val state: String, val detail: String, val total: Int, val rows: List<AffinityDiagnosticRow>)

object AffinityDiagnostics {
    fun read(pkg: String): AffinityDiagnosticReport {
        require(DaemonBridge.isValidBasePackage(pkg))
        val result = DaemonBridge.runRootCommand("'${DaemonBridge.BIN_RS_FILE}' --affinity-diagnostics '$pkg'", 8)
        check(result.success) { if (result.timedOut) "读取超时，可稍后重试" else "当前守护未提供诊断，请确认已刷入新版模块并运行" }
        return parse(result.output, pkg)
    }

    internal fun parse(raw: String, pkg: String): AffinityDiagnosticReport {
        require(raw.length <= 2 * 1024 * 1024)
        val lines = raw.trimEnd().lines()
        val head = lines.first().split('\t')
        require(head.size == 8 && head[0] == "QIXIA_DIAG" && head[1] == "1" && head[2] == pkg && lines.last() == "END")
        fun hex(value: String): String {
            require(value.length % 2 == 0 && value.length <= 4096)
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        }
        fun cpus(value: String): List<Int>? = if (value == "-") null else {
            require(value.length in 1..16)
            val mask = value.toULong(16)
            require(mask != 0uL)
            (0..63).filter { mask and (1uL shl it) != 0uL }
        }
        val rows = lines.drop(1).dropLast(1).map { line ->
            val fields = line.split('\t')
            require(fields.size == 15 && fields[0] == "T")
            val identity = ThreadIdentity(fields[1].toInt(), fields[2].toInt(), fields[3].toLong())
            require(identity.pid > 0 && identity.tid > 0 && identity.startTicks > 0)
            val average = fields[5].toFloat().also { require(it.isFinite() && it in 0f..105f) }
            val life = fields[13].also { require(it in setOf("alive", "exited", "reused", "unknown")) }
            AffinityDiagnosticRow(identity, hex(fields[4]), average, cpus(fields[6]), cpus(fields[7]),
                hex(fields[8]), hex(fields[9]), fields[10].toLong(), hex(fields[11]), hex(fields[12]), life, hex(fields[14]))
        }
        require(rows.size <= 512 && rows.map { it.identity }.distinct().size == rows.size)
        val total = head[7].toInt().also { require(it >= rows.size) }
        return AffinityDiagnosticReport(pkg, head[3].toLong(), head[4].toLong(), hex(head[5]), hex(head[6]), total, rows)
    }
}

data class AffinityDiagnosticsUiState(val packageName: String, val label: String, val loading: Boolean = true,
    val report: AffinityDiagnosticReport? = null, val error: String? = null)
