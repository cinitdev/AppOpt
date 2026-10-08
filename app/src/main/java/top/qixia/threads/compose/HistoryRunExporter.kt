package top.qixia.threads.compose

import android.content.Context
import top.qixia.threads.AutoHistoryStore
import top.qixia.threads.FpsSessionRecorder
import top.qixia.threads.HistoryMetrics
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次导出包含每条选中记录的全部已保存区间，并按记录顺序排列。 */
internal object HistoryRunExporter {
    fun export(context: Context, pkg: String, label: String, sessions: List<SessionSummary>,
        detail: (SessionSummary, Int) -> QixiaThreadsRepository.HistorySessionDetail): String {
        check(sessions.isNotEmpty()) { "没有可导出的会话" }
        val previousFps = if (sessions.any { it.source == HistorySource.CALIBRATION })
            FpsSessionRecorder.readLastAverage(context.filesDir, pkg) else null
        val suffix = sessions.singleOrNull()?.id?.toString() ?: "all"
        return exportText(context, "QixiaThreads_${pkg}_$suffix") { output ->
            appendTo(output, pkg, label, sessions, previousFps, detail)
        }
    }

    internal fun appendTo(output: Appendable, pkg: String, label: String,
        sessions: List<SessionSummary>, previousFps: FpsSessionRecorder.AverageRecord? = null,
        detail: (SessionSummary, Int) -> QixiaThreadsRepository.HistorySessionDetail) {
        if (sessions.any { it.source == HistorySource.AUTO_ALLOCATION }) {
            // 导入可能补入更早完成的分片，导致所有查看区间重新划分。
            // 需锁定归档直到最后一个区间写入完成，不能只在每次读取时加锁。
            synchronized(AutoHistoryStore) { appendSnapshot(output, pkg, label, sessions, previousFps, detail) }
        } else appendSnapshot(output, pkg, label, sessions, previousFps, detail)
    }

    private fun appendSnapshot(output: Appendable, pkg: String, label: String,
        sessions: List<SessionSummary>, previousFps: FpsSessionRecorder.AverageRecord?,
        detail: (SessionSummary, Int) -> QixiaThreadsRepository.HistorySessionDetail) = with(output) {
        check(sessions.isNotEmpty()) { "没有可导出的会话" }
        if (previousFps != null && sessions.any { it.source == HistorySource.CALIBRATION }) {
            appendLine("上次完成运行的平均 FPS: ${previousFps.averageFps}")
            appendLine("运行时长毫秒: ${previousFps.durationMs}")
            appendLine("该值独立于下方校准会话，不代表每条历史会话的帧率。")
            appendLine()
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        fun time(timestamp: Long) = "${dateFormat.format(Date(timestamp))} ($timestamp ms)"
        for (visit in sessions) {
            // 前进时只保留区间元数据，在请求下一区间前释放已解析的区间，
            // 不累积整次记录的全部列表。
            var current: QixiaThreadsRepository.HistorySessionDetail? = detail(visit, 0)
            val windows = current!!.windows.toList()
            val automatic = visit.source == HistorySource.AUTO_ALLOCATION
            if (automatic) {
                val start = visit.startedAtMs.takeIf { it > 0L }
                    ?: (visit.endedAtMs - visit.durationMs).coerceAtLeast(0L)
                appendLine("QixiaThreads 自动分配完整运行记录")
                appendLine("应用: $label")
                appendLine("包名: $pkg")
                appendLine("完整运行会话 ID: ${visit.id}")
                appendLine("完整运行开始: ${time(start)}")
                appendLine("完整运行结束: ${time(visit.endedAtMs)}")
                appendLine("完整运行时长 (ms): ${visit.durationMs}")
                appendLine("完整运行采样轮数: ${visit.rounds}")
                appendLine("完整运行活跃线程数: ${visit.threadCount}")
                appendLine("数据窗口数: ${windows.size.coerceAtLeast(1)}")
                appendLine("以下逐窗保留全部已记录数据；每窗的曲线进度、平均值和极值对应本窗真实起止时间。")
                appendLine()
            }
            val indexes = windows.map { it.index }.ifEmpty { listOf(0) }
            check(indexes.first() == 0 && indexes.distinct().size == indexes.size) { "记录窗口列表无效" }
            indexes.forEachIndexed { position, index ->
                if (current == null) current = detail(visit, index)
                val window = windows.getOrNull(position)
                if (automatic) {
                    val sample = checkNotNull(current).session
                    val start = window?.startMs ?: sample.startedAtMs.takeIf { it > 0L }
                        ?: (sample.endedAtMs - sample.durationMs).coerceAtLeast(0L)
                    appendLine("数据窗口 ${position + 1}/${indexes.size}（索引 $index）")
                    appendLine("窗口开始: ${time(start)}")
                    appendLine("窗口结束: ${time(window?.endMs ?: sample.endedAtMs)}")
                }
                appendWindow(output, pkg, label, checkNotNull(current))
                current = null
                appendLine()
            }
        }
    }

    private fun appendWindow(output: Appendable, pkg: String, label: String,
        detail: QixiaThreadsRepository.HistorySessionDetail) = with(output) {
        val session = detail.session
        HistoryExportText.appendSession(output, pkg, label, session, detail.threads)
        appendLine()
        detail.fps[session.id]?.let { fps ->
            appendLine("本次记录时段 FPS: AVG ${fps.average} / MIN ${fps.minimum} / MAX ${fps.maximum}")
            appendLine("FPS 采样点数: ${fps.samples}")
            appendLine("5% LOW: ${fps.low5 ?: "未达到 101 个样本"} / JITTER: ${fps.jitter ?: "未计算"} %")
            append("FPS 曲线 (时段进度,FPS): ")
            fps.points.forEachIndexed { index, point ->
                if (index > 0) append(';')
                append("${point.progress},${point.fps}")
            }
            appendLine()
            append("FPS 断点 (时段进度): ")
            var first = true
            for (point in fps.points) if (point.breakBefore) {
                if (!first) append(';')
                first = false
                append(point.progress.toString())
            }
            appendLine()
        }
        detail.metrics[session.id]?.let { report ->
            appendLine("设备指标：每 2 秒采样，${report.samples} 点，充电 ${report.chargingSamples} 点")
            report.device.forEach { (key, value) -> appendLine("设备 $key: $value") }
            appendLine("放电功率为电池电流×电压估算，充电时不代表整机功耗；CPU/GPU 温度取已识别传感器最大值。")
            report.series.values.forEach { series ->
                val unit = HistoryMetrics.unit(series.key)
                appendLine("${HistoryMetrics.title(series.key)} [$unit]: AVG ${series.average} / MIN ${series.minimum} / MAX ${series.maximum}")
                append("曲线 (进度,数值,断点): ")
                series.points.forEachIndexed { index, point ->
                    if (index > 0) append(';')
                    append("${point.progress},${point.value},${point.breakBefore}")
                }
                appendLine()
            }
            report.distributions.forEach { (key, values) ->
                append("${HistoryMetrics.title(key)} 分布 (最低MHz,最高MHz,有效样本%): ")
                values.forEachIndexed { index, value ->
                    if (index > 0) append(';')
                    append("${value.minimum},${value.maximum},${value.percent}")
                }
                appendLine()
            }
        }
        if (session.source == HistorySource.AUTO_ALLOCATION) {
            detail.coreTimeline?.appendTo(output)
        }
    }
}
