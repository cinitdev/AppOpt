package top.qixia.threads

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * 将目标应用运行期间的 FPS 以固定周期写入 App 私有目录，并只发布上一轮已结束会话的平均值。
 *
 * 当前会话写入 `<hash>.current.csv`；正常结束后保存为 `<hash>.last.csv`，同时原子更新
 * `<hash>.properties`。开始下一轮会话不会清除上一轮平均值，只有新一轮产生有效样本并结束后
 * 才会替换它。进程异常退出后，下一次启动同一应用时也会先从 current 文件恢复上轮结果。
 */
internal class FpsSessionRecorder(
    private val filesDir: File,
    private val retainedCalibrations: () -> Map<String, List<Pair<Long, Long>>> = { emptyMap() },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    data class AverageRecord(
        val packageName: String,
        val averageFps: Float,
        val sampleCount: Long,
        val startedAtMs: Long,
        val endedAtMs: Long
    ) {
        val durationMs: Long = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    }

    private data class ActiveSession(
        val packageName: String,
        val collecting: Boolean = false,
        val startedAtMs: Long = 0L,
        val sampleFile: File? = null,
        val fpsSum: Double = 0.0,
        val sampleCount: Long = 0L,
        val lastSampleAtMs: Long = 0L,
        val calibration: Boolean = false
    )

    private data class ParsedSamples(
        val packageName: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val fpsSum: Double,
        val sampleCount: Long,
        val calibration: Boolean
    )

    private var activeSession: ActiveSession? = null

    @Synchronized
    fun begin(targetPackage: String) {
        val target = normalizePackageName(targetPackage)
        if (target.isEmpty()) return
        val current = activeSession
        if (current?.packageName == target) return
        if (current != null) {
            finishActive(current, nowMillis())
            activeSession = null
        }
        recoverInterruptedSession(target)
        activeSession = ActiveSession(packageName = target)
    }

    /** 标记目标应用已经真实进入前台；在此之前收到的启动阶段 FPS 不计入平均值。 */
    @Synchronized
    fun markRunning(targetPackage: String): Boolean {
        val target = normalizePackageName(targetPackage)
        val current = activeSession ?: return false
        if (current.packageName != target) return false
        if (current.collecting) return true

        val startedAt = nowMillis()
        val sampleFile = currentSampleFile(filesDir, target)
        if (!writeSampleHeader(sampleFile, target, startedAt, current.calibration)) return false
        activeSession = current.copy(
            collecting = true,
            startedAtMs = startedAt,
            sampleFile = sampleFile
        )
        return true
    }

    /** 即使进程在完成记录导入前退出，此标记仍可持久保存。 */
    @Synchronized
    fun markCalibration(targetPackage: String) {
        val current = activeSession ?: return
        if (current.packageName != normalizePackageName(targetPackage) || current.calibration) return
        runCatching { current.sampleFile?.appendText("# calibration=1\n") }
        activeSession = current.copy(calibration = true)
    }

    /** 记录一次本地固定周期采样。只有已确认进入前台的当前目标才会被接收。 */
    @Synchronized
    fun record(targetPackage: String, fps: Float, sampledAtMs: Long = nowMillis()): Boolean {
        val target = normalizePackageName(targetPackage)
        val current = activeSession ?: return false
        val sampleFile = current.sampleFile ?: return false
        if (!current.collecting || current.packageName != target || !fps.isFinite() || fps < 0f) {
            return false
        }

        val normalizedFps = fps.coerceAtMost(MAX_REASONABLE_FPS)
        val line = String.format(Locale.US, "%d,%.3f\n", sampledAtMs, normalizedFps)
        val persisted = runCatching {
            FileOutputStream(sampleFile, true).use { output ->
                output.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        }.isSuccess
        if (!persisted) return false

        activeSession = current.copy(
            fpsSum = current.fpsSum + normalizedFps.toDouble(),
            sampleCount = current.sampleCount + 1L,
            lastSampleAtMs = sampledAtMs
        )
        return true
    }

    /** 完成本轮采样。没有有效样本时保留旧平均值，不写入空结果。 */
    @Synchronized
    fun finish(targetPackage: String, endedAtMs: Long = nowMillis()): AverageRecord? {
        val target = normalizePackageName(targetPackage)
        val current = activeSession ?: return null
        if (current.packageName != target) return null
        activeSession = null
        return finishActive(current, endedAtMs)
    }

    private fun finishActive(session: ActiveSession, requestedEndAtMs: Long): AverageRecord? {
        val sampleFile = session.sampleFile
        if (!session.collecting || session.sampleCount <= 0L || sampleFile == null) {
            runCatching { sampleFile?.delete() }
            return null
        }
        val endedAt = maxOf(requestedEndAtMs, session.startedAtMs, session.lastSampleAtMs)
        val record = AverageRecord(
            packageName = session.packageName,
            averageFps = (session.fpsSum / session.sampleCount.toDouble()).toFloat(),
            sampleCount = session.sampleCount,
            startedAtMs = session.startedAtMs,
            endedAtMs = endedAt
        )
        if (!writeAverageRecord(filesDir, record)) return null
        HistoryFpsStore.archive(filesDir, record, sampleFile,
            runCatching(retainedCalibrations).getOrNull(), session.calibration)
        archiveSamples(filesDir, session.packageName, sampleFile)
        return record
    }

    private fun recoverInterruptedSession(targetPackage: String) {
        val sampleFile = currentSampleFile(filesDir, targetPackage)
        if (!sampleFile.isFile) return
        val parsed = parseSamples(sampleFile, targetPackage)
        if (parsed == null || parsed.sampleCount <= 0L) {
            runCatching { sampleFile.delete() }
            return
        }
        val record = AverageRecord(
            packageName = parsed.packageName,
            averageFps = (parsed.fpsSum / parsed.sampleCount.toDouble()).toFloat(),
            sampleCount = parsed.sampleCount,
            startedAtMs = parsed.startedAtMs,
            endedAtMs = parsed.endedAtMs
        )
        if (writeAverageRecord(filesDir, record)) {
            HistoryFpsStore.archive(filesDir, record, sampleFile,
                runCatching(retainedCalibrations).getOrNull(), parsed.calibration)
            archiveSamples(filesDir, targetPackage, sampleFile)
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "fps_sessions"
        private const val FORMAT_VERSION = "1"
        private const val MAX_REASONABLE_FPS = 1_000f

        fun readLastAverage(filesDir: File, targetPackage: String): AverageRecord? {
            val target = normalizePackageName(targetPackage)
            if (target.isEmpty()) return null
            val file = averageFile(filesDir, target)
            if (!file.isFile) return null
            return runCatching {
                val properties = Properties().also { values ->
                    file.inputStream().buffered().use(values::load)
                }
                if (properties.getProperty("version") != FORMAT_VERSION) return@runCatching null
                val storedPackage = normalizePackageName(properties.getProperty("package").orEmpty())
                val average = properties.getProperty("average_fps")?.toFloatOrNull()
                val count = properties.getProperty("sample_count")?.toLongOrNull()
                val startedAt = properties.getProperty("started_at_ms")?.toLongOrNull()
                val endedAt = properties.getProperty("ended_at_ms")?.toLongOrNull()
                if (
                    storedPackage != target || average == null || !average.isFinite() || average < 0f ||
                    count == null || count <= 0L || startedAt == null || endedAt == null
                ) {
                    return@runCatching null
                }
                AverageRecord(storedPackage, average, count, startedAt, endedAt)
            }.getOrNull()
        }

        internal fun normalizePackageName(packageName: String): String =
            packageName.trim().substringBefore(':').trim()

        private fun writeSampleHeader(file: File, packageName: String, startedAtMs: Long, calibration: Boolean): Boolean =
            runCatching {
                file.parentFile?.mkdirs()
                val header = buildString {
                    append("# QixiaThreads FPS samples v1\n")
                    append("# package=").append(packageName).append('\n')
                    append("# started_at_ms=").append(startedAtMs).append('\n')
                    append("timestamp_ms,fps\n")
                    if (calibration) append("# calibration=1\n")
                }
                FileOutputStream(file, false).use { output ->
                    output.write(header.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
            }.isSuccess

        private fun parseSamples(file: File, expectedPackage: String): ParsedSamples? = runCatching {
            var storedPackage = ""
            var startedAt = 0L
            var firstSampleAt = 0L
            var lastSampleAt = 0L
            var fpsSum = 0.0
            var sampleCount = 0L
            var calibration = false
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("# package=") -> storedPackage =
                            normalizePackageName(line.substringAfter('='))
                        line.startsWith("# started_at_ms=") -> startedAt =
                            line.substringAfter('=').trim().toLongOrNull() ?: 0L
                        line == "# calibration=1" -> calibration = true
                        line.isEmpty() || line[0] == '#' || line.startsWith("timestamp_ms,") -> Unit
                        else -> {
                            val parts = line.split(',', limit = 2)
                            val timestamp = parts.getOrNull(0)?.trim()?.toLongOrNull()
                            val fps = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                            if (timestamp != null && fps != null && fps.isFinite() && fps >= 0f) {
                                if (firstSampleAt == 0L) firstSampleAt = timestamp
                                lastSampleAt = maxOf(lastSampleAt, timestamp)
                                fpsSum += fps.coerceAtMost(MAX_REASONABLE_FPS).toDouble()
                                sampleCount++
                            }
                        }
                    }
                }
            }
            if (storedPackage != expectedPackage || sampleCount <= 0L) return@runCatching null
            ParsedSamples(
                packageName = storedPackage,
                startedAtMs = startedAt.takeIf { it > 0L } ?: firstSampleAt,
                endedAtMs = maxOf(lastSampleAt, startedAt, firstSampleAt),
                fpsSum = fpsSum,
                sampleCount = sampleCount,
                calibration = calibration
            )
        }.getOrNull()

        private fun writeAverageRecord(filesDir: File, record: AverageRecord): Boolean {
            val destination = averageFile(filesDir, record.packageName)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            val properties = Properties().apply {
                setProperty("version", FORMAT_VERSION)
                setProperty("package", record.packageName)
                setProperty("average_fps", record.averageFps.toString())
                setProperty("sample_count", record.sampleCount.toString())
                setProperty("started_at_ms", record.startedAtMs.toString())
                setProperty("ended_at_ms", record.endedAtMs.toString())
                setProperty("duration_ms", record.durationMs.toString())
            }
            return runCatching {
                destination.parentFile?.mkdirs()
                FileOutputStream(temporary, false).use { output ->
                    properties.store(output, "QixiaThreads last completed FPS session")
                    output.fd.sync()
                }
                if (!replaceFile(temporary, destination)) error("Unable to replace FPS average file")
            }.onFailure {
                runCatching { temporary.delete() }
            }.isSuccess
        }

        private fun archiveSamples(filesDir: File, packageName: String, currentFile: File) {
            if (!currentFile.isFile) return
            replaceFile(currentFile, lastSampleFile(filesDir, packageName))
        }

        private fun replaceFile(source: File, destination: File): Boolean {
            destination.parentFile?.mkdirs()
            return try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
                true
            } catch (_: AtomicMoveNotSupportedException) {
                runCatching {
                    Files.move(
                        source.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }.isSuccess
            } catch (_: Exception) {
                false
            }
        }

        private fun averageFile(filesDir: File, packageName: String): File =
            File(sessionDirectory(filesDir), "${packageKey(packageName)}.properties")

        private fun currentSampleFile(filesDir: File, packageName: String): File =
            File(sessionDirectory(filesDir), "${packageKey(packageName)}.current.csv")

        internal fun lastSampleFile(filesDir: File, packageName: String): File =
            File(sessionDirectory(filesDir), "${packageKey(packageName)}.last.csv")

        private fun sessionDirectory(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

        private fun packageKey(packageName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(packageName.toByteArray(StandardCharsets.UTF_8))
            return buildString(digest.size * 2) {
                digest.forEach { byte -> append(String.format(Locale.US, "%02x", byte.toInt() and 0xff)) }
            }
        }
    }
}
