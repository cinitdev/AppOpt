package top.qixia.threads.compose

/** 经过校验的展示元数据，不用于分配决策。 */
internal data class SettingsTopologyPresentation(
    val fields: List<Pair<String, String>>,
    val groups: List<Group>,
    val pendingReason: String?,
    val domains: List<Group> = emptyList(),
    val domainPendingReason: String? = "尚未获取独立调频域信息。"
) {
    data class Group(val cpus: Set<Int>)

    val confirmed: Boolean get() = pendingReason == null
    val domainsConfirmed: Boolean get() = domainPendingReason == null

    companion object {
        private const val GROUP_PREFIX = "detected_group_"
        private const val DOMAIN_PREFIX = "detected_freq_domain_"
        private val cpuToken = Regex("([0-9]+)(?:\\s*-\\s*([0-9]+))?")

        fun parse(block: String, presentCpus: Set<Int>): SettingsTopologyPresentation {
            // 重复字段保留在详情中，不静默选取其中一项。
            val fields = block.lineSequence().map(String::trim)
                .filter { it.startsWith("detected_") && '=' in it }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.toList()
            fun pending(reason: String) = SettingsTopologyPresentation(fields, emptyList(), reason,
                domainPendingReason = reason)
            if (presentCpus.isEmpty() || presentCpus.any { it < 0 }) return pending("尚未读取完整设备核心列表。")
            if (fields.map { it.first }.distinct().size != fields.size) return pending("拓扑字段重复，等待重新识别。")
            val values = fields.toMap()
            values["detected_all"]?.let { all ->
                if (parseCpus(all, presentCpus) != presentCpus) return pending("拓扑记录与设备核心列表不一致。")
            }
            val performance = partition(values, presentCpus, GROUP_PREFIX, "detected_clusters",
                "detected_complete", "性能组")
            val frequency = partition(values, presentCpus, DOMAIN_PREFIX, "detected_freq_domains",
                "detected_freq_complete", "调频域", requireCount = true)
            return SettingsTopologyPresentation(fields, performance.first, performance.second,
                frequency.first, frequency.second)
        }

        private fun partition(
            values: Map<String, String>, presentCpus: Set<Int>, prefix: String,
            countKey: String, completeKey: String, label: String, requireCount: Boolean = false
        ): Pair<List<Group>, String?> {
            fun pending(reason: String): Pair<List<Group>, String?> = emptyList<Group>() to reason
            if (values[completeKey] != "1") return pending("${label}信息尚未完整识别。")
            val rawGroups = values.filterKeys { it.startsWith(prefix) }
            if (rawGroups.isEmpty()) return pending("缺少完整的${label}信息。")
            val indexedGroups = rawGroups.map { (key, value) ->
                val index = key.removePrefix(prefix).toIntOrNull()
                if (index == null || index < 0 || key != "$prefix$index") {
                    return pending("${label}编号无效，等待重新识别。")
                }
                index to value
            }.sortedBy { it.first }
            if (indexedGroups.withIndex().any { (index, group) -> index != group.first }) {
                return pending("${label}信息缺失，等待重新识别。")
            }
            if (requireCount && countKey !in values) return pending("${label}数量缺失。")
            values[countKey]?.let { count ->
                if (count.toIntOrNull() != indexedGroups.size) return pending("${label}数量与记录不一致。")
            }

            val covered = mutableSetOf<Int>()
            val groups = indexedGroups.map { (_, value) ->
                val cpus = parseCpus(value, presentCpus)
                    ?: return pending("${label}含有无效、重复或设备之外的核心。")
                if (cpus.any { !covered.add(it) }) return pending("${label}存在重叠，等待重新识别。")
                Group(cpus)
            }
            if (covered != presentCpus) return pending("${label}尚未覆盖全部设备核心。")
            // 数字分组顺序来自原生性能排序，不按 CPU 编号排序。
            return groups to null
        }

        private fun parseCpus(value: String, presentCpus: Set<Int>): Set<Int>? {
            val cpus = linkedSetOf<Int>()
            for (token in value.split(',')) {
                val match = cpuToken.matchEntire(token.trim()) ?: return null
                val first = match.groupValues[1].toIntOrNull() ?: return null
                val last = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
                    ?: if (match.groupValues[2].isEmpty()) first else return null
                // 展开前拒绝过大或格式错误的范围，计算量受实际核心数约束。
                if (last < first || last.toLong() - first + 1 > presentCpus.size) return null
                for (cpu in first..last) {
                    if (cpu !in presentCpus || !cpus.add(cpu)) return null
                }
            }
            return cpus.takeIf { it.isNotEmpty() }
        }
    }
}
