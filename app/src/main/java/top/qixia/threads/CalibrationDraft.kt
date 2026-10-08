package top.qixia.threads

data class CalibrationThread(
    val owner: String, val name: String, val average: Double, val peak: Double,
    val activity: Double, val suggested: Set<Int>, val reason: String,
    val generatedPattern: Boolean = false, val memberCount: Int = 1, val members: List<String> = listOf(name)
) {
    val editable: Boolean get() {
        if (name.isBlank() || name != name.trim() || name.any { it.isISOControl() || it in "{}=/\\" }) return false
        if (!generatedPattern) return name.none { it in "[]*?" }
        val literals = name.replace("[0-9]", "").replace("*", "").replace("?", "")
        return literals.none { it in "[]" } &&
            (name.none { it in "*?[" } || literals.count { it.isLetter() } >= 2)
    }
}

data class CalibrationDraft(
    val id: String, val packageName: String, val createdMs: Long, val durationMs: Long,
    val capacities: Map<Int, Long>, val threads: List<CalibrationThread>, val wire: String,
    val version: Int = 1, val omitted: Int = 0
) {
    val fileName get() = "$packageName.$id.draft"

    internal val stamp get() = CalibrationDraftStamp(createdMs, id)
    fun compareVersion(other: CalibrationDraft): Int = stamp.compareTo(other.stamp)

    companion object {
        fun parse(raw: String): CalibrationDraft? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val lines = raw.lineSequence().filter(String::isNotBlank).toList()
            require(lines.size in 4..194)
            val version = when (lines[0]) {
                "QIXIA_CALIBRATION_DRAFT\t1" -> 1
                "QIXIA_CALIBRATION_DRAFT\t2" -> 2
                else -> error("Unknown draft version")
            }
            val meta = lines[1].split('\t')
            require(meta.size == (if (version == 1) 5 else 6) && meta[0] == "meta")
            val omitted = if (version == 2) meta[5].toInt().also { require(it in 0..2048) } else 0
            val id = meta[1]; val pkg = meta[2]
            require(id.matches(Regex("[0-9]{1,18}-[0-9]{1,10}")))
            require(pkg.length <= 180 && pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
            val created = meta[3].toLong(); val duration = meta[4].toLong()
            require(created > 0 && duration in 29500L..21660000L)
            val cpus = linkedMapOf<Int, Long>()
            val rows = mutableListOf<CalibrationThread>()
            lines.drop(2).forEach { line ->
                val f = line.split('\t')
                when (f[0]) {
                    "cpu" -> {
                        require(f.size == 3)
                        val cpu = f[1].toInt(); val capacity = f[2].toLong()
                        require(cpu in 0..63 && capacity in 0..1_000_000 && cpus.put(cpu, capacity) == null)
                    }
                    "thread" -> {
                        require(f.size == (if (version == 1) 8 else 10) && rows.size < (if (version == 1) 24 else 128))
                        val owner = decode(f[1]); val name = decode(f[2])
                        require(owner == pkg || (owner.startsWith("$pkg:") &&
                            owner.substringAfter(':').matches(Regex("[A-Za-z0-9_.-]+"))))
                        require(name.isNotEmpty() && name.length <= 128)
                        val avg = f[3].toDouble(); val peak = f[4].toDouble(); val activity = f[5].toDouble()
                        require(avg.isFinite() && peak.isFinite() && activity.isFinite() &&
                            avg in 0.0..2_000_000.0 && peak in avg..2_000_000.0 && activity in 0.0..100.0)
                        val selected = if (f[6] == "-") emptySet() else
                            requireNotNull(RuleConfigLogic.parseCpuRangeList(f[6]))
                        require(rows.none { it.owner == owner && it.name == name })
                        val count = if (version == 2) f[8].toInt().also { require(it in 1..2048) } else 1
                        val members = if (version == 2) decode(f[9], 8192).split('\n').also { names ->
                            require(names.size in 1..minOf(count, 16) && names.distinct().size == names.size)
                            require(names.all { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) })
                        } else listOf(name)
                        rows += CalibrationThread(owner, name, avg, peak, activity, selected, decode(f[7]), version == 2, count, members)
                    }
                    else -> error("Unknown draft record")
                }
            }
            require(rows.isNotEmpty() && rows.all { cpus.keys.containsAll(it.suggested) && (it.editable || it.suggested.isEmpty()) })
            CalibrationDraft(id, pkg, created, duration, cpus, rows, raw, version, omitted)
        }.getOrNull()

        const val MAX_BYTES = 262144
        private fun decode(hex: String, limit: Int = 2048): String {
            require(hex.length <= limit && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null })
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
}

internal data class CalibrationDraftStamp(val createdMs: Long, val id: String) : Comparable<CalibrationDraftStamp> {
    override fun compareTo(other: CalibrationDraftStamp): Int = compareValuesBy(this, other,
        { it.createdMs }, { it.id.substringBefore('-').toLong() }, { it.id.substringAfter('-').toLong() })
}

data class CalibrationReview(
    val draft: CalibrationDraft,
    val selections: List<Set<Int>> = draft.threads.map { it.suggested },
    val original: List<String>? = null,
    val automatic: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val processSelections: Map<String, Set<Int>> = emptyMap()
) {
    val processOwners: Set<String> get() = setOf(draft.packageName) + draft.threads.map { it.owner }
    val ruleCount: Int get() = selections.count { it.isNotEmpty() } + processSelections.values.count { it.isNotEmpty() }
    // 只调整显示顺序，编辑索引和保存后的规则优先级保持原样。
    val threadDisplayOrder: List<Int> get() = draft.threads.indices.sortedWith(
        compareByDescending<Int> { selections[it].maxOrNull() ?: -1 }
            .thenByDescending { selections[it].minOrNull() ?: -1 }
    )
    fun rules(): List<String> {
        require(selections.size == draft.threads.size)
        require(processSelections.keys.all { it in processOwners })
        return draft.threads.mapIndexedNotNull { i, row ->
        selections[i].takeIf { it.isNotEmpty() }?.let {
            require(row.editable && draft.capacities.keys.containsAll(it))
            "${row.owner}{${row.name}}=${RuleConfigLogic.formatCpuRangeList(it)}"
        }
        } + processSelections.toSortedMap().mapNotNull { (owner, cpus) ->
            cpus.takeIf { it.isNotEmpty() }?.let {
                require(draft.capacities.keys.containsAll(it))
                "$owner=${RuleConfigLogic.formatCpuRangeList(it)}"
            }
        }
    }
}
