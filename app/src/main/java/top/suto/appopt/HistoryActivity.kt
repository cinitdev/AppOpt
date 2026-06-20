package top.suto.appopt

import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityHistoryBinding
import top.suto.appopt.databinding.DialogSessionDeleteBinding
import top.suto.appopt.databinding.DialogSessionManageBinding
import top.suto.appopt.databinding.ItemCalibSessionBinding
import top.suto.appopt.databinding.ItemThreadLoadBinding
import top.suto.appopt.db.AppOptDbHelper
import top.suto.appopt.db.ThreadData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示某应用的历史线程负载记录, 仿 Scene 风格: 每次校准一张卡片,
 * 卡片内每个线程一行: 线程名 + AVG/MAX 占比 + 折线图(瞬时占比随时间)。
 *
 * 数据来自守护进程写入的 history/<pkg>.log, 每段:
 *   # <epoch秒> <采样轮数>
 *   <AVG%> <MAX%> <线程名>|<p1,p2,...,pN>
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
        val series: FloatArray
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
        binding.sessionContainer.removeAllViews()
        expandedCard = null
        val inflater = LayoutInflater.from(this)

        for (s in sessions) {
            val card = ItemCalibSessionBinding.inflate(inflater, binding.sessionContainer, false)
            card.sessionTime.text = formatHistoryTime(s.epoch)
            // round_count = 采样轮数, 每轮间隔 0.5 秒; 显示成采样时长
            val durationSec = s.rounds / 2
            val durationStr = formatDuration(durationSec)
            card.sessionMeta.text = "${s.threadCount} 线程 · $durationStr"
            // 默认全部折叠; 点击头部展开本卡, 同时折叠上一张展开的卡(单展开互斥)
            setCardExpanded(card, false)
            card.sessionHeader.setOnClickListener {
                if (expandedCard === card) {
                    setCardExpanded(card, false)
                    expandedCard = null
                } else {
                    expandedCard?.let { setCardExpanded(it, false) }
                    ensureThreadsLoaded(card, s.id, durationSec)
                    setCardExpanded(card, true)
                    expandedCard = card
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
            "$appLabel · ${formatHistoryTime(session.epoch)} · ${session.threadCount} 线程 · ${formatDuration(durationSec)}"

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
            "$appLabel · ${formatHistoryTime(session.epoch)} · ${session.threadCount} 线程"
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
            appendLine("线程数: ${threads.size}")
            appendLine()
            appendLine("线程负载:")
            appendLine("AVG%  MAX%  线程名")
            for (t in threads) {
                appendLine(String.format(Locale.US, "%.1f  %.1f  %s", t.avg, t.max, t.name))
            }
            appendLine()
            appendLine("曲线数据:")
            for (t in threads) {
                appendLine("${t.name}|${t.series}")
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
            val db = AppOptDbHelper.getInstance(this)
            val loads = db.getThreadsBySessionId(sessionId).map { it.toThreadLoad() }
            runOnUiThreadIfAlive {
                if (generation != reloadGeneration) return@runOnUiThreadIfAlive
                card.threadRows.removeAllViews()
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
        if (isThreadRenderStale(generation) || startIndex >= loads.size) return
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
        if (isThreadRenderStale(generation)) return
        val nextIndex = minOf(startIndex + THREAD_RENDER_BATCH_SIZE, endIndex)
        for (i in startIndex until nextIndex) {
            val tl = loads[i]
            val row = ItemThreadLoadBinding.inflate(inflater, card.threadRows, false)
            row.threadName.text = tl.name
            row.threadStats.text =
                String.format(Locale.US, "AVG %.1f%%  MAX %.1f%%", tl.avg, tl.max)
            row.threadSpark.setData(tl.series, colorFor(tl.avg, tl.max), durationSec)
            card.threadRows.addView(row.root)
        }
        if (nextIndex < endIndex) {
            card.threadRows.post {
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
        } else {
            onDone()
        }
    }

    private fun isThreadRenderStale(generation: Int): Boolean {
        return isFinishing || isDestroyed || generation != reloadGeneration
    }

    private fun ThreadData.toThreadLoad(): ThreadLoad {
        return ThreadLoad(
            name = name,
            avg = avg,
            max = max,
            series = series.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
        )
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 按平均负载分档着色: 高=暖橙, 中=蓝, 低=青绿 */
    private fun colorFor(avg: Float, max: Float): Int = when {
        avg >= 13f || max >= 22f -> Color.parseColor("#E67E22")
        avg >= 8f || max >= 18f  -> Color.parseColor("#5B5BD6")
        else        -> Color.parseColor("#2ECC71")
    }

    /** 解析日志文本为会话列表, 最新一次排在前面; 会话内线程按 AVG 降序。 */
}
