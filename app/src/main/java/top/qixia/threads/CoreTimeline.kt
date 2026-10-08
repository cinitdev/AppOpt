package top.qixia.threads

/** 包含创建时间的 Linux 线程身份；名称和数字 TID 并不唯一。 */
data class ThreadIdentity(val pid: Int, val tid: Int, val startTicks: Long) {
    val stableKey: String get() = "$pid:$tid:$startTicks"
}

enum class CoreEventKind { ASSIGN, RELEASE, OBSERVE, EXTERNAL, EXIT, ERROR }

/** 表示允许 CPU 范围的控制归属，不表示采样到的迁核由谁执行。 */
enum class CoreEventSource { QIXIA, SYSTEM, UNKNOWN }

data class CoreEvent(
    val timestampMs: Long,
    val identity: ThreadIdentity,
    /** 恢复时已知线程身份但未采到名称，此字段为空。 */
    val name: String,
    val kind: CoreEventKind,
    val source: CoreEventSource,
    val beforeCpus: List<Int>?,
    val afterCpus: List<Int>?,
    /** 最后一次从 procfs 采到的运行核心，不代表 QixiaThreads 迁核命令。 */
    val runningCpu: Int?,
    val averagePercent: Float?,
    val reason: String,
    val legacy: Boolean = false
)

enum class CoreTimelineCoverage { LEGACY_ACTIONS_ONLY, SAMPLED, INCOMPLETE }

data class CoreTimelineReport(
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val events: List<CoreEvent>,
    val coreEventsVersion: Int? = null,
    val droppedEvents: Long = 0,
    val incomplete: Boolean = false,
    val legacyMissingDetails: Boolean = coreEventsVersion != 1
) {
    /** 轮转文件可以接收上一分片留下的有界事件队列。 */
    val hasEventsBeforeStart: Boolean get() = events.any { it.timestampMs < startMs }

    /** SAMPLED 表示已保存样本完整，不表示连续捕获了每次迁核。 */
    val coverage: CoreTimelineCoverage get() = when {
        incomplete || droppedEvents > 0 -> CoreTimelineCoverage.INCOMPLETE
        coreEventsVersion == 1 -> CoreTimelineCoverage.SAMPLED
        else -> CoreTimelineCoverage.LEGACY_ACTIONS_ONLY
    }

    fun exportText(): String = buildString {
        this@CoreTimelineReport.appendTo(this)
    }

    /** 逐事件直接输出，长记录无需再生成一份巨大的文本副本。 */
    fun appendTo(output: Appendable) = with(output) {
        appendLine("核心变化时间线：${events.size} 条，覆盖状态=$coverage，丢失=$droppedEvents")
        appendLine("执行核仅为线程 stat 的上次执行 CPU 采样，不代表记录了全部系统迁核；source 表示允许核心范围的归属。")
        if (hasEventsBeforeStart) appendLine("含前一记录段排队到达的事件，保留原始时间；早于本段开始的事件具有负相对时间。")
        if (legacyMissingDetails) appendLine("旧版仅保存已有 QixiaThreads 操作；未记录系统执行核变化、操作前范围和释放原因。")
        if (incomplete || droppedEvents > 0) appendLine("本会话核心事件不完整；缺失时段不能解释为没有发生变化。")
        appendLine("时间毫秒\tPID\tTID\t启动标记\t线程名\t事件\t范围归属\t原范围\t新范围\t观测执行核\t平均占用%\t原因\t旧版")
        fun cpus(values: List<Int>?) = values?.joinToString(",") ?: "-"
        fun field(value: String) = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
        events.forEach { event ->
            appendLine(listOf(event.timestampMs, event.identity.pid, event.identity.tid, event.identity.startTicks,
                field(event.name), event.kind.name.lowercase(), event.source.name.lowercase(),
                cpus(event.beforeCpus), cpus(event.afterCpus), event.runningCpu ?: "-",
                event.averagePercent ?: "-", field(event.reason).ifEmpty { "-" }, if (event.legacy) 1 else 0).joinToString("\t"))
        }
    }
}

/**
 * 在同次采集的分片之间共享重复字段，不持有事件本身。
 * 顺序读取一次采集时，共用同一字段池及名称解码器；所有缓存均有容量限制，
 * 淘汰只结束复用，不删除或修改已保留事件。
 */
