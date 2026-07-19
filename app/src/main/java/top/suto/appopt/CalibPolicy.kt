package top.suto.appopt

data class CalibPolicy(
    val bestAvg: Double = 18.0,
    val bestMax: Double = 30.0,
    val bestCores: String = DEFAULT_BEST_CORES,
    val highAvg: Double = 13.0,
    val highMax: Double = 22.0,
    val highCores: String = DEFAULT_HIGH_CORES,
    val midAvg: Double = 8.0,
    val midMax: Double = 18.0,
    val midCores: String = DEFAULT_MID_CORES,
    val maxThreadRules: Int = 6,
    val wildcardGroup: WildcardGroup = WildcardGroup.MAX_MEMBER,
    val ruleOutputFormat: RuleOutputFormat = RuleOutputFormat.LEGACY,
    val fallbackCores: String = DEFAULT_FALLBACK_CORES,
    val detectedTopologyBlock: String = ""
) {
    enum class WildcardGroup(val wire: String) {
        MAX_MEMBER("max_member"),
        SUM("sum");

        companion object {
            fun fromWire(value: String): WildcardGroup {
                return values().firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
                    ?: MAX_MEMBER
            }
        }
    }

    enum class RuleOutputFormat(val wire: String) {
        LEGACY("legacy"),
        AUTHOR_BLOCK("author_block"),
        COMPACT_HEADER_BLOCK("compact_header_block"),
        SEPARATE_FALLBACK_BLOCK("separate_fallback_block"),
        COMPACT_SEPARATE_FALLBACK_BLOCK("compact_separate_fallback_block"),
        EXTENDED_BLOCK("extended_block"),
        COMPACT_EXTENDED_BLOCK("compact_extended_block"),
        TAGGED_BLOCK("tagged_block"),
        NATURAL_BLOCK("natural_block"),
        NESTED_BLOCK("nested_block"),
        FUNCTION_BLOCK("function_block"),
        YAML("yaml");

        val requiresAuthorMigration: Boolean
            get() = this == COMPACT_HEADER_BLOCK ||
                this == SEPARATE_FALLBACK_BLOCK ||
                this == COMPACT_SEPARATE_FALLBACK_BLOCK ||
                this == EXTENDED_BLOCK

        fun generationTarget(): RuleOutputFormat {
            return if (requiresAuthorMigration) AUTHOR_BLOCK else this
        }

        companion object {
            fun fromWire(value: String): RuleOutputFormat {
                return values().firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
                    ?: LEGACY
            }
        }
    }

    fun normalized(
        bestDefault: String = DEFAULT_BEST_CORES,
        highDefault: String = DEFAULT_HIGH_CORES,
        midDefault: String = DEFAULT_MID_CORES,
        fallbackDefault: String = DEFAULT_FALLBACK_CORES
    ): CalibPolicy {
        return copy(
            bestAvg = bestAvg.coerceIn(0.0, 100.0),
            bestMax = bestMax.coerceIn(0.0, 100.0),
            highAvg = highAvg.coerceIn(0.0, 100.0),
            highMax = highMax.coerceIn(0.0, 100.0),
            midAvg = midAvg.coerceIn(0.0, 100.0),
            midMax = midMax.coerceIn(0.0, 100.0),
            maxThreadRules = maxThreadRules.coerceIn(1, 12),
            bestCores = normalizeCoresOrDefault(bestCores, bestDefault),
            highCores = normalizeCoresOrDefault(highCores, highDefault),
            midCores = normalizeCoresOrDefault(midCores, midDefault),
            fallbackCores = normalizeCoresOrDefault(fallbackCores, fallbackDefault)
        )
    }

    fun toConfigText(): String {
        val defaults = topologyDefaults(detectedTopologyBlock)
        val p = normalized(
            bestDefault = defaults.best,
            highDefault = defaults.high,
            midDefault = defaults.mid,
            fallbackDefault = defaults.fallback
        )
        return buildString {
            appendLine("# AppOpt 自动校准策略")
            appendLine("# App 内可视化编辑；手动改动时请保持 key=value 格式。")
            appendLine("# 分配核心为连续 CPU 编号范围, 例如 7、5-6、0-6。")
            appendLine("version=1")
            appendLine("best_thread=avg:${fmt(p.bestAvg)},max:${fmt(p.bestMax)},cores:${p.bestCores}")
            appendLine("group_high=avg:${fmt(p.highAvg)},max:${fmt(p.highMax)},cores:${p.highCores}")
            appendLine("group_mid=avg:${fmt(p.midAvg)},max:${fmt(p.midMax)},cores:${p.midCores}")
            appendLine("wildcard_group=${p.wildcardGroup.wire}")
            appendLine("rule_output_format=${p.ruleOutputFormat.generationTarget().wire}")
            appendLine("max_thread_rules=${p.maxThreadRules}")
            appendLine("fallback=cores:${p.fallbackCores}")
            if (p.detectedTopologyBlock.isNotBlank()) {
                appendLine()
                appendLine(p.detectedTopologyBlock.trimEnd())
            }
        }
    }

    companion object {
        const val DEFAULT_BEST_CORES = "7"
        const val DEFAULT_HIGH_CORES = "5-6"
        const val DEFAULT_MID_CORES = "4-6"
        const val DEFAULT_FALLBACK_CORES = "0-6"
        val DEFAULT = CalibPolicy()

        fun parse(raw: String): CalibPolicy {
            var policy = DEFAULT.copy(
                bestCores = "",
                highCores = "",
                midCores = "",
                fallbackCores = ""
            )
            val topologyLines = mutableListOf<String>()
            var inTopologyBlock = false
            for (rawLine in raw.lineSequence()) {
                val rawTrim = rawLine.trim()
                if (rawTrim == "# AppOpt detected CPU topology begin") {
                    inTopologyBlock = true
                    topologyLines += rawLine
                    continue
                }
                if (inTopologyBlock) {
                    topologyLines += rawLine
                    if (rawTrim == "# AppOpt detected CPU topology end") {
                        inTopologyBlock = false
                    }
                    continue
                }
                if (rawTrim.startsWith("# CPU 拓扑识别:") || rawTrim.startsWith("detected_")) {
                    topologyLines += rawLine
                    continue
                }
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "best_thread" -> {
                        val avg = ruleNumber(value, "avg") ?: policy.bestAvg
                        val max = ruleNumber(value, "max") ?: policy.bestMax
                        val cores = ruleText(value, "cores") ?: policy.bestCores
                        policy = policy.copy(bestAvg = avg, bestMax = max, bestCores = cores)
                    }
                    "group_high" -> {
                        val avg = ruleNumber(value, "avg") ?: policy.highAvg
                        val max = ruleNumber(value, "max") ?: policy.highMax
                        val cores = ruleText(value, "cores") ?: policy.highCores
                        policy = policy.copy(highAvg = avg, highMax = max, highCores = cores)
                    }
                    "group_mid" -> {
                        val avg = ruleNumber(value, "avg") ?: policy.midAvg
                        val max = ruleNumber(value, "max") ?: policy.midMax
                        val cores = ruleText(value, "cores") ?: policy.midCores
                        policy = policy.copy(midAvg = avg, midMax = max, midCores = cores)
                    }
                    "wildcard_group" -> {
                        policy = policy.copy(wildcardGroup = WildcardGroup.fromWire(value))
                    }
                    "rule_output_format" -> {
                        policy = policy.copy(ruleOutputFormat = RuleOutputFormat.fromWire(value))
                    }
                    "max_thread_rules" -> {
                        policy = policy.copy(maxThreadRules = value.toIntOrNull() ?: policy.maxThreadRules)
                    }
                    "fallback" -> {
                        if (value.contains(':')) {
                            val cores = ruleText(value, "cores") ?: policy.fallbackCores
                            policy = policy.copy(fallbackCores = cores)
                        } else {
                            policy = policy.copy(fallbackCores = value)
                        }
                    }
                }
            }
            val topologyBlock = topologyLines.joinToString("\n")
            val defaults = topologyDefaults(topologyBlock)
            return policy.copy(
                detectedTopologyBlock = topologyBlock
            ).normalized(
                bestDefault = defaults.best,
                highDefault = defaults.high,
                midDefault = defaults.mid,
                fallbackDefault = defaults.fallback
            )
        }

        private fun ruleNumber(rule: String, name: String): Double? {
            return ruleText(rule, name)?.toDoubleOrNull()
        }

        private fun ruleText(rule: String, name: String): String? {
            val key = "$name:"
            val start = rule.indexOf(key, ignoreCase = true)
            if (start < 0) return null
            val valueStart = start + key.length
            val valueEnd = if (name.equals("cores", ignoreCase = true)) {
                listOf(",avg:", ",max:", ",cores:")
                    .map { marker -> rule.indexOf(marker, startIndex = valueStart, ignoreCase = true) }
                    .filter { it >= 0 }
                    .minOrNull()
                    ?: rule.length
            } else {
                rule.indexOf(',', startIndex = valueStart).takeIf { it >= 0 } ?: rule.length
            }
            return rule.substring(valueStart, valueEnd)
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        fun normalizeCoresOrDefault(value: String, fallback: String): String {
            return normalizeCoresOrNull(value)
                ?: normalizeCoresOrNull(fallback)
                ?: DEFAULT_FALLBACK_CORES
        }

        fun normalizeCoresOrNull(value: String): String? {
            val compact = value.trim().replace(" ", "")
            if (compact.isBlank()) return null
            return parseCores(compact)?.let { formatContinuousRange(it) }
        }

        private fun parseCores(value: String): Set<Int>? {
            val out = linkedSetOf<Int>()
            for (raw in value.split(',')) {
                val part = raw.trim()
                if (part.isEmpty()) return null
                val dash = part.indexOf('-')
                if (dash >= 0) {
                    val start = part.substring(0, dash).toIntOrNull() ?: return null
                    val end = part.substring(dash + 1).toIntOrNull() ?: return null
                    if (start < 0 || end < start) return null
                    for (cpu in start..end) out.add(cpu)
                } else {
                    val cpu = part.toIntOrNull() ?: return null
                    if (cpu < 0) return null
                    out.add(cpu)
                }
            }
            return out.takeIf { it.isNotEmpty() }
        }

        private fun isContinuous(cpus: Set<Int>): Boolean {
            if (cpus.isEmpty()) return false
            val sorted = cpus.sorted()
            return sorted.last() - sorted.first() + 1 == sorted.size
        }

        private fun formatContinuousRange(cpus: Set<Int>): String? {
            if (!isContinuous(cpus)) return null
            val sorted = cpus.sorted()
            val start = sorted.first()
            val end = sorted.last()
            return if (start == end) start.toString() else "$start-$end"
        }

        private fun fmt(value: Double): String {
            val rounded = kotlin.math.round(value * 10.0) / 10.0
            return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        }

        private data class CoreDefaults(
            val best: String,
            val high: String,
            val mid: String,
            val fallback: String
        )

        private fun topologyDefaults(block: String): CoreDefaults {
            val map = mutableMapOf<String, String>()
            for (line in block.lineSequence()) {
                val trimmed = line.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                when (key) {
                    "detected_top", "detected_big" -> map["best"] = value
                    "detected_high", "detected_middle_high" -> map["high"] = value
                    "detected_main", "detected_middle" -> map["mid"] = value
                    "detected_non_top", "detected_nonbig" -> map["fallback"] = value
                }
            }

            val mid = normalizeCoresOrNull(map["mid"].orEmpty()) ?: DEFAULT_MID_CORES
            return CoreDefaults(
                best = normalizeCoresOrNull(map["best"].orEmpty()) ?: DEFAULT_BEST_CORES,
                high = normalizeCoresOrNull(map["high"].orEmpty()) ?: mid,
                mid = mid,
                fallback = normalizeCoresOrNull(map["fallback"].orEmpty()) ?: DEFAULT_FALLBACK_CORES
            )
        }
    }
}
