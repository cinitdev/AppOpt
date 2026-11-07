package top.qixia.threads

/** 用基本类型数组精确记录线程身份和活跃证据，避免为每个身份分配对象。
 * 容量由调用方按每段线程数和分片数限制。
 *
 * 除身份集合外，这里还累积每个身份的活跃证据（被采样次数、有负载次数、
 * 首次有负载的时刻及是否达到 30 秒门槛），用于统计报告里的线程数，
 * 不再把运行期间只活跃几下的线程算进去。
 */
internal class AutoHistoryIdentitySet(private val maximum: Int) {
    private var processes = LongArray(16)
    private var starts = LongArray(16)
    private var active = BooleanArray(16)
    private var observed = IntArray(16)
    private var positive = IntArray(16)
    private var firstPositiveSecond = IntArray(16)
    private var lastPositiveSecond = IntArray(16)
    var size = 0
        private set
    var activeSize = 0
        private set

    fun add(pid: Int, tid: Int, ticks: Long, isActive: Boolean, atSecond: Long): Boolean {
        require(pid > 0 && tid > 0 && ticks >= 0)
        val process = (pid.toLong() shl 32) or tid.toLong()
        var slot = find(process, ticks)
        if (processes[slot] != 0L) {
            observe(slot, isActive, atSecond)
            return false
        }
        require(size < maximum)
        if ((size + 1) * 5L > processes.size * 4L) { grow(); slot = find(process, ticks) }
        processes[slot] = process
        starts[slot] = ticks
        active[slot] = false
        size++
        observe(slot, isActive, atSecond)
        return true
    }

    private fun observe(slot: Int, isActive: Boolean, atSecond: Long) {
        observed[slot] = increment(observed[slot])
        if (!isActive) return
        if (!active[slot]) {
            active[slot] = true
            activeSize++
        }
        positive[slot] = increment(positive[slot])
        val second = atSecond.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (positive[slot] == 1) {
            firstPositiveSecond[slot] = second
            lastPositiveSecond[slot] = second
        } else {
            firstPositiveSecond[slot] = minOf(firstPositiveSecond[slot], second)
            lastPositiveSecond[slot] = maxOf(lastPositiveSecond[slot], second)
        }
    }

    /** 持续活跃口径：达到采样数、活跃占比和固定存在期门槛。 */
    fun sustained(policy: SustainedThreadPolicy = SustainedThreadPolicy()): Int {
        if (size == 0) return 0
        val requiredSpan = policy.minSpanMs.coerceAtLeast(0L) / 1000L
        var count = 0
        for (slot in processes.indices) {
            if (processes[slot] == 0L) continue
            val samples = observed[slot]
            val activeSamples = positive[slot]
            if (samples == 0 || activeSamples < policy.minSamples) continue
            if (activeSamples.toLong() * 100L < policy.minPercent.toLong() * samples.toLong()) continue
            val span = lastPositiveSecond[slot].toLong() - firstPositiveSecond[slot].toLong()
            if (span < requiredSpan) continue
            count++
        }
        return count
    }

    private fun increment(value: Int) = if (value == Int.MAX_VALUE) value else value + 1

    private fun find(process: Long, ticks: Long): Int {
        var hash = process xor java.lang.Long.rotateLeft(ticks, 23)
        hash = (hash xor (hash ushr 33)) * -49064778989728563L
        hash = (hash xor (hash ushr 33)) * -4265267296055464877L
        var slot = (hash xor (hash ushr 33)).toInt() and (processes.size - 1)
        while (processes[slot] != 0L && (processes[slot] != process || starts[slot] != ticks)) {
            slot = (slot + 1) and (processes.size - 1)
        }
        return slot
    }

    private fun grow() {
        val oldProcesses = processes
        val oldStarts = starts
        val oldActive = active
        val oldObserved = observed
        val oldPositive = positive
        val oldFirst = firstPositiveSecond
        val oldLast = lastPositiveSecond
        processes = LongArray(oldProcesses.size * 2)
        starts = LongArray(processes.size)
        active = BooleanArray(processes.size)
        observed = IntArray(processes.size)
        positive = IntArray(processes.size)
        firstPositiveSecond = IntArray(processes.size)
        lastPositiveSecond = IntArray(processes.size)
        for (index in oldProcesses.indices) if (oldProcesses[index] != 0L) {
            val slot = find(oldProcesses[index], oldStarts[index])
            processes[slot] = oldProcesses[index]
            starts[slot] = oldStarts[index]
            active[slot] = oldActive[index]
            observed[slot] = oldObserved[index]
            positive[slot] = oldPositive[index]
            firstPositiveSecond[slot] = oldFirst[index]
            lastPositiveSecond[slot] = oldLast[index]
        }
    }

}
