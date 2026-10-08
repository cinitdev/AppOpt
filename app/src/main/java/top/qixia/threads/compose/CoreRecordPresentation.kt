package top.qixia.threads.compose

import androidx.compose.runtime.Immutable
import top.qixia.threads.CoreEvent
import top.qixia.threads.CoreEventKind
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 每个选中线程的文字只准备一次，不受 LazyColumn 条目生命周期影响。 */
@Immutable
internal data class CoreRecordRow(
    val index: Int,
    val before: String,
    val after: String,
    val rangeUnchanged: Boolean,
    val date: String,
    val time: String,
    val average: String,
    val reason: String,
    val sourceLabel: String,
    val runningCpuLabel: String?,
    val kind: CoreEventKind
)

internal object CoreRecordPresentation {
    /** 在滚动和组合之外调用，只处理选中线程，不处理整份报告。 */
    fun prepare(
        events: List<CoreEvent>,
        locale: Locale = Locale.getDefault(),
        timezone: TimeZone = TimeZone.getDefault()
    ): List<CoreRecordRow> {
        val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss.SSS", locale).apply {
            timeZone = timezone.clone() as TimeZone
        }
        val numberFormat = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            multiplier = 1
        }
        // 通常只有少量重复掩码，但异常或导入数据也不能让辅助缓存无限增长。
        // 复制缓存键，避免缓存持有调用方拥有的列表。
        val cpuLabels = object : LinkedHashMap<List<Int>, String>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Int>, String>?): Boolean = size > 64
        }
        fun cpus(values: List<Int>?): String {
            if (values.isNullOrEmpty()) return "范围未记录"
            cpuLabels[values]?.let { return it }
            return CoreTimelinePresentation.cpus(values).also { cpuLabels[values.toList()] = it }
        }
        return events.mapIndexed { index, event ->
            val datetime = dateFormat.format(Date(event.timestampMs))
            CoreRecordRow(
                index = index,
                before = cpus(event.beforeCpus),
                after = cpus(event.afterCpus),
                rangeUnchanged = CoreTimelinePresentation.rangeUnchanged(event),
                date = datetime.substringBefore(' '),
                time = datetime.substringAfter(' '),
                average = event.averagePercent?.takeIf { it.isFinite() }?.let {
                    numberFormat.format(it.toDouble()) + "%"
                } ?: "未采集",
                reason = CoreTimelinePresentation.reason(event),
                sourceLabel = if (event.legacy && event.kind == CoreEventKind.RELEASE) "旧版未记录"
                    else CoreTimelinePresentation.source(event.source),
                runningCpuLabel = event.runningCpu?.let { "执行核采样 · CPU $it" },
                kind = event.kind
            )
        }
    }
}
