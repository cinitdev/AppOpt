package top.suto.appopt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
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
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityMainBinding
import top.suto.appopt.databinding.DialogConfigRuleEditBinding
import top.suto.appopt.databinding.DialogConfigRulesBinding
import top.suto.appopt.databinding.DialogDeleteConfigBinding
import top.suto.appopt.databinding.DialogDiscardRulesBinding
import top.suto.appopt.databinding.DialogRuleHistoryPickerBinding
import top.suto.appopt.databinding.DialogThreadWildcardBinding
import top.suto.appopt.databinding.ItemAddAppBinding
import top.suto.appopt.databinding.ItemAutoAppBinding
import top.suto.appopt.databinding.ItemConfigRuleBinding
import top.suto.appopt.databinding.ItemConfiguredAppBinding
import top.suto.appopt.databinding.ItemRuleHistoryCandidateBinding
import top.suto.appopt.db.AppOptDbHelper
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 引导授予悬浮窗权限，并列出配置文件中的待校准、可添加和已配置应用。
 * 每个应用项显示图标/名称，并按当前模块状态启用启动、查看、删除等操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRoot = false
    private var daemonRunning = false
    private var foregroundHelperStatus = ForegroundHelperStatus()
    private var moduleVersion: DaemonBridge.ModuleVersion? = null
    private var moduleCompatible = false
    private var pendingModuleUpdate = false
    private var moduleWarningShown = false
    private var startupUpdateCheckStarted = false
    private var startupUpdateDialogShowing = false
    private var appTab = AppTab.PENDING
    private var appSearchQuery = ""
    private var appLists = AppLists()
    private var environmentLoading = true
    private var appListsLoading = true
    private var addableAppsLoading = true
    private var hideMissingConfigured = false
    private var environmentLoadingShownAt = 0L
    private var appSearchRender: Runnable? = null
    private var emptyIconAnimator: ObjectAnimator? = null
    private var processNames: Set<String> = emptySet()
    private val iconCache = LruCache<String, Drawable>(768)
    private val pendingIconLoads = Collections.synchronizedSet(mutableSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor()
    private val rulesLoadingPackages = mutableSetOf<String>()
    @Volatile private var activityDestroyed = false
    private var firstResume = true
    private lateinit var appAdapter: AppAdapter

    private data class ForegroundHelperStatus(
        val state: DaemonBridge.TaskForegroundState? = null,
        val startRequested: Boolean = false
    )

    private companion object {
        const val PREFS_NAME = "appopt_prefs"
        const val PREF_HIDE_MISSING_CONFIGURED = "hide_missing_configured"
        const val MIN_ENV_LOADING_MS = 1800L
        const val RULE_TOOLS_THRESHOLD = 9
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
        val configPkgs: List<String>,
        val ruleCount: Int
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

    private data class EditableConfigRule(
        val sourceIndex: Int?,
        val owner: String,
        val thread: String?,
        val cpus: String
    ) {
        fun asLine(): String {
            val key = thread?.let { "$owner{$it}" } ?: owner
            return "$key=$cpus"
        }
    }

    private data class ConfigRuleListItem(
        val listIndex: Int,
        val rule: EditableConfigRule
    )

    private enum class ConfigRuleFilter {
        ALL,
        MAIN,
        CHILD,
        THREAD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.applyEdgeToEdge(this, binding.root, binding.mainHeader)
        environmentLoadingShownAt = SystemClock.uptimeMillis()
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

        // root 检测 + 读配置 + 批量导入旧 .log 放后台线程，避免 su 弹窗阻塞 UI
        thread {
            val r = DaemonBridge.hasRoot()
            val pendingUpdate = if (r) DaemonBridge.hasPendingModuleUpdate() else false
            val version = if (r && !pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = isCompatibleModule(version)
            val running = if (r && compatible && !pendingUpdate) DaemonBridge.isDaemonRunning() else false
            val helperStatus = if (r && compatible && !pendingUpdate) queryForegroundHelperStatus() else ForegroundHelperStatus()
            val enabled = r && compatible && running
            val config = if (enabled) ConfigReader.readPackages() else ConfigReader.ConfigPackages(emptyList(), emptyList())
            val resolvedNames = resolveProcessComponentNames(config, enabled)
            val visibleLists = if (enabled) buildConfiguredLists(config, resolvedNames) else AppLists()

            runOnUiThreadIfAlive {
                runAfterEnvironmentLoadingMinimum {
                    hasRoot = r
                    pendingModuleUpdate = pendingUpdate
                    moduleVersion = version
                    moduleCompatible = compatible
                    daemonRunning = running
                    foregroundHelperStatus = helperStatus
                    environmentLoading = false
                    appListsLoading = false
                    processNames = resolvedNames
                    appLists = if (addableAppsLoading) {
                        visibleLists
                    } else {
                        appLists.copy(
                            pending = visibleLists.pending,
                            configured = visibleLists.configured
                        )
                    }
                    refresh()
                    buildAppList()
                    showModuleWarningIfNeeded()
                    maybeCheckStartupUpdate()
                }
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

    private fun runAfterEnvironmentLoadingMinimum(action: () -> Unit) {
        val remain = MIN_ENV_LOADING_MS - (SystemClock.uptimeMillis() - environmentLoadingShownAt)
        if (remain <= 0L) {
            action()
            return
        }
        mainHandler.postDelayed({
            if (!activityDestroyed && !isFinishing && !isDestroyed) {
                action()
            }
        }, remain)
    }


    override fun onResume() {
        super.onResume()
        refresh()
        val shouldRefreshConfig = firstResume.not()
        firstResume = false
        // 守护进程、Root 授权和配置可能在后台变化，回到前台时后台重查一次。
        if (shouldRefreshConfig) refreshForegroundState(refreshConfig = true)
    }

    private fun refreshForegroundState(refreshConfig: Boolean) {
        val previousAddable = appLists.addable
        thread {
            val root = DaemonBridge.hasRoot()
            val pendingUpdate = if (root) DaemonBridge.hasPendingModuleUpdate() else false
            val version = if (root && !pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = root && isCompatibleModule(version)
            val running = if (root && compatible && !pendingUpdate) DaemonBridge.isDaemonRunning() else false
            val helperStatus = if (root && compatible && !pendingUpdate) queryForegroundHelperStatus() else ForegroundHelperStatus()
            val enabled = root && compatible && running
            val config = if (enabled && refreshConfig) ConfigReader.readPackages() else null
            val resolvedNames = config?.let { resolveProcessComponentNames(it, enabled) } ?: processNames
            val visibleLists = when {
                !enabled -> AppLists()
                config != null -> buildConfiguredLists(config, resolvedNames).copy(addable = previousAddable)
                else -> null
            }
            runOnUiThreadIfAlive {
                hasRoot = root
                pendingModuleUpdate = pendingUpdate
                moduleVersion = version
                moduleCompatible = compatible
                daemonRunning = running
                foregroundHelperStatus = helperStatus
                if (visibleLists != null) {
                    appListsLoading = false
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                }
                refresh()
                if (!enabled) buildAppList()
                showModuleWarningIfNeeded()
                maybeCheckStartupUpdate()
            }
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        appSearchRender?.let { binding.appSection.appRecycler.removeCallbacks(it) }
        updateEmptyAnimation(false)
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

    private fun queryForegroundHelperStatus(): ForegroundHelperStatus {
        var state = DaemonBridge.readTaskForegroundState()
        if (state.available) return ForegroundHelperStatus(state = state)

        val started = DaemonBridge.ensureTaskForegroundHelper()
        if (started) {
            for (_i in 0 until 3) {
                if (state.available) break
                try {
                    Thread.sleep(160L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                state = DaemonBridge.readTaskForegroundState()
            }
        }
        return ForegroundHelperStatus(state = state, startRequested = started)
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

    private fun maybeCheckStartupUpdate() {
        if (startupUpdateCheckStarted || activityDestroyed || !hasRoot || pendingModuleUpdate) return
        startupUpdateCheckStarted = true
        thread(name = "AppOptStartupUpdateCheck") {
            val result = try {
                ModuleUpdater.checkForUpdate()
            } catch (_: Exception) {
                null
            }
            runOnUiThreadIfAlive {
                val update = (result as? ModuleUpdater.CheckResult.UpdateAvailable)?.update ?: return@runOnUiThreadIfAlive
                if (startupUpdateDialogShowing) return@runOnUiThreadIfAlive
                startupUpdateDialogShowing = true
                ModuleUpdateDialog.show(
                    activity = this,
                    update = update
                ) {
                    startupUpdateDialogShowing = false
                }
            }
        }
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
            s.foregroundHelperState.text = "检查中"
            setDot(s.dotForegroundHelper, R.color.status_warn)
            return
        }

        s.rootState.text = if (hasRoot) "可用" else "不可用"
        setDot(s.dotRoot, if (hasRoot) R.color.status_ok else R.color.status_off)

        // 守护进程：无 root 时无从判断，显示“未知”；有 root 时按当前模块验证结果显示
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

        val helper = foregroundHelperStatus.state
        when {
            !hasRoot -> {
                s.foregroundHelperState.text = "未知"
                setDot(s.dotForegroundHelper, R.color.status_off)
            }
            pendingModuleUpdate -> {
                s.foregroundHelperState.text = "待重启"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            !moduleCompatible -> {
                s.foregroundHelperState.text = "模块需更新"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            helper?.available == true && helper.mode == "poll" -> {
                s.foregroundHelperState.text = "轮询中"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            helper?.available == true -> {
                s.foregroundHelperState.text = "运行中"
                setDot(s.dotForegroundHelper, R.color.status_ok)
            }
            helper?.status == "error" -> {
                s.foregroundHelperState.text = "错误"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            helper?.status == "empty" -> {
                s.foregroundHelperState.text = "无任务"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            helper?.ageMs != null -> {
                s.foregroundHelperState.text = "状态过期"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            foregroundHelperStatus.startRequested -> {
                s.foregroundHelperState.text = "启动中"
                setDot(s.dotForegroundHelper, R.color.status_warn)
            }
            else -> {
                s.foregroundHelperState.text = "不可用"
                setDot(s.dotForegroundHelper, R.color.status_off)
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
                if (supportsAppSearch()) {
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
            layoutParams = layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                    .coerceIn(dp(360f), dp(620f))
            }
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            itemAnimator = null
        }
    }

    /** 下拉刷新：重新读取配置文件并更新应用列表 */
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
            updateEmptyAnimation(blocked.animated)
            appAdapter.submit(appTab, emptyList())
            return
        }

        val entries = entriesForCurrentTab()
        a.appTitle.text = appTab.title
        a.appTabs.visibility = View.VISIBLE
        a.appSearchBox.visibility = if (supportsAppSearch()) View.VISIBLE else View.GONE
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
        updateEmptyAnimation(false)
        appAdapter.submit(appTab, entries)
    }

    private fun bindEmptyState() {
        val a = binding.appSection
        val state = emptyStateForCurrentTab()
        a.emptyIcon.setImageResource(state.iconRes)
        a.emptyTitle.text = state.title
        a.emptyDesc.text = state.desc
        updateEmptyAnimation(state.animated)
    }

    private fun updateEmptyAnimation(active: Boolean) {
        val icon = binding.appSection.emptyIcon
        if (!active) {
            emptyIconAnimator?.cancel()
            emptyIconAnimator = null
            icon.rotation = 0f
            icon.scaleX = 1f
            icon.scaleY = 1f
            icon.alpha = 0.88f
            return
        }
        if (emptyIconAnimator?.isRunning == true) return
        icon.setImageResource(R.drawable.ic_loading_ring)
        icon.alpha = 1f
        emptyIconAnimator = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
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
            ComponentKind.APP -> "${entry.pkg} · ${entry.ruleCount} 条规则"
            ComponentKind.SYSTEM_COMPONENT -> "系统组件 · ${entry.ruleCount} 条规则"
            ComponentKind.MISSING_APP -> "未安装/配置残留 · ${entry.ruleCount} 条规则"
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

    private fun supportsAppSearch(): Boolean {
        return appTab == AppTab.ADD || appTab == AppTab.CONFIGURED
    }

    private fun filteredConfiguredApps(): List<AppEntry> {
        val base = if (hideMissingConfigured) {
            appLists.configured.filter { it.component != ComponentKind.MISSING_APP }
        } else {
            appLists.configured
        }
        return filterAppsBySearch(base)
    }

    private fun filteredAddableApps(): List<AppEntry> {
        return filterAppsBySearch(appLists.addable)
    }

    private fun filterAppsBySearch(entries: List<AppEntry>): List<AppEntry> {
        val q = appSearchQuery.lowercase()
        if (q.isBlank()) return entries
        return entries.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    private data class EmptyState(
        val iconRes: Int,
        val title: String,
        val desc: String,
        val animated: Boolean = false
    )

    private fun blockedState(): EmptyState? {
        if (environmentLoading) {
            return EmptyState(
                R.drawable.ic_loading_ring,
                "正在检测运行环境",
                "正在确认 Root、模块版本和守护进程状态",
                animated = true
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
                "C进程未运行",
                "请确认模块已启用并重启设备\n仍异常可在「设置」中查看C进程日志"
            )
            else -> null
        }
    }

    private fun emptyStateForCurrentTab(): EmptyState = when (appTab) {
        AppTab.PENDING -> if (appListsLoading) {
            EmptyState(
                R.drawable.ic_loading_ring,
                "正在读取配置",
                "待校准应用会在加载完成后显示",
                animated = true
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
                R.drawable.ic_loading_ring,
                "正在加载应用",
                "应用较多时需要稍等片刻",
                animated = true
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
        AppTab.CONFIGURED -> when {
            appListsLoading -> EmptyState(
                R.drawable.ic_loading_ring,
                "正在读取配置",
                "已配置应用会在加载完成后显示",
                animated = true
            )
            appSearchQuery.isNotBlank() -> EmptyState(
                R.drawable.ic_empty_configured,
                "没有匹配的已配置应用",
                "试试应用名称或包名里的其他关键词"
            )
            hideMissingConfigured && appLists.configured.isNotEmpty() -> EmptyState(
                R.drawable.ic_empty_configured,
                "未安装应用已隐藏",
                "关闭「隐藏未安装应用」可查看配置残留项"
            )
            else -> EmptyState(
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
            .map { (pkg, configPkgs) ->
                val groupPkgs = configPkgs.toList()
                val ruleCount = groupPkgs.sumOf { config.configuredRuleCounts[it] ?: 0 }
                    .takeIf { it > 0 } ?: groupPkgs.size
                appEntry(pkg, names, groupPkgs, ruleCount)
            }
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
        configPkgs: List<String> = listOf(pkg),
        ruleCount: Int = 0
    ): AppEntry {
        val installed = isInstalled(pkg)
        return AppEntry(
            pkg = pkg,
            label = appLabel(pkg),
            installed = installed,
            component = componentKind(pkg, installed, names),
            installTime = installTime(pkg),
            configPkgs = configPkgs.distinct(),
            ruleCount = ruleCount
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

    /** 包名 -> 应用显示名；未安装则回退为包名 */
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

    /** 跳转系统「使用情况访问」设置页，让用户为本应用授予 PACKAGE_USAGE_STATS */
    private fun requestUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // 部分 ROM 支持直接定位到本应用条目；失败则回退到列表页
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

    /** 启动目标应用并显示悬浮球，把目标包名传给服务用于校准 */
    private fun startAppWithBall(pkg: String) {
        android.util.Log.d("AppOpt", "startAppWithBall: pkg=$pkg")
        if (!canUseModuleFeatures()) {
            android.util.Log.d("AppOpt", "startAppWithBall blocked: ${blockedState()?.title ?: "模块不可用"}")
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasOverlay()) {
            android.util.Log.d("AppOpt", "startAppWithBall blocked: overlay permission missing")
            toast("请先授予悬浮窗权限")
            refresh()
            return
        }
        if (!ForegroundDetector.hasUsageAccess(this)) {
            android.util.Log.d("AppOpt", "startAppWithBall blocked: usage access missing")
            toast("请先授予使用情况访问权限")
            refresh()
            return
        }

        val launchPkg = packageLookupName(pkg)
        val launch = packageManager.getLaunchIntentForPackage(launchPkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                val svc = Intent(this, FloatingBallService::class.java)
                    .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
                    .putExtra(FloatingBallService.EXTRA_LAUNCH_PKG, launchPkg)
                android.util.Log.d("AppOpt", "startAppWithBall start floating service: pkg=$pkg")
                startForegroundService(svc)
                android.util.Log.d("AppOpt", "startAppWithBall launch: launchPkg=$launchPkg configPkg=$pkg")
                startActivity(launch)
            } catch (e: Exception) {
                android.util.Log.e("AppOpt", "startAppWithBall launch failed: $launchPkg ${e.message}")
                stopService(Intent(this, FloatingBallService::class.java))
                toast("启动 $launchPkg 失败")
            }
        } else {
            android.util.Log.d("AppOpt", "startAppWithBall blocked: no launcher for $launchPkg")
            toast("未找到 $launchPkg 的启动入口，请手动进入应用")
        }
    }

    /** 把未配置应用写入 applist.conf，形式为 "包名=auto" */
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

    /** 删除前展示该包名当前配置规则，确认后再删除 */
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
        view.deleteConfirm.isEnabled = false
        view.deleteConfirm.text = "读取中"
        view.deleteCancel.setOnClickListener { dialog.dismiss() }
        view.deleteConfirm.setOnClickListener {
            dialog.dismiss()
            deleteConfig(targets)
        }
        dialog.setContentView(view.root)
        dialog.show()

        thread {
            val result = runCatching {
                targets.flatMap { DaemonBridge.readPkgConfigLines(it) }
            }
            runOnUiThreadIfAlive {
                view.deleteRules.text = result.fold(
                    onSuccess = { rules ->
                        if (rules.isEmpty()) {
                            "未读取到当前规则；确认后仍会删除这些配置项在 applist.conf 中的所有配置行。"
                        } else {
                            rules.joinToString("\n")
                        }
                    },
                    onFailure = { error ->
                        android.util.Log.e("AppOpt", "read rules before delete failed", error)
                        "读取当前规则失败。仍可确认删除，删除操作会重新读取并修改 applist.conf。"
                    }
                )
                view.deleteConfirm.isEnabled = true
                view.deleteConfirm.text = "确认删除"
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

    /** 以结构化列表查看并编辑已配置应用当前生效规则。 */
    private fun showConfiguredRules(entry: AppEntry) {
        if (!canUseModuleFeatures()) {
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        if (!rulesLoadingPackages.add(entry.pkg)) {
            toast("正在读取规则")
            return
        }
        toast("正在读取规则")
        val targets = entry.configPkgs.distinct()
        thread {
            try {
                val lines = DaemonBridge.readPkgRules(targets)
                val allowedCpus = DaemonBridge.readConfigAllowedCpus()
                val historyCandidates = try {
                    RuleHistoryCandidates.build(
                        entry.pkg,
                        AppOptDbHelper.getInstance(this).getRuleHistoryRecordsByPackage(entry.pkg)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("AppOpt", "read rule history candidates failed: ${e.message}")
                    emptyList()
                }
                runOnUiThreadIfAlive {
                    rulesLoadingPackages.remove(entry.pkg)
                    showConfiguredRulesDialog(entry, targets, lines, allowedCpus, historyCandidates)
                }
            } catch (e: Exception) {
                android.util.Log.e("AppOpt", "read configured rules failed: ${entry.pkg}", e)
                runOnUiThreadIfAlive {
                    rulesLoadingPackages.remove(entry.pkg)
                    toast("读取规则失败，请重试")
                }
            }
        }
    }

    private fun showConfiguredRulesDialog(
        entry: AppEntry,
        targets: List<String>,
        lines: List<String>,
        allowedCpus: Set<Int>,
        historyCandidates: List<RuleHistoryCandidate>
    ) {
                val rules = lines.mapIndexedNotNull { index, line ->
                    parseEditableConfigRule(line, index)
                }.toMutableList()
                if (rules.size != lines.size) {
                    toast("部分规则格式无法解析，请检查 applist.conf")
                    return
                }
                val initialRuleSnapshot = rules.map { it.asLine() }.sorted()
                var ruleSearchQuery = ""
                var ruleFilter = ConfigRuleFilter.ALL
                var rulesSaving = false

                val view = DialogConfigRulesBinding.inflate(layoutInflater)
                view.rulesTitle.text = if (entry.installed) entry.label else entry.pkg
                view.rulesPkg.text = entry.pkg
                view.rulesIcon.setImageDrawable(loadIconForEntry(entry) ?: placeholderIcon(entry))
                val dialog = BottomSheetDialog(this)
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                dialog.setContentView(view.root)
                dialog.setOnShowListener {
                    dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    dialog.behavior.skipCollapsed = true
                    dialog.behavior.isHideable = false
                }
                view.rulesList.layoutManager = LinearLayoutManager(this)
                view.rulesList.itemAnimator = null
                lateinit var renderRules: () -> Unit
                val adapter = ConfigRuleAdapter(
                    onEdit = { item ->
                        if (!rulesSaving) {
                            val index = findEditableRuleIndex(rules, item)
                            if (index >= 0) {
                                showConfigRuleEditor(
                                    rules[index],
                                    targets,
                                    rules,
                                    allowedCpus,
                                    historyCandidates
                                ) { updated ->
                                    rules[index] = updated
                                    showRulesError(view, null)
                                    renderRules()
                                }
                            }
                        }
                    },
                    onDelete = { item ->
                        if (!rulesSaving) {
                            confirmDeleteConfigRule(item.rule) {
                                val index = findEditableRuleIndex(rules, item)
                                if (index >= 0) {
                                    rules.removeAt(index)
                                    showRulesError(view, null)
                                    renderRules()
                                }
                            }
                        }
                    }
                )
                view.rulesList.adapter = adapter

                renderRules = {
                    renderConfigRules(
                        view,
                        rules,
                        ruleSearchQuery,
                        ruleFilter,
                        rulesSaving,
                        adapter
                    )
                }

                view.rulesSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        ruleSearchQuery = s?.toString().orEmpty().trim()
                        renderRules()
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                })
                view.rulesFilterGroup.check(R.id.rulesFilterAll)
                view.rulesFilterGroup.addOnButtonCheckedListener { _, checkedId, checked ->
                    if (!checked) return@addOnButtonCheckedListener
                    ruleFilter = when (checkedId) {
                        R.id.rulesFilterMain -> ConfigRuleFilter.MAIN
                        R.id.rulesFilterChild -> ConfigRuleFilter.CHILD
                        R.id.rulesFilterThread -> ConfigRuleFilter.THREAD
                        else -> ConfigRuleFilter.ALL
                    }
                    renderRules()
                }

                view.rulesAdd.setOnClickListener {
                    if (rulesSaving) return@setOnClickListener
                    showConfigRuleEditor(
                        null,
                        targets,
                        rules,
                        allowedCpus,
                        historyCandidates
                    ) { added ->
                        rules.add(added)
                        showRulesError(view, null)
                        renderRules()
                    }
                }
                view.rulesClose.setOnClickListener {
                    if (rulesSaving) return@setOnClickListener
                    val currentSnapshot = rules.map { it.asLine() }.sorted()
                    if (currentSnapshot == initialRuleSnapshot) {
                        dialog.dismiss()
                    } else {
                        showDiscardRulesConfirm { dialog.dismiss() }
                    }
                }
                view.rulesSave.setOnClickListener {
                    if (rulesSaving) return@setOnClickListener
                    val replacements = rules.mapNotNull { rule ->
                        rule.sourceIndex?.let { it to rule.asLine() }
                    }.toMap()
                    val addedLines = rules.filter { it.sourceIndex == null }.map { it.asLine() }
                    rulesSaving = true
                    renderRules()
                    val started = saveConfiguredRules(
                        dialog = dialog,
                        view = view,
                        targets = targets,
                        expectedOriginalLines = lines,
                        replacements = replacements,
                        addedLines = addedLines
                    ) {
                        rulesSaving = false
                        renderRules()
                    }
                    if (!started) {
                        rulesSaving = false
                        renderRules()
                    }
                }
                renderRules()
                dialog.show()
    }

    private fun parseEditableConfigRule(line: String, sourceIndex: Int?): EditableConfigRule? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        val cpus = line.substring(eq + 1).trim()
        if (key.isEmpty() || cpus.isEmpty()) return null

        val brace = key.indexOf('{')
        if (brace < 0) return EditableConfigRule(sourceIndex, key, null, cpus)
        if (brace == 0 || !key.endsWith('}')) return null
        val owner = key.substring(0, brace).trim()
        val thread = key.substring(brace + 1, key.length - 1).trim()
        if (owner.isEmpty() || thread.isEmpty()) return null
        return EditableConfigRule(sourceIndex, owner, thread, cpus)
    }

    private fun findEditableRuleIndex(
        rules: List<EditableConfigRule>,
        target: ConfigRuleListItem
    ): Int {
        val preferred = rules.getOrNull(target.listIndex)
        if (preferred === target.rule ||
            (target.rule.sourceIndex != null && preferred?.sourceIndex == target.rule.sourceIndex)) {
            return target.listIndex
        }
        val sourceIndex = target.rule.sourceIndex
        return if (sourceIndex != null) {
            rules.indexOfFirst { it.sourceIndex == sourceIndex }
        } else {
            rules.indexOfFirst { it === target.rule }
        }
    }

    private fun renderConfigRules(
        view: DialogConfigRulesBinding,
        rules: MutableList<EditableConfigRule>,
        searchQuery: String,
        filter: ConfigRuleFilter,
        saving: Boolean,
        adapter: ConfigRuleAdapter
    ) {
        view.rulesCount.text = if (rules.isEmpty()) "暂无规则" else "${rules.size} 条规则"
        view.rulesSave.isEnabled = rules.isNotEmpty() && !saving
        view.rulesSave.text = if (saving) "保存中" else "保存"
        view.rulesClose.isEnabled = !saving
        view.rulesAdd.isEnabled = !saving
        view.rulesSearch.isEnabled = !saving
        for (index in 0 until view.rulesFilterGroup.childCount) {
            view.rulesFilterGroup.getChildAt(index).isEnabled = !saving
        }

        val normalizedQuery = searchQuery.lowercase(Locale.ROOT)
        val filteredRules = rules.withIndex().filter { indexedRule ->
            val rule = indexedRule.value
            val typeMatches = when (filter) {
                ConfigRuleFilter.ALL -> true
                ConfigRuleFilter.MAIN -> rule.thread == null && !rule.owner.contains(':')
                ConfigRuleFilter.CHILD -> rule.thread == null && rule.owner.contains(':')
                ConfigRuleFilter.THREAD -> rule.thread != null
            }
            typeMatches && (normalizedQuery.isEmpty() ||
                rule.asLine().lowercase(Locale.ROOT).contains(normalizedQuery))
        }
        val showTools = rules.size >= RULE_TOOLS_THRESHOLD ||
            searchQuery.isNotEmpty() || filter != ConfigRuleFilter.ALL
        view.rulesTools.visibility = if (showTools) View.VISIBLE else View.GONE
        view.rulesFilterSummary.visibility = if (showTools) View.VISIBLE else View.GONE
        view.rulesFilterSummary.text = "显示 ${filteredRules.size} / ${rules.size}"
        view.rulesEmpty.text = when {
            rules.isEmpty() -> "暂无绑定规则"
            filteredRules.isEmpty() -> "没有匹配的规则"
            else -> ""
        }
        view.rulesEmpty.visibility = if (filteredRules.isEmpty()) View.VISIBLE else View.GONE
        view.rulesList.visibility = if (filteredRules.isEmpty()) View.GONE else View.VISIBLE
        adapter.interactionsEnabled = !saving
        adapter.submitList(filteredRules.map { ConfigRuleListItem(it.index, it.value) })
    }

    private inner class ConfigRuleAdapter(
        private val onEdit: (ConfigRuleListItem) -> Unit,
        private val onDelete: (ConfigRuleListItem) -> Unit
    ) : ListAdapter<ConfigRuleListItem, ConfigRuleAdapter.Holder>(
        object : DiffUtil.ItemCallback<ConfigRuleListItem>() {
            override fun areItemsTheSame(
                oldItem: ConfigRuleListItem,
                newItem: ConfigRuleListItem
            ): Boolean {
                val oldSource = oldItem.rule.sourceIndex
                val newSource = newItem.rule.sourceIndex
                return if (oldSource != null && newSource != null) {
                    oldSource == newSource
                } else {
                    oldItem.rule === newItem.rule
                }
            }

            override fun areContentsTheSame(
                oldItem: ConfigRuleListItem,
                newItem: ConfigRuleListItem
            ): Boolean = oldItem == newItem
        }
    ) {
        var interactionsEnabled: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                notifyItemRangeChanged(0, itemCount)
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemConfigRuleBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val listItem = getItem(position)
            val rule = listItem.rule
            val item = holder.binding
            val isThread = rule.thread != null
            val isChildProcess = !isThread && rule.owner.contains(':')
            item.ruleType.text = when {
                isThread -> "线程"
                isChildProcess -> "子进程"
                else -> "主进程"
            }
            item.ruleType.setBackgroundResource(
                if (isThread) R.drawable.bg_rule_type_thread else R.drawable.bg_rule_type_main
            )
            item.ruleType.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isThread) R.color.brand_primary_dark else R.color.brand_secondary
                )
            )
            item.ruleTarget.text = when {
                isThread -> rule.thread
                isChildProcess -> rule.owner.substringAfter(':')
                else -> "主进程"
            }
            item.ruleOwner.text = rule.owner
            item.ruleCpus.text = rule.cpus
            item.root.isEnabled = interactionsEnabled
            item.ruleEdit.isEnabled = interactionsEnabled
            item.ruleDelete.isEnabled = interactionsEnabled
            item.root.setOnClickListener { if (interactionsEnabled) onEdit(listItem) }
            item.ruleEdit.setOnClickListener { if (interactionsEnabled) onEdit(listItem) }
            item.ruleDelete.setOnClickListener { if (interactionsEnabled) onDelete(listItem) }
        }

        private inner class Holder(
            val binding: ItemConfigRuleBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }

    private fun confirmDeleteConfigRule(rule: EditableConfigRule, onConfirm: () -> Unit) {
        val view = DialogDeleteConfigBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        view.deleteTitle.text = "删除规则"
        view.deletePkg.text = rule.owner
        view.deleteRules.text = rule.asLine()
        view.deleteCancel.setOnClickListener { dialog.dismiss() }
        view.deleteConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.setContentView(view.root)
        dialog.show()
    }

    private fun showDiscardRulesConfirm(onDiscard: () -> Unit) {
        val view = DialogDiscardRulesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        view.discardRulesContinue.setOnClickListener { dialog.dismiss() }
        view.discardRulesConfirm.setOnClickListener {
            dialog.dismiss()
            onDiscard()
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        dialog.show()
    }

    private fun showConfigRuleEditor(
        current: EditableConfigRule?,
        targets: List<String>,
        existingRules: List<EditableConfigRule>,
        allowedCpus: Set<Int>,
        historyCandidates: List<RuleHistoryCandidate>,
        onConfirm: (EditableConfigRule) -> Unit
    ) {
        val view = DialogConfigRuleEditBinding.inflate(layoutInflater)
        val baseOwner = targets.firstOrNull()?.substringBefore(':').orEmpty()
        val threadOwner = current?.takeIf { it.thread != null }?.owner ?: baseOwner
        view.ruleEditTitle.text = if (current == null) "新增规则" else "编辑规则"
        view.ruleEditPkg.text = baseOwner
        view.ruleFixedOwnerInput.setText(baseOwner)
        view.ruleChildInput.setText(
            current?.owner
                ?.takeIf { current.thread == null && it.startsWith("$baseOwner:") }
                ?.substringAfter(':')
                .orEmpty()
        )
        view.ruleThreadInput.setText(current?.thread.orEmpty())
        val currentIsMain = current?.thread == null && current?.owner == baseOwner
        val mainRuleExists = existingRules.any {
            it !== current && it.thread == null && it.owner == baseOwner
        }
        view.ruleTypeMain.visibility = if (mainRuleExists && !currentIsMain) View.GONE else View.VISIBLE
        val initialType = when {
            current?.thread != null -> R.id.ruleTypeThread
            current?.owner?.contains(':') == true -> R.id.ruleTypeChild
            mainRuleExists -> R.id.ruleTypeChild
            else -> R.id.ruleTypeMain
        }
        val cpuSelections = mutableMapOf<Int, MutableSet<Int>>(
            R.id.ruleTypeMain to linkedSetOf(),
            R.id.ruleTypeChild to linkedSetOf(),
            R.id.ruleTypeThread to linkedSetOf()
        )
        cpuSelections.getValue(initialType).addAll(
            parseCpuSet(current?.cpus.orEmpty()).filter { it in allowedCpus }
        )
        val cpuBoxes = linkedMapOf<Int, MaterialCheckBox>()
        var activeCpuType = initialType
        var suppressCpuChange = false

        fun showCpuWarning(message: String) {
            view.ruleCpuWarning.text = message
            view.ruleCpuWarning.visibility = View.VISIBLE
        }

        view.ruleCpuGrid.columnCount = minOf(4, allowedCpus.size.coerceAtLeast(1))
        for (cpu in allowedCpus.sorted()) {
            val box = MaterialCheckBox(this).apply {
                text = "CPU$cpu"
                textSize = 15f
                minHeight = dp(44f)
                setPadding(0, 0, dp(8f), 0)
                setOnCheckedChangeListener { button, checked ->
                    if (suppressCpuChange) return@setOnCheckedChangeListener
                    val selectedCpus = cpuSelections.getValue(activeCpuType)
                    val next = selectedCpus.toMutableSet()
                    if (checked) {
                        next.add(cpu)
                    } else if (selectedCpus.size <= 1) {
                        suppressCpuChange = true
                        button.isChecked = true
                        suppressCpuChange = false
                        showCpuWarning("至少选择一个核心")
                        return@setOnCheckedChangeListener
                    } else {
                        next.remove(cpu)
                    }
                    if (!isContinuousCpuSelection(next)) {
                        suppressCpuChange = true
                        button.isChecked = !checked
                        suppressCpuChange = false
                        showCpuWarning("核心范围必须连续，例如 0-3、4-7，不能跳选")
                        return@setOnCheckedChangeListener
                    }
                    selectedCpus.clear()
                    selectedCpus.addAll(next.sorted())
                    view.ruleCpuSummary.text = formatCpuSet(selectedCpus)
                    view.ruleCpuWarning.visibility = View.GONE
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, dp(2f), 0, dp(2f))
            }
            cpuBoxes[cpu] = box
            view.ruleCpuGrid.addView(box, params)
        }
        if (allowedCpus.isEmpty()) {
            view.ruleEditConfirm.isEnabled = false
        }

        fun showCpuSelection(type: Int) {
            activeCpuType = type
            val selected = cpuSelections.getValue(type)
            suppressCpuChange = true
            cpuBoxes.forEach { (cpu, box) -> box.isChecked = cpu in selected }
            suppressCpuChange = false
            view.ruleCpuSummary.text = if (selected.isEmpty()) "未选择" else formatCpuSet(selected)
            if (allowedCpus.isEmpty()) {
                view.ruleCpuSummary.text = "不可用"
                showCpuWarning("CPU 核心读取失败，请重新打开后重试")
            } else {
                view.ruleCpuWarning.visibility = View.GONE
            }
        }

        fun availableHistoryCandidates(type: Int): List<RuleHistoryCandidate> {
            val kind = when (type) {
                R.id.ruleTypeChild -> RuleHistoryKind.CHILD_PROCESS
                R.id.ruleTypeThread -> RuleHistoryKind.THREAD
                else -> return emptyList()
            }
            if (kind == RuleHistoryKind.THREAD && threadOwner != baseOwner) {
                return emptyList()
            }
            val selectedOwner = if (kind == RuleHistoryKind.THREAD) {
                baseOwner
            } else {
                ""
            }
            return historyCandidates.filter { candidate ->
                candidate.kind == kind &&
                    (kind != RuleHistoryKind.THREAD || candidate.owner == selectedOwner) &&
                    existingRules.none { rule ->
                        rule !== current &&
                            rule.owner == candidate.owner &&
                            rule.thread == candidate.thread
                    }
            }
        }

        fun selectHistoryCandidate(type: Int) {
            val candidates = availableHistoryCandidates(type)
            if (candidates.isEmpty()) return
            showRuleHistoryPicker(baseOwner, type, candidates) { candidate ->
                when (candidate.kind) {
                    RuleHistoryKind.CHILD_PROCESS -> {
                        view.ruleChildInput.setText(
                            candidate.owner.removePrefix(baseOwner).removePrefix(":")
                        )
                        view.ruleChildBox.error = null
                    }
                    RuleHistoryKind.THREAD -> {
                        val mainThreadCandidates = historyCandidates.filter {
                            it.kind == RuleHistoryKind.THREAD && it.owner == baseOwner
                        }
                        val suggestion = RuleHistoryCandidates.suggestThreadWildcard(
                            candidate,
                            mainThreadCandidates
                        )
                        if (suggestion == null) {
                            view.ruleThreadInput.setText(candidate.thread.orEmpty())
                            view.ruleThreadBox.error = null
                        } else {
                            showThreadWildcardSuggestion(suggestion) { selectedName ->
                                view.ruleThreadInput.setText(selectedName)
                                view.ruleThreadBox.error = null
                                view.ruleEditError.visibility = View.GONE
                            }
                        }
                    }
                }
                view.ruleEditError.visibility = View.GONE
            }
        }

        fun updateType(checkedId: Int) {
            val childMode = checkedId == R.id.ruleTypeChild
            val threadMode = checkedId == R.id.ruleTypeThread
            view.ruleFixedOwnerBox.visibility = if (childMode) View.GONE else View.VISIBLE
            view.ruleFixedOwnerInput.setText(if (threadMode) threadOwner else baseOwner)
            view.ruleChildBox.visibility = if (childMode) View.VISIBLE else View.GONE
            view.ruleThreadBox.visibility = if (threadMode) View.VISIBLE else View.GONE
            view.ruleChildBox.error = null
            view.ruleThreadBox.error = null
            view.ruleChildBox.isEndIconVisible = childMode &&
                availableHistoryCandidates(R.id.ruleTypeChild).isNotEmpty()
            view.ruleThreadBox.isEndIconVisible = threadMode &&
                availableHistoryCandidates(R.id.ruleTypeThread).isNotEmpty()
            showCpuSelection(checkedId)
        }

        view.ruleChildBox.setEndIconOnClickListener {
            selectHistoryCandidate(R.id.ruleTypeChild)
        }
        view.ruleThreadBox.setEndIconOnClickListener {
            selectHistoryCandidate(R.id.ruleTypeThread)
        }
        view.ruleTypeGroup.check(initialType)
        updateType(initialType)
        view.ruleTypeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) updateType(checkedId)
        }

        val dialog = BottomSheetDialog(this)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        view.ruleEditCancel.setOnClickListener { dialog.dismiss() }
        view.ruleEditConfirm.setOnClickListener {
            view.ruleChildBox.error = null
            view.ruleThreadBox.error = null
            view.ruleCpuWarning.visibility = View.GONE
            view.ruleEditError.visibility = View.GONE

            val checkedType = view.ruleTypeGroup.checkedButtonId
            val cpus = formatCpuSet(cpuSelections.getValue(checkedType))
            val isChild = checkedType == R.id.ruleTypeChild
            val isThread = checkedType == R.id.ruleTypeThread
            val childSuffix = view.ruleChildInput.text?.toString().orEmpty()
                .trim()
                .removePrefix("$baseOwner:")
                .removePrefix(":")
            val threadName = view.ruleThreadInput.text?.toString().orEmpty().trim()
            val owner = when {
                isChild -> "$baseOwner:$childSuffix"
                isThread -> threadOwner
                else -> baseOwner
            }
            when {
                baseOwner.isEmpty() -> {
                    view.ruleEditError.text = "未识别到当前应用主进程"
                    view.ruleEditError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                isChild && (childSuffix.isEmpty() || childSuffix.length > 63) -> {
                    view.ruleChildBox.error = "请输入不超过 63 个字符的子进程后缀"
                    return@setOnClickListener
                }
                isChild && childSuffix.any { it.isWhitespace() || it == ':' || it == '{' || it == '}' || it == '=' } -> {
                    view.ruleChildBox.error = "子进程后缀不能包含空格、冒号或 { } ="
                    return@setOnClickListener
                }
                isThread && threadName.isEmpty() -> {
                    view.ruleThreadBox.error = "请输入线程名称"
                    return@setOnClickListener
                }
                isThread && (threadName.length > 31 || threadName.any { it == '{' || it == '}' || it == '=' }) -> {
                    view.ruleThreadBox.error = "线程名称不能超过 31 个字符或包含 { } ="
                    return@setOnClickListener
                }
                cpus.isEmpty() -> {
                    showCpuWarning("至少选择一个核心")
                    return@setOnClickListener
                }
            }

            val rule = EditableConfigRule(
                sourceIndex = current?.sourceIndex,
                owner = owner,
                thread = threadName.takeIf { isThread },
                cpus = cpus
            )
            val targetChanged = current == null ||
                current.owner != rule.owner || current.thread != rule.thread
            val duplicate = targetChanged && existingRules.any {
                it !== current && it.owner == rule.owner && it.thread == rule.thread
            }
            if (duplicate) {
                view.ruleEditError.text = "规则已存在"
                view.ruleEditError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val check = DaemonBridge.validateConfigRulesForPackages(
                targets,
                rule.asLine(),
                allowedCpus.takeIf { it.isNotEmpty() }
            )
            when {
                check.invalidLines.isNotEmpty() -> view.ruleEditError.text = "规则格式不正确"
                check.foreignLines.isNotEmpty() -> view.ruleEditError.text = "规则不属于当前应用"
                check.invalidCoreLines.isNotEmpty() -> {
                    showCpuWarning("CPU 核心范围不正确")
                    return@setOnClickListener
                }
                check.validLines.isEmpty() -> view.ruleEditError.text = "规则内容为空"
                else -> {
                    onConfirm(rule)
                    dialog.dismiss()
                    return@setOnClickListener
                }
            }
            view.ruleEditError.visibility = View.VISIBLE
        }
        dialog.show()
    }

    private fun showRuleHistoryPicker(
        baseOwner: String,
        type: Int,
        candidates: List<RuleHistoryCandidate>,
        onSelect: (RuleHistoryCandidate) -> Unit
    ) {
        val view = DialogRuleHistoryPickerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val adapter = RuleHistoryAdapter(baseOwner) { candidate ->
            dialog.dismiss()
            mainHandler.post { onSelect(candidate) }
        }
        view.ruleHistoryTitle.text = when {
            type == R.id.ruleTypeChild -> "选择历史子进程"
            candidates.firstOrNull()?.owner == baseOwner -> "选择主进程线程"
            else -> {
                val owner = candidates.firstOrNull()?.owner.orEmpty()
                val suffix = owner.removePrefix(baseOwner).ifBlank { owner }
                "选择 $suffix 线程"
            }
        }
        view.ruleHistoryList.layoutManager = LinearLayoutManager(this)
        view.ruleHistoryList.adapter = adapter
        view.ruleHistoryList.itemAnimator = null

        fun renderFilter() {
            val query = view.ruleHistorySearch.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
            val filtered = if (query.isEmpty()) {
                candidates
            } else {
                candidates.filter { candidate ->
                    historyCandidateName(baseOwner, candidate).lowercase(Locale.ROOT).contains(query) ||
                        candidate.owner.lowercase(Locale.ROOT).contains(query) ||
                        candidate.thread.orEmpty().lowercase(Locale.ROOT).contains(query)
                }
            }
            adapter.submit(filtered)
            view.ruleHistoryMeta.text = if (filtered.size == candidates.size) {
                "全部历史去重后 ${candidates.size} 个候选"
            } else {
                "匹配 ${filtered.size} / ${candidates.size} 个候选"
            }
            view.ruleHistoryEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            view.ruleHistoryList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        view.ruleHistorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.ruleHistoryCancel.setOnClickListener { dialog.dismiss() }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        renderFilter()
        dialog.show()
    }

    private fun showThreadWildcardSuggestion(
        suggestion: ThreadWildcardSuggestion,
        onSelect: (String) -> Unit
    ) {
        val view = DialogThreadWildcardBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        view.wildcardExactName.text = suggestion.exactName
        view.wildcardPattern.text = suggestion.pattern
        view.wildcardMatchTitle.text =
            "${suggestion.pattern} 会匹配以下 ${suggestion.matchedNames.size} 个历史线程"
        view.wildcardMatchedNames.text = suggestion.matchedNames
            .mapIndexed { index, name -> "${index + 1}. $name" }
            .joinToString("\n")
        view.wildcardMatchList.layoutParams = view.wildcardMatchList.layoutParams.apply {
            height = dp((minOf(suggestion.matchedNames.size, 5) * 25 + 24).toFloat())
        }

        fun finish(name: String) {
            onSelect(name)
            dialog.dismiss()
        }

        view.wildcardKeepExact.setOnClickListener { finish(suggestion.exactName) }
        view.wildcardUsePattern.setOnClickListener { finish(suggestion.pattern) }
        view.wildcardClose.setOnClickListener { dialog.dismiss() }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        dialog.show()
    }

    private inner class RuleHistoryAdapter(
        private val baseOwner: String,
        private val onSelect: (RuleHistoryCandidate) -> Unit
    ) : RecyclerView.Adapter<RuleHistoryAdapter.Holder>() {
        private var items: List<RuleHistoryCandidate> = emptyList()

        fun submit(value: List<RuleHistoryCandidate>) {
            items = value
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemRuleHistoryCandidateBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val candidate = items[position]
            holder.binding.candidateName.text = historyCandidateName(baseOwner, candidate)
            val scopeLabel = when {
                candidate.kind == RuleHistoryKind.CHILD_PROCESS -> "子进程"
                candidate.owner == baseOwner -> "主进程"
                candidate.owner.startsWith("$baseOwner:") -> candidate.owner.removePrefix(baseOwner)
                else -> candidate.owner
            }
            holder.binding.candidateType.text = scopeLabel
            holder.binding.candidateType.setBackgroundResource(
                if (candidate.kind == RuleHistoryKind.CHILD_PROCESS) {
                    R.drawable.bg_rule_type_main
                } else {
                    R.drawable.bg_rule_type_thread
                }
            )
            holder.binding.candidateType.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (candidate.kind == RuleHistoryKind.CHILD_PROCESS) {
                        R.color.brand_secondary
                    } else {
                        R.color.brand_primary_dark
                    }
                )
            )
            holder.binding.candidateOwner.text =
                "${candidate.owner} · ${formatRuleHistoryTime(candidate.epoch)}"
            holder.binding.candidateAvg.text = candidate.avg?.let {
                String.format(Locale.US, "AVG %.1f%%", it)
            } ?: "AVG --"
            holder.binding.candidateMax.text = candidate.max?.let {
                String.format(Locale.US, "MAX %.1f%%", it)
            } ?: "MAX --"
            holder.binding.root.setOnClickListener { onSelect(candidate) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            val binding: ItemRuleHistoryCandidateBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }

    private fun historyCandidateName(
        baseOwner: String,
        candidate: RuleHistoryCandidate
    ): String {
        return candidate.thread ?: candidate.owner
            .removePrefix(baseOwner)
            .ifBlank { candidate.owner }
    }

    private fun formatRuleHistoryTime(epoch: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epoch * 1000L))
    }

    private fun formatCpuSet(cpus: Set<Int>): String {
        val sorted = cpus.sorted()
        if (sorted.isEmpty()) return "未知"
        val ranges = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (cpu in sorted.drop(1)) {
            if (cpu == end + 1) {
                end = cpu
            } else {
                ranges += if (start == end) "$start" else "$start-$end"
                start = cpu
                end = cpu
            }
        }
        ranges += if (start == end) "$start" else "$start-$end"
        return ranges.joinToString(",")
    }

    private fun parseCpuSet(ranges: String): Set<Int> {
        val cpus = linkedSetOf<Int>()
        for (part in ranges.split(',')) {
            val value = part.trim()
            if (value.isEmpty()) continue
            val bounds = value.split('-', limit = 2)
            if (bounds.size == 2) {
                val start = bounds[0].toIntOrNull() ?: continue
                val end = bounds[1].toIntOrNull() ?: continue
                cpus.addAll(if (start <= end) start..end else end..start)
            } else {
                value.toIntOrNull()?.let(cpus::add)
            }
        }
        return cpus
    }

    private fun isContinuousCpuSelection(cpus: Set<Int>): Boolean {
        if (cpus.isEmpty()) return false
        val sorted = cpus.sorted()
        return sorted.last() - sorted.first() + 1 == sorted.size
    }

    private fun showRulesError(view: DialogConfigRulesBinding, message: String?) {
        view.rulesError.text = message.orEmpty()
        view.rulesError.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun saveConfiguredRules(
        dialog: BottomSheetDialog,
        view: DialogConfigRulesBinding,
        targets: List<String>,
        expectedOriginalLines: List<String>,
        replacements: Map<Int, String>,
        addedLines: List<String>,
        onFailed: () -> Unit
    ): Boolean {
        val lines = (expectedOriginalLines.indices.mapNotNull(replacements::get) + addedLines)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) {
            showRulesError(view, "至少保留一条当前应用的配置规则")
            return false
        }
        val editedRules = lines.joinToString("\n")
        val quickCheck = DaemonBridge.validateConfigRulesForPackages(targets, editedRules)
        when {
            quickCheck.invalidLines.isNotEmpty() -> {
                showRulesError(view, "存在格式错误的规则：${quickCheck.invalidLines.first()}")
                return false
            }
            quickCheck.foreignLines.isNotEmpty() -> {
                showRulesError(view, "不能保存其他应用的规则：${quickCheck.foreignLines.first()}")
                return false
            }
            quickCheck.invalidCoreLines.isNotEmpty() -> {
                showRulesError(view, "核心范围不合理：${quickCheck.invalidCoreLines.first()}")
                return false
            }
            quickCheck.validLines.isEmpty() -> {
                showRulesError(view, "至少保留一条当前应用的配置规则")
                return false
            }
        }
        showRulesError(view, null)
        thread {
            val allowedCpus = DaemonBridge.readConfigAllowedCpus().takeIf { it.isNotEmpty() }
            val result = DaemonBridge.replaceConfigRulesPreservingLayout(
                pkgs = targets,
                expectedOriginalLines = expectedOriginalLines,
                replacements = replacements,
                addedLines = addedLines,
                allowedCpus = allowedCpus
            )
            val ok = result == DaemonBridge.ConfigReplaceResult.SUCCESS
            val config = if (ok) ConfigReader.readPackages() else null
            val fullLists = config?.let { buildAppLists(it, processNames) }
            runOnUiThreadIfAlive {
                if (ok) {
                    if (fullLists != null) {
                        appLists = fullLists
                        buildAppList()
                    } else {
                        refreshAppList()
                    }
                    dialog.dismiss()
                    toast("配置已保存")
                } else {
                    onFailed()
                    showRulesError(
                        view,
                        when (result) {
                            DaemonBridge.ConfigReplaceResult.SOURCE_CHANGED ->
                                "配置文件已被其他程序修改，请关闭弹窗后重新打开"
                            DaemonBridge.ConfigReplaceResult.INVALID ->
                                "规则校验失败，请检查规则和 CPU 核心范围"
                            else -> "保存失败，请检查 Root 权限"
                        }
                    )
                    toast("保存配置失败")
                }
            }
        }
        return true
    }

    private fun toast(msg: String) {
        AppToast.show(this, msg)
    }
}
