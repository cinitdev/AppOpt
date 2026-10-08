package top.qixia.threads

/** 精确记录线程身份，整次记录摘要无需为每个线程保存字符串、对象和曲线数组。
 * 各物理分片分别限制容量，单次记录最多 400 个分片。
 * 按每片 4096 个原生线程身份计算，最坏占用 32 MiB，另加 2 MiB 标志位；
 * 常见的 10000 个身份约占 272 KiB，不丢弃任何身份。
 */
internal class AutoHistoryIdentitySet(private val maximum: Int) {
    private var processes = LongArray(16)
    private var starts = LongArray(16)
    private var active = BooleanArray(16)
    var size = 0
        private set
    var activeSize = 0
        private set

    fun add(pid: Int, tid: Int, ticks: Long, isActive: Boolean): Boolean {
        require(pid > 0 && tid > 0 && ticks >= 0)
        val process = (pid.toLong() shl 32) or tid.toLong()
        var slot = find(process, ticks)
        if (processes[slot] != 0L) {
            if (isActive && !active[slot]) { active[slot] = true; activeSize++ }
            return false
        }
        require(size < maximum)
        if ((size + 1) * 5L > processes.size * 4L) { grow(); slot = find(process, ticks) }
        processes[slot] = process
        starts[slot] = ticks
        active[slot] = isActive
        size++
        if (isActive) activeSize++
        return true
    }

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
        processes = LongArray(oldProcesses.size * 2)
        starts = LongArray(processes.size)
        active = BooleanArray(processes.size)
        for (index in oldProcesses.indices) if (oldProcesses[index] != 0L) {
            val slot = find(oldProcesses[index], oldStarts[index])
            processes[slot] = oldProcesses[index]
            starts[slot] = oldStarts[index]
            active[slot] = oldActive[index]
        }
    }
}
