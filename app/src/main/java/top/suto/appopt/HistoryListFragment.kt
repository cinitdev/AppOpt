package top.suto.appopt

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread
import top.suto.appopt.databinding.FragmentHistoryListBinding
import top.suto.appopt.databinding.DialogHistoryAppDeleteBinding
import top.suto.appopt.databinding.DialogHistoryAppManageBinding
import top.suto.appopt.databinding.ItemHistoryAppBinding
import top.suto.appopt.db.AppOptDbHelper
import top.suto.appopt.db.SessionWithThreads
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 全局历史入口: 列出所有产生过线程负载记录的应用 (history 目录下的 .log 文件)。
 *
 * 独立于配置: 即使应用的 "=auto" 行已被生成的规则替换、从主界面列表消失,
 * 其历史记录依然能在这里查看。点击进入对应应用的会话可视化 (HistoryActivity)。
 */
class HistoryListFragment : TopLevelFragment() {

    private var _binding: FragmentHistoryListBinding? = null
    private val binding: FragmentHistoryListBinding
        get() = checkNotNull(_binding)
    private var historyAdapter: HistoryAdapter? = null
    private var viewGeneration = 0
    private var loadGeneration = 0
    private var loadInFlight = false
    private var reloadPending = false
    private var pendingRetryIfEmpty = false
    private var loadCompleted = false
    private var lastLoadFinishedAt = 0L
    private var retryRunnable: Runnable? = null

    private data class HistoryItem(
        val pkg: String,
        val mtime: Long,
        val sessionCount: Int,
        val label: String,
        val icon: Drawable?
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryListBinding.inflate(inflater, container, false)
        viewGeneration++
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prepareTopLevelPage(binding.historyListHeader)
        historyAdapter = HistoryAdapter()
        binding.historyRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.historyRecycler.adapter = historyAdapter
        binding.historyRecycler.itemAnimator = null
        showLoading()
    }

    override fun onTopLevelPageSelected() {
        if (_binding == null) return
        if (loadInFlight) return
        if (!loadCompleted || shouldRefresh()) {
            loadHistory(retryIfEmpty = !loadCompleted)
        }
    }

    private fun shouldRefresh(): Boolean =
        SystemClock.elapsedRealtime() - lastLoadFinishedAt >= HISTORY_REFRESH_INTERVAL_MS

    private fun loadHistory(retryIfEmpty: Boolean = true) {
        if (_binding == null) return
        if (loadInFlight) {
            reloadPending = true
            pendingRetryIfEmpty = pendingRetryIfEmpty || retryIfEmpty
            return
        }
        loadInFlight = true
        val generation = ++loadGeneration
        val currentViewGeneration = viewGeneration
        HISTORY_IO_EXECUTOR.execute {
            var cachedItems = emptyList<HistoryItem>()
            var items = emptyList<HistoryItem>()
            runCatching {
                val db = AppOptDbHelper.getInstance(appContext)
                cachedItems = runCatching { readHistoryItems(db) }
                    .onFailure { android.util.Log.e("AppOpt", "读取历史数据库失败", it) }
                    .getOrDefault(emptyList())

                // 先显示数据库中已有的记录，不等待 Root 枚举和旧日志导入。
                if (cachedItems.isNotEmpty()) {
                    postHistoryUi(currentViewGeneration) {
                        if (generation != loadGeneration) return@postHistoryUi
                        loadCompleted = true
                        render(cachedItems)
                    }
                }

                runCatching {
                    for (entry in DaemonBridge.listHistoryEntries()) {
                        runCatching { DatabaseMigrator.migrateIfNeeded(appContext, entry.pkg) }
                            .onFailure {
                                android.util.Log.e("AppOpt", "导入 ${entry.pkg} 历史记录失败", it)
                            }
                        }
                }.onFailure { android.util.Log.e("AppOpt", "枚举历史记录失败", it) }

                items = runCatching { readHistoryItems(db) }
                    .onFailure { android.util.Log.e("AppOpt", "刷新历史数据库失败", it) }
                    .getOrDefault(cachedItems)
            }.onFailure { android.util.Log.e("AppOpt", "加载历史记录失败", it) }

            postHistoryUi(currentViewGeneration) {
                val current = generation == loadGeneration
                loadInFlight = false
                if (current) {
                    loadCompleted = true
                    lastLoadFinishedAt = SystemClock.elapsedRealtime()
                    render(items)
                    if (items.isEmpty() && retryIfEmpty) scheduleEmptyRetry()
                }
                if (reloadPending) {
                    val retry = pendingRetryIfEmpty
                    reloadPending = false
                    pendingRetryIfEmpty = false
                    loadHistory(retryIfEmpty = retry)
                }
            }
        }
    }

