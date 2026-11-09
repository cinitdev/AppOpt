package top.qixia.threads

/** 每应用的独立开关；兜底核心仍复用主进程规则，性能记录不隐式开启自动分配。 */
internal object AutomaticAffinityConfig {
    data class Options(val automatic: Boolean, val fallback: Boolean, val recording: Boolean,
        val custom: List<AutomaticCustomRule> = emptyList())

    fun entries(raw: String): Map<String, Options> = buildMap {
        raw.lineSequence().forEach { line ->
            val parts = line.substringBefore('#').trim().split(',').map(String::trim)
            val pkg = parts.first()
            if (pkg.length >= 128 || '.' !in pkg || pkg.split('.').any { part ->
                    part.isEmpty() || part.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' }
                }) return@forEach
            val flags = parts.drop(1)
            val customFlags = flags.filter { it.startsWith("custom=") }
            if (customFlags.size > 1 || flags.any { it !in setOf("auto", "fallback", "record") && !it.startsWith("custom=") }) return@forEach
            val custom = customFlags.firstOrNull()?.let { AutomaticCustomRules.decode(it.removePrefix("custom=")) ?: return@forEach }
            val previous = get(pkg)
            put(pkg, Options(
                automatic = previous?.automatic == true || flags.isEmpty() || "auto" in flags || "fallback" in flags || custom != null,
                fallback = previous?.fallback == true || "fallback" in flags,
                recording = previous?.recording == true || "record" in flags,
                custom = custom ?: previous?.custom.orEmpty()
            ))
        }
    }

    fun modes(raw: String): Map<String, Boolean> = entries(raw).filterValues { it.automatic }.mapValues { it.value.fallback }

    fun recordingPackages(raw: String): Set<String> = entries(raw).filterValues { it.recording }.keys

    fun fallbackCpus(rules: List<RuleSyntax.Rule>, pkg: String, present: Set<Int>?): String? =
        rules.asSequence().filter { it.owner == pkg && it.thread == null && !it.cpus.equals("auto", true) }
            .mapNotNull { RuleConfigLogic.parseNativeCpuRangeList(it.cpus) }
            .map { if (present != null) it.intersect(present) else it }
            .filter { it.isNotEmpty() && it.all { cpu -> cpu < 64 } }
            // 与 Rust 重复规则的取舍一致：核心数、最高编号、最低编号。
            .maxWithOrNull(compareBy<Set<Int>> { it.size }.thenBy { it.max() }.thenBy { -it.min() })
            ?.let(RuleConfigLogic::formatCpuRangeList)
}
