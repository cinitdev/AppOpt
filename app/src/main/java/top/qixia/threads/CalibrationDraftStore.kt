package top.qixia.threads

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅在 IO 线程调用，串行磁盘写入避免与保存或刷新竞争。 */
class CalibrationDraftStore(
    context: Context,
    private val rootCommand: (String) -> DaemonBridge.RootCommandResult = { DaemonBridge.runRootCommand(it) }
) {
    private val ROOT = File(context.filesDir, "capture/calibration_drafts").absolutePath
    private val directory = File(context.filesDir, "calibration_review").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("calibration_review_receipts", Context.MODE_PRIVATE)

    @Synchronized fun load(): List<CalibrationReview> = loadPending(requireRemote = false)

    private fun loadPending(requireRemote: Boolean): List<CalibrationReview> {
        val local = localFiles().mapNotNull(::readReview)
        val result = rootCommand("""
            count=0
            for file in '$ROOT'/*.draft; do
                [ -f "${'$'}file" ] || continue
                count=${'$'}((count + 1)); [ "${'$'}count" -le 128 ] || exit 1
                size=${'$'}(wc -c < "${'$'}file") || exit 1
                [ "${'$'}size" -le ${CalibrationDraft.MAX_BYTES} ] || continue
                printf '\n__QIXIA_DRAFT__\n'
                head -c ${CalibrationDraft.MAX_BYTES} "${'$'}file" || exit 1
            done
        """.trimIndent())
        check(!requireRemote || result.success) { "无法检查最新采集结果，请重试" }
        val remote = if (result.success) result.output.split("__QIXIA_DRAFT__").mapNotNull {
            CalibrationDraft.parse(it.trim())?.let(::CalibrationReview)
        } else emptyList()
        // 本地结果优先，刷新同一会话时保留已编辑的选择。
        val candidates = (local + remote).distinctBy { it.draft.fileName }
        val eligible = candidates.filterNot { completedOrSuperseded(it.draft) }
        val latest = eligible.groupBy { it.draft.packageName }.values.map { reviews ->
            reviews.maxWith { a, b -> a.draft.compareVersion(b.draft) }
        }.sortedWith { a, b -> b.draft.compareVersion(a.draft) }
        val packages = local.filterNot { completedOrSuperseded(it.draft) }.map { it.draft.packageName }.toMutableSet()
        val retained = latest.filter { it.draft.packageName in packages || (packages.size < 64 && packages.add(it.draft.packageName)) }
        retained.forEach { review ->
            if (local.none { it.draft.fileName == review.draft.fileName }) writeReview(review)
            rememberLatest(review.draft, completed = false)
        }
        // 替代结果及版本标记持久化后，才清理旧会话。
        retire(candidates.map { it.draft }.filter(::completedOrSuperseded))
        return retained
    }

    private fun readReview(f: File): CalibrationReview? = runCatching {
        if (f.length() > CalibrationDraft.MAX_BYTES * 2L) return@runCatching null
        val json = JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
        val draft = CalibrationDraft.parse(json.getString("wire")) ?: return@runCatching null
        require(f.name == "${draft.fileName}.json")
        val selections = json.getJSONArray("selections")
        require(selections.length() == draft.threads.size)
        val values = (0 until selections.length()).map { i ->
            val array = selections.getJSONArray(i)
            require(array.length() <= 64)
            (0 until array.length()).map { array.getInt(it) }.toSet().also {
                require(draft.capacities.keys.containsAll(it))
            }
        }
        val original = json.optJSONArray("original")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        val processes = json.optJSONObject("processSelections")
        val processSelections = processes?.keys()?.asSequence()?.associateWith { owner ->
            val array = processes.getJSONArray(owner)
            require(array.length() <= 64)
            (0 until array.length()).map { array.getInt(it) }.toSet()
        }.orEmpty()
        CalibrationReview(draft, values, original, json.optBoolean("automatic"),
            processSelections = processSelections).also { it.rules() }
    }.getOrNull()

    private fun completedOrSuperseded(draft: CalibrationDraft): Boolean {
        if (preferences.getBoolean(draft.fileName, false)) return true
        val marker = latestMarker(draft.packageName) ?: return false
        val order = marker.first.compareTo(draft.stamp)
        return order > 0 || (order == 0 && marker.second)
    }

    // 每个应用保存一份持久的最高版本标记，防止旧草稿快照
    // 在单文件回执已确认后，重新恢复已放弃或已保存的会话。
    private fun latestMarker(pkg: String): Pair<CalibrationDraftStamp, Boolean>? = runCatching {
        val parts = preferences.getString("latest:$pkg", null)?.split('\t') ?: return null
        require(parts.size == 3 && parts[1].matches(Regex("[0-9]{1,18}-[0-9]{1,10}")))
        CalibrationDraftStamp(parts[0].toLong(), parts[1]) to (parts[2] == "1")
    }.getOrNull()

    private fun rememberLatest(draft: CalibrationDraft, completed: Boolean) {
        val marker = "${draft.createdMs}\t${draft.id}\t${if (completed) 1 else 0}"
        if (preferences.getString("latest:${draft.packageName}", null) == marker) return
        check(preferences.edit().putString("latest:${draft.packageName}", marker).commit()) { "待确认状态保存失败，请重试" }
    }

    private fun requireCurrent(draft: CalibrationDraft) {
        val marker = latestMarker(draft.packageName)
        if (completedOrSuperseded(draft) || (marker != null && marker.first.id != draft.id)) {
            throw SupersededCalibrationDraftException()
        }
    }

    private fun requireLatest(draft: CalibrationDraft) {
        val current = loadPending(requireRemote = true).firstOrNull { it.draft.packageName == draft.packageName }
        if (current?.draft?.fileName != draft.fileName) throw SupersededCalibrationDraftException()
    }

    @Synchronized fun persist(review: CalibrationReview) {
        requireCurrent(review.draft)
        writeReview(review)
    }

    private fun writeReview(review: CalibrationReview) {
        val json = JSONObject().put("wire", review.draft.wire)
            .put("selections", JSONArray(review.selections.map { JSONArray(it.sorted()) }))
            .put("automatic", review.automatic)
        review.original?.let { json.put("original", JSONArray(it)) }
        json.put("processSelections", JSONObject().apply {
            review.processSelections.forEach { (owner, cpus) -> put(owner, JSONArray(cpus.sorted())) }
        })
        val atomic = AtomicFile(file(review.draft))
        val bytes = json.toString().toByteArray()
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        // AtomicFile 对部分同步或重命名失败只记录日志，不抛异常。
        check(atomic.openRead().use { it.readBytes().contentEquals(bytes) }) { "待确认结果写入失败，旧记录已保留" }
    }

    @Synchronized fun open(review: CalibrationReview): CalibrationReview {
        requireCurrent(review.draft)
        if (review.original != null) return review
        val original = DaemonBridge.readPkgRulesOrNull(listOf(review.draft.packageName))
            ?: error("读取现有配置失败，请重试")
        val automatic = DaemonBridge.readAutomaticAffinity()
        check(automatic.supported) { "请先更新模块并重启守护进程" }
        return review.copy(original = original, automatic = review.draft.packageName in automatic.packages).also(::persist)
    }

    @Synchronized fun save(review: CalibrationReview) {
        requireCurrent(review.draft)
        check(review.original != null) { "请重新打开待确认结果" }
        check(DaemonBridge.readState().startsWith("sampling ").not()) { "请先结束正在进行的采集" }
        check(!DaemonBridge.hasPendingModuleUpdate()) { "模块更新待重启，请重启后再保存" }
        val available = DaemonBridge.readConfigAllowedCpus()
        check(available.isNotEmpty() && available == review.draft.capacities.keys) { "处理器核心信息已变化，请重新采集" }
        val capacityRead = DaemonBridge.runRootCommand(available.sorted().joinToString("\n") { id ->
            "printf '$id '; cat '/sys/devices/system/cpu/cpu$id/cpu_capacity' 2>/dev/null || printf '0\\n'"
        })
        val capacities = capacityRead.output.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 2) null else fields[0].toIntOrNull()?.let { id -> fields[1].toLongOrNull()?.let { id to it } }
        }.toMap()
        check(capacityRead.success && capacities == review.draft.capacities) { "核心能力信息与采集时不一致，请重新采集" }
        requireLatest(review.draft)
        val rules = review.rules()
        DaemonBridge.saveCalibrationRules(review.draft.packageName, review.original, rules, available)?.let { error(it) }
        complete(review.draft)
    }

    @Synchronized fun discard(draft: CalibrationDraft) {
        requireLatest(draft)
        complete(draft)
    }

    private fun complete(draft: CalibrationDraft) {
        rememberLatest(draft, completed = true)
        retire(listOf(draft))
    }

    private fun retire(drafts: List<CalibrationDraft>) {
        if (drafts.isEmpty()) return
        val editor = preferences.edit()
        drafts.forEach { editor.putBoolean(it.fileName, true) }
        check(editor.commit()) { "完成状态保存失败，请重试" }
        drafts.forEach { AtomicFile(file(it)).delete() }
        val paths = drafts.map { "'$ROOT/${it.fileName}'" }
        val result = rootCommand("rm -f ${paths.joinToString(" ")} && " +
            paths.joinToString(" && ") { "[ ! -e $it ]" } + " && printf acknowledged")
        if (result.success && result.output.trim() == "acknowledged") {
            val acknowledged = preferences.edit()
            drafts.forEach { acknowledged.remove(it.fileName) }
            acknowledged.commit()
        }
    }
    private fun file(draft: CalibrationDraft) = File(directory, "${draft.fileName}.json")
    private fun localFiles(): List<File> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }.distinct()
}

class SupersededCalibrationDraftException : IllegalStateException("此应用已有更新的待确认结果，请确认最新建议")
