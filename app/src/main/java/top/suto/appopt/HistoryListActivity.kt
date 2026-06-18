package top.suto.appopt

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityHistoryListBinding
import top.suto.appopt.databinding.ItemHistoryAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局历史入口: 列出所有产生过线程负载记录的应用 (history 目录下的 .log 文件)。
 *
 * 独立于配置: 即使应用的 "=auto" 行已被生成的规则替换、从主界面列表消失,
 * 其历史记录依然能在这里查看。点击进入对应应用的会话可视化 (HistoryActivity)。
 */
class HistoryListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        thread {
            // 1. 先把所有 .log 文件同步进数据库
            val logEntries = DaemonBridge.listHistoryEntries()
            for (e in logEntries) {
                DatabaseMigrator.migrateIfNeeded(this, e.pkg)
            }

            // 2. 从数据库读取所有有记录的应用
            val db = top.suto.appopt.db.AppOptDbHelper.getInstance(this)
            val entries = db.getPackagesWithHistory().map {
                DaemonBridge.HistoryEntry(it.pkg, it.lastTime / 1000)  // 毫秒→秒
            }
            runOnUiThread { render(entries) }
        }
    }

    private fun render(entries: List<DaemonBridge.HistoryEntry>) {
        binding.listContainer.removeAllViews()
        if (entries.isEmpty()) {
            binding.listEmpty.visibility = View.VISIBLE
            return
        }
        binding.listEmpty.visibility = View.GONE
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val item = ItemHistoryAppBinding.inflate(inflater, binding.listContainer, false)
            val label = appLabel(e.pkg)
            item.hisName.text = label
            item.hisTime.text = "最近生成 ${fmt.format(Date(e.mtime * 1000))}"
            loadIcon(e.pkg)?.let { item.hisIcon.setImageDrawable(it) }
                ?: item.hisIcon.setImageResource(R.drawable.ic_launcher_foreground)
            item.itemCard.setOnClickListener { openDetail(e.pkg, label) }
            item.hisDelete.setOnClickListener { confirmDeleteApp(e.pkg, label) }
            binding.listContainer.addView(item.root)
        }
    }

    /** 删除某应用的全部历史记录前先确认(不可恢复) */
    private fun confirmDeleteApp(pkg: String, label: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除历史记录")
            .setMessage("确定删除「$label」的全部线程负载历史吗？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                thread {
                    // 删数据库 + .log 文件
                    val db = top.suto.appopt.db.AppOptDbHelper.getInstance(this)
                    db.deleteAllSessionsByPackage(pkg)
                    DaemonBridge.deleteHistory(pkg)
                    // 重新读取
                    val entries = db.getPackagesWithHistory().map {
                        DaemonBridge.HistoryEntry(it.pkg, it.lastTime / 1000)
                    }
                    runOnUiThread { render(entries) }
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openDetail(pkg: String, label: String) {
        startActivity(
            Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_PKG, pkg)
                .putExtra(HistoryActivity.EXTRA_LABEL, label)
        )
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun loadIcon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
