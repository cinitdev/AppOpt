package top.suto.appopt

internal object RuleConfigLogic {
    const val MAX_CPU_INDEX = 1023
    const val MAX_OWNER_BYTES = 127
    const val MAX_THREAD_BYTES = 31

    private const val MAX_CPU_TEXT_LENGTH = 8192

    data class CpuBounds(val first: Int, val last: Int) {
        val size: Int
            get() = last - first + 1
    }

    fun parseSingleCpuRange(value: String): CpuBounds? {
        val token = value.trim()
        if (token.isEmpty() || token.length > MAX_CPU_TEXT_LENGTH || token.contains(',')) return null

        val dash = token.indexOf('-')
        if (dash < 0) {
            val cpu = parseCpuToken(token) ?: return null
            return CpuBounds(cpu, cpu)
        }
        if (token.indexOf('-', dash + 1) >= 0) return null

        val first = parseCpuToken(token.substring(0, dash)) ?: return null
        val last = parseCpuToken(token.substring(dash + 1)) ?: return null
        if (first >= last) return null
        return CpuBounds(first, last)
    }

    fun parseCpuBounds(value: String): CpuBounds? {
        val cpus = parseCpuRangeList(value) ?: return null
        if (cpus.isEmpty()) return null
        return CpuBounds(cpus.min(), cpus.max())
    }

    fun parseCpuRangeList(value: String): Set<Int>? {
        val text = value.trim()
        if (text.isEmpty()) return emptySet()
        if (text.length > MAX_CPU_TEXT_LENGTH) return null

        val cpus = linkedSetOf<Int>()
        var expandedCpuCount = 0
        for (rawPart in text.split(',')) {
            val bounds = parseSingleCpuRange(rawPart) ?: return null
            expandedCpuCount += bounds.size
            if (expandedCpuCount > MAX_CPU_INDEX + 1) return null
            for (cpu in bounds.first..bounds.last) cpus.add(cpu)
        }
        return cpus
    }

    fun cpuBoundsFromRuleLine(line: String): CpuBounds? {
        val separator = line.indexOf('=')
        if (separator < 0) return null
        return parseCpuBounds(line.substring(separator + 1))
    }

    fun ownerFitsNativeBuffer(owner: String): Boolean {
        return owner.isNotEmpty() && owner.utf8Size() <= MAX_OWNER_BYTES
    }

    fun threadFitsNativeBuffer(thread: String): Boolean {
        return thread.isNotEmpty() && thread.utf8Size() <= MAX_THREAD_BYTES
    }

    private fun parseCpuToken(text: String): Int? {
        if (text.isEmpty() || (text.length > 1 && text.startsWith('0'))) return null
        if (!text.all { it in '0'..'9' }) return null
        return text.toIntOrNull()?.takeIf { it in 0..MAX_CPU_INDEX }
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}

internal class RequestGeneration {
    private var generation = 0L

    fun next(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    fun current(): Long = generation

    fun isCurrent(candidate: Long): Boolean = candidate == generation
}
