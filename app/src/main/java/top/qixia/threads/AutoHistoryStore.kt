package top.qixia.threads

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import top.qixia.threads.db.HistorySource
import top.qixia.threads.db.SessionSummary

/** 应用私有归档，独立于校准数据库及其导入认领状态。 */
internal object AutoHistoryStore {
    private val ROOT_DIRECTORY: String get() = PrivateCaptureStorage.automatic.absolutePath
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_IMPORTS = 24
    private const val META_VERSION = 6
    private const val MAX_MEMBERS = 400
    private const val MAX_REPORT_BYTES = 24L * 1024 * 1024
    private const val WINDOW_BYTES = 16L * 1024 * 1024
    data class ReportWindow(val index: Int, val startMs: Long, val endMs: Long, val memberFileNames: List<String>)
    private val cache = object : LinkedHashMap<String, AutoHistoryRecord.Record>(3, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AutoHistoryRecord.Record>?) = size > 2
    }
    private fun directory(filesDir: File) = File(filesDir, "auto_history")
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** 校验不可变的已完成文件后，原子移入私有归档目录。 */
    @Synchronized
    fun importCompleted(context: Context) {
        val folder = directory(context.filesDir)
        // 先压缩旧明文记录，避免按无损归档不再需要的空间占用将其清理。
        // 转换失败时保留源文件。
        val compacted = compactHistory(context.filesDir)
        pruneHistory(context.filesDir, enforceByteLimit = compacted)
        val listing = DaemonBridge.runRootCommand(
            "for f in $ROOT_DIRECTORY/auto_*.log $ROOT_DIRECTORY/auto_*.log.gz; do " +
                "[ -f \"\$f\" ] && [ ! -L \"\$f\" ] && basename \"\$f\"; done; true")
        if (!listing.success) return
        val sourceNames = listing.output.lineSequence().filter { AutoHistoryRecord.fileName.matches(it) }
            .distinct().toList().sortedByDescending { it.substringAfter("auto_").substringBefore('_').toLongOrNull() ?: 0L }
        val acknowledged = mutableListOf<String>()
        // 删除标记在 Root 失败或应用重启后仍保留，确认成功后才移除。
        val deleted = folder.listFiles { file -> file.name.endsWith(".log.deleted") }.orEmpty()
        deleted.forEach { marker ->
            val name = marker.name.removeSuffix(".deleted")
            val matches = sourceNames.filter { AutoHistoryIo.canonicalName(it) == name }
            if (matches.isNotEmpty()) acknowledged += matches
            else if (variants(name).none { File(folder, it).exists() }) marker.delete()
        }
        folder.listFiles { file -> file.name.endsWith(".invalid") }.orEmpty().forEach { marker ->
            if (marker.name.removeSuffix(".invalid") !in sourceNames) marker.delete()
        }
        val pendingNames = sourceNames.filter { name ->
            !isDeleted(folder, name) && !File(folder, "$name.invalid").exists() &&
                variants(name).none { File(folder, it).isFile && entry(File(folder, it)) != null }
        }.take(MAX_IMPORTS)
        // 长记录可能包含多个分片，一次 Root 调用统一准备访问权限，
        // 避免每个文件都启动 shell；仍逐文件检查类型、大小和执行结果。
        val prepared = if (pendingNames.isEmpty()) emptySet() else {
            val uid = context.applicationInfo.uid
            val paths = pendingNames.joinToString(" ") { quote(File(ROOT_DIRECTORY, it).absolutePath) }
            DaemonBridge.runRootCommand("for f in $paths; do " +
                "[ -f \"\$f\" ] && [ ! -L \"\$f\" ] && " +
                "[ \$(wc -c < \"\$f\") -le ${AutoHistoryIo.MAX_ARCHIVE_BYTES} ] && " +
                "chown $uid:$uid \"\$f\" && chmod 600 \"\$f\" && restorecon \"\$f\" && basename \"\$f\"; done; true", 20)
                .output.lineSequence().filter { it in pendingNames }.toSet()
        }
        sourceNames.forEach { name ->
            if (isDeleted(folder, name)) return@forEach
            if (File(folder, "$name.invalid").exists()) return@forEach
            val file = File(folder, name)
            if (variants(name).any { File(folder, it).isFile && entry(File(folder, it)) != null }) {
                acknowledged += name; return@forEach
            }
            if (name !in prepared) return@forEach
            val source = File(ROOT_DIRECTORY, name)
            try {
                val summary = AutoHistoryRecord.summary(source, name) ?: run {
                    // 完成的文件不会再变化，不必每次刷新都重新解析损坏记录。
                    check(folder.isDirectory || folder.mkdirs())
                    File(folder, "$name.invalid").writeText("Invalid automatic history v1\n")
                    return@forEach
                }
                if (captureDeleted(folder, summary.captureId) ||
                    summary.session.endedAtMs <= packageCutoff(folder, summary.pkg)) {
                    acknowledged += name
                    return@forEach
                }
                check(folder.isDirectory || folder.mkdirs())
                FileOutputStream(source, true).use { it.fd.sync() }
                check(source.renameTo(file)) { "无法归档自动分配记录" }
                syncDirectory(folder)
                syncDirectory(source.parentFile!!)
                writeEntry(file, summary)
                acknowledged += name
            } catch (_: Exception) {
                // 重命名前可从收件目录重试，重命名后以归档为准。
                // 元数据缓存可重建，保留采集记录不依赖它。
            }
        }
        if (acknowledge(acknowledged)) acknowledged.forEach { clearMarkerIfRemoved(folder, it) }
        val importedCompacted = compactHistory(context.filesDir)
        pruneHistory(context.filesDir, enforceByteLimit = importedCompacted)
        cleanupInbox()
    }

    private fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    /** 迁移只改变物理存储，不改变记录 ID 或采集内容。 */
    @Synchronized
    internal fun compactHistory(filesDir: File, sync: (File) -> Unit = ::syncDirectory): Boolean {
        val folder = directory(filesDir)
        var complete = true
        folder.listFiles { it.name.endsWith(".log") && AutoHistoryRecord.fileName.matches(it.name) }
            .orEmpty().forEach { source ->
                if (isDeleted(folder, source.name)) return@forEach
                try {
                    val original = entry(source) ?: return@forEach
                    AutoHistoryIo.compress(source) { compressed ->
                        val migrated = original.copy(memberFileNames = listOf(compressed.name))
                        writeEntry(compressed, migrated)
                        sync(folder)
                    }
                    File(folder, "${source.name}.meta").delete()
                    cache.clear()
                    sync(folder)
                } catch (_: Exception) { complete = false }
            }
        return complete
    }

    private fun variants(name: String): List<String> = AutoHistoryIo.canonicalName(name).let { listOf(it, "$it.gz") }
    private fun isDeleted(folder: File, name: String): Boolean =
        File(folder, "${AutoHistoryIo.canonicalName(name)}.deleted").exists()

    private fun cleanupInbox() {
        // 仅执行 rmdir；存在临时文件、未处理记录或并发写入时保留目录。
        DaemonBridge.runRootCommand("rmdir ${quote(ROOT_DIRECTORY)} 2>/dev/null || true")
    }

    @Synchronized
    fun entries(filesDir: File): List<AutoHistoryRecord.Entry> = readEntries(filesDir, cachedOnly = false)

    /** 首页不等待导入或导出锁，也不展开记录。摘要缺失、过期或被原子替换时，
     * 后台导入完成后再重试。 */
    fun cachedEntriesForHome(filesDir: File): List<AutoHistoryRecord.Entry> = readEntries(filesDir, cachedOnly = true)

    private fun readEntries(filesDir: File, cachedOnly: Boolean): List<AutoHistoryRecord.Entry> {
        val folder = directory(filesDir)
        return folder.listFiles { file -> AutoHistoryRecord.fileName.matches(file.name) &&
            !isDeleted(folder, file.name) }.orEmpty()
            .groupBy { AutoHistoryIo.canonicalName(it.name) }.values.mapNotNull { alternatives ->
                // 迁移未完成时，以保留的原文件为准。
                alternatives.sortedBy { it.name.endsWith(".gz") }.firstNotNullOfOrNull { entry(it, cachedOnly) }
            }
            .filter { !captureDeleted(folder, it.captureId) && it.session.endedAtMs > packageCutoff(folder, it.pkg) }
            .groupBy { canonicalName(it) }.mapNotNull { (name, members) -> groupedEntry(folder, name, members, cachedOnly) }
            .sortedWith(compareByDescending<AutoHistoryRecord.Entry> { it.session.endedAtMs }.thenByDescending { it.fileName })
    }

    @Synchronized
    fun reportWindows(filesDir: File, sessionId: Long): List<ReportWindow> {
        val selected = entries(filesDir).firstOrNull { it.session.id == sessionId } ?: return emptyList()
        return windows(directory(filesDir), selected)
    }

    private fun windows(folder: File, selected: AutoHistoryRecord.Entry): List<ReportWindow> {
        if (selected.memberFileNames.size !in 1..MAX_MEMBERS) return emptyList()
        val members = selected.memberFileNames.map { entry(File(folder, it)) ?: return emptyList() }
        return reportWindowsForMembers(members)
    }

    /** 仅根据缓存元数据划分查看区间，不为此展开归档。 */
    internal fun reportWindowsForMembers(segments: List<AutoHistoryRecord.Entry>): List<ReportWindow> {
        if (segments.size !in 1..MAX_MEMBERS) return emptyList()
        val members = segments
            .sortedBy { it.session.startedAtMs }
        val groups = mutableListOf<List<AutoHistoryRecord.Entry>>()
        if (members.sumOf { it.expandedBytes } <= MAX_REPORT_BYTES &&
            members.sumOf { it.identityCount.toLong() } <= AutoHistoryRecord.MAX_THREADS) groups += members
        else {
            var current = mutableListOf<AutoHistoryRecord.Entry>()
            var bytes = 0L
            var identities = 0L
            for (member in members) {
                if (current.isNotEmpty() && (bytes + member.expandedBytes > WINDOW_BYTES ||
                    identities + member.identityCount > AutoHistoryRecord.MAX_THREADS)) {
                    groups += current; current = mutableListOf(); bytes = 0L; identities = 0L
                }
                current += member; bytes += member.expandedBytes; identities += member.identityCount
            }
            if (current.isNotEmpty()) groups += current
        }
        return groups.mapIndexed { index, group -> ReportWindow(index,
            group.minOf { it.session.startedAtMs }, group.maxOf { it.session.endedAtMs },
            group.flatMap { it.memberFileNames }) }
    }

    @Synchronized
    fun record(filesDir: File, sessionId: Long, windowIndex: Int = 0): AutoHistoryRecord.Record? {
        if (sessionId >= 0) return null
        val folder = directory(filesDir)
        val selected = entries(filesDir).firstOrNull { it.session.id == sessionId } ?: return null
        val windows = windows(folder, selected)
        val window = windows.getOrNull(windowIndex) ?: return null
        val files = window.memberFileNames.map { File(folder, it) }
        val key = recordCacheKey(folder, selected) + "::window=$windowIndex"
        val record = cache[key] ?: AutoHistoryRecord.read(files, selected.fileName, windowed = windows.size > 1)
            ?.also { cache[key] = it }
        return record?.takeIf { it.entry.session.endedAtMs > packageCutoff(folder, it.entry.pkg) }
    }

    @Synchronized
    fun deleteSession(filesDir: File, sessionId: Long): Boolean {
        return sessionId in deleteSessions(filesDir, listOf(sessionId))
    }

    /** 持久删除标记支持无 Root 删除，并防止后续导入恢复已删除记录。 */
    @Synchronized
    fun deleteSessions(
        filesDir: File,
        sessionIds: Collection<Long>,
        acknowledgeDeleted: (List<String>) -> Boolean = ::acknowledge
    ): Set<Long> {
        val ids = sessionIds.toSet()
        val selected = entries(filesDir).filter { it.session.id in ids }
        val folder = directory(filesDir)
        val deleted = selected.filter { markEntryDeleted(folder, it) }
        deleted.forEach { removeEntryLocal(folder, it) }
        val names = deleted.flatMap { it.memberFileNames }
        if (acknowledgeDeleted(names)) names.forEach { clearMarkerIfRemoved(folder, it) }
        return deleted.mapTo(mutableSetOf()) { it.session.id }
    }

    @Synchronized
    fun deletePackage(filesDir: File, pkg: String): Boolean {
        val folder = directory(filesDir)
        val selected = entries(filesDir).filter { it.pkg == pkg }
        if (!markPackageDeleted(folder, pkg)) return false
        // 先提交全部删除标记，再移除归档，确保重试结果一致。
        if (selected.any { !markEntryDeleted(folder, it) }) return false
        selected.forEach { removeEntryLocal(folder, it) }
        val names = selected.flatMap { it.memberFileNames }
        if (acknowledge(names)) names.forEach { clearMarkerIfRemoved(folder, it) }
        return true
    }

    private fun entry(file: File, cachedOnly: Boolean = false): AutoHistoryRecord.Entry? {
        val metadata = File(file.parentFile, "${file.name}.meta")
        val saved = runCatching { DataInputStream(metadata.inputStream().buffered()).use { input ->
            require(file.isFile && metadata.length() <= 128 * 1024L)
            require(input.readInt() == META_VERSION && input.readLong() == file.length() && input.readLong() == file.lastModified())
            readEntry(input, AutoHistoryIo.canonicalName(file.name)).also {
                require(it.memberFileNames == listOf(file.name) && it.expandedBytes in 1..AutoHistoryRecord.MAX_FILE_BYTES)
            }
        } }.getOrNull()
        if (saved != null) return saved
        if (cachedOnly) return null
        val summary = AutoHistoryRecord.summary(file) ?: return null
        runCatching { writeEntry(file, summary) }
        return summary
    }

    private fun writeEntry(file: File, entry: AutoHistoryRecord.Entry) {
        val pending = File(file.parentFile, "${file.name}.meta.tmp")
        try {
            FileOutputStream(pending).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(META_VERSION); output.writeLong(file.length()); output.writeLong(file.lastModified()); writeEntry(output, entry)
                output.flush(); stream.fd.sync()
            }
            publishMetadata(pending, File(file.parentFile, "${file.name}.meta"))
        } finally { pending.delete() }
    }

    /** 版本升级需要替换已有缓存，但部分文件系统的 renameTo 不允许覆盖。
     * 新缓存移动成功之前，保留旧的完整缓存。 */
    private fun publishMetadata(pending: File, destination: File) {
        try {
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun canonicalName(entry: AutoHistoryRecord.Entry): String = entry.captureId
        ?.let { "$it.log" }?.takeIf(AutoHistoryRecord.fileName::matches) ?: entry.fileName

    private fun memberStamp(folder: File, names: List<String>): String = names.sorted().joinToString("|") { name ->
        val file = File(folder, name)
        "$name:${file.length()}:${file.lastModified()}"
    }

    private fun recordCacheKey(folder: File, entry: AutoHistoryRecord.Entry): String =
        "${folder.absolutePath}/${entry.fileName}::${memberStamp(folder, entry.memberFileNames)}"

    /** 分片摘要开销较小，只有成员变化时才重建整次记录统计。 */
    private fun groupedEntry(folder: File, name: String, members: List<AutoHistoryRecord.Entry>,
        cachedOnly: Boolean = false): AutoHistoryRecord.Entry? {
        if (members.map { it.pkg }.distinct().size != 1) return null
        val names = members.flatMap { it.memberFileNames }.distinct().sorted()
        if (names.size !in 1..MAX_MEMBERS) return null
        val metadata = File(folder, "group_$name.meta")
        // 成员缓存过期或缺失时，不得把已知的多分片记录
        // 误显示为完整的单分片首页报告。
        if (members.size == 1 && (!cachedOnly || !metadata.isFile)) return members.single().copy(fileName = name,
            session = members.single().session.copy(id = AutoHistoryRecord.sessionId(name)), memberFileNames = names)
        val stamp = memberStamp(folder, names)
        val saved = runCatching { DataInputStream(metadata.inputStream().buffered()).use { input ->
            require(metadata.length() <= 128 * 1024L)
            require(input.readInt() == META_VERSION && input.readUTF() == stamp)
            readEntry(input, name).also { require(it.memberFileNames == names) }
        } }.getOrNull()
        if (saved != null) return saved
        if (cachedOnly) return null
        val summary = AutoHistoryRecord.summary(names.map { File(folder, it) }, name) ?: return null
        val combined = summary.copy(fileName = name, memberFileNames = names)
        val pending = File(folder, "group_$name.meta.tmp")
        runCatching {
            FileOutputStream(pending).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(META_VERSION); output.writeUTF(stamp); writeEntry(output, combined)
                output.flush(); stream.fd.sync()
            }
            publishMetadata(pending, metadata)
        }
        pending.delete()
        return combined
    }

    private fun readEntry(input: DataInputStream, name: String): AutoHistoryRecord.Entry {
        val pkg = input.readUTF()
        val session = SessionSummary(AutoHistoryRecord.sessionId(name), input.readLong(), input.readInt(),
            input.readInt(), HistorySource.AUTO_ALLOCATION, input.readLong(), input.readLong(), input.readLong())
        val fps = if (!input.readBoolean()) null else {
            val average = input.readFloat(); val minimum = input.readFloat(); val maximum = input.readFloat()
            val samples = input.readInt()
            val low = input.readFloat().takeIf { it.isFinite() }; val jitter = input.readFloat().takeIf { it.isFinite() }
            require(samples > 0 && listOf(average, minimum, maximum).all { it.isFinite() && it in 0f..1000f })
            val count = input.readInt().also { require(it in 0..FpsPreview.MAX_POINTS) }
            var previous = Long.MIN_VALUE
            val points = List(count) {
                val time = input.readLong().also { require(it in session.startedAtMs..session.endedAtMs && it > previous); previous = it }
                val value = input.readFloat().also { require(it.isFinite() && it in 0f..1000f) }
                HistoryFpsStore.Point(((time - session.startedAtMs).toDouble() / session.durationMs.coerceAtLeast(1)).toFloat(),
                    value, input.readBoolean(), time)
            }
            HistoryFpsStore.Report(average, minimum, maximum, samples, points, low, jitter)
        }
        val captureId = input.readUTF().takeIf { it.isNotEmpty() }
        require(captureId == null || AutoHistoryRecord.fileName.matches("$captureId.log"))
        val foregroundMs = input.readLong().takeIf { it >= 0 }
        val count = input.readInt().also { require(it in 1..MAX_MEMBERS) }
        val members = List(count) { input.readUTF().also { require(AutoHistoryRecord.fileName.matches(it)) } }
        val expanded = input.readLong().also { require(it in 1..(AutoHistoryRecord.MAX_FILE_BYTES * MAX_MEMBERS)) }
        val identities = input.readInt().also { require(it in session.threadCount..(AutoHistoryRecord.MAX_THREADS * count)) }
        return AutoHistoryRecord.Entry(AutoHistoryIo.canonicalName(name), pkg, session, fps, captureId, foregroundMs, members, expanded, identities)
    }

    private fun writeEntry(output: DataOutputStream, entry: AutoHistoryRecord.Entry) {
        output.writeUTF(entry.pkg)
        output.writeLong(entry.session.epoch); output.writeInt(entry.session.rounds)
        output.writeInt(entry.session.threadCount); output.writeLong(entry.session.startedAtMs)
        output.writeLong(entry.session.recordedDurationMs); output.writeLong(entry.session.recordedEndedAtMs)
        output.writeBoolean(entry.fps != null)
        entry.fps?.let { fps ->
            output.writeFloat(fps.average); output.writeFloat(fps.minimum); output.writeFloat(fps.maximum)
            output.writeInt(fps.samples); output.writeFloat(fps.low5 ?: Float.NaN)
            output.writeFloat(fps.jitter ?: Float.NaN)
            require(fps.points.size <= FpsPreview.MAX_POINTS)
            output.writeInt(fps.points.size)
            fps.points.forEach { point -> output.writeLong(checkNotNull(point.timestampMs)); output.writeFloat(point.fps); output.writeBoolean(point.breakBefore) }
        }
        output.writeUTF(entry.captureId.orEmpty()); output.writeLong(entry.foregroundMs ?: -1)
        output.writeInt(entry.memberFileNames.size); entry.memberFileNames.forEach(output::writeUTF)
        output.writeLong(entry.expandedBytes)
        output.writeInt(entry.identityCount)
    }

    private fun captureMarker(folder: File, captureId: String): File {
        require(AutoHistoryRecord.fileName.matches("$captureId.log"))
        return File(folder, "capture_$captureId.deleted")
    }
    private fun captureDeleted(folder: File, captureId: String?): Boolean =
        captureId != null && captureMarker(folder, captureId).exists()

    private fun markEntryDeleted(folder: File, entry: AutoHistoryRecord.Entry): Boolean {
        // 可见记录删除后，仍可能收到迟到的已完成分片。
        // 即使全部 Root 文件已确认，也保留整次记录的删除标记。
        if (entry.captureId != null && !runCatching {
            val marker = captureMarker(folder, entry.captureId)
            if (!marker.exists()) FileOutputStream(marker).use { it.write(1); it.fd.sync() }
            true
        }.getOrDefault(false)) return false
        return entry.memberFileNames.all { markDeleted(folder, it) }
    }

    private fun removeEntryLocal(folder: File, entry: AutoHistoryRecord.Entry) {
        cache.clear()
        entry.memberFileNames.forEach { removeLocal(folder, it) }
        File(folder, "group_${entry.fileName}.meta").delete()
    }

    private fun markDeleted(folder: File, name: String): Boolean = runCatching {
        val marker = File(folder, "${AutoHistoryIo.canonicalName(name)}.deleted")
        if (!marker.exists()) FileOutputStream(marker).use { it.write(1); it.fd.sync() }
        true
    }.getOrDefault(false)

    private fun removeLocal(folder: File, name: String) {
        variants(name).forEach { variant ->
            val file = File(folder, variant)
            file.delete()
            File(folder, "$variant.meta").delete()
        }
    }

    private fun clearMarkerIfRemoved(folder: File, name: String) {
        if (variants(name).none { File(folder, it).exists() }) File(folder, "${AutoHistoryIo.canonicalName(name)}.deleted").delete()
    }

    private fun packageMarker(folder: File, pkg: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(pkg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(folder, "package_$key.deleted")
    }

    private fun packageCutoff(folder: File, pkg: String): Long = runCatching {
        packageMarker(folder, pkg).readText().trim().toLong()
    }.getOrDefault(0L)

    private fun markPackageDeleted(folder: File, pkg: String): Boolean = runCatching {
        check(folder.isDirectory || folder.mkdirs())
        val marker = packageMarker(folder, pkg)
        val pending = File(folder, "${marker.name}.tmp")
        try {
            FileOutputStream(pending).use { output ->
                output.write(maxOf(System.currentTimeMillis(), packageCutoff(folder, pkg)).toString().toByteArray())
                output.fd.sync()
            }
            check(pending.renameTo(marker))
        } finally { pending.delete() }
        true
    }.getOrDefault(false)

    private fun acknowledge(names: List<String>): Boolean {
        if (names.isEmpty()) return true
        if (names.any { !AutoHistoryRecord.fileName.matches(it) }) return false
        return runCatching { DaemonBridge.runRootCommand("rm -f " + names.flatMap(::variants).distinct()
            .joinToString(" ") { quote("$ROOT_DIRECTORY/$it") } +
            " && { rmdir ${quote(ROOT_DIRECTORY)} 2>/dev/null || true; }").success }.getOrDefault(false)
    }

    @Synchronized
    fun pruneHistory(filesDir: File, enforceByteLimit: Boolean = true,
        acknowledgeDeleted: (List<String>) -> Boolean = ::acknowledge) {
        val folder = directory(filesDir)
        val all = entries(filesDir)
        var bytes = 0L
        var count = 0
        val deleted = mutableListOf<String>()
        for (entry in all) {
            val size = entry.memberFileNames.sumOf { File(folder, it).length() }
            if (count >= HistoryRetention.AUTOMATIC_LIMIT || enforceByteLimit && bytes + size > MAX_TOTAL_BYTES) {
                if (markEntryDeleted(folder, entry)) { removeEntryLocal(folder, entry); deleted += entry.memberFileNames }
            } else { count++; bytes += size }
        }
        if (acknowledgeDeleted(deleted)) deleted.forEach { clearMarkerIfRemoved(folder, it) }
    }
}