    private fun scheduleEmptyRetry() {
        retryRunnable?.let(binding.root::removeCallbacks)
        val runnable = Runnable {
            retryRunnable = null
            if (!isFinishing && !isDestroyed) loadHistory(retryIfEmpty = false)
        }
        retryRunnable = runnable
        binding.root.postDelayed(runnable, 1800L)
    }

    private fun postHistoryUi(expectedViewGeneration: Int, action: () -> Unit) {
        activity?.runOnUiThread {
            if (expectedViewGeneration == viewGeneration && _binding != null &&
                !isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun readHistoryItems(db: AppOptDbHelper): List<HistoryItem> =
        db.getPackagesWithHistory().map {
            val pkg = it.pkg
            HistoryItem(
                pkg = pkg,
                mtime = it.lastTime,
                sessionCount = it.sessionCount,
                label = appLabel(pkg),
                icon = loadIcon(pkg)
            )
        }

    private fun showLoading() {
        binding.historyListCount.text = ""
        binding.listLoading.visibility = View.VISIBLE
        binding.listEmpty.visibility = View.GONE
        binding.historyRecycler.visibility = View.GONE
    }

    private fun runOnUiThreadIfAlive(action: () -> Unit) {
        postHistoryUi(viewGeneration, action)
    }

    private fun render(entries: List<HistoryItem>) {
        binding.listLoading.visibility = View.GONE
        binding.historyListCount.text = if (entries.isEmpty()) "" else "${entries.size} 个应用"
        if (entries.isEmpty()) {
            historyAdapter?.submit(emptyList())
            binding.historyRecycler.visibility = View.GONE
            binding.listEmpty.visibility = View.VISIBLE
            return
        }
        binding.listEmpty.visibility = View.GONE
        historyAdapter?.submit(entries)
        binding.historyRecycler.visibility = View.VISIBLE
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        private var items: List<HistoryItem> = emptyList()

        fun submit(entries: List<HistoryItem>) {
            items = entries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemHistoryAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            with(holder.binding) {
                hisName.text = entry.label
                hisPkg.text = entry.pkg
                hisTime.text = formatHistoryTime(entry.mtime)
                hisCount.text = "${entry.sessionCount} 次"
                entry.icon?.let(hisIcon::setImageDrawable)
                    ?: hisIcon.setImageResource(R.drawable.ic_launcher_foreground)
                itemCard.setOnClickListener { openDetail(entry.pkg, entry.label) }
                hisManage.setOnClickListener { showHistoryAppManageSheet(entry) }
            }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(val binding: ItemHistoryAppBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private fun showHistoryAppManageSheet(entry: HistoryItem) {
        val view = DialogHistoryAppManageBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext())
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
        val dialog = BottomSheetDialog(requireContext())
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
        invalidateCurrentLoad()
        thread {
            DatabaseMigrator.withPackageLock(pkg) {
                val db = AppOptDbHelper.getInstance(appContext)
                db.deleteAllSessionsByPackage(pkg)
                DaemonBridge.deleteHistory(pkg)
            }
            runOnUiThreadIfAlive {
                toast("已删除历史记录")
                loadHistory(retryIfEmpty = false)
            }
        }
    }

    private fun exportAllHistory(pkg: String) {
        thread {
            val sessions = DatabaseMigrator.withPackageLock(pkg) {
                val db = AppOptDbHelper.getInstance(appContext)
                db.getSessionsByPackage(
                    pkg,
                    preserveOriginalThreadOrder = true
                ).sortedBy { it.epoch }
            }
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

    private fun invalidateCurrentLoad() {
        loadGeneration++
        if (loadInFlight) {
            reloadPending = true
            pendingRetryIfEmpty = false
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
        AppToast.show(requireContext(), msg)
    }

    private fun openDetail(pkg: String, label: String) {
        startActivity(
            Intent(requireContext(), HistoryActivity::class.java)
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

    override fun onDestroyView() {
        retryRunnable?.let { _binding?.root?.removeCallbacks(it) }
        retryRunnable = null
        loadGeneration++
        loadInFlight = false
        reloadPending = false
        pendingRetryIfEmpty = false
        loadCompleted = false
        _binding?.historyRecycler?.adapter = null
        historyAdapter = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val HISTORY_REFRESH_INTERVAL_MS = 1_500L
        val HISTORY_IO_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
