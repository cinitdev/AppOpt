package top.qixia.threads.compose

import top.qixia.threads.HistoryFpsStore
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.db.SessionSummary
import kotlin.math.abs
import kotlin.math.max

data class FrameDrop(val progress: Float, val fps: Float?, val frameMaxMs: Float?, val referenceFps: Float?)

/** 只定位已保存的采样区间，不能从每秒 FPS 倒推出完整的逐帧耗时。 */
object HistoryAnalysis {
    fun reference(fps: HistoryFpsStore.Report?): Float? {
        val values = fps?.points.orEmpty().map { it.fps }.filter { it.isFinite() && it >= 10f }.sorted()
        return values.takeIf { it.size >= 8 }?.get(((values.size - 1) * .8).toInt())
    }

    fun drops(fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?, durationMs: Long): List<FrameDrop> {
        if (durationMs <= 0) return emptyList()
        val reference = reference(fps)
        val frames = metrics?.series?.get("frame_max_ms")?.points.orEmpty()
        val frameCurve = frames.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }
        val fpsCurve = fps?.points.orEmpty().map { HistoryCurvePoint(it.progress, it.fps, it.breakBefore) }
        val candidates = sortedMapOf<Float, FrameDrop>()
        fps?.points.orEmpty().filter { reference != null && it.fps.isFinite() && it.fps >= 0 &&
            it.fps < reference * .8f && !it.breakBefore }.forEach {
            candidates[it.progress] = FrameDrop(it.progress, it.fps,
                nearby(frameCurve, it.progress, durationMs)?.value, reference)
        }
        frames.filter { !it.breakBefore && it.value.isFinite() &&
            it.value >= max(50f, reference?.let { fps -> 2000f / fps } ?: 50f) }.forEach {
            val value = nearby(fpsCurve, it.progress, durationMs)?.value
            candidates[it.progress] = FrameDrop(it.progress, value, it.value, reference)
        }
        // 连续两秒内的候选合为一个区间，只保留最明显的采样点，避免图上堆叠标记。
        val result = mutableListOf<FrameDrop>()
        var groupStart = -1f
        candidates.values.forEach { point ->
            if (result.isEmpty() || (point.progress - groupStart) * durationMs > 2000) {
                groupStart = point.progress
                result += point
            } else {
                fun severity(p: FrameDrop): Float = max((p.frameMaxMs ?: 0f) / 50f,
                    p.referenceFps?.let { it / (p.fps ?: it).coerceAtLeast(1f) } ?: 0f)
                if (severity(point) > severity(result.last())) result[result.lastIndex] = point
            }
        }
        return result
    }

    /** 不跨断点、长空洞或报告边界借用其他时间的数据。 */
    fun at(points: List<HistoryMetrics.Point>, progress: Float, durationMs: Long): Float? {
        val curves = points.map { HistoryCurvePoint(it.progress, it.value, it.breakBefore) }
        return nearby(curves, progress, durationMs)?.value
    }

    internal fun nearby(points: List<HistoryCurvePoint>, progress: Float, durationMs: Long): HistoryCurvePoint? =
        if (durationMs <= 0) null else HistoryPlotMath.at(points, progress, durationMs)?.takeIf {
            it.value.isFinite() && abs(it.progress - progress).toDouble() * durationMs <= 1500.0
        }
}

data class RunComparisonSummary(
    val session: SessionSummary,
    val averageFps: Float?, val minimumFps: Float?, val maximumFps: Float?,
    val fpsSamples: Long, val averagePower: Float?, val powerSamples: Long,
    val maximumFrameMs: Float?, val batteryUsed: Float?, val charging: Boolean,
    val metricsCoverage: Float, val device: Map<String, String>,
    val sampledFps: List<Float>, val completeFpsSamples: Boolean, val referenceFps: Float? = null
)

/** 顺序读取分片，只保留汇总和有界 FPS 数组，不把两次长会话的线程事件同时放进内存。 */
internal class RunComparisonAccumulator(private val session: SessionSummary) {
    private var fpsSum = 0.0
    private var fpsCount = 0L
    private var minimum: Float? = null
    private var maximum: Float? = null
    private var powerSum = 0.0
    private var powerCount = 0L
    private var frameMax: Float? = null
    private var batteryFirst: Pair<Long, Float>? = null
    private var batteryLast: Pair<Long, Float>? = null
    private var charging = false
    private var coveredMs = 0.0
    private var device = emptyMap<String, String>()
    private val values = ArrayList<Float>()
    private var complete = true

