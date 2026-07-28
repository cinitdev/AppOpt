package top.suto.appopt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors
import top.suto.appopt.databinding.FragmentLogBinding
import top.suto.appopt.databinding.ItemLogEntryBinding

/** 将守护进程与前台助手日志解析为可筛选的结构化列表。 */
class LogFragment : TopLevelFragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding: FragmentLogBinding
        get() = checkNotNull(_binding)

    private lateinit var adapter: LogAdapter
    private var source = LogSource.DAEMON
    private var filter = LogFilter.ALL
    private var loadGeneration = 0
    private var lastSelectedAt = 0L
    private val entriesBySource = mutableMapOf<LogSource, List<LogEntry>>()
    private val loadedAtBySource = mutableMapOf<LogSource, Long>()

    private companion object {
        const val LOG_REFRESH_INTERVAL_MS = 5_000L
        val LOG_IO_EXECUTOR = Executors.newSingleThreadExecutor()
        val TAG_PATTERN = Regex("^\\[([^]]+)]\\s*")
        val NEUTRAL_COUNT_PATTERN = Regex(
            "(?:失败|错误|异常|无效规则|系统限制|抢写)[=:：]0|(?:已)?跳过[=:：]\\d+",
            RegexOption.IGNORE_CASE
        )
    }

    private enum class LogSource(val title: String) {
        DAEMON("守护进程"),
        FOREGROUND("前台助手")
    }

    private enum class LogFilter {
        ALL,
        ATTENTION,
        ERROR
    }

    private enum class LogLevel(val title: String) {
        INFO("信息"),
        SUCCESS("完成"),
        WARNING("提醒"),
        ERROR("错误")
    }

    private data class LogEntry(
        val id: Long,
        val lineNumber: Int,
        val tag: String,
        val level: LogLevel,
        val message: String,
        val copyText: String,
        val repeatCount: Int = 1
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prepareTopLevelPage(binding.logHeader)

        adapter = LogAdapter(::copyLogEntry)
        binding.logRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.logRecycler.adapter = adapter
        binding.logRecycler.itemAnimator = null

        setupSourceTabs()
        setupFilters()
        binding.logRefreshButton.setOnClickListener { loadLog(force = true) }
        binding.logRefresh.setOnRefreshListener { loadLog(force = true) }
        renderCurrentSource(loading = true)
    }

    override fun onTopLevelPageSelected() {
        if (_binding == null) return
        lastSelectedAt = SystemClock.elapsedRealtime()
        val lastLoaded = loadedAtBySource[source] ?: 0L
        loadLog(force = lastSelectedAt - lastLoaded >= LOG_REFRESH_INTERVAL_MS)
    }

    private fun setupSourceTabs() {
        val tabs = binding.logSourceTabs
        LogSource.entries.forEach { item ->
            tabs.addTab(tabs.newTab().setText(item.title), false)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val next = LogSource.entries.getOrElse(tab.position) { LogSource.DAEMON }
                if (source == next && entriesBySource.containsKey(next)) return
                source = next
                renderCurrentSource(loading = !entriesBySource.containsKey(next))
                val lastLoaded = loadedAtBySource[next] ?: 0L
                loadLog(force = SystemClock.elapsedRealtime() - lastLoaded >= LOG_REFRESH_INTERVAL_MS)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.logRecycler.smoothScrollToPosition(0)
            }
        })
        tabs.getTabAt(source.ordinal)?.select()
    }

    private fun setupFilters() {
        binding.logFilterGroup.check(R.id.logFilterAll)
        binding.logFilterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.logFilterAttention -> LogFilter.ATTENTION
                R.id.logFilterError -> LogFilter.ERROR
                else -> LogFilter.ALL
            }
            renderCurrentSource(loading = false)
        }
    }

    private fun loadLog(force: Boolean) {
        if (_binding == null) return
        if (!force && entriesBySource.containsKey(source)) {
            renderCurrentSource(loading = false)
            return
        }

        val requestedSource = source
        val generation = ++loadGeneration
        if (!entriesBySource.containsKey(requestedSource)) {
            renderCurrentSource(loading = true)
        } else {
            binding.logRefresh.isRefreshing = true
        }
        LOG_IO_EXECUTOR.execute {
            val text = runCatching {
                when (requestedSource) {
                    LogSource.DAEMON -> DaemonBridge.readDaemonLog()
                    LogSource.FOREGROUND -> DaemonBridge.readForegroundHelperLog()
                }
            }.onFailure {
                android.util.Log.e("AppOpt", "读取${requestedSource.title}日志失败", it)
            }.getOrDefault("")
            val entries = parseLog(requestedSource, text)
            runOnUiThread {
                if (_binding == null || generation != loadGeneration) return@runOnUiThread
                entriesBySource[requestedSource] = entries
                loadedAtBySource[requestedSource] = SystemClock.elapsedRealtime()
                if (source == requestedSource) renderCurrentSource(loading = false)
            }
        }
    }

    private fun renderCurrentSource(loading: Boolean) {
        if (_binding == null) return
        val all = entriesBySource[source].orEmpty()
        val warningCount = all.count { it.level == LogLevel.WARNING }
        val errorCount = all.count { it.level == LogLevel.ERROR }
        val attentionCount = warningCount + errorCount
        val visible = when (filter) {
            LogFilter.ALL -> all
            LogFilter.ATTENTION -> all.filter {
                it.level == LogLevel.WARNING || it.level == LogLevel.ERROR
            }
            LogFilter.ERROR -> all.filter { it.level == LogLevel.ERROR }
        }

        binding.logFilterAll.text = "全部 ${all.size}"
        binding.logFilterAttention.text = "提醒 $attentionCount"
        binding.logFilterError.text = "错误 $errorCount"
        val rawCount = all.sumOf(LogEntry::repeatCount)
        val mergedCount = rawCount - all.size
        binding.logSummary.text = if (all.isEmpty()) {
            "${source.title} · 最近 500 行"
        } else if (mergedCount > 0) {
            "${source.title} · ${all.size} 条事件 · 已合并 $mergedCount 条重复"
        } else {
            "${source.title} · ${all.size} 条 · 最新在前"
        }
        binding.logLoading.visibility = if (loading && all.isEmpty()) View.VISIBLE else View.GONE
        binding.logRefresh.isRefreshing = false
        binding.logEmpty.visibility = if (!loading && visible.isEmpty()) View.VISIBLE else View.GONE
        binding.logEmptyTitle.text = when {
            all.isEmpty() -> "暂无${source.title}日志"
            filter == LogFilter.ERROR -> "没有错误日志"
            else -> "没有需要提醒的日志"
        }
        binding.logEmptyDescription.text = when {
            all.isEmpty() -> "日志文件可能尚未生成，或当前没有 Root 读取权限"
            else -> "切换到“全部”可查看其余运行记录"
        }
        binding.logRecycler.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(visible) {
            if (visible.isNotEmpty() && _binding != null) {
                binding.logRecycler.scrollToPosition(0)
            }
        }
    }

    private fun parseLog(source: LogSource, text: String): List<LogEntry> {
        if (text.isBlank()) return emptyList()
        val blocks = mutableListOf<Pair<Int, String>>()
        var currentLine = 0
        var current = StringBuilder()

        fun flush() {
            if (current.isEmpty()) return
            blocks += currentLine to current.toString().trimEnd()
            current = StringBuilder()
        }

        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val continuation = current.isNotEmpty() && (
                raw.firstOrNull()?.isWhitespace() == true ||
                    raw.startsWith("at ") ||
                    raw.startsWith("Caused by:") ||
                    raw.startsWith("Suppressed:")
                )
            if (continuation) {
                current.append('\n').append(raw.trimEnd())
            } else {
                flush()
                currentLine = index + 1
                current.append(raw.trim())
            }
        }
        flush()

        val chronological = blocks.map { (lineNumber, block) ->
            val match = TAG_PATTERN.find(block)
            val rawTag = match?.groupValues?.getOrNull(1).orEmpty()
            val tag = displayTag(source, rawTag)
            val message = if (match != null) {
                block.removeRange(match.range).trimStart()
            } else {
                block
            }
            LogEntry(
                id = (source.ordinal.toLong() shl 32) or lineNumber.toLong(),
                lineNumber = lineNumber,
                tag = tag,
                level = classifyLevel(block),
                message = message,
                copyText = block
            )
        }
        val merged = mutableListOf<LogEntry>()
        for (entry in chronological) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.copyText == entry.copyText) {
                merged[merged.lastIndex] = previous.copy(
                    id = entry.id,
                    lineNumber = entry.lineNumber,
                    repeatCount = previous.repeatCount + 1
                )
            } else {
                merged += entry
            }
        }
        return merged.asReversed()
    }

    private fun displayTag(source: LogSource, rawTag: String): String {
        return when (rawTag.lowercase()) {
            "rs" -> "Rust"
            "calib" -> "校准"
            "fps" -> "帧率"
            "boost" -> "性能增强"
            "ctrl" -> "控制"
            "前台助手" -> "前台助手"
            "" -> if (source == LogSource.DAEMON) "守护进程" else "前台助手"
            else -> rawTag.take(12)
        }
    }

    private fun classifyLevel(text: String): LogLevel {
        val normalized = NEUTRAL_COUNT_PATTERN.replace(text, "").lowercase()
        return when {
            listOf("fatal", "panic", "失败", "错误", "异常", "无法", "崩溃", "not attached")
                .any(normalized::contains) -> LogLevel.ERROR
            listOf(
                "警告", "降级", "超时", "未检测", "未找到", "未命中", "未更新",
                "缺少", "重试", "已停用", "不可用"
            )
                .any(normalized::contains) -> LogLevel.WARNING
            listOf("成功", "完成", "已启动", "已加载", "已激活", "已确认", "已更新", "已监听")
                .any(normalized::contains) -> LogLevel.SUCCESS
            else -> LogLevel.INFO
        }
    }

    private fun copyLogEntry(entry: LogEntry) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AppOpt 日志", entry.copyText))
        AppToast.show(requireContext(), "已复制这条日志")
    }

    override fun onDestroyView() {
        loadGeneration++
        binding.logRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private inner class LogAdapter(
        private val onCopy: (LogEntry) -> Unit
    ) : ListAdapter<LogEntry, LogAdapter.Holder>(LogDiff) {
        private val expandedIds = mutableSetOf<Long>()

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemLogEntryBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        private inner class Holder(
            private val item: ItemLogEntryBinding
        ) : RecyclerView.ViewHolder(item.root) {

            fun bind(entry: LogEntry) {
                val colorRes = when (entry.level) {
                    LogLevel.ERROR -> R.color.log_error
                    LogLevel.WARNING -> R.color.log_warning
                    LogLevel.SUCCESS -> R.color.log_success
                    LogLevel.INFO -> R.color.log_info
                }
                val softColorRes = when (entry.level) {
                    LogLevel.ERROR -> R.color.log_error_soft
                    LogLevel.WARNING -> R.color.log_warning_soft
                    LogLevel.SUCCESS -> R.color.log_success_soft
                    LogLevel.INFO -> R.color.log_info_soft
                }
                val color = ContextCompat.getColor(requireContext(), colorRes)
                val softColor = ContextCompat.getColor(requireContext(), softColorRes)
                item.logAccent.backgroundTintList = ColorStateList.valueOf(color)
                item.logTag.backgroundTintList = ColorStateList.valueOf(softColor)
                item.logTag.setTextColor(color)
                item.logTag.text = entry.tag
                item.logMeta.text = buildString {
                    append("L${entry.lineNumber} · ${entry.level.title}")
                    if (entry.repeatCount > 1) append(" · 重复 ${entry.repeatCount} 次")
                }
                item.logMessage.text = entry.message

                val expandable = entry.message.length > 240 || entry.message.count { it == '\n' } >= 4
                val expanded = entry.id in expandedIds
                item.logMessage.maxLines = if (expanded || !expandable) Int.MAX_VALUE else 5
                item.logExpand.visibility = if (expandable) View.VISIBLE else View.GONE
                item.logExpand.rotation = if (expanded) 180f else 0f
                item.root.setOnClickListener {
                    if (!expandable) return@setOnClickListener
                    if (!expandedIds.add(entry.id)) expandedIds.remove(entry.id)
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
                }
                item.root.setOnLongClickListener {
                    onCopy(entry)
                    true
                }
            }
        }
    }

    private object LogDiff : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
            oldItem == newItem
    }
}
