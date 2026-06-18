package top.suto.appopt

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityHistoryBinding
import top.suto.appopt.databinding.ItemCalibSessionBinding
import top.suto.appopt.databinding.ItemThreadLoadBinding
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
        val id: Long,       // 数据库主键(作为序号,删除后不变)
        val epoch: Long,
        val rounds: Int,
        val threads: List<ThreadLoad>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { pkg }
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

    private fun reload() {
        thread {
            val db = top.suto.appopt.db.AppOptDbHelper.getInstance(this)
            val sessionsWithThreads = db.getSessionsByPackage(pkg)
            val sessions = sessionsWithThreads.map { swt ->
                Session(
                    id = swt.id,
                    epoch = swt.epoch,
                    rounds = swt.rounds,
                    threads = swt.threads.map { td ->
                        ThreadLoad(
                            name = td.name,
                            avg = td.avg,
                            max = td.max,
                            series = td.series.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
                        )
                    }
                )
            }
            runOnUiThread { render(sessions) }
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
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val inflater = LayoutInflater.from(this)

        for (s in sessions) {
            val card = ItemCalibSessionBinding.inflate(inflater, binding.sessionContainer, false)
            // 使用数据库 ID 作为序号(删除后不变)
            card.sessionTime.text = "第 ${s.id} 次  ${fmt.format(Date(s.epoch * 1000))}"
            // round_count = 采样轮数, 每轮间隔 0.5 秒; 显示成采样时长
            val durationSec = s.rounds / 2
            val durationStr = when {
                durationSec < 60 -> "${durationSec}s"
                durationSec < 3600 -> "${durationSec / 60}m ${durationSec % 60}s"
                else -> "${durationSec / 3600}h ${(durationSec % 3600) / 60}m"
            }
            card.sessionMeta.text = "${s.threads.size} 线程 · $durationStr"
            for (tl in s.threads) {
                val row = ItemThreadLoadBinding.inflate(inflater, card.threadRows, false)
                row.threadName.text = tl.name
                row.threadStats.text =
                    String.format(Locale.US, "AVG %.1f%%  MAX %.1f%%", tl.avg, tl.max)
                row.threadSpark.setData(tl.series, colorFor(tl.avg), durationSec)
                card.threadRows.addView(row.root)
            }
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
                }
            }
            // 使用数据库 ID 删除
            card.sessionDelete.setOnClickListener { confirmDeleteSession(s.id) }
            binding.sessionContainer.addView(card.root)
        }
    }

    /** 删除单次会话记录前先确认(不可恢复); 删完重新加载, 删空则回退到空状态 */
    private fun confirmDeleteSession(sessionId: Long) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除这次记录")
            .setMessage("确定删除「第 $sessionId 次」校准记录吗？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                thread {
                    val db = top.suto.appopt.db.AppOptDbHelper.getInstance(this)
                    db.deleteSession(sessionId)
                    runOnUiThread {
                        reload()
                    }
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 按平均负载分档着色: 高=暖橙, 中=蓝, 低=青绿 */
    private fun colorFor(avg: Float): Int = when {
        avg >= 25f -> Color.parseColor("#E67E22")
        avg >= 8f  -> Color.parseColor("#5B5BD6")
        else        -> Color.parseColor("#2ECC71")
    }

    /** 解析日志文本为会话列表, 最新一次排在前面; 会话内线程按 AVG 降序。 */
}
