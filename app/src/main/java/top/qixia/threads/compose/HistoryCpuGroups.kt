package top.qixia.threads.compose

/** 使用记录当时的调频策略分组，不使用查看设备的当前拓扑。 */
internal class HistoryCpuGroups private constructor(private val byCpu: Map<Int, Group>) {
    data class Group(val cpus: List<Int>) {
        val key = "cpu_group.${cpus.joinToString("_")}"
        val label = HistoryPlotMath.cpuLabel(key)
        val color = HistoryChartPalette.core(cpus.first())
    }

    fun forMetric(key: String): Group? {
        if (!key.startsWith("cpu_core.") && !key.startsWith("cpu_cycles_core.")) return null
        return key.substringAfter('.').toIntOrNull()?.let(byCpu::get)
    }

    companion object {
        fun fromKeys(keys: Collection<String>): HistoryCpuGroups {
            val byCpu = mutableMapOf<Int, Group>()
            // 以调频策略分组为准；使用率分组仅补充无法读取频率节点的部分，
            // 重叠的兜底分组不得替换已知调频策略。
            for (prefix in listOf("cpu_mhz.", "cpu_cluster.")) {
                val candidates = keys.filter { it.startsWith(prefix) }.mapNotNull { key ->
                    val tokens = key.removePrefix(prefix).split('_')
                    if (tokens.any { token -> token.isEmpty() || token.any { it !in '0'..'9' } }) return@mapNotNull null
                    val cpus = tokens.map { it.toIntOrNull() ?: return@mapNotNull null }
                    if (cpus.any { it !in 0..63 }) return@mapNotNull null
                    cpus.distinct().sorted()
                }.distinct().filter { cpus -> cpus.none { it in byCpu } }
                val occurrences = candidates.flatten().groupingBy { it }.eachCount()
                // 归属不明确时按单核展示，不推测分组。
                candidates.filter { cpus -> cpus.all { occurrences[it] == 1 } }.forEach { cpus ->
                    val group = Group(cpus)
                    cpus.forEach { byCpu[it] = group }
                }
            }
            return HistoryCpuGroups(byCpu)
        }
    }
}