internal class CoreEventPool {
    private class Cache<K, V>(private val limit: Int) : LinkedHashMap<K, V>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > limit
    }

    private val identities = Cache<ThreadIdentity, ThreadIdentity>(8192)
    private val names = Cache<String, String>(4096)
    private val ranges = Cache<String, List<Int>>(256)
    private val masks = Cache<Long, List<Int>>(256)
    private val reasons = Cache<String, String>(128)

    fun identity(value: ThreadIdentity): ThreadIdentity =
        identities[value] ?: value.also { identities[it] = it }

    fun name(encoded: String, decode: (String) -> String): String {
        if (encoded.isEmpty()) return ""
        names[encoded]?.let { return it }
        val decoded = decode(encoded)
        // 正式名称最长为 256 个 UTF-8 字节；自定义解码器返回的超长内容
        // 保留原有处理方式，但不能扩大缓存。
        if (encoded.length <= 512 && decoded.length <= 256) names[encoded] = decoded
        return decoded
    }

    fun reason(value: String): String = reasons[value] ?: value.also {
        if (it.length <= 512) reasons[it] = it
    }

    fun cpus(value: String): List<Int>? {
        if (value == "-") return null
        require(value.length in 1..256)
        ranges[value]?.let { return it }
        var mask = 0L
        value.split(',').forEach { part ->
            require(part.matches(CPU_PART))
            val range = part.split('-')
            val first = range[0].toInt()
            val last = range.getOrNull(1)?.toInt() ?: first
            require(first in 0..63 && last in first..63)
            for (cpu in first..last) mask = mask or (1L shl cpu)
        }
        val result = masks[mask] ?: java.util.Collections.unmodifiableList(
            (0..63).filter { mask and (1L shl it) != 0L }
        ).also { masks[mask] = it }
        ranges[value] = result
        return result
    }

    private companion object {
        val CPU_PART = Regex("[0-9]{1,2}(?:-[0-9]{1,2})?")
    }
}

