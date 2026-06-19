package top.suto.appopt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityMainBinding
import top.suto.appopt.databinding.DialogConfigRulesBinding
import top.suto.appopt.databinding.DialogDeleteConfigBinding
import top.suto.appopt.databinding.ItemAddAppBinding
import top.suto.appopt.databinding.ItemAutoAppBinding
import top.suto.appopt.databinding.ItemConfiguredAppBinding

/**
 * 引导授予悬浮窗权限, 并列出配置文件中写为 "包名=auto" 的应用。
 * 每个应用项显示图标/名称, 提供「启动」(拉起目标 App 并显示悬浮球) 与「历史」两项操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRoot = false
    private var daemonRunning = false
    private var appTab = AppTab.PENDING
    private var appSearchQuery = ""
    private var appLists = AppLists()
    private var addableAppsLoading = true
    private var hideMissingConfigured = false
    private var appSearchRender: Runnable? = null
    private var processNames: Set<String> = emptySet()
    private val iconCache = LruCache<String, Drawable>(768)
    private lateinit var appAdapter: AppAdapter

    private companion object {
        const val PREFS_NAME = "appopt_prefs"
        const val PREF_HIDE_MISSING_CONFIGURED = "hide_missing_configured"
    }

    private enum class AppTab(val title: String) {
        PENDING("待校准"),
        ADD("添加应用"),
        CONFIGURED("已配置应用")
    }

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val installed: Boolean,
        val component: ComponentKind,
        val installTime: Long,
        val configPkgs: List<String>
    )

    private enum class ComponentKind {
        APP,
        SYSTEM_COMPONENT,
        MISSING_APP
    }

    private data class AppLists(
        val pending: List<AppEntry> = emptyList(),
        val addable: List<AppEntry> = emptyList(),
        val configured: List<AppEntry> = emptyList()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideMissingConfigured = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HIDE_MISSING_CONFIGURED, false)

        // 启动文件监听服务 (监听 .log 变化自动导入数据库)
        startService(Intent(this, FileWatcherService::class.java))

        binding.statusSection.btnOverlay.setOnClickListener { requestOverlay() }
        binding.statusSection.btnUsage.setOnClickListener { requestUsageAccess() }
        binding.statusSection.btnHelp.setOnClickListener { showHelp() }
        binding.btnHistoryTop.setOnClickListener {
            startActivity(Intent(this, HistoryListActivity::class.java))
        }
        binding.btnLogTop.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.appRefresh.setOnRefreshListener {
            refreshAppList()
        }
        setupAppTabs()
        setupAppSearch()
        setupConfiguredFilter()
        setupAppRecycler()

        // root 检测 + 读配置 + 批量导入旧 .log 放后台线程, 避免 su 弹窗阻塞 UI
        thread {
            val r = DaemonBridge.hasRoot()
            val running = if (r) DaemonBridge.isDaemonRunning() else false
            val config = if (r) ConfigReader.readPackages() else ConfigReader.ConfigPackages(emptyList(), emptyList())
            val resolvedNames = resolveProcessComponentNames(config, r)
            val visibleLists = buildConfiguredLists(config, resolvedNames)

            runOnUiThread {
                hasRoot = r
                daemonRunning = running
                processNames = resolvedNames
                appLists = visibleLists
                refresh()
                buildAppList()
            }

            // 首次启动/旧版升级时批量导入所有 .log 到数据库(epoch 去重,只导入新数据)
            if (r && config.autoPackages.isNotEmpty()) {
                android.util.Log.d("AppOpt", "启动时批量导入 ${config.autoPackages.size} 个应用的 .log")
                for (pkg in config.autoPackages) {
                    try {
                        DatabaseMigrator.migrateIfNeeded(this, pkg)
                    } catch (e: Exception) {
                        android.util.Log.e("AppOpt", "导入 $pkg 失败: ${e.message}")
                    }
                }
                android.util.Log.d("AppOpt", "批量导入完成")
            }

            val fullLists = buildAppLists(config, resolvedNames)
            runOnUiThread {
                addableAppsLoading = false
                appLists = fullLists
                buildAppList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 守护进程可能在后台被重启/杀掉, 回到前台时后台重查一次(有 root 才有意义)
        if (hasRoot) thread {
            val running = DaemonBridge.isDaemonRunning()
            runOnUiThread {
                daemonRunning = running
                refresh()
            }
        }
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun refresh() {
        val overlay = hasOverlay()
        val s = binding.statusSection
        s.btnOverlay.visibility = if (overlay) View.GONE else View.VISIBLE
        s.overlayState.text = if (overlay) "已授予" else "未授予"
        setDot(s.dotOverlay, if (overlay) R.color.status_ok else R.color.status_warn)

        val usage = ForegroundDetector.hasUsageAccess(this)
        s.btnUsage.visibility = if (usage) View.GONE else View.VISIBLE
        s.usageState.text = if (usage) "已授予" else "未授予"
        setDot(s.dotUsage, if (usage) R.color.status_ok else R.color.status_warn)

        s.rootState.text = if (hasRoot) "可用" else "不可用"
        setDot(s.dotRoot, if (hasRoot) R.color.status_ok else R.color.status_off)

        // 守护进程: 无 root 时无从判断, 显示"未知"(灰); 有 root 时按 pgrep 结果显示
        when {
            !hasRoot -> {
                s.daemonState.text = "未知"
                setDot(s.dotDaemon, R.color.status_off)
            }
            daemonRunning -> {
                s.daemonState.text = "运行中"
                setDot(s.dotDaemon, R.color.status_ok)
            }
            else -> {
                s.daemonState.text = "未运行"
                setDot(s.dotDaemon, R.color.status_warn)
            }
        }
    }

    private fun setDot(dot: View, colorRes: Int) {
        dot.background?.mutate()?.setTint(ContextCompat.getColor(this, colorRes))
    }

    private fun setupAppTabs() {
        val tabs = binding.appSection.appTabs
        AppTab.values().forEach { tabs.addTab(tabs.newTab().setText(it.title)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                appTab = AppTab.values().getOrElse(tab.position) { AppTab.PENDING }
                buildAppList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupAppSearch() {
        binding.appSection.appSearchBox.visibility = View.GONE
        binding.appSection.appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSearchQuery = s?.toString().orEmpty().trim()
                if (appTab == AppTab.ADD) {
                    appSearchRender?.let { binding.appSection.appRecycler.removeCallbacks(it) }
                    appSearchRender = Runnable { buildAppList() }
                    binding.appSection.appRecycler.postDelayed(appSearchRender, 180)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupConfiguredFilter() {
        val section = binding.appSection
        section.hideMissingConfiguredSwitch.isChecked = hideMissingConfigured
        section.hideMissingConfiguredSwitch.setOnCheckedChangeListener { _, checked ->
            hideMissingConfigured = checked
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIDE_MISSING_CONFIGURED, checked)
                .apply()
            if (appTab == AppTab.CONFIGURED) buildAppList()
        }
        section.configuredFilterRow.setOnClickListener {
            section.hideMissingConfiguredSwitch.toggle()
        }
    }

    private fun setupAppRecycler() {
        appAdapter = AppAdapter()
        binding.appSection.appRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            itemAnimator = null
        }
    }

    /** 下拉刷新: 重新读取配置文件并更新应用列表 */
    private fun refreshAppList() {
        val previousAddable = appLists.addable
        addableAppsLoading = true
        if (appTab == AppTab.ADD && previousAddable.isEmpty()) {
            buildAppList()
        }
        thread {
            val config = if (hasRoot) ConfigReader.readPackages() else ConfigReader.ConfigPackages(emptyList(), emptyList())
            val resolvedNames = resolveProcessComponentNames(config, hasRoot)
            val visibleLists = buildConfiguredLists(config, resolvedNames).copy(addable = previousAddable)
            runOnUiThread {
                processNames = resolvedNames
                appLists = visibleLists
                buildAppList()
            }

            val fullLists = buildAppLists(config, resolvedNames)
            runOnUiThread {
                addableAppsLoading = false
                appLists = fullLists
                buildAppList()
                binding.appRefresh.isRefreshing = false
            }
        }
    }

    private fun buildAppList() {
        val a = binding.appSection
        val entries = entriesForCurrentTab()
        a.appTitle.text = appTab.title
        a.appSearchBox.visibility = if (appTab == AppTab.ADD) View.VISIBLE else View.GONE
        a.configuredFilterRow.visibility = if (appTab == AppTab.CONFIGURED) View.VISIBLE else View.GONE
        a.appCount.text = if (entries.isEmpty()) "" else "${entries.size} 个"
        a.appRecycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) {
            a.emptyState.visibility = View.VISIBLE
            bindEmptyState()
            appAdapter.submit(appTab, emptyList())
            return
        }
        a.emptyState.visibility = View.GONE
        appAdapter.submit(appTab, entries)
    }

    private fun bindEmptyState() {
        val a = binding.appSection
        val state = emptyStateForCurrentTab()
        a.emptyIcon.setImageResource(state.iconRes)
        a.emptyTitle.text = state.title
        a.emptyDesc.text = state.desc
    }

    private fun bindAppItem(item: ItemAutoAppBinding, entry: AppEntry, mode: AppTab) {
        item.itemName.text = entry.label
        item.itemPkg.text = when (entry.component) {
            ComponentKind.APP -> entry.pkg
            ComponentKind.SYSTEM_COMPONENT -> "${entry.pkg} · 系统组件"
            ComponentKind.MISSING_APP -> "${entry.pkg} · 未安装/配置残留"
        }
        item.itemIcon.setImageDrawable(
            iconForEntry(entry)
        )
        item.btnStart.isEnabled = entry.installed
        item.btnStart.alpha = if (entry.installed) 1f else 0.42f
        item.btnStart.contentDescription = if (entry.installed) "启动校准" else "未安装，无法启动"
        item.btnStart.setOnClickListener {
            if (entry.installed) startAppWithBall(entry.pkg)
        }
        item.btnDelete.isEnabled = hasRoot
        item.btnDelete.alpha = if (hasRoot) 1f else 0.42f
        item.btnDelete.setOnClickListener { confirmDeleteConfig(entry.pkg) }
    }

    private fun bindAddAppItem(item: ItemAddAppBinding, entry: AppEntry) {
        item.addName.text = entry.label
        item.addPkg.text = entry.pkg
        item.addIcon.setImageDrawable(
            iconForEntry(entry)
        )
        item.btnAdd.isEnabled = hasRoot
        item.btnAdd.setOnClickListener { addAutoConfig(entry.pkg) }
    }

    private fun bindConfiguredAppItem(item: ItemConfiguredAppBinding, entry: AppEntry) {
        item.configName.text = if (entry.installed) entry.label else entry.pkg
        item.configPkg.text = when (entry.component) {
            ComponentKind.APP -> if (entry.configPkgs.size > 1) {
                "${entry.pkg} · ${entry.configPkgs.size} 组配置"
            } else {
                entry.pkg
            }
            ComponentKind.SYSTEM_COMPONENT -> "系统组件"
            ComponentKind.MISSING_APP -> "未安装/配置残留"
        }
        item.configIcon.setImageDrawable(
            iconForEntry(entry)
        )
        item.btnView.isEnabled = hasRoot
        item.btnRemove.isEnabled = hasRoot
        item.btnView.setOnClickListener { showConfiguredRules(entry) }
        item.btnRemove.setOnClickListener { confirmDeleteConfig(entry) }
    }

    private fun iconForEntry(entry: AppEntry): Drawable? {
        val key = "v2:${entry.component}:${entry.pkg}"
        iconCache.get(key)?.let { return it }
        val raw = when (entry.component) {
            ComponentKind.SYSTEM_COMPONENT -> ContextCompat.getDrawable(this, R.drawable.ic_linux)
            ComponentKind.MISSING_APP -> ContextCompat.getDrawable(this, R.drawable.ic_missing_app)
            else -> try {
                packageManager.getApplicationIcon(packageLookupName(entry.pkg))
            } catch (_: PackageManager.NameNotFoundException) {
                ContextCompat.getDrawable(this, R.drawable.ic_missing_app)
            }
        }
        val rounded = raw?.let { makeRoundIcon(it.mutate()) }
        if (rounded != null) iconCache.put(key, rounded)
        return rounded
    }

    private fun makeRoundIcon(source: Drawable): Drawable {
        val size = dp(42f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.surface_app)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val clip = Path().apply {
            addCircle(radius, radius, radius - dp(0.5f), Path.Direction.CW)
        }
        val saved = canvas.save()
        canvas.clipPath(clip)
        drawDrawableCoveringCircle(canvas, source, size)
        canvas.restoreToCount(saved)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22000000
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
        }
        canvas.drawCircle(radius, radius, radius - strokePaint.strokeWidth / 2f, strokePaint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun drawDrawableCoveringCircle(canvas: Canvas, source: Drawable, size: Int) {
        val tempSize = size * 3
        val temp = Bitmap.createBitmap(tempSize, tempSize, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(temp)
        val oldBounds = Rect(source.bounds)
        source.setBounds(0, 0, tempSize, tempSize)
        source.draw(tempCanvas)
        source.setBounds(oldBounds)

        val content = findOpaqueBounds(temp)
        if (content == null) {
            canvas.drawBitmap(temp, null, Rect(0, 0, size, size), null)
            temp.recycle()
            return
        }

        val target = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val scale = maxOf(
            target.width() / content.width(),
            target.height() / content.height()
        ) * 1.08f
        val drawW = content.width() * scale
        val drawH = content.height() * scale
        val left = (size - drawW) / 2f
        val top = (size - drawH) / 2f
        canvas.drawBitmap(temp, content, RectF(left, top, left + drawW, top + drawH), null)
        temp.recycle()
    }

    private fun findOpaqueBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val alphaThreshold = 8

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] ushr 24) > alphaThreshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun prewarmIcons(lists: AppLists) {
        prewarmIcons(lists.pending)
        prewarmIcons(lists.addable)
        prewarmIcons(lists.configured)
    }

    private fun prewarmIcons(entries: List<AppEntry>) {
        for (entry in entries) iconForEntry(entry)
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private inner class AppAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val viewTypeAdd = 1
        private val viewTypeNormal = 2
        private val viewTypeConfigured = 3
        private var mode = AppTab.PENDING
        private var items: List<AppEntry> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(newMode: AppTab, newItems: List<AppEntry>) {
            mode = newMode
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (mode) {
                AppTab.ADD -> viewTypeAdd
                AppTab.CONFIGURED -> viewTypeConfigured
                AppTab.PENDING -> viewTypeNormal
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                viewTypeAdd -> AddHolder(ItemAddAppBinding.inflate(layoutInflater, parent, false))
                viewTypeConfigured -> ConfiguredHolder(ItemConfiguredAppBinding.inflate(layoutInflater, parent, false))
                else -> NormalHolder(ItemAutoAppBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = items[position]
            when (holder) {
                is AddHolder -> bindAddAppItem(holder.binding, entry)
                is ConfiguredHolder -> bindConfiguredAppItem(holder.binding, entry)
                is NormalHolder -> bindAppItem(holder.binding, entry, mode)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long {
            val entry = items[position]
            return 31L * mode.ordinal + entry.pkg.hashCode().toLong()
        }

        inner class AddHolder(val binding: ItemAddAppBinding) : RecyclerView.ViewHolder(binding.root)
        inner class ConfiguredHolder(val binding: ItemConfiguredAppBinding) : RecyclerView.ViewHolder(binding.root)
        inner class NormalHolder(val binding: ItemAutoAppBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun entriesForCurrentTab(): List<AppEntry> = when (appTab) {
        AppTab.PENDING -> appLists.pending
        AppTab.ADD -> filteredAddableApps()
        AppTab.CONFIGURED -> filteredConfiguredApps()
    }

    private fun filteredConfiguredApps(): List<AppEntry> {
        if (!hideMissingConfigured) return appLists.configured
        return appLists.configured.filter { it.component != ComponentKind.MISSING_APP }
    }

    private fun filteredAddableApps(): List<AppEntry> {
        val q = appSearchQuery.lowercase()
        if (q.isBlank()) return appLists.addable
        return appLists.addable.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    private data class EmptyState(
        val iconRes: Int,
        val title: String,
        val desc: String
    )

    private fun emptyStateForCurrentTab(): EmptyState = when (appTab) {
        AppTab.PENDING -> EmptyState(
            R.drawable.ic_empty_pending,
            "暂无待校准应用",
            "可在「添加应用」中选择应用写入 auto 配置"
        )
        AppTab.ADD -> when {
            addableAppsLoading -> EmptyState(
                R.drawable.ic_empty_add,
                "正在加载应用",
                "应用较多时需要稍等片刻"
            )
            appSearchQuery.isBlank() -> EmptyState(
                R.drawable.ic_empty_add,
                "未发现可添加的应用",
                "已配置的应用不会重复显示"
            )
            else -> EmptyState(
                R.drawable.ic_empty_add,
                "没有匹配的应用",
                "试试应用名称或包名里的其他关键词"
            )
        }
        AppTab.CONFIGURED -> if (hideMissingConfigured && appLists.configured.isNotEmpty()) {
            EmptyState(
                R.drawable.ic_empty_configured,
                "未安装应用已隐藏",
                "关闭「隐藏未安装应用」可查看配置残留项"
            )
        } else {
            EmptyState(
                R.drawable.ic_empty_configured,
                "未发现已配置应用",
                "完成 auto 校准后会在这里显示生成规则的应用"
            )
        }
    }

    private fun buildAppLists(
        config: ConfigReader.ConfigPackages,
        names: Set<String> = processNames
    ): AppLists {
        val base = buildConfiguredLists(config, names)
        val configuredSet = (config.autoPackages + config.configuredPackages)
            .flatMap { listOf(it, configOwnerName(it), packageLookupName(it)) }
            .toHashSet()
        val installed = installedLaunchableApps()
            .filter { it.pkg !in configuredSet }
            .sortedByInstallTime()
        val lists = base.copy(addable = installed)
        prewarmIcons(lists.addable)
        return lists
    }

    private fun buildConfiguredLists(
        config: ConfigReader.ConfigPackages,
        names: Set<String> = processNames
    ): AppLists {
        val pending = config.autoPackages
            .map { appEntry(it, names) }
            .sortedByInstallTime()
        val configuredGroups = LinkedHashMap<String, LinkedHashSet<String>>()
        for (pkg in config.configuredPackages) {
            configuredGroups.getOrPut(configOwnerName(pkg)) { LinkedHashSet() }.add(pkg)
        }
        val configured = configuredGroups
            .map { (pkg, configPkgs) -> appEntry(pkg, names, configPkgs.toList()) }
            .sortedByInstallTime()
        val lists = AppLists(
            pending = pending,
            configured = configured
        )
        prewarmIcons(lists.pending)
        prewarmIcons(lists.configured)
        return lists
    }

    private fun installedLaunchableApps(): List<AppEntry> {
        val result = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(intent, 0)
        for (ri in activities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            result.add(appEntry(pkg))
        }
        return result
    }

    private fun appEntry(
        pkg: String,
        names: Set<String> = processNames,
        configPkgs: List<String> = listOf(pkg)
    ): AppEntry {
        val installed = isInstalled(pkg)
        return AppEntry(
            pkg = pkg,
            label = appLabel(pkg),
            installed = installed,
            component = componentKind(pkg, installed, names),
            installTime = installTime(pkg),
            configPkgs = configPkgs.distinct()
        )
    }

    private fun componentKind(pkg: String, installed: Boolean, names: Set<String>): ComponentKind {
        if (installed) return ComponentKind.APP
        return if (pkg in names) {
            ComponentKind.SYSTEM_COMPONENT
        } else {
            ComponentKind.MISSING_APP
        }
    }

    private fun resolveProcessComponentNames(
        config: ConfigReader.ConfigPackages,
        canQuery: Boolean = hasRoot
    ): Set<String> {
        if (!canQuery) return emptySet()
        val candidates = (config.autoPackages + config.configuredPackages)
            .distinct()
            .filterNot { isInstalled(it) }
        return DaemonBridge.findRunningProcessNames(candidates)
    }

    private fun List<AppEntry>.sortedByInstallTime(): List<AppEntry> {
        return sortedWith(
            compareByDescending<AppEntry> { it.installTime }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun installTime(pkg: String): Long = try {
        packageManager.getPackageInfo(packageLookupName(pkg), 0).firstInstallTime
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    private fun isInstalled(pkg: String): Boolean {
        return isExactPackageInstalled(packageLookupName(pkg))
    }

    private fun isExactPackageInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun packageLookupName(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.isNotBlank() && isExactPackageInstalled(base)) {
            base
        } else {
            pkg
        }
    }

    private fun configOwnerName(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.contains('.')) base else pkg
    }

    /** 包名 -> 应用显示名; 未安装则回退为包名 */
    private fun appLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageLookupName(pkg), 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** 跳转系统「使用情况访问」设置页, 让用户为本应用授予 PACKAGE_USAGE_STATS */
    private fun requestUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // 部分 ROM 支持直接定位到本应用条目; 失败则回退到列表页
        try {
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                toast("请在系统设置 > 使用情况访问 中授予本应用权限")
            }
        }
    }

    private fun showHelp() {
        val view = layoutInflater.inflate(R.layout.section_help, null, false)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.show()
    }

    /** 启动目标应用并显示悬浮球, 把目标包名传给服务用于校准 */
    private fun startAppWithBall(pkg: String) {
        if (!hasOverlay()) {
            toast("请先授予悬浮窗权限")
            refresh()
            return
        }
        // 先拉起悬浮球服务 (带目标包名)
        val svc = Intent(this, FloatingBallService::class.java)
            .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
        startForegroundService(svc)

        // 再拉起目标应用
        val launchPkg = packageLookupName(pkg)
        val launch = packageManager.getLaunchIntentForPackage(launchPkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            toast("未找到 $launchPkg 的启动入口, 请手动进入应用")
            moveTaskToBack(true)
        }
    }

    /** 把未配置应用写入 applist.conf, 形式为 "包名=auto" */
    private fun addAutoConfig(pkg: String) {
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        thread {
            val ok = DaemonBridge.addAutoPackage(pkg)
            val config = if (ok) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, hasRoot) } ?: processNames
            val visibleLists = config?.let { buildConfiguredLists(it, resolvedNames) } ?: appLists
            runOnUiThread {
                if (ok) {
                    processNames = resolvedNames
                    appLists = visibleLists
                    appTab = AppTab.PENDING
                    binding.appSection.appTabs.getTabAt(AppTab.PENDING.ordinal)?.select()
                    buildAppList()
                    toast("已添加到待校准")
                } else {
                    toast("添加配置失败，请检查 Root 或模块权限")
                }
            }
            if (ok && config != null) {
                val fullLists = buildAppLists(config, resolvedNames)
                runOnUiThread {
                    appLists = fullLists
                    buildAppList()
                }
            }
        }
    }

    /** 删除前展示该包名当前配置规则, 确认后再删除 */
    private fun confirmDeleteConfig(entry: AppEntry) {
        confirmDeleteConfig(entry.pkg, entry.configPkgs)
    }

    private fun confirmDeleteConfig(pkg: String) {
        confirmDeleteConfig(pkg, listOf(pkg))
    }

    private fun confirmDeleteConfig(displayPkg: String, configPkgs: List<String>) {
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        val targets = configPkgs.distinct()
        thread {
            val rules = targets.flatMap { DaemonBridge.readPkgConfigLines(it) }
            runOnUiThread {
                val view = DialogDeleteConfigBinding.inflate(layoutInflater)
                val dialog = BottomSheetDialog(this)
                view.deleteTitle.text = "删除 ${appLabel(displayPkg)}"
                view.deletePkg.text = targets.joinToString("\n")
                view.deleteRules.text = if (rules.isEmpty()) {
                    "未读取到当前规则；确认后仍会删除这些配置项在 applist.conf 中的所有配置行。"
                } else {
                    rules.joinToString("\n")
                }
                view.deleteCancel.setOnClickListener { dialog.dismiss() }
                view.deleteConfirm.setOnClickListener {
                    dialog.dismiss()
                    deleteConfig(targets)
                }
                dialog.setContentView(view.root)
                dialog.show()
            }
        }
    }

    /** 从 applist.conf 删除该包名的所有配置行 */
    private fun deleteConfig(pkg: String) {
        deleteConfig(listOf(pkg))
    }

    private fun deleteConfig(pkgs: List<String>) {
        thread {
            val ok = DaemonBridge.deleteConfigPackages(pkgs)
            val config = if (ok) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, hasRoot) } ?: processNames
            val visibleLists = config?.let { buildConfiguredLists(it, resolvedNames) } ?: appLists
            runOnUiThread {
                if (ok) {
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                    toast("已删除配置")
                } else {
                    toast("删除配置失败，请检查 Root 或模块权限")
                }
            }
            if (ok && config != null) {
                val fullLists = buildAppLists(config, resolvedNames)
                runOnUiThread {
                    appLists = fullLists
                    buildAppList()
                }
            }
        }
    }

    /** 查看已配置应用当前生效规则 */
    private fun showConfiguredRules(entry: AppEntry) {
        showConfiguredRules(entry.pkg, entry.configPkgs)
    }

    private fun showConfiguredRules(pkg: String) {
        showConfiguredRules(pkg, listOf(pkg))
    }

    private fun showConfiguredRules(displayPkg: String, configPkgs: List<String>) {
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        val targets = configPkgs.distinct()
        thread {
            val rules = targets.flatMap { DaemonBridge.readPkgRules(it) }
            runOnUiThread {
                val message = if (rules.isEmpty()) {
                    "未读取到 ${targets.joinToString(", ")} 的配置规则"
                } else {
                    rules.joinToString("\n")
                }
                val view = DialogConfigRulesBinding.inflate(layoutInflater)
                view.rulesTitle.text = appLabel(displayPkg)
                view.rulesPkg.text = targets.joinToString("\n")
                view.rulesCount.text = if (rules.isEmpty()) "暂无规则" else "${rules.size} 条规则"
                view.rulesText.text = message
                val dialog = BottomSheetDialog(this)
                dialog.setContentView(view.root)
                view.rulesClose.setOnClickListener { dialog.dismiss() }
                dialog.show()
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
