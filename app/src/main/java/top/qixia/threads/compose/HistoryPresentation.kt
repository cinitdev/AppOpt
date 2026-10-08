package top.qixia.threads.compose

import top.qixia.threads.HistoryFieldCodec
import top.qixia.threads.db.ThreadData
import top.qixia.threads.db.HistorySource

data class ChildLoadModel(val name: String, val avg: Float?, val max: Float?)
data class HistoryCurvePoint(val progress: Float, val value: Float, val breakBefore: Boolean = false)

object HistoryPresentation {
    fun sourceLabel(source: HistorySource): String = when (source) {
        HistorySource.CALIBRATION -> "校准记录"
        HistorySource.AUTO_ALLOCATION -> "自动分配记录"
    }
    fun durationMs(rounds: Int): Long = rounds.coerceAtLeast(0).toLong() * 500L

    fun isProcess(thread: ThreadData): Boolean =
        HistoryFieldCodec.parseChildDetails(thread.details)?.processAggregate == true

    /** 保留完整时间范围及各桶极值，不只截取前 N 个样本。 */
    fun curve(series: String, budget: Int = 240): List<HistoryCurvePoint> {
        if (series.startsWith("@t:")) return series.removePrefix("@t:").split(',').mapNotNull { token ->
            val fields = token.split('=')
            val progress = fields.getOrNull(0)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
            val value = fields.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            if (progress == null || value == null) null else HistoryCurvePoint(progress, value, fields.getOrNull(2) == "1")
        }
        val tokens = series.split(',', ' ', ';').filter { it.isNotBlank() }
        val values = tokens.mapIndexedNotNull { index, token ->
            token.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }?.let {
                HistoryCurvePoint(index.toFloat() / (tokens.size - 1).coerceAtLeast(1), it)
            }
        }
        val limit = budget.coerceAtLeast(8)
        if (values.size <= limit) return values
        val buckets = (limit - 2) / 2
        val middle = values.subList(1, values.lastIndex).chunked((values.size - 2 + buckets - 1) / buckets)
        return listOf(values.first()) + middle.flatMap { bucket ->
            listOf(bucket.minBy { it.value }, bucket.maxBy { it.value }).distinct().sortedBy { it.progress }
        } + values.last()
    }

    fun children(details: String): List<ChildLoadModel> {
        if (details.isBlank()) return emptyList()
        val payload = HistoryFieldCodec.parseChildDetails(details)
            ?: return details.split(',').map(String::trim).filter(String::isNotEmpty).map { ChildLoadModel(it, null, null) }
        return payload.body.split(';').mapNotNull { record ->
            val fields = record.split(',', limit = 3)
            val rawName = fields.firstOrNull()?.trim().orEmpty()
            val name = if (payload.encodedNames) HistoryFieldCodec.decodeName(rawName) else rawName
            if (name.isBlank()) null else ChildLoadModel(name, fields.getOrNull(1)?.toFloatOrNull(), fields.getOrNull(2)?.toFloatOrNull())
        }
    }
}
