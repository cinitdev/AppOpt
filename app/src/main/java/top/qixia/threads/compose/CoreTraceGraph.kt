package top.qixia.threads.compose

import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import top.qixia.threads.CoreEventSource

/** 真实的 procfs 观察点，不将采样点连接成迁核路径。 */
internal data class CoreTraceSample(val eventIndex: Int, val fraction: Float)

/** 已知的 QixiaThreads 允许范围，不表示线程在整段时间内占用该核心。 */
internal data class CoreTraceInterval(
    val startFraction: Float,
    val endFraction: Float,
    val startEventIndex: Int,
    val endEventIndex: Int?
)

internal data class CoreTraceLane(
    val cpu: Int,
    val samples: List<CoreTraceSample>,
    val allowedIntervals: List<CoreTraceInterval>
)

/**
 * 每个线程只预计算一次，不在指针移动或绘制回调中计算。
 * 每条核心轨道的采样点和区间分别最多 [bucketCount] 个，全部原始事件
 * 仍可通过精确游标访问；压缩会省略观察点或区间，但不连接空缺。
 */
internal class CoreTraceGraph private constructor(
    val startMs: Long,
    val endMs: Long,
    val events: List<CoreEvent>,
    val lanes: List<CoreTraceLane>,
    val compressed: Boolean,
    val bucketCount: Int
) {
    fun fraction(timestampMs: Long): Float =
        ((timestampMs.toDouble() - startMs) / (endMs.toDouble() - startMs)).coerceIn(0.0, 1.0).toFloat()

    /**
     * 以 O(log n) 返回原始事件索引。可选 [indices] 必须按时间排列，
     * 指向 [events]，且在筛选时预先构建。距离或时间相同时取首个匹配事件；
     * 方向按钮仍可逐条访问同一时刻的全部事件。
     */
    fun nearestEventIndex(fraction: Float, indices: List<Int>? = null): Int? {
        val count = indices?.size ?: events.size
        if (events.isEmpty() || count == 0) return null
        fun eventIndex(position: Int) = indices?.get(position) ?: position
        fun timestamp(position: Int) = events[eventIndex(position)].timestampMs
        val position = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val time = startMs + (endMs.toDouble() - startMs) * position
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high) ushr 1
            if (timestamp(middle).toDouble() < time) low = middle + 1 else high = middle
        }
        val candidate = when {
            low == 0 -> 0
            low == count -> count - 1
            time - timestamp(low - 1) <= timestamp(low) - time -> low - 1
            else -> low
        }
        // 即使同一时间戳有大量事件，也以 O(log n) 找到第一条。
        val chosenTime = timestamp(candidate)
        low = 0
        high = candidate
        while (low < high) {
            val middle = (low + high) ushr 1
            if (timestamp(middle) < chosenTime) low = middle + 1 else high = middle
        }
        return eventIndex(low)
    }

    fun previousIndex(index: Int): Int? = (index - 1).takeIf { index in events.indices && it >= 0 }
    fun nextIndex(index: Int): Int? = (index + 1).takeIf { index in events.indices && it < events.size }

    companion object {
        fun build(
            reportStartMs: Long,
            reportEndMs: Long,
            sortedEvents: List<CoreEvent>,
            bucketCount: Int = 256
        ): CoreTraceGraph {
            val buckets = bucketCount.coerceIn(1, 512)
            // 正常调用方提供稳定的时间有序列表；对导入或手工构建的数据补做稳定排序，
            // 已排序的列表不再复制。
            val events = if ((1 until sortedEvents.size).any {
                    sortedEvents[it - 1].timestampMs > sortedEvents[it].timestampMs
                }) sortedEvents.sortedBy { it.timestampMs } else sortedEvents
            val start = minOf(reportStartMs, events.firstOrNull()?.timestampMs ?: reportStartMs)
                .coerceAtMost(Long.MAX_VALUE - 1)
            val end = maxOf(reportEndMs, events.lastOrNull()?.timestampMs ?: reportEndMs, start + 1)
            val duration = end.toDouble() - start
            fun position(time: Long) = ((time.toDouble() - start) / duration).coerceIn(0.0, 1.0).toFloat()
            fun bucket(fraction: Float) = (fraction * buckets).toInt().coerceIn(0, buckets - 1)
            val lanes = sortedMapOf<Int, LaneBuilder>()
            val active = mutableMapOf<Int, OpenInterval>()
            fun lane(cpu: Int) = lanes.getOrPut(cpu) { LaneBuilder(cpu, buckets) }
            fun close(cpu: Int, time: Long, eventIndex: Int?) {
                val open = active.remove(cpu) ?: return
                if (time <= open.time) return
                val interval = CoreTraceInterval(position(open.time), position(time), open.eventIndex, eventIndex)
                lane(cpu).add(interval, bucket(interval.startFraction))
            }
            events.forEachIndexed { index, event ->
                val realThread = event.identity.pid > 0 && event.identity.tid > 0
                val fraction = position(event.timestampMs)
                val column = bucket(fraction)
                if (realThread) {
                    event.beforeCpus?.filter { it >= 0 }?.forEach { lane(it) }
                    event.afterCpus?.filter { it >= 0 }?.forEach { lane(it) }
                    event.runningCpu?.takeIf { it >= 0 && event.kind != CoreEventKind.EXIT }?.let { cpu ->
                        lane(cpu).add(CoreTraceSample(index, fraction), column)
                    }
                }
                val allowed = if (realThread && event.source == CoreEventSource.QIXIA &&
                    event.kind != CoreEventKind.EXIT && event.kind != CoreEventKind.ERROR &&
                    !(event.legacy && event.kind == CoreEventKind.RELEASE)
                ) event.afterCpus?.filter { it >= 0 }?.toSet().orEmpty() else emptySet()
                active.keys.filter { it !in allowed }.forEach { close(it, event.timestampMs, index) }
                allowed.forEach { cpu ->
                    if (cpu !in active) active[cpu] = OpenInterval(event.timestampMs, index)
                }
            }
            active.keys.toList().forEach { close(it, end, null) }
            return CoreTraceGraph(start, end, events, lanes.values.map { it.build() },
                lanes.values.any { it.compressed }, buckets)
        }
    }
}

private data class OpenInterval(val time: Long, val eventIndex: Int)

private class LaneBuilder(private val cpu: Int, bucketCount: Int) {
    private val samples = arrayOfNulls<CoreTraceSample>(bucketCount)
    private val intervals = arrayOfNulls<CoreTraceInterval>(bucketCount)
    var compressed = false
        private set

    fun add(sample: CoreTraceSample, bucket: Int) {
        if (samples[bucket] != null) compressed = true
        samples[bucket] = sample
    }

    fun add(interval: CoreTraceInterval, bucket: Int) {
        val previous = intervals[bucket]
        if (previous != null) compressed = true
        // 保留真实区间，不合并跨越未观察或系统接管空缺的范围。
        if (previous == null || interval.endFraction - interval.startFraction >=
            previous.endFraction - previous.startFraction
        ) intervals[bucket] = interval
    }

    fun build() = CoreTraceLane(cpu, samples.filterNotNull(), intervals.filterNotNull())
}