/** 事件损坏不会影响该次记录中独立的 FPS、线程和指标数据。 */
internal class CoreTimelineParser(
    private val sessionId: Long,
    private val startMs: Long,
    private val endMs: Long,
    private val decodeName: (String) -> String,
    private val pool: CoreEventPool = CoreEventPool()
) {
    private var version: Int? = null
    private var sawHeader = false
    private var damaged = false
    private var declaredDropped = 0L
    private var discarded = 0L
    private var legacyDiscarded = 0L
    private val events = ArrayList<CoreEvent>()
    private val legacyEvents = ArrayList<CoreEvent>()
    private val names = HashMap<ThreadIdentity, String>()

    fun rememberName(identity: ThreadIdentity, name: String) {
        if (names.size < MAX_IDENTITIES || identity in names) names[pool.identity(identity)] = name
    }

    fun markIncomplete() { damaged = true }

    fun accepts(line: String): Boolean = line.substringBefore('\t') in KEYS

    fun consume(line: String) {
        val key = line.substringBefore('\t')
        if (line.length > MAX_LINE_LENGTH) {
            damaged = true
            if (key == "E") discarded++
            return
        }
        val fields = line.split('\t', limit = 14)
        try {
            when (key) {
                "core_events" -> {
                    require(fields.size == 2)
                    val parsed = fields[1].toInt()
                    require(!sawHeader || version == parsed)
                    sawHeader = true
                    version = parsed
                    require(parsed == 1)
                }
                "core_events_dropped" -> {
                    require(fields.size == 2)
                    declaredDropped = maxOf(declaredDropped, fields[1].toLong().also { require(it >= 0) })
                }
                "core_events_incomplete" -> {
                    require(fields.size == 2 && fields[1] == "1")
                    damaged = true
                }
                "E" -> {
                    require(version == 1 && fields.size == 13)
                    if (events.size >= MAX_EVENTS) { discarded++; damaged = true; return }
                    val kind = when (fields[6]) {
                        "assign" -> CoreEventKind.ASSIGN
                        "release" -> CoreEventKind.RELEASE
                        "observe" -> CoreEventKind.OBSERVE
                        "external" -> CoreEventKind.EXTERNAL
                        "exit" -> CoreEventKind.EXIT
                        "error" -> CoreEventKind.ERROR
                        else -> error("Unknown core event")
                    }
                    val identity = identity(fields[2], fields[3], fields[4], kind)
                    // 恢复和守护重试时，可能保留有效身份却没有控制器补充信息。
                    // 名称缺失不得丢掉释放事件。
                    val name = pool.name(fields[5], decodeName)
                    val event = CoreEvent(
                        time(fields[1]), identity, name, kind,
                        when (fields[7]) {
                            "qixia" -> CoreEventSource.QIXIA
                            "system" -> CoreEventSource.SYSTEM
                            "unknown" -> CoreEventSource.UNKNOWN
                            else -> error("Unknown range ownership")
                        },
                        pool.cpus(fields[8]), pool.cpus(fields[9]),
                        fields[10].takeUnless { it == "-" }?.toInt()?.also { require(it in 0..MAX_CPU) },
                        fields[11].takeUnless { it == "-" }?.toFloat()?.also { require(it.isFinite() && it in 0f..105f) },
                        pool.reason(fields[12].also { require(it.length <= 512 && it.none(Char::isISOControl)) }
                            .takeUnless { it == "-" }.orEmpty())
                    )
                    events += event
                }
            }
        } catch (_: IllegalArgumentException) {
            damaged = true
            if (key == "E") discarded++
        } catch (_: java.nio.charset.CharacterCodingException) {
            damaged = true
            if (key == "E") discarded++
        } catch (_: IllegalStateException) {
            damaged = true
            if (key == "E") discarded++
        }
    }

    fun legacy(timestampMs: Long, pid: Int, tid: Int, startTicks: Long, cpus: String) {
        try {
            require(timestampMs in startMs..endMs && pid > 0 && tid > 0 && startTicks >= 0)
            val restore = cpus == "restore"
            val event = CoreEvent(timestampMs, pool.identity(ThreadIdentity(pid, tid, startTicks)), "",
                if (restore) CoreEventKind.RELEASE else CoreEventKind.ASSIGN, CoreEventSource.QIXIA,
                null, if (restore) null else pool.cpus(cpus), null, null, "", legacy = true)
            if (legacyEvents.size < MAX_EVENTS) legacyEvents += event else legacyDiscarded++
        } catch (_: IllegalArgumentException) { legacyDiscarded++ }
    }

    fun finish(): CoreTimelineReport {
        // 新文件可以保留 A 记录兼容旧读取器，新时间线以 E 记录为准；
        // 混入兼容记录会制造重复操作，并引入详情缺失。
        val selected = if (version == 1) events else legacyEvents.map { event ->
            event.copy(name = names[event.identity] ?: "线程 ${event.identity.tid}")
        }
        val skipped = discarded + if (version == 1) 0 else legacyDiscarded
        val dropped = if (Long.MAX_VALUE - declaredDropped < skipped) Long.MAX_VALUE else declaredDropped + skipped
        return CoreTimelineReport(sessionId, startMs, endMs, selected.sortedBy { it.timestampMs }, version,
            dropped, damaged || dropped > 0, legacyMissingDetails = version != 1)
    }

    private fun time(value: String) = value.toLong().also {
        require(it in (startMs - MAX_EVENT_LEAD_MS).coerceAtLeast(1L)..endMs)
    }
    private fun identity(pid: String, tid: String, start: String, kind: CoreEventKind): ThreadIdentity {
        val result = ThreadIdentity(pid.toInt(), tid.toInt(), start.toLong())
        require((result.pid > 0 && result.tid > 0 && result.startTicks > 0) ||
            (kind == CoreEventKind.ERROR && result.pid == 0 && result.tid == 0 && result.startTicks == 0L))
        return pool.identity(result)
    }

    companion object {
        internal const val MAX_EVENTS = 20_000
        internal const val MAX_EVENT_LEAD_MS = 30L * 60 * 1000
        private const val MAX_IDENTITIES = 8192
        private const val MAX_LINE_LENGTH = 16_384
        private const val MAX_CPU = 63
        private val KEYS = setOf("E", "core_events", "core_events_dropped", "core_events_incomplete")
    }
}
