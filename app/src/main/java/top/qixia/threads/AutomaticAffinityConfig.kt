package top.qixia.threads

/** 自动模式只保存开关；兜底核心复用主进程规则，避免两份范围互相覆盖。 */
internal object AutomaticAffinityConfig {
    fun modes(raw: String): Map<String, Boolean> = buildMap {
        raw.lineSequence().forEach { line ->
            val parts = line.substringBefore('#').trim().split(',').map(String::trim)
            val pkg = parts.first()
            if (pkg.length >= 128 || '.' !in pkg || pkg.split('.').any { part ->
                    part.isEmpty() || part.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' }
                }) return@forEach
            if (parts.size != 1 && parts != listOf(pkg, "fallback")) return@forEach
            put(pkg, get(pkg) == true || parts.size == 2)
        }
    }

    fun fallbackCpus(rules: List<RuleSyntax.Rule>, pkg: String, present: Set<Int>?): String? =
        rules.asSequence().filter { it.owner == pkg && it.thread == null && !it.cpus.equals("auto", true) }
            .mapNotNull { RuleConfigLogic.parseNativeCpuRangeList(it.cpus) }
            .map { if (present != null) it.intersect(present) else it }
            .filter { it.isNotEmpty() && it.all { cpu -> cpu < 64 } }
            // 与 Rust 重复规则的取舍一致：核心数、最高编号、最低编号。
            .maxWithOrNull(compareBy<Set<Int>> { it.size }.thenBy { it.max() }.thenBy { -it.min() })
            ?.let(RuleConfigLogic::formatCpuRangeList)
}
