package top.suto.appopt

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityHistoryListBinding
import top.suto.appopt.databinding.DialogHistoryAppDeleteBinding
import top.suto.appopt.databinding.DialogHistoryAppManageBinding
import top.suto.appopt.databinding.ItemHistoryAppBinding
import top.suto.appopt.db.AppOptDbHelper
import top.suto.appopt.db.SessionWithThreads
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
    private var loadGeneration = 0

    private data class HistoryItem(
        val pkg: String,
        val mtime: Long,
        val sessionCount: Int,
        val label: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.applyEdgeToEdge(this, binding.root, binding.historyListHeader)
        binding.historyListBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory(retryIfEmpty: Boolean = true) {
        val generation = ++loadGeneration
        thread {
            // 1. 先把所有 .log 文件同步进数据库
            val logEntries = DaemonBridge.listHistoryEntries()
            for (e in logEntries) {
                DatabaseMigrator.migrateIfNeeded(this, e.pkg)
            }

            // 2. 从数据库读取所有有记录的应用, 并在后台解析应用名/图标, 避免 UI 线程卡顿。
            val db = top.suto.appopt.db.AppOptDbHelper.getInstance(this)
            val items = db.getPackagesWithHistory().map {
                val pkg = it.pkg
                HistoryItem(
                    pkg = pkg,
                    mtime = it.lastTime,
                    sessionCount = it.sessionCount,
                    label = appLabel(pkg),
                    icon = loadIcon(pkg)
                )
            }
            runOnUiThreadIfAlive {
                if (generation != loadGeneration) return@runOnUiThreadIfAlive
                render(items)
                if (items.isEmpty() && retryIfEmpty) {
                    binding.root.postDelayed({
                        if (!isFinishing && !isDestroyed) loadHistory(retryIfEmpty = false)
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

    private fun render(entries: List<HistoryItem>) {
        binding.listContainer.removeAllViews()
        binding.historyListCount.text = if (entries.isEmpty()) "" else "${entries.size} 个应用"
        if (entries.isEmpty()) {
            binding.listEmpty.visibility = View.VISIBLE
            return
        }
        binding.listEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val item = ItemHistoryAppBinding.inflate(inflater, binding.listContainer, false)
            item.hisName.text = e.label
            item.hisPkg.text = e.pkg
            item.hisTime.text = formatHistoryTime(e.mtime)
            item.hisCount.text = "${e.sessionCount} 次"
            e.icon?.let { item.hisIcon.setImageDrawable(it) }
                ?: item.hisIcon.setImageResource(R.drawable.ic_launcher_foreground)
            item.itemCard.setOnClickListener { openDetail(e.pkg, e.label) }
            item.hisManage.setOnClickListener { showHistoryAppManageSheet(e) }
            binding.listContainer.addView(item.root)
        }
    }

    private fun showHistoryAppManageSheet(entry: HistoryItem) {
        val view = DialogHistoryAppManageBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        view.historyAppManageTitle.text = "历史数据管理"
        view.historyAppManageMeta.text = "${entry.label} · ${entry.pkg}"
        view.historyAppManageCancel.setOnClickListener { dialog.dismiss() }
        view.historyAppExport.setOnClickListener {
            dialog.dismiss()
            exportAllHistory(entry.pkg)
        }
        view.historyAppDelete.setOnClickListener {
            dialog.dismiss()
            showDeleteAppConfirm(entry.pkg, entry.label)
        }
        dialog.show()
    }

    private fun showDeleteAppConfirm(pkg: String, label: String) {
        val view = DialogHistoryAppDeleteBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        view.historyAppDeleteTitle.text = "删除全部历史记录"
        view.historyAppDeleteMeta.text = "$label · $pkg"
        view.historyAppDeleteCancel.setOnClickListener { dialog.dismiss() }
        view.historyAppDeleteConfirm.setOnClickListener {
            dialog.dismiss()
            deleteAllHistory(pkg)
        }
        dialog.show()
    }

    private fun deleteAllHistory(pkg: String) {
        thread {
            val db = AppOptDbHelper.getInstance(this)
            db.deleteAllSessionsByPackage(pkg)
            DaemonBridge.deleteHistory(pkg)
            runOnUiThreadIfAlive {
                toast("已删除历史记录")
                loadHistory(retryIfEmpty = false)
            }
        }
    }

    private fun exportAllHistory(pkg: String) {
        thread {
            val db = AppOptDbHelper.getInstance(this)
            val sessions = db.getSessionsByPackage(
                pkg,
                preserveOriginalThreadOrder = true
            ).sortedBy { it.epoch }
            val result = if (sessions.isEmpty()) {
                Result.failure(IllegalStateException("没有可导出的历史数据"))
            } else {
                writeOriginalHistoryFile(pkg, buildOriginalHistoryLog(sessions))
            }
            runOnUiThreadIfAlive {
                result.fold(
                    onSuccess = { toast("已导出到 $it") },
                    onFailure = { toast("导出失败: ${it.message ?: "无法写入 Download"}") }
                )
            }
        }
    }

    private fun buildOriginalHistoryLog(sessions: List<SessionWithThreads>): String {
        return buildString {
            for (session in sessions) {
                append("# ")
                    .append(session.epoch)
                    .append(' ')
                    .append(session.rounds)
                    .append('\n')
                for (thread in session.threads) {
                    append(String.format(Locale.US, "%.2f %.2f %s|%s",
                        thread.avg, thread.max, thread.name, thread.series))
                    if (thread.details.isNotBlank()) append('|').append(thread.details)
                    append('\n')
                }
            }
        }
    }

    private fun writeOriginalHistoryFile(pkg: String, text: String): Result<String> {
        return runCatching {
            val fileName = "${pkg.replace(Regex("[/\\\\]"), "_")}.log"
            val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/AppOpt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/x-log")
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
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/x-log")
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

    private fun toast(msg: String) {
        AppToast.show(this, msg)
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

    private fun formatHistoryTime(epochSeconds: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(epochSeconds * 1000))
    }
}
