package top.qixia.threads

/** 阈值为单线程近期平均负载百分比，核心范围使用应用现有的 CPU 列表格式。 */
data class AutomaticCustomRule(val threshold: Int, val cpus: String)

internal object AutomaticCustomRules {
    const val MAX_RULES = 8
    private val thresholdPattern = Regex("(?:[1-9][0-9]?|100)")
    private val corePattern = Regex("(?:0|[1-9][0-9]?)(?:-(?:0|[1-9][0-9]?))?")

    // 配置行以逗号分隔功能开关，所以档位内部以 + 分隔不连续的核心段。
    fun decode(value: String): List<AutomaticCustomRule>? {
        if (value.length > 2048) return null
        val parts = value.split('|')
        if (parts.size !in 1..MAX_RULES) return null
        val rules = parts.map { part ->
            val pair = part.split(':')
            if (pair.size != 2 || !thresholdPattern.matches(pair[0])) return null
            val ranges = pair[1].split('+')
            if (ranges.any { !corePattern.matches(it) }) return null
            val cpus = RuleConfigLogic.parseNativeCpuRangeList(ranges.joinToString(",")) ?: return null
            if (cpus.isEmpty() || cpus.any { it !in 0..63 }) return null
            AutomaticCustomRule(pair[0].toInt(), RuleConfigLogic.formatCpuRangeList(cpus))
        }
        if (rules.map { it.threshold }.distinct().size != rules.size) return null
        return rules.sortedBy { it.threshold }
    }

    fun encode(rules: List<AutomaticCustomRule>): String {
        val raw = rules.joinToString("|") { "${it.threshold}:${it.cpus.replace(',', '+')}" }
        val normalized = decode(raw) ?: throw IllegalArgumentException("请设置 1～100% 的整数阈值和有效核心；最多 8 档，阈值不能重复")
        return normalized.joinToString("|") { "${it.threshold}:${it.cpus.replace(',', '+')}" }
    }

    /** Shell 在持有配置锁期间校验旧值，防止编辑其他开关时破坏自定义模式。 */
    val awkValidator = """
        function customValid(value, tiers, pair, ranges, ends, seen, n, i, j, k, count, first, last) {
            if(length(value)>2048) return 0
            n=split(value, tiers, "|")
            if(n<1 || n>8) return 0
            for(i=1;i<=n;i++) {
                if(split(tiers[i],pair,":")!=2 || pair[1]!~/^([1-9][0-9]?|100)${'$'}/ || seen[pair[1]]++) return 0
                count=split(pair[2],ranges,"+")
                if(count<1) return 0
                for(j=1;j<=count;j++) {
                    if(ranges[j]!~/^(0|[1-9][0-9]?)(-(0|[1-9][0-9]?))?${'$'}/) return 0
                    k=split(ranges[j],ends,"-"); first=ends[1]+0; last=(k==2 ? ends[2]+0 : first)
                    if(first>last || last>63) return 0
                }
            }
            return 1
        }
    """.trimIndent()
}
