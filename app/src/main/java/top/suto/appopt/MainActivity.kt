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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityMainBinding
import top.suto.appopt.databinding.DialogConfigRulesBinding
import top.suto.appopt.databinding.DialogDeleteConfigBinding
import top.suto.appopt.databinding.ItemAddAppBinding
import top.suto.appopt.databinding.ItemAutoAppBinding
import top.suto.appopt.databinding.ItemConfiguredAppBinding
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 引导授予悬浮窗权限, 并列出配置文件中写为 "包名=auto" 的应用。
 * 每个应用项显示图标/名称, 提供「启动」(拉起目标 App 并显示悬浮球) 与「历史」两项操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRoot = false
    private var daemonRunning = false
    private var moduleVersion: DaemonBridge.ModuleVersion? = null
    private var moduleCompatible = false
    private var pendingModuleUpdate = false
    private var moduleWarningShown = false
    private var appTab = AppTab.PENDING
    private var appSearchQuery = ""
    private var appLists = AppLists()
    private var environmentLoading = true
    private var appListsLoading = true
    private var addableAppsLoading = true
    private var hideMissingConfigured = false
    private var appSearchRender: Runnable? = null
    private var processNames: Set<String> = emptySet()
    private val iconCache = LruCache<String, Drawable>(768)
    private val pendingIconLoads = Collections.synchronizedSet(mutableSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var activityDestroyed = false
    private var firstResume = true
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

        binding.statusSection.btnOverlay.setOnClickListener { requestOverlay() }
        binding.statusSection.btnUsage.setOnClickListener { requestUsageAccess() }
        binding.statusSection.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.appRefresh.setOnRefreshListener {
            refreshAppList()
        }
        setupAppTabs()
        setupAppSearch()
        setupConfiguredFilter()
        setupAppRecycler()
        refresh()
        buildAppList()

        // root 检测 + 读配置 + 批量导入旧 .log 放后台线程, 避免 su 弹窗阻塞 UI
        thread {
            val r = DaemonBridge.hasRoot()
            val pendingUpdate = if (r) DaemonBridge.hasPendingModuleUpdate() else false
            val version = if (r && !pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = isCompatibleModule(version)
            val running = if (r && compatible && !pendingUpdate) DaemonBridge.isDaemonRunning() else false
            val enabled = r && compatible && running
            val config = if (enabled) ConfigReader.readPackages() else ConfigReader.ConfigPackages(emptyList(), emptyList())
            val resolvedNames = resolveProcessComponentNames(config, enabled)
            val visibleLists = if (enabled) buildConfiguredLists(config, resolvedNames) else AppLists()

            runOnUiThreadIfAlive {
                hasRoot = r
                pendingModuleUpdate = pendingUpdate
                moduleVersion = version
                moduleCompatible = compatible
                daemonRunning = running
                environmentLoading = false
                appListsLoading = false
                processNames = resolvedNames
                appLists = visibleLists
                refresh()
                buildAppList()
                showModuleWarningIfNeeded()
            }

            val fullLists = if (enabled) buildAppLists(config, resolvedNames) else AppLists()
            runOnUiThreadIfAlive {
                addableAppsLoading = false
                appLists = fullLists
                buildAppList()
            }

            migrateLogsLater(enabled, config)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        val shouldRefreshConfig = firstResume.not()
        firstResume = false
        // 守护进程和配置可能在后台变化, 回到前台时后台重查一次(有 root 才有意义)
        if (hasRoot) refreshForegroundState(shouldRefreshConfig)
    }

    private fun refreshForegroundState(refreshConfig: Boolean) {
        val previousAddable = appLists.addable
        thread {
            val pendingUpdate = DaemonBridge.hasPendingModuleUpdate()
            val version = if (!pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = isCompatibleModule(version)
            val running = if (compatible && !pendingUpdate) DaemonBridge.isDaemonRunning() else false
            val enabled = compatible && running
            val config = if (enabled && refreshConfig) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, enabled) } ?: processNames
            val visibleLists = when {
                !enabled -> AppLists()
                config != null -> buildConfiguredLists(config, resolvedNames).copy(addable = previousAddable)
                else -> null
            }
            runOnUiThreadIfAlive {
                pendingModuleUpdate = pendingUpdate
                moduleVersion = version
                moduleCompatible = compatible
                daemonRunning = running
                if (visibleLists != null) {
                    appListsLoading = false
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                }
                refresh()
                if (!enabled) buildAppList()
                showModuleWarningIfNeeded()
            }
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        appSearchRender?.let { binding.appSection.appRecycler.removeCallbacks(it) }
        pendingIconLoads.clear()
        iconExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun runOnUiThreadIfAlive(action: () -> Unit) {
        runOnUiThread {
            if (!activityDestroyed && !isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun isCompatibleModule(version: DaemonBridge.ModuleVersion?): Boolean {
        return version?.versionCode?.let { it >= DaemonBridge.REQUIRED_MODULE_VERSION_CODE } == true
    }

    private fun canUseModuleFeatures(): Boolean {
        return hasRoot && !pendingModuleUpdate && moduleCompatible && daemonRunning
    }

    private fun moduleVersionLabel(): String {
        val version = moduleVersion ?: return "未检测到"
        return "${version.versionName} (${version.versionCode})"
    }

    private fun showModuleWarningIfNeeded() {
        if (moduleWarningShown || environmentLoading || !hasRoot || pendingModuleUpdate || moduleCompatible) return
        moduleWarningShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("模块版本不兼容")
            .setMessage(
                "当前模块版本：${moduleVersionLabel()}\n\n" +
                    "请刷入 v${DaemonBridge.REQUIRED_MODULE_VERSION_NAME} " +
                    "(${DaemonBridge.REQUIRED_MODULE_VERSION_CODE}) 或更高版本模块后重启。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun refresh() {
        val overlay = hasOverlay()
        val s = binding.statusSection
        s.btnSettings.visibility = if (!environmentLoading && hasRoot && (pendingModuleUpdate || !moduleCompatible)) {
            View.GONE
        } else {
            View.VISIBLE
        }
        s.btnOverlay.visibility = if (overlay) View.GONE else View.VISIBLE
        s.overlayState.text = if (overlay) "已授予" else "未授予"
        setDot(s.dotOverlay, if (overlay) R.color.status_ok else R.color.status_warn)

        val usage = ForegroundDetector.hasUsageAccess(this)
        s.btnUsage.visibility = if (usage) View.GONE else View.VISIBLE
        s.usageState.text = if (usage) "已授予" else "未授予"
        setDot(s.dotUsage, if (usage) R.color.status_ok else R.color.status_warn)

        if (environmentLoading) {
            s.rootState.text = "检查中"
            setDot(s.dotRoot, R.color.status_warn)
            s.daemonState.text = "检查中"
            setDot(s.dotDaemon, R.color.status_warn)
            return
        }

        s.rootState.text = if (hasRoot) "可用" else "不可用"
        setDot(s.dotRoot, if (hasRoot) R.color.status_ok else R.color.status_off)

        // 守护进程: 无 root 时无从判断, 显示"未知"(灰); 有 root 时按 pgrep 结果显示
        when {
            !hasRoot -> {
                s.daemonState.text = "未知"
                setDot(s.dotDaemon, R.color.status_off)
            }
            pendingModuleUpdate -> {
                s.daemonState.text = "待重启"
                setDot(s.dotDaemon, R.color.status_warn)
            }
            !moduleCompatible -> {
                s.daemonState.text = "模块需更新"
                setDot(s.dotDaemon, R.color.status_warn)
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

    private fun selectAppTab(tab: AppTab) {
        appSearchRender?.let { binding.appSection.appRecycler.removeCallbacks(it) }
        appSearchRender = null
        binding.appSection.appRecycler.stopScroll()
        if (appTab == tab) {
            buildAppList()
            return
        }
        appTab = tab
        val target = binding.appSection.appTabs.getTabAt(tab.ordinal)
        if (target != null) {
            binding.appSection.appTabs.selectTab(target)
        } else {
            buildAppList()
        }
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
        if (!canUseModuleFeatures()) {
            appListsLoading = false
            addableAppsLoading = false
            appLists = AppLists()
            buildAppList()
            binding.appRefresh.isRefreshing = false
            return
        }
        val previousAddable = appLists.addable
        addableAppsLoading = true
        if (appTab == AppTab.ADD && previousAddable.isEmpty()) {
            buildAppList()
        }
        thread {
            val config = if (hasRoot) ConfigReader.readPackages() else ConfigReader.ConfigPackages(emptyList(), emptyList())
            val resolvedNames = resolveProcessComponentNames(config, hasRoot)
            val visibleLists = buildConfiguredLists(config, resolvedNames).copy(addable = previousAddable)
            runOnUiThreadIfAlive {
                appListsLoading = false
                processNames = resolvedNames
                appLists = visibleLists
                buildAppList()
            }

            val fullLists = buildAppLists(config, resolvedNames)
            runOnUiThreadIfAlive {
                addableAppsLoading = false
                appLists = fullLists
                buildAppList()
                if (appTab == AppTab.ADD) {
                    binding.appSection.appRecycler.stopScroll()
                    binding.appSection.appRecycler.post {
                        binding.appSection.appRecycler.scrollToPosition(0)
                    }
                }
                binding.appRefresh.isRefreshing = false
            }
        }
    }

    private fun buildAppList() {
        val a = binding.appSection
        val blocked = blockedState()
        if (blocked != null) {
            a.appTitle.text = "应用功能不可用"
            a.appCount.text = ""
            a.appTabs.visibility = View.GONE
            a.appSearchBox.visibility = View.GONE
            a.configuredFilterRow.visibility = View.GONE
            a.appRecycler.visibility = View.GONE
            a.emptyState.visibility = View.VISIBLE
            a.emptyIcon.setImageResource(blocked.iconRes)
            a.emptyTitle.text = blocked.title
            a.emptyDesc.text = blocked.desc
            appAdapter.submit(appTab, emptyList())
            return
        }

        val entries = entriesForCurrentTab()
        a.appTitle.text = appTab.title
        a.appTabs.visibility = View.VISIBLE
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
        bindEntryIcon(item.itemIcon, entry)
        val usable = canUseModuleFeatures()
        item.btnStart.isEnabled = entry.installed && usable
        item.btnStart.alpha = if (entry.installed && usable) 1f else 0.42f
        item.btnStart.contentDescription = if (entry.installed) "启动校准" else "未安装，无法启动"
        item.btnStart.setOnClickListener {
            if (entry.installed) startAppWithBall(entry.pkg)
        }
        item.btnDelete.isEnabled = usable
        item.btnDelete.alpha = if (usable) 1f else 0.42f
        item.btnDelete.setOnClickListener { confirmDeleteConfig(entry.pkg) }
    }

    private fun bindAddAppItem(item: ItemAddAppBinding, entry: AppEntry) {
        item.addName.text = entry.label
        item.addPkg.text = entry.pkg
        bindEntryIcon(item.addIcon, entry)
        item.btnAdd.isEnabled = canUseModuleFeatures()
        item.btnAdd.setOnClickListener { addAutoConfig(entry) }
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
        bindEntryIcon(item.configIcon, entry)
        val usable = canUseModuleFeatures()
        item.btnView.isEnabled = usable
        item.btnRemove.isEnabled = usable
        item.btnView.setOnClickListener { showConfiguredRules(entry) }
        item.btnRemove.setOnClickListener { confirmDeleteConfig(entry) }
    }

    private fun migrateLogsLater(rootAvailable: Boolean, config: ConfigReader.ConfigPackages) {
        if (!rootAvailable || config.autoPackages.isEmpty()) return
        val appContext = applicationContext
        thread {
            try {
                Thread.sleep(1200)
            } catch (_: InterruptedException) {
                return@thread
            }
            android.util.Log.d("AppOpt", "延后检查 ${config.autoPackages.size} 个待校准应用的历史 .log")
            for (pkg in config.autoPackages) {
                try {
                    DatabaseMigrator.migrateIfNeeded(appContext, pkg)
                } catch (e: Exception) {
                    android.util.Log.e("AppOpt", "导入 $pkg 失败: ${e.message}")
                }
            }
            android.util.Log.d("AppOpt", "延后历史 .log 检查完成")
        }
    }

    private fun bindEntryIcon(view: ImageView, entry: AppEntry) {
        val key = iconCacheKey(entry)
        view.tag = key
        cachedIcon(key)?.let {
            view.setImageDrawable(it)
            return
        }

        view.setImageDrawable(placeholderIcon(entry))
        scheduleIconLoad(entry, key)
    }

    private fun scheduleIconLoad(entry: AppEntry, key: String) {
        if (!pendingIconLoads.add(key)) return
        try {
            iconExecutor.execute {
                try {
                    loadIconForEntry(entry)
                } finally {
                    pendingIconLoads.remove(key)
                }
                if (!activityDestroyed && cachedIcon(key) != null) {
                    mainHandler.post {
                        if (!activityDestroyed) appAdapter.notifyIconChanged(key)
                    }
                }
            }
        } catch (_: Exception) {
            pendingIconLoads.remove(key)
        }
    }

    private fun iconCacheKey(entry: AppEntry): String {
        return "v2:${entry.component}:${entry.pkg}"
    }

    private fun cachedIcon(key: String): Drawable? = synchronized(iconCache) {
        iconCache.get(key)
    }

    private fun putCachedIcon(key: String, icon: Drawable) {
        synchronized(iconCache) {
            iconCache.put(key, icon)
        }
    }

    private fun placeholderIcon(entry: AppEntry): Drawable? {
        val key = "placeholder:${entry.component}"
        cachedIcon(key)?.let { return it }
        val resId = when (entry.component) {
            ComponentKind.SYSTEM_COMPONENT -> R.drawable.ic_linux
            ComponentKind.MISSING_APP -> R.drawable.ic_missing_app
            ComponentKind.APP -> R.drawable.ic_launcher_foreground
        }
        val icon = ContextCompat.getDrawable(this, resId)?.let { makeRoundIcon(it.mutate()) }
        if (icon != null) putCachedIcon(key, icon)
        return icon
    }

    private fun loadIconForEntry(entry: AppEntry): Drawable? {
        val key = iconCacheKey(entry)
        cachedIcon(key)?.let { return it }
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
        if (rounded != null) putCachedIcon(key, rounded)
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

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private inner class AppAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val payloadIcon = "icon"
        private val viewTypeAdd = 1
        private val viewTypeNormal = 2
        private val viewTypeConfigured = 3
        private var mode = AppTab.PENDING
        private var items: List<AppEntry> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submit(newMode: AppTab, newItems: List<AppEntry>) {
            val oldMode = mode
            val oldItems = items
            if (oldMode != newMode) {
                mode = newMode
                items = newItems
                notifyDataSetChanged()
                return
            }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].pkg == newItems[newItemPosition].pkg
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition] == newItems[newItemPosition]
                }
            })
            mode = newMode
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        fun notifyIconChanged(key: String) {
            val index = items.indexOfFirst { iconCacheKey(it) == key }
            if (index >= 0) notifyItemChanged(index, payloadIcon)
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

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.contains(payloadIcon)) {
                val entry = items[position]
                when (holder) {
                    is AddHolder -> bindEntryIcon(holder.binding.addIcon, entry)
                    is ConfiguredHolder -> bindEntryIcon(holder.binding.configIcon, entry)
                    is NormalHolder -> bindEntryIcon(holder.binding.itemIcon, entry)
                }
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long {
            val entry = items[position]
            return stableItemId("${mode.name}:${entry.pkg}")
        }

        private fun stableItemId(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (ch in value) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
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

    private fun blockedState(): EmptyState? {
        if (environmentLoading) {
            return EmptyState(
                R.drawable.ic_empty_pending,
                "正在检测运行环境",
                "正在确认 Root、模块版本和守护进程状态"
            )
        }
        return when {
            !hasRoot -> EmptyState(
                R.drawable.ic_error,
                "Root 权限不可用",
                "应用列表、校准和配置修改需要 Root 权限"
            )
            pendingModuleUpdate -> EmptyState(
                R.drawable.ic_warning,
                "模块更新待重启",
                "已检测到待生效模块更新\n请重启设备后再使用应用列表和自动校准"
            )
            !moduleCompatible -> EmptyState(
                R.drawable.ic_warning,
                "模块版本不兼容",
                "当前模块版本：${moduleVersionLabel()}\n请刷入 v${DaemonBridge.REQUIRED_MODULE_VERSION_NAME} (${DaemonBridge.REQUIRED_MODULE_VERSION_CODE}) 或更高版本模块后重启"
            )
            !daemonRunning -> EmptyState(
                R.drawable.ic_warning,
                "守护进程未运行",
                "请确认模块已启用并重启设备\n仍异常可在「设置」中查看守护进程日志"
            )
            else -> null
        }
    }

    private fun emptyStateForCurrentTab(): EmptyState = when (appTab) {
        AppTab.PENDING -> if (appListsLoading) {
            EmptyState(
                R.drawable.ic_empty_pending,
                "正在读取配置",
                "待校准应用会在加载完成后显示"
            )
        } else {
            EmptyState(
                R.drawable.ic_empty_pending,
                "暂无待校准应用",
                "可在「添加应用」中选择应用写入 auto 配置"
            )
        }
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
        AppTab.CONFIGURED -> if (appListsLoading) {
            EmptyState(
                R.drawable.ic_empty_configured,
                "正在读取配置",
                "已配置应用会在加载完成后显示"
            )
        } else if (hideMissingConfigured && appLists.configured.isNotEmpty()) {
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
        return base.copy(addable = installed)
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
        return AppLists(
            pending = pending,
            configured = configured
        )
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

    /** 启动目标应用并显示悬浮球, 把目标包名传给服务用于校准 */
    private fun startAppWithBall(pkg: String) {
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasOverlay()) {
            toast("请先授予悬浮窗权限")
            refresh()
            return
        }
        if (!ForegroundDetector.hasUsageAccess(this)) {
            toast("请先授予使用情况访问权限")
            refresh()
            return
        }

        val launchPkg = packageLookupName(pkg)
        val launch = packageManager.getLaunchIntentForPackage(launchPkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ForegroundDetector.reset()
                startActivity(launch)
                waitForLaunchThenShowBall(pkg, launchPkg)
            } catch (_: Exception) {
                toast("启动 $launchPkg 失败")
            }
        } else {
            toast("未找到 $launchPkg 的启动入口, 请手动进入应用")
        }
    }

    private fun waitForLaunchThenShowBall(pkg: String, launchPkg: String, attempt: Int = 0) {
        mainHandler.postDelayed({
            if (activityDestroyed || isFinishing || isDestroyed) return@postDelayed
            val foreground = ForegroundDetector.isAppForeground(this, launchPkg, initialLookbackMs = 8_000L)
            if (foreground) {
                val svc = Intent(this, FloatingBallService::class.java)
                    .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
                startForegroundService(svc)
            } else if (attempt < 15) {
                waitForLaunchThenShowBall(pkg, launchPkg, attempt + 1)
            } else {
                toast("未检测到目标应用启动，已取消显示悬浮球")
            }
        }, 500L)
    }

    /** 把未配置应用写入 applist.conf, 形式为 "包名=auto" */
    private fun addAutoConfig(entry: AppEntry) {
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        val pkg = entry.pkg
        val previousLists = appLists
        val optimisticPending = (previousLists.pending + entry.copy(configPkgs = listOf(pkg)))
            .distinctBy { it.pkg }
            .sortedByInstallTime()
        val optimisticLists = previousLists.copy(
            pending = optimisticPending,
            addable = removeConfiguredFromAddable(previousLists.addable, pkg)
        )
        appLists = optimisticLists
        selectAppTab(AppTab.PENDING)

        thread {
            val ok = DaemonBridge.addAutoPackage(pkg)
            val config = if (ok) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, hasRoot) } ?: processNames
            val visibleLists = config?.let {
                buildConfiguredLists(it, resolvedNames).copy(
                    addable = optimisticLists.addable
                )
            } ?: optimisticLists
            runOnUiThreadIfAlive {
                if (ok) {
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                } else {
                    appLists = previousLists
                    buildAppList()
                    toast("添加配置失败，请检查 Root 或模块权限")
                }
            }
        }
    }

    private fun removeConfiguredFromAddable(addable: List<AppEntry>, pkg: String): List<AppEntry> {
        val owner = configOwnerName(pkg)
        val lookup = packageLookupName(pkg)
        return addable.filterNot {
            it.pkg == pkg || it.pkg == owner || it.pkg == lookup
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
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        val targets = configPkgs.distinct()
        val view = DialogDeleteConfigBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        view.deleteTitle.text = "删除 ${appLabel(displayPkg)}"
        view.deletePkg.text = targets.joinToString("\n")
        view.deleteRules.text = "正在读取当前规则..."
        view.deleteCancel.setOnClickListener { dialog.dismiss() }
        view.deleteConfirm.setOnClickListener {
            dialog.dismiss()
            deleteConfig(targets)
        }
        dialog.setContentView(view.root)
        dialog.show()

        thread {
            val rules = targets.flatMap { DaemonBridge.readPkgConfigLines(it) }
            runOnUiThreadIfAlive {
                view.deleteRules.text = if (rules.isEmpty()) {
                    "未读取到当前规则；确认后仍会删除这些配置项在 applist.conf 中的所有配置行。"
                } else {
                    rules.joinToString("\n")
                }
            }
        }
    }

    /** 从 applist.conf 删除该包名的所有配置行 */
    private fun deleteConfig(pkg: String) {
        deleteConfig(listOf(pkg))
    }

    private fun deleteConfig(pkgs: List<String>) {
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        val previousLists = appLists
        val targetSet = pkgs.toSet()
        val optimisticLists = appLists.copy(
            pending = appLists.pending.filterNot { entry ->
                entry.configPkgs.any { it in targetSet } || entry.pkg in targetSet
            },
            configured = appLists.configured.filterNot { entry ->
                entry.configPkgs.any { it in targetSet } || entry.pkg in targetSet
            }
        )
        appLists = optimisticLists.copy(
            addable = addDeletedBackToAddable(previousLists.addable, pkgs, optimisticLists)
        )
        buildAppList()

        thread {
            val ok = DaemonBridge.deleteConfigPackages(pkgs)
            val config = if (ok) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, hasRoot) } ?: processNames
            val visibleLists = config?.let { buildAppLists(it, resolvedNames) } ?: appLists
            runOnUiThreadIfAlive {
                if (ok) {
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                    toast("已删除配置")
                } else {
                    appLists = previousLists
                    buildAppList()
                    toast("删除配置失败，请检查 Root 或模块权限")
                }
            }
        }
    }

    private fun addDeletedBackToAddable(
        addable: List<AppEntry>,
        deletedPkgs: List<String>,
        currentLists: AppLists
    ): List<AppEntry> {
        val blocked = (currentLists.pending + currentLists.configured)
            .flatMap { it.configPkgs + it.pkg }
            .flatMap { listOf(it, configOwnerName(it), packageLookupName(it)) }
            .toHashSet()
        val merged = LinkedHashMap<String, AppEntry>()
        for (entry in addable) merged[entry.pkg] = entry
        for (pkg in deletedPkgs) {
            val owner = configOwnerName(pkg)
            val lookup = packageLookupName(owner)
            if (owner in blocked || lookup in blocked || owner in merged) continue
            if (isInstalled(owner)) {
                merged[owner] = appEntry(owner)
            }
        }
        return merged.values.toList().sortedByInstallTime()
    }

    /** 查看已配置应用当前生效规则 */
    private fun showConfiguredRules(entry: AppEntry) {
        showConfiguredRules(entry.pkg, entry.configPkgs)
    }

    private fun showConfiguredRules(pkg: String) {
        showConfiguredRules(pkg, listOf(pkg))
    }

    private fun showConfiguredRules(displayPkg: String, configPkgs: List<String>) {
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        val targets = configPkgs.distinct()
        thread {
            val rules = targets.flatMap { DaemonBridge.readPkgRules(it) }
            runOnUiThreadIfAlive {
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
        AppToast.show(this, msg)
    }
}