    fun add(window: SessionSummary, fps: HistoryFpsStore.Report?, metrics: HistoryMetrics.Report?) {
        fps?.takeIf { it.samples > 0 && it.average.isFinite() }?.let {
            fpsSum += it.average.toDouble() * it.samples; fpsCount += it.samples
            minimum = minOf(minimum ?: it.minimum, it.minimum)
            maximum = maxOf(maximum ?: it.maximum, it.maximum)
            if (it.samples != it.points.size || values.size + it.points.size > 100_000) complete = false
            if (values.size + it.points.size <= 100_000) values += it.points.map { point -> point.fps }
        }
        metrics?.let {
            coveredMs += it.coverage.coerceIn(0f, 1f) * window.durationMs
            if (device.isEmpty()) device = it.device
            charging = charging || it.chargingSamples > 0
            it.series["power_w"]?.takeIf { series -> series.samples > 0 }?.let { series ->
                powerSum += series.average.toDouble() * series.samples; powerCount += series.samples
            }
            it.series["frame_max_ms"]?.maximum?.let { value -> frameMax = maxOf(frameMax ?: value, value) }
            val start = window.endedAtMs - window.durationMs
            it.series["battery_pct"]?.points?.forEach { point ->
                val time = start + (point.progress * window.durationMs).toLong()
                if (batteryFirst == null || time < batteryFirst!!.first) batteryFirst = time to point.value
                if (batteryLast == null || time > batteryLast!!.first) batteryLast = time to point.value
            }
        }
    }

    fun finish(): RunComparisonSummary {
        val start = session.endedAtMs - session.durationMs
        val tolerance = maxOf(5000L, (session.durationMs * .02).toLong())
        val used = batteryFirst?.let { first -> batteryLast?.takeIf { last ->
            last.first > first.first && abs(first.first - start) <= tolerance &&
                abs(session.endedAtMs - last.first) <= tolerance
        }?.let { last -> first.second - last.second } }
        return RunComparisonSummary(session, if (fpsCount > 0) (fpsSum / fpsCount).toFloat() else null,
            minimum, maximum, fpsCount, if (powerCount > 0) (powerSum / powerCount).toFloat() else null,
            powerCount, frameMax, used, charging,
            (coveredMs / session.durationMs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f), device,
            values, complete && values.size.toLong() == fpsCount,
            values.filter { it.isFinite() && it >= 10f }.sorted().takeIf { it.size >= 8 }
                ?.let { it[((it.size - 1) * .8).toInt()] })
    }
}

data class HistoryComparisonUiState(val packageName: String, val label: String,
    val first: SessionSummary, val second: SessionSummary, val loading: Boolean = true,
    val summaries: List<RunComparisonSummary> = emptyList(), val error: String? = null)

internal object HistoryRunComparison {
    fun summarize(session: SessionSummary, checkCancelled: () -> Unit,
        load: (Int) -> QixiaThreadsRepository.HistorySessionDetail): RunComparisonSummary {
        val accumulator = RunComparisonAccumulator(session)
        checkCancelled()
        val windows = load(0).let { first ->
            if (first.windows.isNotEmpty()) check(first.windows.first().startMs == session.endedAtMs - session.durationMs &&
                first.windows.last().endMs == session.endedAtMs) { "记录仍在更新，请刷新历史列表后重试" }
            accumulator.add(first.session, first.fps[session.id], first.metrics[session.id])
            first.windows
        }
        check(windows.isEmpty() || windows.map { it.index } == windows.indices.toList()) { "记录窗口列表无效" }
        for (window in windows.drop(1)) {
            checkCancelled()
            val next = load(window.index)
            check(next.windows == windows) { "记录分片已变化，请重新对比" }
            accumulator.add(next.session, next.fps[session.id], next.metrics[session.id])
        }
        checkCancelled()
        return accumulator.finish()
    }
}
