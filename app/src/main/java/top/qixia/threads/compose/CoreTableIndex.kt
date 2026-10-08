package top.qixia.threads.compose

import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource
import top.qixia.threads.CoreTimelineReport
import top.qixia.threads.ThreadIdentity

/**
 * 拖动前构建整份报告的不可变索引；某个时刻包含截至该毫秒的全部已保存事件。
 * 同一时刻的事件保留原顺序，以最后一条为准。
 * 运行核心和负载各自保留采样时间，不插值推断状态。
 */
internal class CoreTableIndex private constructor(
    val threads: List<Thread>,
    val startMs: Long,
    val endMs: Long,
    val eventTimes: List<Long>,
    /** 列标签来自已记录的数据，保持稳定并支持不连续的 CPU 编号。 */
    val cpuIds: List<Int>
) {
    data class Snapshot(
        /** 在线程按时间排序的 [Thread.events] 中的索引，可作为 UI 缓存键。 */
        val eventIndex: Int,
        val latestEvent: CoreEvent,
        /** 最后已知的允许范围；遇到未知、失败或退出记录后清空。 */
        val rangeEvent: CoreEvent?,
        /** 最后一次真实运行核心采样，保留原始时间戳。 */
        val sampleEvent: CoreEvent?,
        /** 最后一次真实负载采样，保留原始时间戳。 */
        val averageEvent: CoreEvent?
    )

    class Thread internal constructor(val identity: ThreadIdentity, val events: List<CoreEvent>) {
        val name: String = if (identity.pid == 0 && identity.tid == 0) "调度器记录"
            else events.lastOrNull { it.name.isNotBlank() }?.name ?: "线程 ${identity.tid}"
        val operations: Int = events.count(CoreTimelinePresentation::isOperation)
        val observations: Int = events.count { it.kind == CoreEventKind.OBSERVE }
        private val rangeIndices = IntArray(events.size)
        private val sampleIndices = IntArray(events.size)
        private val averageIndices = IntArray(events.size)

        init {
            var range = -1
            var sample = -1
            var average = -1
            events.forEachIndexed { index, event ->
                val exited = event.kind == CoreEventKind.EXIT
                val after = event.afterCpus
                range = if (exited || event.kind == CoreEventKind.ERROR ||
                    event.source == CoreEventSource.UNKNOWN || after.isNullOrEmpty() ||
                    after.any { it < 0 } || (event.legacy && event.kind == CoreEventKind.RELEASE)
                ) -1 else index
                if (exited) {
                    sample = -1
                    average = -1
                } else {
                    if (event.runningCpu != null && event.runningCpu >= 0) sample = index
                    if (event.averagePercent?.let { it.isFinite() && it >= 0f } == true) average = index
                }
                rangeIndices[index] = range
                sampleIndices[index] = sample
                averageIndices[index] = average
            }
        }

        /** 复杂度 O(log n)，指针移动时不筛选、格式化或复制事件历史。 */
        fun snapshotAt(timestamp: Long): Snapshot? {
            var low = 0
            var high = events.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (events[middle].timestampMs <= timestamp) low = middle + 1 else high = middle
            }
            val index = low - 1
            if (index < 0) return null
            return Snapshot(index, events[index], events.getOrNull(rangeIndices[index]),
                events.getOrNull(sampleIndices[index]), events.getOrNull(averageIndices[index]))
        }
    }

    fun timeAt(progress: Float): Long {
        if (startMs == endMs || progress.isNaN() || progress <= 0f) return startMs
        if (progress >= 1f) return endMs
        return (startMs.toDouble() + (endMs.toDouble() - startMs) * progress)
            .toLong().coerceIn(startMs, endMs)
    }

    fun progressAt(time: Long): Float {
        if (startMs == endMs || time <= startMs) return 0f
        if (time >= endMs) return 1f
        return ((time.toDouble() - startMs) / (endMs.toDouble() - startMs)).toFloat().coerceIn(0f, 1f)
    }

    /** 查找严格早于或晚于当前时刻的已保存时间，同毫秒事件视为一步。 */
    fun previousTime(time: Long): Long? = eventTimes.getOrNull(bound(time, inclusive = false) - 1)
    fun nextTime(time: Long): Long? = eventTimes.getOrNull(bound(time, inclusive = true))

    private fun bound(time: Long, inclusive: Boolean): Int {
        var low = 0
        var high = eventTimes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (eventTimes[middle] < time || (inclusive && eventTimes[middle] == time)) low = middle + 1
            else high = middle
        }
        return low
    }

    companion object {
        fun build(report: CoreTimelineReport): CoreTableIndex {
            // 一次稳定的全局排序同时保证各线程事件按时间排列。
            val ordered = report.events.sortedBy { it.timestampMs }
            val times = ArrayList<Long>()
            ordered.forEach { event -> if (times.lastOrNull() != event.timestampMs) times += event.timestampMs }
            val threads = ordered.groupBy { it.identity }.map { (identity, events) -> Thread(identity, events) }
                .sortedWith(compareByDescending<Thread> { it.operations }
                    .thenByDescending { it.events.size }.thenBy { it.identity.tid }
                    .thenBy { it.identity.pid }.thenBy { it.identity.startTicks })
            val start = minOf(report.startMs, report.endMs, times.firstOrNull() ?: report.startMs)
            val end = maxOf(report.startMs, report.endMs, times.lastOrNull() ?: report.endMs)
            val cpus = sortedSetOf<Int>()
            ordered.forEach { event ->
                event.beforeCpus?.filterTo(cpus) { it >= 0 }
                event.afterCpus?.filterTo(cpus) { it >= 0 }
                event.runningCpu?.takeIf { it >= 0 }?.let(cpus::add)
            }
            return CoreTableIndex(threads, start, end, times, cpus.toList())
        }
    }
}
