package top.suto.appopt

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityHistoryBinding
import top.suto.appopt.databinding.DialogSessionDeleteBinding
import top.suto.appopt.databinding.DialogSessionManageBinding
import top.suto.appopt.databinding.ItemCalibSessionBinding
import top.suto.appopt.databinding.ItemChildThreadLoadBinding
import top.suto.appopt.databinding.ItemThreadLoadBinding
import top.suto.appopt.db.AppOptDbHelper
import top.suto.appopt.db.ThreadData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示某应用的历史负载记录: 每次校准一张卡片, 主进程按线程展示,
 * 子进程按整体负载展示 AVG/MAX 占比和瞬时占比折线图。
 *
 * 数据来自守护进程写入的 history/<pkg>.log, 每段:
 *   # <epoch秒> <采样轮数>
 *   <AVG%> <MAX%> <名称>|<p1,p2,...,pN>[|v2:子线程名,AVG,MAX;...]
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        private const val THREAD_RENDER_BATCH_SIZE = 12
    }

    /** 单个线程的负载折线 */
    private data class ThreadLoad(
        val name: String,
        val avg: Float,
        val max: Float,
        val series: FloatArray,
        val details: String
    )

    private data class ChildThreadLoad(
        val name: String,
        val avg: Float?,
        val max: Float?
    )

    /** 一次校准会话 */
    private data class Session(
        val id: Long,       // 数据库主键, 仅用于内部查询/删除
        val epoch: Long,
        val rounds: Int,
        val threadCount: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { pkg }
        appLabel = label
        binding.historyTitle.text = label
        binding.historyPkg.text = pkg
        binding.historyBack.setOnClickListener { finish() }
        try {
            binding.historyIcon.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (_: Exception) {
            binding.historyIcon.setImageResource(R.drawable.ic_launcher_foreground)
        }

        if (pkg.isBlank()) {
            showEmpty("无效的包名")
            return
        }
        this.pkg = pkg

        // 直接加载数据库(MainActivity 启动时已批量导入所有 .log)
        reload()
    }

    /** 当前展示的包名(删除会话后重新加载用) */
    private var pkg: String = ""
    private var appLabel: String = ""
    private var reloadGeneration = 0

    private fun reload(retryIfEmpty: Boolean = true) {
        val generation = ++reloadGeneration
        thread {
            DatabaseMigrator.migrateIfNeeded(applicationContext, pkg)
            val db = AppOptDbHelper.getInstance(this)
            val sessions = db.getSessionSummariesByPackage(pkg).map { summary ->
                Session(
                    id = summary.id,
                    epoch = summary.epoch,
                    rounds = summary.rounds,
                    threadCount = summary.threadCount
                )
            }
            runOnUiThreadIfAlive {
                if (generation != reloadGeneration) return@runOnUiThreadIfAlive
                render(sessions)
                if (sessions.isEmpty() && retryIfEmpty) {
                    binding.root.postDelayed({
                        if (!isFinishing && !isDestroyed) reload(retryIfEmpty = false)
                    }, 1800)
                }
            }
        }
    }

    private fun runOnUiThreadIfAlive(action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun showEmpty(msg: String) {
        binding.historyEmpty.text = msg
        binding.historyEmpty.visibility = View.VISIBLE
        binding.sessionContainer.visibility = View.GONE
        binding.historyCount.text = ""
    }

    /** 当前展开的卡片绑定; 每次只展开一张, 点新的先折叠旧的。 */
    private var expandedCard: ItemCalibSessionBinding? = null

    private fun setCardExpanded(card: ItemCalibSessionBinding, expanded: Boolean) {
        card.threadRows.visibility = if (expanded) View.VISIBLE else View.GONE
        card.sessionDivider.visibility = if (expanded) View.VISIBLE else View.GONE
        // chevron 向下(收起) <-> 向上(展开): 旋转 180° 表现状态
        card.sessionArrow.rotation = if (expanded) 180f else 0f
    }

    private fun render(sessions: List<Session>) {
        if (sessions.isEmpty()) {
            showEmpty("暂无历史记录\n\n进入应用后用悬浮球完成一次校准即可生成记录")
            return
        }
        binding.historyEmpty.visibility = View.GONE
        binding.sessionContainer.visibility = View.VISIBLE
        binding.historyCount.text = "${sessions.size} 次校准"
        binding.sessionContainer.removeAllViews()
        expandedCard = null
        val inflater = LayoutInflater.from(this)

        for (s in sessions) {
            val card = ItemCalibSessionBinding.inflate(inflater, binding.sessionContainer, false)
            card.sessionDate.text = formatHistoryDate(s.epoch)
            card.sessionTime.text = formatHistoryClock(s.epoch)
            // round_count = 采样轮数, 每轮间隔 0.5 秒; 显示成采样时长
            val durationSec = s.rounds / 2
            val durationStr = formatDuration(durationSec)
            card.sessionThreads.text = "${s.threadCount} 条记录"
            card.sessionDuration.text = durationStr
            // 默认全部折叠; 点击头部展开本卡, 同时折叠上一张展开的卡(单展开互斥)
            setCardExpanded(card, false)
            card.sessionHeader.setOnClickListener {
                if (expandedCard === card) {
                    setCardExpanded(card, false)
                    expandedCard = null
                } else {
                    expandedCard?.let { setCardExpanded(it, false) }
                    setCardExpanded(card, true)
                    expandedCard = card
                    ensureThreadsLoaded(card, s.id, durationSec)
                }
            }
            card.sessionManage.setOnClickListener { showSessionManageSheet(s) }
            binding.sessionContainer.addView(card.root)
        }
    }

    private fun showSessionManageSheet(session: Session) {
        val view = DialogSessionManageBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        val durationSec = session.rounds / 2
        view.sessionManageTitle.text = "校准记录"
        view.sessionManageMeta.text =
            "$appLabel · ${formatHistoryTime(session.epoch)} · ${session.threadCount} 条记录 · ${formatDuration(durationSec)}"

        view.sessionManageCancel.setOnClickListener { dialog.dismiss() }
        view.sessionManageExport.setOnClickListener {
            dialog.dismiss()
            exportSession(session)
        }
        view.sessionManageDelete.setOnClickListener {
            dialog.dismiss()
            showDeleteSessionConfirm(session)
        }
        dialog.show()
    }

    private fun showDeleteSessionConfirm(session: Session) {
        val view = DialogSessionDeleteBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        view.sessionDeleteTitle.text = "删除校准记录"
        view.sessionDeleteMeta.text =
            "$appLabel · ${formatHistoryTime(session.epoch)} · ${session.threadCount} 条记录"
        view.sessionDeleteCancel.setOnClickListener { dialog.dismiss() }
        view.sessionDeleteConfirm.setOnClickListener {
            dialog.dismiss()
            deleteSession(session.id)
        }
        dialog.show()
    }

    private fun deleteSession(sessionId: Long) {
        thread {
            val db = AppOptDbHelper.getInstance(this)
            db.deleteSession(sessionId)
            runOnUiThreadIfAlive {
                toast("已删除记录")
                reload()
            }
        }
    }

    private fun exportSession(session: Session) {
        thread {
            val db = AppOptDbHelper.getInstance(this)
            val threads = db.getThreadsBySessionId(session.id)
            val text = buildSessionExportText(session, threads)
            val result = writeSessionExportFile(session, text)
            runOnUiThreadIfAlive {
                result.fold(
                    onSuccess = { toast("已导出到 $it") },
                    onFailure = { toast("导出失败: ${it.message ?: "无法写入 Download"}") }
                )
            }
        }
    }

    private fun buildSessionExportText(session: Session, threads: List<ThreadData>): String {
        val durationSec = session.rounds / 2
        return buildString {
            appendLine("AppOpt 历史记录导出")
            appendLine("应用: $appLabel")
            appendLine("包名: $pkg")
            appendLine("时间: ${formatHistoryTime(session.epoch)}")
            appendLine("采样轮数: ${session.rounds}")
            appendLine("采样时长: ${formatDuration(durationSec)}")
            appendLine("负载记录数: ${threads.size}")
            appendLine()
            appendLine("负载记录:")
            appendLine("AVG%  MAX%  类型    名称")
            for (t in threads) {
                val childProcess = isChildProcessLoad(t.name)
                appendLine(
                    String.format(
                        Locale.US,
                        "%.1f  %.1f  %-6s  %s",
                        t.avg,
                        t.max,
                        if (childProcess) "子进程" else "线程",
                        displayLoadName(t.name)
                    )
                )
                val childThreads = parseChildThreadLoads(t.details)
                if (childProcess && childThreads.isNotEmpty()) {
                    appendLine("             线程明细:")
                    for (childThread in childThreads) {
                        val stats = if (childThread.avg != null && childThread.max != null) {
                            String.format(
                                Locale.US,
                                "AVG %.1f%%  MAX %.1f%%",
                                childThread.avg,
                                childThread.max
                            )
                        } else {
                            "AVG --  MAX --"
                        }
                        appendLine("               ${childThread.name}  $stats")
                    }
                }
            }
            appendLine()
            appendLine("曲线数据:")
            for (t in threads) {
                append(t.name).append('|').append(t.series)
                if (t.details.isNotBlank()) append('|').append(t.details)
                appendLine()
            }
        }
    }

    private fun writeSessionExportFile(session: Session, text: String): Result<String> {
        return runCatching {
            val fileName = exportFileName(session)
            val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/AppOpt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("创建导出文件失败")

            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("打开导出文件失败")

                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                contentResolver.update(uri, done, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }

            "$relativeDir/$fileName"
        }
    }

    private fun exportFileName(session: Session): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(session.epoch * 1000))
        val safePkg = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "AppOpt_${safePkg}_history_$stamp.txt"
    }

    private fun formatDuration(durationSec: Int): String {
        return when {
            durationSec < 60 -> "${durationSec}s"
            durationSec < 3600 -> "${durationSec / 60}m ${durationSec % 60}s"
            else -> "${durationSec / 3600}h ${(durationSec % 3600) / 60}m"
        }
    }

    private fun formatHistoryTime(epochSeconds: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(epochSeconds * 1000))
    }

    private fun formatHistoryDate(epochSeconds: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(epochSeconds * 1000))
    }

    private fun formatHistoryClock(epochSeconds: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.US)
            .format(Date(epochSeconds * 1000))
    }

    private fun ensureThreadsLoaded(card: ItemCalibSessionBinding, sessionId: Long, durationSec: Int) {
        if (card.threadRows.childCount > 0) return
        val generation = reloadGeneration
        card.threadRows.removeAllViews()
        val loading = android.widget.TextView(this).apply {
            text = "加载中..."
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        card.threadRows.addView(loading)

        thread {
            val result = runCatching {
                val db = AppOptDbHelper.getInstance(this)
                db.getThreadsBySessionId(sessionId).map { it.toThreadLoad() }
            }
            runOnUiThreadIfAlive {
                if (isThreadRenderStale(card, generation)) return@runOnUiThreadIfAlive
                card.threadRows.removeAllViews()
                val loads = result.getOrElse { error ->
                    android.util.Log.e("AppOpt", "history thread load failed: session=$sessionId", error)
                    val retry = android.widget.TextView(this).apply {
                        text = "加载失败，点击重试"
                        setTextColor(resources.getColor(R.color.brand_primary, theme))
                        textSize = 13f
                        setPadding(0, 12, 0, 12)
                        setOnClickListener {
                            card.threadRows.removeAllViews()
                            ensureThreadsLoaded(card, sessionId, durationSec)
                        }
                    }
                    card.threadRows.addView(retry)
                    return@runOnUiThreadIfAlive
                }
                if (loads.isEmpty()) {
                    card.threadRows.addView(android.widget.TextView(this).apply {
                        text = "暂无负载明细"
                        setTextColor(resources.getColor(R.color.text_secondary, theme))
                        textSize = 13f
                        setPadding(0, 12, 0, 12)
                    })
                    return@runOnUiThreadIfAlive
                }
                val inflater = LayoutInflater.from(this)
                renderThreadPage(card, inflater, loads, durationSec, 0, generation)
            }
        }
    }

    private fun renderThreadPage(
        card: ItemCalibSessionBinding,
        inflater: LayoutInflater,
        loads: List<ThreadLoad>,
        durationSec: Int,
        startIndex: Int,
        generation: Int
    ) {
        if (isThreadRenderStale(card, generation) || startIndex >= loads.size) return
        renderThreadBatch(card, inflater, loads, durationSec, startIndex, loads.size, generation) {}
    }

    private fun renderThreadBatch(
        card: ItemCalibSessionBinding,
        inflater: LayoutInflater,
        loads: List<ThreadLoad>,
        durationSec: Int,
        startIndex: Int,
        endIndex: Int,
        generation: Int,
        onDone: () -> Unit
    ) {
        if (isThreadRenderStale(card, generation)) return
        val nextIndex = minOf(startIndex + THREAD_RENDER_BATCH_SIZE, endIndex)
        for (i in startIndex until nextIndex) {
            val tl = loads[i]
            val row = ItemThreadLoadBinding.inflate(inflater, card.threadRows, false)
            val isChildProcess = isChildProcessLoad(tl.name)
            row.loadType.text = if (isChildProcess) "子进程" else "线程"
            row.loadType.setBackgroundResource(
                if (isChildProcess) R.drawable.bg_rule_type_main else R.drawable.bg_rule_type_thread
            )
            row.loadType.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isChildProcess) R.color.brand_secondary else R.color.brand_primary_dark
                )
            )
            row.threadName.text = displayLoadName(tl.name)
            row.threadName.setOnLongClickListener {
                copyLoadName(tl.name)
                true
            }
            val childThreads = parseChildThreadLoads(tl.details)
            bindChildThreadDetails(row, inflater, isChildProcess, childThreads)
            row.threadAvg.text = String.format(
                Locale.US,
                if (isChildProcess) "整体 AVG %.1f%%" else "AVG %.1f%%",
                tl.avg
            )
            row.threadMax.text = String.format(
                Locale.US,
                if (isChildProcess) "整体 MAX %.1f%%" else "MAX %.1f%%",
                tl.max
            )
            row.threadSpark.setData(tl.series, colorFor(tl.avg, tl.max), durationSec)
            card.threadRows.addView(row.root)
        }
        if (nextIndex < endIndex) {
            card.threadRows.post {
                if (!isThreadRenderStale(card, generation)) {
                    renderThreadBatch(
                        card,
                        inflater,
                        loads,
                        durationSec,
                        nextIndex,
                        endIndex,
                        generation,
                        onDone
                    )
                }
            }
        } else {
            onDone()
        }
    }

    private fun isThreadRenderStale(generation: Int): Boolean {
        return isFinishing || isDestroyed || generation != reloadGeneration
    }

    private fun isThreadRenderStale(card: ItemCalibSessionBinding, generation: Int): Boolean {
        return isThreadRenderStale(generation) || card.root.parent == null
    }

    private fun ThreadData.toThreadLoad(): ThreadLoad {
        return ThreadLoad(
            name = name,
            avg = avg,
            max = max,
            series = series.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray(),
            details = details
        )
    }

    private fun parseChildThreadLoads(details: String): List<ChildThreadLoad> {
        if (details.isBlank()) return emptyList()
        if (!details.startsWith("v2:")) {
            return details.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ChildThreadLoad(it, null, null) }
        }
        return details.removePrefix("v2:")
            .split(';')
            .mapNotNull { record ->
                val parts = record.split(',', limit = 3)
                val name = parts.getOrNull(0)?.trim().orEmpty()
                val avg = parts.getOrNull(1)?.toFloatOrNull()
                val max = parts.getOrNull(2)?.toFloatOrNull()
                if (name.isEmpty() || avg == null || max == null) null
                else ChildThreadLoad(name, avg, max)
            }
    }

    private fun bindChildThreadDetails(
        row: ItemThreadLoadBinding,
        inflater: LayoutInflater,
        isChildProcess: Boolean,
        loads: List<ChildThreadLoad>
    ) {
        val available = isChildProcess && loads.isNotEmpty()
        row.childThreadsToggle.visibility = if (available) View.VISIBLE else View.GONE
        row.childThreadsContainer.visibility = View.GONE
        row.childThreadsArrow.rotation = 0f
        if (!available) return

        row.childThreadCount.text = "${loads.size} 个活跃"
        var rendered = false
        var expanded = false
        row.childThreadsToggle.setOnClickListener {
            if (!rendered) {
                for (load in loads) {
                    val item = ItemChildThreadLoadBinding.inflate(
                        inflater,
                        row.childThreadsRows,
                        false
                    )
                    item.childThreadName.text = load.name
                    item.childThreadName.setOnLongClickListener {
                        copyLoadName(load.name)
                        true
                    }
                    item.childThreadAvg.text = load.avg?.let {
                        String.format(Locale.US, "%.1f%%", it)
                    } ?: "--"
                    item.childThreadMax.text = load.max?.let {
                        String.format(Locale.US, "%.1f%%", it)
                    } ?: "--"
                    row.childThreadsRows.addView(item.root)
                }
                rendered = true
            }
            expanded = !expanded
            row.childThreadsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            row.childThreadsArrow.rotation = if (expanded) 180f else 0f
        }
    }

    private fun displayLoadName(name: String): String {
        return if (isChildProcessLoad(name)) {
            name.removePrefix(pkg)
        } else {
            name
        }
    }

    private fun isChildProcessLoad(name: String): Boolean {
        return name.startsWith("$pkg:") && !name.contains('{')
    }

    private fun copyLoadName(name: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AppOpt 负载名称", name))
        val isChildProcess = isChildProcessLoad(name)
        toast(if (isChildProcess) "已复制子进程名" else "已复制线程名")
    }

    private fun toast(msg: String) {
        AppToast.show(this, msg)
    }

    /** 按平均负载分档着色: 高=暖橙, 中=蓝, 低=青绿 */
    private fun colorFor(avg: Float, max: Float): Int = when {
        avg >= 18f && max >= 30f -> Color.parseColor("#E74C3C")
        avg >= 13f && max >= 22f -> Color.parseColor("#E67E22")
        avg >= 8f && max >= 18f -> Color.parseColor("#5B5BD6")
        else -> Color.parseColor("#2ECC71")
    }

    /** 会话内主进程线程与子进程整体负载统一按 AVG 降序。 */
}
