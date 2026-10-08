package top.qixia.threads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread
import top.qixia.threads.databinding.ActivityMainBinding
import top.qixia.threads.databinding.DialogAutoStartCalibrationWarningBinding
import top.qixia.threads.databinding.DialogConfigRuleEditBinding
import top.qixia.threads.databinding.DialogConfigRulesBinding
import top.qixia.threads.databinding.DialogConfiguredAppManageBinding
import top.qixia.threads.databinding.DialogDeleteConfigBinding
import top.qixia.threads.databinding.DialogDiscardRulesBinding
import top.qixia.threads.databinding.DialogFloatingInterruptionBinding
import top.qixia.threads.databinding.DialogRuleHistoryPickerBinding
import top.qixia.threads.databinding.DialogThreadWildcardBinding
import top.qixia.threads.databinding.DialogThreadWildcardBatchBinding
import top.qixia.threads.databinding.ItemAddAppBinding
import top.qixia.threads.databinding.ItemAutoAppBinding
import top.qixia.threads.databinding.ItemConfigRuleBinding
import top.qixia.threads.databinding.ItemConfiguredAppBinding
import top.qixia.threads.databinding.ItemRuleHistoryCandidateBinding
import top.qixia.threads.databinding.ItemThreadRuleEditorBinding
import top.qixia.threads.databinding.ItemThreadWildcardChoiceBinding
import top.qixia.threads.databinding.ViewUsageGuideOverlayBinding
import top.qixia.threads.db.QixiaThreadsDbHelper
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引导授予悬浮窗权限，并列出配置文件中的待校准、可添加和已配置应用。
 * 每个应用项显示图标/名称，并按当前模块状态启用启动、查看、删除等操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRoot = false
    private var daemonRunning = false
    private var daemonRuntime = DaemonBridge.DaemonRuntime(running = false)
    private var ruleHealth: Map<String, DaemonBridge.RuleHealth> = emptyMap()
    private var foregroundHelperStatus = ForegroundHelperStatus()
    private var moduleVersion: DaemonBridge.ModuleVersion? = null
    private var moduleCompatible = false
    private var pendingModuleUpdate = false
    private var moduleWarningShown = false
    private var startupUpdateCheckStarted = false
    private var startupUpdateDialogShowing = false
    private var floatingInterruptionDialogShowing = false
    private var pendingFloatingLaunchPkg: String? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val pkg = pendingFloatingLaunchPkg
        pendingFloatingLaunchPkg = null
        if (pkg != null && !activityDestroyed) launchAppWithBall(pkg)
    }
    private var appTab = AppTab.PENDING
    private var appSearchQuery = ""
    private var appLists = AppLists()
    private var environmentLoading = true
    private var appListsLoading = true
    private var addableAppsLoading = true
    private var hideMissingConfigured = false
    private var autoStartCalibrationEnabled = false
    private var autoStartCalibrationDelayMs = 0L
    private var autoStartCalibrationSwitchUpdating = false
    private var autoStartCalibrationWarningTimer: CountDownTimer? = null
    private var autoStartCalibrationWarningDialog: BottomSheetDialog? = null
    private var autoStartCalibrationWarningDraft: String? = null
    private var autoStartCalibrationWarningDeadline = 0L
    private var environmentLoadingShownAt = 0L
    private var appSearchRender: Runnable? = null
    private var emptyIconAnimator: ObjectAnimator? = null
    private var processNames: Set<String> = emptySet()
    private val iconCache = LruCache<String, Drawable>(768)
    private val pendingIconLoads = Collections.synchronizedSet(mutableSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor()
    private val rulesLoadingPackages = mutableSetOf<String>()
    private val environmentRequests = RequestGeneration()
    private val appListRequests = RequestGeneration()
    private val mutationRequests = RequestGeneration()
    private val ruleDialogRequests = RequestGeneration()
    private val lifecycleRequests = RequestGeneration()
    private var ruleHealthRevision = 0L
    private var configMutationInFlight = 0
    private var activityResumed = false
    private var resumedAt = 0L
    private var settledHealthRefresh: Runnable? = null
    private var activeRuleHealthObserver: ((Map<String, DaemonBridge.RuleHealth>) -> Unit)? = null
    @Volatile private var startupLoadInFlight = false
    private val foregroundRefreshesInFlight = AtomicInteger(0)
    private val appListRefreshesInFlight = AtomicInteger(0)
    @Volatile private var activityDestroyed = false
    private var firstResume = true
    private lateinit var appAdapter: AppAdapter
    private lateinit var bottomNavigation: ResponsiveBottomNavigationView
    private lateinit var bottomNavigationBlur: BackdropBlurLayout
    private var selectedTopLevelPage = R.id.navApps
    private var lastAppsPageRefreshAt = SystemClock.elapsedRealtime()
    private var appsPageRefreshRunnable: Runnable? = null
    private var usageGuideBinding: ViewUsageGuideOverlayBinding? = null
    private var usageGuideSteps: List<UsageGuide.Step> = emptyList()
    private var usageGuideIndex = 0
    private var usageGuideForced = false
    private var usageGuidePreviousPage = R.id.navApps
    private var usageGuidePreviousAppTab = AppTab.PENDING
    private var usageGuidePreviousSettingsTab = 0
    private var usageGuideBackCallback: OnBackPressedCallback? = null
    private var startupGuideShown = false
    private var usageGuideAddInFlight = false
    private var usageGuideWaitingForPrerequisites = false
    private var usageGuideRootRequestInFlight = false
    private var usageGuideRootRequestAttempted = false
    private var usageGuideSkipNextResumeRefresh = false
    private var activeRuleDraftProvider: (() -> ConfigRulesRestore)? = null
    private var activeRuleDraftToken: Any? = null
    private var activeRuleEditorDraftProvider: (() -> RuleEditorRestore)? = null
    private var activeRuleEditorToken: Any? = null
    private var ruleDraftRestoreStarted = false

    private val ruleDraftHolder: RuleDraftHolder by lazy {
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == RuleDraftHolder::class.java)
                    @Suppress("UNCHECKED_CAST")
                    return RuleDraftHolder() as T
                }
            }
        )[RuleDraftHolder::class.java]
    }

    private data class RuleDraftLine(
        val sourceIndex: Int?,
        val line: String
    )

    private data class ThreadEditorRestore(
        val owner: String,
        val name: String,
        val cpus: Set<Int>
    )

    private data class RuleEditorRestore(
        val sourceIndex: Int?,
        val draftIndex: Int?,
        val checkedType: Int,
        val childSuffix: String,
        val cpuSelections: Map<Int, Set<Int>>,
        val threads: List<ThreadEditorRestore>
    )

    private data class ConfigRulesRestore(
        val entryPkg: String,
        val targets: List<String>,
        val originalLines: List<String>,
        val draftLines: List<RuleDraftLine>,
        val allowedCpus: Set<Int>,
        val searchQuery: String,
        val filter: ConfigRuleFilter,
        val expandedChildOwners: Set<String>,
        val editor: RuleEditorRestore?
    )

    private class RuleDraftHolder : ViewModel() {
        var restore: ConfigRulesRestore? = null
    }
    private var pendingUsageGuideRestore: UsageGuideRestore? = null

    private data class UsageGuideRestore(
        val stepIds: List<String>,
        val index: Int,
        val forced: Boolean,
        val previousPage: Int,
        val previousAppTab: AppTab,
        val previousSettingsTab: Int
    )

    private data class ForegroundHelperStatus(
        val state: DaemonBridge.TaskForegroundState? = null,
        val startRequested: Boolean = false
    )

    private companion object {
        const val PREFS_NAME = "qixia_prefs"
        const val PREF_HIDE_MISSING_CONFIGURED = "hide_missing_configured"
        const val PREF_AUTO_START_CALIBRATION = "auto_start_calibration"
        const val PREF_AUTO_START_CALIBRATION_DELAY_MS = "auto_start_calibration_delay_ms"
        const val AUTO_START_CALIBRATION_WARNING_MS = 5_000L
        const val MIN_ENV_LOADING_MS = 1800L
        const val RULE_TOOLS_THRESHOLD = 9
        const val RULE_SWIPE_REVEAL_DP = 64f
        const val RULE_SWIPE_SNAP_MS = 180L
        const val RULE_HEALTH_SETTLE_MS = 2600L
        const val TOP_LEVEL_REFRESH_INTERVAL_MS = 5_000L
        const val MAX_EDITOR_CPU_COUNT = 128
        const val MAX_RULE_HISTORY_SELECTION = 32
        const val STATE_TOP_LEVEL_PAGE = "top_level_page"
        const val STATE_APP_TAB = "app_tab"
        const val STATE_APP_SEARCH_QUERY = "app_search_query"
        const val STATE_PENDING_FLOATING_LAUNCH_PKG = "pending_floating_launch_pkg"
        const val STATE_USAGE_GUIDE_ACTIVE = "usage_guide_active"
        const val STATE_USAGE_GUIDE_STEP_IDS = "usage_guide_step_ids"
        const val STATE_USAGE_GUIDE_INDEX = "usage_guide_index"
        const val STATE_USAGE_GUIDE_FORCED = "usage_guide_forced"
        const val STATE_USAGE_GUIDE_PREVIOUS_PAGE = "usage_guide_previous_page"
        const val STATE_USAGE_GUIDE_PREVIOUS_APP_TAB = "usage_guide_previous_app_tab"
        const val STATE_USAGE_GUIDE_PREVIOUS_SETTINGS_TAB = "usage_guide_previous_settings_tab"
        const val STATE_AUTO_CALIBRATION_WARNING_ACTIVE = "auto_calibration_warning_active"
        const val STATE_AUTO_CALIBRATION_WARNING_DRAFT = "auto_calibration_warning_draft"
        const val STATE_AUTO_CALIBRATION_WARNING_REMAINING = "auto_calibration_warning_remaining"
        val TOP_LEVEL_PAGE_IDS = setOf(
            R.id.navApps,
            R.id.navEnvironment,
            R.id.navHistory,
            R.id.navLog,
            R.id.navSettings
        )
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
        val ruleCount: Int,
        val missedRuleCount: Int = 0,
        val pendingReviewRuleCount: Int = 0,
        val missedRuleKinds: Set<RuleHealthKind> = emptySet(),
        val pendingReviewRuleKinds: Set<RuleHealthKind> = emptySet()
    )

    private data class ConfiguredAppHealthUi(
        val label: String?,
        val description: String?
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
        val stableKey: String,
        val listIndex: Int?,
        val rule: EditableConfigRule?,
        val health: DaemonBridge.RuleHealth?,
        val kind: ConfigRuleRowKind = ConfigRuleRowKind.RULE,
        val owner: String = rule?.owner.orEmpty(),
        val childCount: Int = 0,
        val expanded: Boolean = false,
        val isLastChild: Boolean = false
    )

    private enum class ConfigRuleRowKind {
        RULE,
        CHILD_PROCESS_GROUP,
        CHILD_THREAD
    }

    private enum class ConfigRuleFilter {
        ALL,
        MAIN,
        CHILD,
        THREAD
    }

    private enum class RuleHealthKind {
        THREAD,
        CHILD_PROCESS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appTab = AppTab.entries.getOrElse(
            savedInstanceState?.getInt(STATE_APP_TAB, AppTab.PENDING.ordinal)
                ?: AppTab.PENDING.ordinal
        ) { AppTab.PENDING }
        appSearchQuery = savedInstanceState?.getString(STATE_APP_SEARCH_QUERY).orEmpty()
        pendingFloatingLaunchPkg = savedInstanceState
            ?.getString(STATE_PENDING_FLOATING_LAUNCH_PKG)
            ?.takeIf(String::isNotBlank)
        pendingUsageGuideRestore = savedInstanceState?.takeIf {
            it.getBoolean(STATE_USAGE_GUIDE_ACTIVE, false)
        }?.let { state ->
            UsageGuideRestore(
                stepIds = state.getStringArrayList(STATE_USAGE_GUIDE_STEP_IDS).orEmpty(),
                index = state.getInt(STATE_USAGE_GUIDE_INDEX, 0),
                forced = state.getBoolean(STATE_USAGE_GUIDE_FORCED, false),
                previousPage = state.getInt(STATE_USAGE_GUIDE_PREVIOUS_PAGE, R.id.navApps),
                previousAppTab = AppTab.entries.getOrElse(
                    state.getInt(STATE_USAGE_GUIDE_PREVIOUS_APP_TAB, AppTab.PENDING.ordinal)
                ) { AppTab.PENDING },
                previousSettingsTab = state.getInt(STATE_USAGE_GUIDE_PREVIOUS_SETTINGS_TAB, 0)
            )
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        val pendingGuideSteps = UsageGuide.pendingSteps(this)
        setContentView(binding.root)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        bottomNavigationBlur = findViewById(R.id.bottomNavigationBlur)
        val bottomNavigationHost = findViewById<View>(R.id.bottomNavigationHost)
        SystemBars.applyEdgeToEdge(
            this,
            binding.root,
            binding.mainHeader,
            bottomOverlay = bottomNavigationHost
        )
        bottomNavigationBlur.setupWith(binding.mainContent)
        centerFloatingBottomNavigation()
        setupTopLevelNavigation(savedInstanceState)
        setupTopLevelBackNavigation()
        environmentLoadingShownAt = SystemClock.uptimeMillis()
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hideMissingConfigured = preferences.getBoolean(PREF_HIDE_MISSING_CONFIGURED, false)
        autoStartCalibrationEnabled = preferences.getBoolean(PREF_AUTO_START_CALIBRATION, false)
        autoStartCalibrationDelayMs = AutoStartCalibrationDelay.normalize(
            preferences.getLong(PREF_AUTO_START_CALIBRATION_DELAY_MS, 0L)
        )

        binding.statusSection.root.setOnClickListener {
            selectTopLevelPage(R.id.navEnvironment)
        }
        binding.appRefresh.setOnRefreshListener {
            refreshAppList()
        }
        setupAppTabs()
        setupAppSearch()
        setupAutoStartCalibration()
        if (savedInstanceState?.getBoolean(STATE_AUTO_CALIBRATION_WARNING_ACTIVE, false) == true) {
            val draft = savedInstanceState.getString(STATE_AUTO_CALIBRATION_WARNING_DRAFT)
            val remaining = savedInstanceState.getLong(
                STATE_AUTO_CALIBRATION_WARNING_REMAINING,
                AUTO_START_CALIBRATION_WARNING_MS
            )
            binding.root.post {
                if (!activityDestroyed && !isFinishing && !isDestroyed) {
                    showAutoStartCalibrationWarning(draft, remaining)
                }
            }
        }
        setupConfiguredFilter()
        setupAppRecycler()
        binding.appSection.appTabs.getTabAt(appTab.ordinal)?.let { tab ->
            if (!tab.isSelected) binding.appSection.appTabs.selectTab(tab)
        }
        if (appSearchQuery.isNotEmpty()) {
            binding.appSection.appSearchInput.setText(appSearchQuery)
            binding.appSection.appSearchInput.setSelection(appSearchQuery.length)
        }
        renderEnvironmentOverview()
        buildAppList()
        restoreModuleUpdateDialogIfNeeded()
        scheduleTopLevelPrewarm()

        // root 检测 + 读配置 + 批量导入旧 .log 放后台线程，避免 su 弹窗阻塞 UI
        val startupEnvironmentGeneration = environmentRequests.next()
        val startupAppListGeneration = appListRequests.next()
        startupLoadInFlight = true
        thread {
            try {
            val r = DaemonBridge.hasRoot()
            val pendingUpdate = if (r) DaemonBridge.hasPendingModuleUpdate() else false
            val version = if (r && !pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = isCompatibleModule(version)
            val runtime = if (r && compatible && !pendingUpdate) {
                DaemonBridge.readDaemonRuntime()
            } else {
                DaemonBridge.DaemonRuntime(running = false)
            }
            val running = runtime.running
            val helperStatus = if (r && compatible && !pendingUpdate) queryForegroundHelperStatus() else ForegroundHelperStatus()
            val enabled = r && compatible && running

            runOnUiThreadIfEnvironmentCurrent(startupEnvironmentGeneration) {
                runAfterEnvironmentLoadingMinimum {
                    if (!environmentRequests.isCurrent(startupEnvironmentGeneration)) {
                        return@runAfterEnvironmentLoadingMinimum
                    }
                    hasRoot = r
                    pendingModuleUpdate = pendingUpdate
                    moduleVersion = version
                    moduleCompatible = compatible
                    daemonRunning = running
                    daemonRuntime = runtime
                    foregroundHelperStatus = helperStatus
                    environmentLoading = false
                    if (!enabled) {
                        appListRequests.next()
                        updateRuleHealthSnapshot(emptyMap())
                        appListsLoading = false
                        addableAppsLoading = false
                        processNames = emptySet()
                        appLists = AppLists()
                    }
                    renderEnvironmentOverview()
                    buildAppList()
                    maybeShowStartupUsageGuide(pendingGuideSteps)
                    showModuleWarningIfNeeded()
                    maybeCheckStartupUpdate()
                }
            }

            val config = if (enabled) {
                ConfigReader.readPackagesOrNull()
            } else {
                ConfigReader.ConfigPackages(emptyList(), emptyList())
            }
            val health = if (enabled) DaemonBridge.readRuleHealthOrNull().orEmpty() else emptyMap()
            val visibleLists = when {
                !enabled -> AppLists()
                config != null -> buildConfiguredLists(config, emptySet(), health)
                else -> null
            }
            if (visibleLists != null) {
                runOnUiThreadIfAppListCurrent(startupAppListGeneration) {
                    updateRuleHealthSnapshot(health)
                    appListsLoading = false
                    processNames = emptySet()
                    appLists = if (addableAppsLoading) {
                        visibleLists
                    } else {
                        appLists.copy(
                            pending = visibleLists.pending,
                            configured = visibleLists.configured
                        )
                    }
                    buildAppList()
                }
            } else if (enabled) {
                runOnUiThreadIfEnvironmentCurrent(startupEnvironmentGeneration) {
                    runAfterEnvironmentLoadingMinimum {
                        if (environmentRequests.isCurrent(startupEnvironmentGeneration) &&
                            appListRequests.isCurrent(startupAppListGeneration)) {
                            refreshAppList(scrollAddableToTop = false)
                        }
                    }
                }
            }

            // 格式整理和进程名识别可能随配置规模增长，不应阻塞运行环境检测页面。
            val startupFormatResult = if (r && compatible && !pendingUpdate) {
                DaemonBridge.detectAndApplyRuleOutputFormat()
            } else {
                null
            }
            val configAfterFormat = if (enabled && startupFormatResult?.success == true &&
                startupFormatResult.changed) {
                ConfigReader.readPackagesOrNull() ?: config
            } else {
                config
            }
            val healthAfterFormat = if (enabled && startupFormatResult?.success == true &&
                startupFormatResult.changed) {
                DaemonBridge.readRuleHealthOrNull() ?: health
            } else {
                health
            }
            val resolvedNames = configAfterFormat
                ?.let { resolveProcessComponentNames(it, enabled) }
                .orEmpty()
            val fullLists = when {
                !enabled -> AppLists()
                configAfterFormat != null -> buildAppLists(
                    configAfterFormat,
                    resolvedNames,
                    healthAfterFormat
                )
                else -> null
            }
            if (fullLists != null) {
                runOnUiThreadIfAppListCurrent(startupAppListGeneration) {
                    updateRuleHealthSnapshot(healthAfterFormat)
                    addableAppsLoading = false
                    appLists = fullLists
                    buildAppList()
                }
            }
            if (startupFormatResult != null) {
                runOnUiThreadIfEnvironmentCurrent(startupEnvironmentGeneration) {
                    if (startupFormatResult.success && startupFormatResult.changed) {
                        if (startupFormatResult.migratedDeprecatedFormat) {
                            toast("已将旧区块规则转换为原作者区块格式")
                        } else {
                            val formatName = startupRuleOutputFormatName(startupFormatResult.format)
                            val mixedHint = if (startupFormatResult.mixed) "（按主要写法识别）" else ""
                            toast("已识别现有规则为$formatName$mixedHint")
                        }
                    } else if (!startupFormatResult.success) {
                        val message = startupFormatResult.detail ?: "现有规则自动转换失败"
                        android.util.Log.w("QixiaThreads", "startup rule format conversion failed: $message")
                        toast(message)
                    }
                }
            }

            configAfterFormat?.let { migrateLogsLater(enabled, it) }
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "startup environment refresh failed", error)
                runOnUiThreadIfEnvironmentCurrent(startupEnvironmentGeneration) {
                    // 后台读取失败时保留已经显示的快照，但必须解除加载态，避免页面永久转圈。
                    environmentLoading = false
                    appListsLoading = false
                    addableAppsLoading = false
                    binding.appRefresh.isRefreshing = false
                    renderEnvironmentOverview()
                    buildAppList()
                    toast("运行环境读取失败，请稍后重试")
                }
            } finally {
                startupLoadInFlight = false
            }
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
        activityResumed = true
        val lifecycleGeneration = lifecycleRequests.next()
        resumedAt = SystemClock.uptimeMillis()
        renderEnvironmentOverview()
        val appsVisible = selectedTopLevelPage == R.id.navApps
        val shouldRefreshConfig = firstResume.not() && appsVisible
        val skipGuideRefresh = usageGuideSkipNextResumeRefresh
        usageGuideSkipNextResumeRefresh = false
        firstResume = false
        // 守护进程、Root 授权和配置可能在后台变化，回到前台时后台重查一次。
        if (shouldRefreshConfig && !usageGuideRootRequestInFlight && !skipGuideRefresh) {
            refreshForegroundState(
                refreshConfig = true,
                lifecycleGeneration = lifecycleGeneration
            )
        }
        if (appsVisible) {
            scheduleSettledHealthRefresh(resumedAt)
        } else {
            mainHandler.post {
                if (activityResumed && selectedTopLevelPage != R.id.navApps) {
                    notifyTopLevelPageSelected(selectedTopLevelPage)
                }
            }
        }
        showFloatingInterruptionIfNeeded()
    }

    private fun setupTopLevelNavigation(savedInstanceState: Bundle?) {
        selectedTopLevelPage = savedInstanceState?.getInt(STATE_TOP_LEVEL_PAGE, R.id.navApps)
            ?: R.id.navApps
        bottomNavigation.setOnItemSelectedListener { itemId ->
            showTopLevelPage(itemId)
        }
        bottomNavigation.setOnItemReselectedListener { }
        bottomNavigation.setSelectedItemSilently(selectedTopLevelPage)
        showTopLevelPage(selectedTopLevelPage, force = true)
    }

    private fun centerFloatingBottomNavigation() {
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.bottom_navigation_screen_margin)
        val minWidth = resources.getDimensionPixelSize(R.dimen.bottom_navigation_min_width)
        val maxWidth = resources.getDimensionPixelSize(R.dimen.bottom_navigation_max_width)
        val availableWidth = (resources.displayMetrics.widthPixels - horizontalPadding).coerceAtLeast(minWidth)
        val targetWidth = minOf(availableWidth, maxWidth)
        (bottomNavigationBlur.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
            params.width = targetWidth
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bottomNavigationBlur.layoutParams = params
        }
    }

    private fun setupTopLevelBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedTopLevelPage != R.id.navApps) {
                    selectTopLevelPage(R.id.navApps)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    fun showUsageGuide(showAll: Boolean) {
        showUsageGuide(showAll, requestedSteps = null)
    }

    private fun maybeShowStartupUsageGuide(steps: List<UsageGuide.Step>) {
        pendingUsageGuideRestore?.let { restore ->
            val savedIds = restore.stepIds.toSet()
            val restoredSteps = UsageGuide.steps.filter { it.id in savedIds }
            pendingUsageGuideRestore = null
            if (restoredSteps.isNotEmpty()) {
                startupGuideShown = true
                showUsageGuide(
                    showAll = !restore.forced,
                    requestedSteps = restoredSteps,
                    restore = restore
                )
                return
            }
        }
        if (startupGuideShown || steps.isEmpty() || activityDestroyed) return
        startupGuideShown = true
        showUsageGuide(showAll = false, requestedSteps = steps)
    }

    private fun showUsageGuide(
        showAll: Boolean,
        requestedSteps: List<UsageGuide.Step>?,
        restore: UsageGuideRestore? = null
    ) {
        if (activityDestroyed || isFinishing || isDestroyed || usageGuideBinding != null) return
        val steps = requestedSteps ?: if (showAll) UsageGuide.steps else UsageGuide.pendingSteps(this)
        if (steps.isEmpty()) return

        usageGuideSteps = steps
        usageGuideIndex = restore?.index?.coerceIn(0, steps.lastIndex) ?: 0
        usageGuideForced = restore?.forced ?: !showAll
        usageGuideAddInFlight = false
        usageGuideWaitingForPrerequisites = false
        usageGuideRootRequestInFlight = false
        usageGuideRootRequestAttempted = false
        usageGuideSkipNextResumeRefresh = false
        usageGuidePreviousPage = restore?.previousPage ?: selectedTopLevelPage
        usageGuidePreviousAppTab = restore?.previousAppTab ?: appTab
        usageGuidePreviousSettingsTab = restore?.previousSettingsTab
            ?: (supportFragmentManager.findFragmentByTag(topLevelFragmentTag(R.id.navSettings)) as? SettingsFragment)
                ?.currentUsageGuideTabIndex() ?: 0

        val overlay = ViewUsageGuideOverlayBinding.inflate(layoutInflater, binding.root, false)
        usageGuideBinding = overlay
        binding.root.addView(
            overlay.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.root.bringToFront()
        overlay.guideCoachClose.visibility = if (usageGuideForced) View.GONE else View.VISIBLE
        overlay.guideCoachClose.setOnClickListener { finishUsageGuide(markCompleted = false) }
        overlay.guideCoachPrevious.setOnClickListener {
            showPreviousUsageGuideStep()
        }
        overlay.guideCoachNext.setOnClickListener { handleUsageGuideNext() }

        usageGuideBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!usageGuideForced) finishUsageGuide(markCompleted = false)
            }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
        renderUsageGuideStep()
    }

    private fun renderUsageGuideStep() {
        val overlay = usageGuideBinding ?: return
        blockedState()?.let { blocked ->
            renderUsageGuidePrerequisite(blocked)
            return
        }
        usageGuideWaitingForPrerequisites = false
        overlay.guideCoachProgress.visibility = View.VISIBLE
        setUsageGuideNextStartMargin(10)
        val step = usageGuideSteps.getOrNull(usageGuideIndex) ?: return
        if (step.target == UsageGuide.Target.START_CALIBRATION && appLists.pending.isEmpty() &&
            usageGuideIndex < usageGuideSteps.lastIndex) {
            usageGuideIndex++
            renderUsageGuideStep()
            return
        }
        overlay.root.clearSpotlight()
        overlay.guideCoachMode.text = when {
            !usageGuideForced -> "完整教程"
            usageGuideSteps.size == UsageGuide.steps.size -> "首次使用"
            else -> "新功能"
        }
        overlay.guideCoachProgress.text = "${usageGuideIndex + 1} / ${usageGuideSteps.size}"
        val existingPendingGuide = step.target == UsageGuide.Target.ADD_APP && appLists.pending.isNotEmpty()
        val noAddableGuide = step.target == UsageGuide.Target.ADD_APP &&
            appLists.pending.isEmpty() && !addableAppsLoading && appLists.addable.isEmpty()
        overlay.guideCoachTitle.text = when {
            existingPendingGuide -> "已有待校准应用"
            noAddableGuide -> "暂时没有可添加应用"
            else -> step.title
        }
        overlay.guideCoachDescription.text = when {
            existingPendingGuide ->
                "已检测到待校准列表中有应用，无需重复添加。下一步会介绍右侧播放按钮，以及如何启动悬浮校准。"
            noAddableGuide ->
                "当前没有可添加的应用。可以先继续查看教程，安装新应用后再回到待校准列表。"
            else -> step.description
        }
        updateUsageGuideControls(step)
        overlay.root.contentDescription =
            "${overlay.guideCoachMode.text}，第 ${usageGuideIndex + 1} 步，共 ${usageGuideSteps.size} 步，${overlay.guideCoachTitle.text}"

        navigateToUsageGuideTarget(step.target)
        val expectedIndex = usageGuideIndex
        mainHandler.postDelayed(
            { resolveUsageGuideTarget(step.target, expectedIndex, attempt = 0) },
            90L
        )
        overlay.root.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun renderUsageGuidePrerequisite(blocked: EmptyState) {
        val overlay = usageGuideBinding ?: return
        usageGuideWaitingForPrerequisites = true
        overlay.root.clearSpotlight()
        overlay.guideCoachMode.text = "开始之前"
        overlay.guideCoachProgress.visibility = View.GONE
        overlay.guideCoachPrevious.visibility = View.GONE
        setUsageGuideNextStartMargin(0)

        val checkingEnvironment = environmentLoading && !usageGuideRootRequestInFlight
        overlay.guideCoachTitle.text = when {
            usageGuideRootRequestInFlight -> "正在等待 Root 授权"
            checkingEnvironment -> "正在检查运行环境"
            !hasRoot -> "需要 Root 权限"
            else -> blocked.title
        }
        overlay.guideCoachDescription.text = when {
            usageGuideRootRequestInFlight ->
                "请在 Root 管理器中允许 QixiaThreads。授权完成后会自动检查模块和应用列表，再从第 ${usageGuideIndex + 1} 步继续教程。"
            checkingEnvironment ->
                "正在确认 Root、模块和守护进程状态。准备完成后会自动进入教程，不需要重复点击。"
            !hasRoot -> {
                val retryHint = if (usageGuideRootRequestAttempted) {
                    "刚才没有检测到授权，可以点击下方按钮重新申请。"
                } else {
                    "请先授予权限，再开始教程。"
                }
                "应用列表、添加应用和悬浮校准都依赖 Root。$retryHint"
            }
            else -> "${blocked.desc}\n\n处理完成后点击重新检测，教程会从当前步骤继续。"
        }
        overlay.guideCoachNext.isEnabled = !usageGuideRootRequestInFlight && !checkingEnvironment
        overlay.guideCoachNext.text = when {
            usageGuideRootRequestInFlight -> "正在等待授权"
            checkingEnvironment -> "正在检测"
            !hasRoot && usageGuideRootRequestAttempted -> "重新申请 Root"
            !hasRoot -> "申请 Root 权限"
            else -> "重新检测"
        }
        overlay.root.contentDescription =
            "使用教程开始前检查，${overlay.guideCoachTitle.text}，${overlay.guideCoachDescription.text}"
        overlay.root.spotlight(binding.statusSection.root, overlay.guideCoachCard)
        overlay.root.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun setUsageGuideNextStartMargin(marginDp: Int) {
        val next = usageGuideBinding?.guideCoachNext ?: return
        val params = next.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.marginStart = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            marginDp.toFloat(),
            resources.displayMetrics
        ).toInt()
        next.layoutParams = params
    }

    private fun requestUsageGuidePrerequisites() {
        if (usageGuideBinding == null || usageGuideRootRequestInFlight || environmentLoading) return
        usageGuideRootRequestAttempted = true
        usageGuideRootRequestInFlight = true
        usageGuideSkipNextResumeRefresh = true
        environmentLoading = true
        appListsLoading = true
        addableAppsLoading = true
        renderEnvironmentOverview()
        buildAppList()
        renderUsageGuideStep()
        refreshForegroundState(
            refreshConfig = true,
            forceFullAppListRefresh = true,
            onApplied = {
                usageGuideRootRequestInFlight = false
                if (activityResumed) usageGuideSkipNextResumeRefresh = false
                refreshUsageGuideForEnvironmentState()
            }
        )
    }

    private fun refreshUsageGuideForEnvironmentState() {
        if (usageGuideBinding == null) return
        if (usageGuideWaitingForPrerequisites || blockedState() != null) {
            renderUsageGuideStep()
        }
    }

    private fun updateUsageGuideControls(step: UsageGuide.Step) {
        val overlay = usageGuideBinding ?: return
        overlay.guideCoachPrevious.visibility = if (usageGuideIndex == 0) View.INVISIBLE else View.VISIBLE
        overlay.guideCoachPrevious.isEnabled = !usageGuideAddInFlight
        when {
            usageGuideAddInFlight -> {
                overlay.guideCoachNext.isEnabled = false
                overlay.guideCoachNext.text = "正在添加"
            }
            requiresUsageGuideAdd(step) -> {
                overlay.guideCoachNext.isEnabled = !addableAppsLoading
                overlay.guideCoachNext.text =
                    if (addableAppsLoading) "正在加载应用" else "请点击列表中的 +"
            }
            step.target == UsageGuide.Target.ADD_APP &&
                appLists.pending.isEmpty() && !addableAppsLoading && appLists.addable.isEmpty() -> {
                overlay.guideCoachNext.isEnabled = true
                overlay.guideCoachNext.text = "跳过此步"
            }
            usageGuideIndex == usageGuideSteps.lastIndex -> {
                overlay.guideCoachNext.isEnabled = true
                overlay.guideCoachNext.text = "完成"
            }
            else -> {
                overlay.guideCoachNext.isEnabled = true
                overlay.guideCoachNext.text = "下一步"
            }
        }
    }

    private fun requiresUsageGuideAdd(step: UsageGuide.Step): Boolean =
        usageGuideForced && step.target == UsageGuide.Target.ADD_APP &&
            appLists.pending.isEmpty() && (addableAppsLoading || appLists.addable.isNotEmpty())

    private fun handleUsageGuideNext() {
        if (usageGuideAddInFlight) return
        if (blockedState() != null) {
            requestUsageGuidePrerequisites()
            return
        }
        val step = usageGuideSteps.getOrNull(usageGuideIndex) ?: return
        if (requiresUsageGuideAdd(step)) {
            if (!addableAppsLoading) toast("请先在亮起的列表中添加一个应用")
            return
        }
        if (usageGuideIndex == usageGuideSteps.lastIndex) {
            finishUsageGuide(markCompleted = usageGuideForced)
            return
        }
        usageGuideIndex++
        renderUsageGuideStep()
    }

    private fun showPreviousUsageGuideStep() {
        if (usageGuideAddInFlight || usageGuideIndex <= 0) return
        var previous = usageGuideIndex - 1
        if (usageGuideSteps.getOrNull(previous)?.target == UsageGuide.Target.START_CALIBRATION &&
            appLists.pending.isEmpty()) {
            previous--
        }
        usageGuideIndex = previous.coerceAtLeast(0)
        renderUsageGuideStep()
    }

    private fun navigateToUsageGuideTarget(target: UsageGuide.Target) {
        when (target) {
            UsageGuide.Target.APP_TABS -> {
                selectTopLevelPage(R.id.navApps)
                selectAppTab(AppTab.PENDING)
            }

            UsageGuide.Target.ADD_APP -> {
                selectTopLevelPage(R.id.navApps)
                selectAppTab(if (appLists.pending.isEmpty()) AppTab.ADD else AppTab.PENDING)
            }

            UsageGuide.Target.START_CALIBRATION -> {
                selectTopLevelPage(R.id.navApps)
                selectAppTab(AppTab.PENDING)
            }

            UsageGuide.Target.CONFIGURED_APP -> {
                selectTopLevelPage(R.id.navApps)
                selectAppTab(AppTab.CONFIGURED)
            }

            UsageGuide.Target.ENVIRONMENT_TOOLS -> selectTopLevelPage(R.id.navEnvironment)
            UsageGuide.Target.HISTORY_AND_LOGS -> selectTopLevelPage(R.id.navHistory)
            UsageGuide.Target.RULE_GENERATION,
            UsageGuide.Target.RULE_GENERATION_LIMIT,
            UsageGuide.Target.SIMILAR_THREADS,
            UsageGuide.Target.PERFORMANCE_TIERS,
            UsageGuide.Target.PROCESS_FALLBACK,
            UsageGuide.Target.CPUSET_RUNTIME,
            UsageGuide.Target.HELP_BUTTON -> selectTopLevelPage(R.id.navSettings)
        }
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.executePendingTransactions()
        }
    }

    private fun resolveUsageGuideTarget(
        target: UsageGuide.Target,
        expectedIndex: Int,
        attempt: Int
    ) {
        val overlay = usageGuideBinding ?: return
        if (expectedIndex != usageGuideIndex || usageGuideSteps.getOrNull(expectedIndex)?.target != target) return

        val targetView = when (target) {
            UsageGuide.Target.APP_TABS -> binding.appSection.appTabs
            UsageGuide.Target.ADD_APP -> if (appLists.pending.isEmpty()) {
                addAppGuideTarget()
            } else {
                appGuideTarget(AppTab.PENDING, preferListRow = false)
            }
            UsageGuide.Target.START_CALIBRATION -> appGuideTarget(AppTab.PENDING, preferListRow = true)
            UsageGuide.Target.CONFIGURED_APP -> appGuideTarget(AppTab.CONFIGURED, preferListRow = true)
            UsageGuide.Target.ENVIRONMENT_TOOLS ->
                topLevelFragmentView(R.id.navEnvironment)?.findViewById(R.id.environmentDiagnosticRow)
            UsageGuide.Target.HISTORY_AND_LOGS -> bottomNavigation
            UsageGuide.Target.RULE_GENERATION,
            UsageGuide.Target.RULE_GENERATION_LIMIT,
            UsageGuide.Target.SIMILAR_THREADS,
            UsageGuide.Target.PERFORMANCE_TIERS,
            UsageGuide.Target.PROCESS_FALLBACK,
            UsageGuide.Target.CPUSET_RUNTIME,
            UsageGuide.Target.HELP_BUTTON ->
                (supportFragmentManager.findFragmentByTag(topLevelFragmentTag(R.id.navSettings)) as? SettingsFragment)
                    ?.prepareUsageGuideTarget(target)
        }

        if (targetView == null || !targetView.isShown || targetView.width <= 0 || targetView.height <= 0) {
            val maxAttempts = when {
                target == UsageGuide.Target.ADD_APP && addableAppsLoading -> 100
                target == UsageGuide.Target.RULE_GENERATION ||
                    target == UsageGuide.Target.RULE_GENERATION_LIMIT ||
                    target == UsageGuide.Target.SIMILAR_THREADS ||
                    target == UsageGuide.Target.PERFORMANCE_TIERS ||
                    target == UsageGuide.Target.PROCESS_FALLBACK ||
                    target == UsageGuide.Target.CPUSET_RUNTIME -> 160
                else -> 16
            }
            if (attempt < maxAttempts) {
                mainHandler.postDelayed(
                    { resolveUsageGuideTarget(target, expectedIndex, attempt + 1) },
                    70L
                )
            } else {
                overlay.root.spotlight(binding.mainContent, overlay.guideCoachCard)
                if (target == UsageGuide.Target.ADD_APP) {
                    updateUsageGuideControls(usageGuideSteps[expectedIndex])
                }
            }
            return
        }

        targetView.requestRectangleOnScreen(
            Rect(0, 0, targetView.width, targetView.height),
            false
        )
        mainHandler.postDelayed({
            val currentOverlay = usageGuideBinding ?: return@postDelayed
            if (expectedIndex == usageGuideIndex && targetView.isShown) {
                val step = usageGuideSteps[expectedIndex]
                val interactiveAdd = requiresUsageGuideAdd(step) && targetView.id == R.id.appRecycler
                currentOverlay.root.spotlight(
                    targetView,
                    currentOverlay.guideCoachCard,
                    allowTargetTouch = interactiveAdd
                )
                updateUsageGuideControls(step)
            }
        }, 110L)
    }

    private fun addAppGuideTarget(): View? {
        if (appTab != AppTab.ADD) selectAppTab(AppTab.ADD)
        if (binding.appSection.appRecycler.isShown &&
            binding.appSection.appRecycler.getChildAt(0) != null) {
            return binding.appSection.appRecycler
        }
        if (!addableAppsLoading && appLists.addable.isEmpty()) {
            val strip = binding.appSection.appTabs.getChildAt(0) as? ViewGroup
            return strip?.getChildAt(AppTab.ADD.ordinal) ?: binding.appSection.appTabs
        }
        return null
    }

    private fun onUsageGuideAutoAddStarted() {
        val step = usageGuideSteps.getOrNull(usageGuideIndex) ?: return
        if (usageGuideAddInFlight || !requiresUsageGuideAdd(step)) return
        usageGuideAddInFlight = true
        usageGuideBinding?.root?.clearSpotlight()
        updateUsageGuideControls(step)
    }

    private fun onUsageGuideAutoAddFinished(pkg: String, success: Boolean) {
        val step = usageGuideSteps.getOrNull(usageGuideIndex) ?: return
        if (!usageGuideAddInFlight || step.target != UsageGuide.Target.ADD_APP) return
        usageGuideAddInFlight = false
        if (success && appLists.pending.any { it.pkg == pkg }) {
            if (usageGuideIndex < usageGuideSteps.lastIndex) usageGuideIndex++
            renderUsageGuideStep()
        } else {
            selectAppTab(AppTab.ADD)
            renderUsageGuideStep()
        }
    }

    private fun appGuideTarget(tab: AppTab, preferListRow: Boolean): View {
        if (appTab != tab) selectAppTab(tab)
        if (preferListRow) {
            binding.appSection.appRecycler.getChildAt(0)?.let { return it }
        }
        val strip = binding.appSection.appTabs.getChildAt(0) as? ViewGroup
        return strip?.getChildAt(tab.ordinal) ?: binding.appSection.appTabs
    }

    private fun topLevelFragmentView(itemId: Int): View? =
        supportFragmentManager.findFragmentByTag(topLevelFragmentTag(itemId))?.view

    private fun finishUsageGuide(markCompleted: Boolean) {
        val overlay = usageGuideBinding ?: return
        if (markCompleted) UsageGuide.markCompleted(this, usageGuideSteps)
        usageGuideBinding = null
        usageGuideAddInFlight = false
        usageGuideWaitingForPrerequisites = false
        usageGuideRootRequestInFlight = false
        usageGuideRootRequestAttempted = false
        usageGuideSkipNextResumeRefresh = false
        usageGuideBackCallback?.remove()
        usageGuideBackCallback = null
        (overlay.root.parent as? ViewGroup)?.removeView(overlay.root)

        val previousPage = usageGuidePreviousPage
        selectTopLevelPage(previousPage)
        if (previousPage == R.id.navApps) {
            selectAppTab(usageGuidePreviousAppTab)
        } else if (previousPage == R.id.navSettings) {
            mainHandler.post {
                (supportFragmentManager.findFragmentByTag(topLevelFragmentTag(R.id.navSettings)) as? SettingsFragment)
                    ?.restoreUsageGuideTab(usageGuidePreviousSettingsTab)
            }
        }

        if (activityResumed) showFloatingInterruptionIfNeeded()
        showModuleWarningIfNeeded()
        maybeCheckStartupUpdate()
    }

    /**
     * 历史、日志与设置页面在主界面空闲后错开预热，避免首次点击时同步膨胀视图。
     */
    private fun scheduleTopLevelPrewarm() {
        mainHandler.postDelayed(
            { prewarmTopLevelPageWhenIdle(R.id.navHistory) },
            450L
        )
        mainHandler.postDelayed(
            { prewarmTopLevelPageWhenIdle(R.id.navLog) },
            850L
        )
        mainHandler.postDelayed(
            { prewarmTopLevelPageWhenIdle(R.id.navSettings) },
            1150L
        )
    }

    private fun prewarmTopLevelPageWhenIdle(itemId: Int) {
        if (activityDestroyed || isFinishing || isDestroyed) return
        if (startupLoadInFlight) {
            mainHandler.postDelayed({ prewarmTopLevelPageWhenIdle(itemId) }, 300L)
            return
        }
        Looper.myQueue().addIdleHandler {
            prewarmTopLevelPage(itemId)
            false
        }
    }

    private fun prewarmTopLevelPage(itemId: Int) {
        if (activityDestroyed || isFinishing || isDestroyed ||
            supportFragmentManager.isStateSaved || selectedTopLevelPage == itemId ||
            supportFragmentManager.findFragmentByTag(topLevelFragmentTag(itemId)) != null
        ) {
            return
        }
        val fragment = createTopLevelFragment(itemId)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.topLevelPageContainer, fragment, topLevelFragmentTag(itemId))
            .hide(fragment)
            .commitNow()
    }

    fun selectTopLevelPage(itemId: Int) {
        if (bottomNavigation.selectedItemId == itemId) {
            showTopLevelPage(itemId)
        } else {
            bottomNavigation.selectedItemId = itemId
        }
    }

    private fun showTopLevelPage(itemId: Int, force: Boolean = false): Boolean {
        if (itemId !in TOP_LEVEL_PAGE_IDS) return false
        if (!force && selectedTopLevelPage == itemId) return true

        selectedTopLevelPage = itemId
        if (itemId == R.id.navApps) {
            hideTopLevelFragments()
            binding.appsPage.visibility = View.VISIBLE
            binding.topLevelPageContainer.visibility = View.GONE
            onAppsPageSelected()
            return true
        }

        binding.appsPage.visibility = View.GONE
        binding.topLevelPageContainer.visibility = View.VISIBLE
        val tag = topLevelFragmentTag(itemId)
        val fragment = supportFragmentManager.findFragmentByTag(tag)
            ?: createTopLevelFragment(itemId)
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        TOP_LEVEL_PAGE_IDS.asSequence()
            .filter { it != R.id.navApps }
            .mapNotNull { supportFragmentManager.findFragmentByTag(topLevelFragmentTag(it)) }
            .filter { it !== fragment }
            .forEach(transaction::hide)
        val addingFragment = !fragment.isAdded
        if (!addingFragment) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.topLevelPageContainer, fragment, tag)
        }
        if (supportFragmentManager.isStateSaved) {
            transaction.commitAllowingStateLoss()
        } else if (addingFragment) {
            transaction.commitNow()
        } else {
            transaction.commit()
        }
        notifyTopLevelPageSelected(itemId, fragment)
        return true
    }

    private fun notifyTopLevelPageSelected(itemId: Int, knownFragment: Fragment? = null) {
        val fragment = knownFragment
            ?: supportFragmentManager.findFragmentByTag(topLevelFragmentTag(itemId))
        (fragment as? TopLevelFragment)?.onTopLevelPageSelected()
    }

    private fun onAppsPageSelected() {
        appsPageRefreshRunnable?.let(mainHandler::removeCallbacks)
        if (environmentLoading || startupLoadInFlight || !activityResumed) return
        val runnable = object : Runnable {
            override fun run() {
                if (activityDestroyed || isFinishing || isDestroyed ||
                    selectedTopLevelPage != R.id.navApps || !activityResumed) {
                    appsPageRefreshRunnable = null
                    return
                }
                if (startupLoadInFlight || configMutationInFlight > 0 ||
                    foregroundRefreshesInFlight.get() > 0 || appListRefreshesInFlight.get() > 0) {
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                val elapsed = SystemClock.elapsedRealtime() - lastAppsPageRefreshAt
                if (elapsed < TOP_LEVEL_REFRESH_INTERVAL_MS) {
                    mainHandler.postDelayed(this, TOP_LEVEL_REFRESH_INTERVAL_MS - elapsed)
                    return
                }
                appsPageRefreshRunnable = null
                lastAppsPageRefreshAt = SystemClock.elapsedRealtime()
                refreshForegroundState(refreshConfig = true)
            }
        }
        appsPageRefreshRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun hideTopLevelFragments() {
        val fragments = TOP_LEVEL_PAGE_IDS.asSequence()
            .filter { it != R.id.navApps }
            .mapNotNull { supportFragmentManager.findFragmentByTag(topLevelFragmentTag(it)) }
            .filter(Fragment::isVisible)
            .toList()
        if (fragments.isEmpty()) return
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        fragments.forEach(transaction::hide)
        if (supportFragmentManager.isStateSaved) {
            transaction.commitAllowingStateLoss()
        } else {
            transaction.commit()
        }
    }

    private fun createTopLevelFragment(itemId: Int): Fragment = when (itemId) {
        R.id.navEnvironment -> EnvironmentFragment()
        R.id.navHistory -> HistoryListFragment()
        R.id.navLog -> LogFragment()
        R.id.navSettings -> SettingsFragment()
        else -> error("未知顶级页面: $itemId")
    }

    private fun topLevelFragmentTag(itemId: Int) = "top_level_$itemId"

    override fun onSaveInstanceState(outState: Bundle) {
        activeRuleDraftProvider?.invoke()?.let { draft ->
            ruleDraftHolder.restore = draft.copy(editor = activeRuleEditorDraftProvider?.invoke())
        }
        outState.putInt(STATE_TOP_LEVEL_PAGE, selectedTopLevelPage)
        outState.putInt(STATE_APP_TAB, appTab.ordinal)
        outState.putString(STATE_APP_SEARCH_QUERY, appSearchQuery)
        pendingFloatingLaunchPkg?.let {
            outState.putString(STATE_PENDING_FLOATING_LAUNCH_PKG, it)
        }
        val guideState = if (usageGuideBinding != null && usageGuideSteps.isNotEmpty()) {
            UsageGuideRestore(
                stepIds = usageGuideSteps.map(UsageGuide.Step::id),
                index = usageGuideIndex,
                forced = usageGuideForced,
                previousPage = usageGuidePreviousPage,
                previousAppTab = usageGuidePreviousAppTab,
                previousSettingsTab = usageGuidePreviousSettingsTab
            )
        } else {
            pendingUsageGuideRestore
        }
        guideState?.let { state ->
            outState.putBoolean(STATE_USAGE_GUIDE_ACTIVE, true)
            outState.putStringArrayList(STATE_USAGE_GUIDE_STEP_IDS, ArrayList(state.stepIds))
            outState.putInt(STATE_USAGE_GUIDE_INDEX, state.index)
            outState.putBoolean(STATE_USAGE_GUIDE_FORCED, state.forced)
            outState.putInt(STATE_USAGE_GUIDE_PREVIOUS_PAGE, state.previousPage)
            outState.putInt(STATE_USAGE_GUIDE_PREVIOUS_APP_TAB, state.previousAppTab.ordinal)
            outState.putInt(STATE_USAGE_GUIDE_PREVIOUS_SETTINGS_TAB, state.previousSettingsTab)
        }
        if (autoStartCalibrationWarningDialog?.isShowing == true) {
            outState.putBoolean(STATE_AUTO_CALIBRATION_WARNING_ACTIVE, true)
            outState.putString(
                STATE_AUTO_CALIBRATION_WARNING_DRAFT,
                autoStartCalibrationWarningDraft
            )
            val remaining = if (autoStartCalibrationWarningDeadline > 0L) {
                (autoStartCalibrationWarningDeadline - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
            } else {
                0L
            }
            outState.putLong(STATE_AUTO_CALIBRATION_WARNING_REMAINING, remaining)
        }
        super.onSaveInstanceState(outState)
    }

    private fun showFloatingInterruptionIfNeeded() {
        if (usageGuideBinding != null || floatingInterruptionDialogShowing || activityDestroyed) return
        val incident = FloatingBallSessionState.consumeIncident(
            this,
            FloatingBallService.isRunningInProcess()
        ) ?: return
        android.util.Log.d(
            "QixiaThreads",
            "FloatingBall interruption prompt: target=${incident.targetPkg} " +
                "calibrating=${incident.calibrating} restart=${incident.detectedAfterRestart}"
        )
        cleanupInterruptedFloatingSession(incident)
        floatingInterruptionDialogShowing = true
        val view = DialogFloatingInterruptionBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        view.interruptionSubtitle.text = if (incident.calibrating) {
            "本次校准已中断"
        } else {
            "上次悬浮球会话未正常结束"
        }
        view.interruptionMessage.text =
            "可能被系统省电或 Thanox、NoActive 等后台管控工具停止。" +
                "请允许 QixiaThreads 后台运行、自启动，并关闭省电限制后重试。"
        view.interruptionSettings.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                toast("请在系统设置中允许 QixiaThreads 后台运行")
            }
        }
        view.interruptionDone.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { floatingInterruptionDialogShowing = false }
        dialog.show()
    }

    private fun cleanupInterruptedFloatingSession(incident: FloatingBallSessionState.Incident) {
        CalibrationCommandDispatcher.execute {
            val calibrationStopped = if (incident.calibrating && incident.targetPkg.isNotBlank()) {
                DaemonBridge.stopCalibration(incident.targetPkg)
            } else {
                true
            }
            val fpsStopped = DaemonBridge.stopFpsMonitor()
            android.util.Log.d(
                "QixiaThreads",
                "FloatingBall interruption cleanup: target=${incident.targetPkg} " +
                    "calibrating=${incident.calibrating} calibrationStopped=$calibrationStopped " +
                    "fpsStopped=$fpsStopped"
            )
        }
    }

    override fun onPause() {
        activityResumed = false
        lifecycleRequests.next()
        cancelSettledHealthRefresh()
        appsPageRefreshRunnable?.let(mainHandler::removeCallbacks)
        appsPageRefreshRunnable = null
        super.onPause()
    }

    private fun refreshForegroundState(
        refreshConfig: Boolean,
        lifecycleGeneration: Long? = null,
        forceFullAppListRefresh: Boolean = false,
        onApplied: (() -> Unit)? = null
    ) {
        if (configMutationInFlight > 0) {
            onApplied?.invoke()
            return
        }
        foregroundRefreshesInFlight.incrementAndGet()
        val environmentGeneration = environmentRequests.next()
        val appListGeneration = if (refreshConfig) {
            appListRequests.next()
        } else {
            appListRequests.current()
        }
        val previousAddable = appLists.addable
        val previousHealth = ruleHealth
        val previousProcessNames = processNames
        val completeInitialAppLists = environmentLoading && addableAppsLoading
        thread {
            try {
            val root = DaemonBridge.hasRoot()
            val pendingUpdate = if (root) DaemonBridge.hasPendingModuleUpdate() else false
            val version = if (root && !pendingUpdate) DaemonBridge.readModuleVersion() else null
            val compatible = root && isCompatibleModule(version)
            val runtime = if (root && compatible && !pendingUpdate) {
                DaemonBridge.readDaemonRuntime()
            } else {
                DaemonBridge.DaemonRuntime(running = false)
            }
            val running = runtime.running
            val helperStatus = if (root && compatible && !pendingUpdate) queryForegroundHelperStatus() else ForegroundHelperStatus()
            val enabled = root && compatible && running
            val config = if (enabled && refreshConfig) ConfigReader.readPackagesOrNull() else null
            val health = if (enabled && refreshConfig) {
                DaemonBridge.readRuleHealthOrNull() ?: previousHealth
            } else {
                previousHealth
            }
            val resolvedNames = config?.let { resolveProcessComponentNames(it, enabled) } ?: previousProcessNames
            val visibleLists = when {
                !enabled -> AppLists()
                config != null -> buildConfiguredLists(config, resolvedNames, health).copy(addable = previousAddable)
                else -> null
            }
            runOnUiThreadIfEnvironmentCurrent(environmentGeneration, lifecycleGeneration) {
                val appListCurrent = appListRequests.isCurrent(appListGeneration)
                hasRoot = root
                pendingModuleUpdate = pendingUpdate
                moduleVersion = version
                moduleCompatible = compatible
                daemonRunning = running
                daemonRuntime = runtime
                foregroundHelperStatus = helperStatus
                environmentLoading = false
                renderEnvironmentOverview()
                var needsAppListRefresh = false
                if (!enabled) {
                    appListRequests.next()
                    updateRuleHealthSnapshot(emptyMap())
                    appListsLoading = false
                    addableAppsLoading = false
                    processNames = emptySet()
                    appLists = AppLists()
                    buildAppList()
                } else if (refreshConfig && appListCurrent && visibleLists != null) {
                    lastAppsPageRefreshAt = SystemClock.elapsedRealtime()
                    updateRuleHealthSnapshot(health)
                    appListsLoading = false
                    processNames = resolvedNames
                    appLists = visibleLists
                    buildAppList()
                    needsAppListRefresh = completeInitialAppLists || forceFullAppListRefresh
                } else if (refreshConfig && !appListCurrent) {
                    // 仅刷新列表的请求可能在较慢的 daemon/root 检查期间启动。
                    // 发布权威环境结果后需重新读取列表，避免展示过期数据。
                    needsAppListRefresh = true
                } else if (refreshConfig && config == null) {
                    binding.appRefresh.isRefreshing = false
                }
                if (needsAppListRefresh) {
                    refreshAppList(
                        scrollAddableToTop = false,
                        lifecycleGeneration = lifecycleGeneration
                    )
                } else {
                    binding.appRefresh.isRefreshing = false
                }
                onApplied?.invoke()
                refreshUsageGuideForEnvironmentState()
                showModuleWarningIfNeeded()
                maybeCheckStartupUpdate()
            }
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "foreground refresh failed", error)
                runOnUiThreadIfEnvironmentCurrent(environmentGeneration, lifecycleGeneration) {
                    binding.appRefresh.isRefreshing = false
                    environmentLoading = false
                    renderEnvironmentOverview()
                    onApplied?.invoke()
                    refreshUsageGuideForEnvironmentState()
                    toast("运行环境刷新失败，请稍后重试")
                }
            } finally {
                foregroundRefreshesInFlight.decrementAndGet()
            }
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        autoStartCalibrationWarningTimer?.cancel()
        autoStartCalibrationWarningTimer = null
        usageGuideBackCallback?.remove()
        usageGuideBackCallback = null
        usageGuideBinding = null
        cancelSettledHealthRefresh()
        appsPageRefreshRunnable?.let(mainHandler::removeCallbacks)
        appsPageRefreshRunnable = null
        activeRuleHealthObserver = null
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

    private fun runOnUiThreadIfEnvironmentCurrent(
        generation: Long,
        lifecycleGeneration: Long? = null,
        action: () -> Unit
    ) {
        runOnUiThreadIfAlive {
            val lifecycleCurrent = lifecycleGeneration == null ||
                (activityResumed && lifecycleRequests.isCurrent(lifecycleGeneration))
            if (environmentRequests.isCurrent(generation) && lifecycleCurrent) action()
        }
    }

    private fun runOnUiThreadIfAppListCurrent(
        generation: Long,
        lifecycleGeneration: Long? = null,
        action: () -> Unit
    ) {
        runOnUiThreadIfAlive {
            val lifecycleCurrent = lifecycleGeneration == null ||
                (activityResumed && lifecycleRequests.isCurrent(lifecycleGeneration))
            if (appListRequests.isCurrent(generation) && lifecycleCurrent) action()
        }
    }

    private fun beginConfigMutation(): Long {
        cancelSettledHealthRefresh()
        configMutationInFlight++
        environmentRequests.next()
        appListRequests.next()
        ruleDialogRequests.next()
        return mutationRequests.next()
    }

    private fun finishConfigMutation(generation: Long, action: () -> Unit) {
        runOnUiThreadIfAlive {
            val current = mutationRequests.isCurrent(generation)
            try {
                if (current) action()
            } finally {
                configMutationInFlight = (configMutationInFlight - 1).coerceAtLeast(0)
                if (configMutationInFlight == 0) {
                    if (activityResumed) {
                        refreshForegroundState(
                            refreshConfig = true,
                            forceFullAppListRefresh = true
                        )
                        scheduleSettledHealthRefresh()
                    }
                }
            }
        }
    }

    private fun updateRuleHealthSnapshot(health: Map<String, DaemonBridge.RuleHealth>) {
        ruleHealth = health
        ruleHealthRevision = if (ruleHealthRevision == Long.MAX_VALUE) 1L else ruleHealthRevision + 1L
        activeRuleHealthObserver?.invoke(health)
    }

    private fun scheduleSettledHealthRefresh(observedAt: Long = SystemClock.uptimeMillis()) {
        cancelSettledHealthRefresh()
        if (!activityResumed) return
        val lifecycleGeneration = lifecycleRequests.current()
        val delay = (observedAt + RULE_HEALTH_SETTLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        val refreshTask = Runnable {
            settledHealthRefresh = null
            if (!activityResumed || activityDestroyed) return@Runnable
            if (!lifecycleRequests.isCurrent(lifecycleGeneration)) return@Runnable
            if (configMutationInFlight > 0) {
                return@Runnable
            }
            if (startupLoadInFlight || foregroundRefreshesInFlight.get() > 0 ||
                appListRefreshesInFlight.get() > 0) {
                scheduleSettledHealthRefresh()
                return@Runnable
            }
            if (environmentLoading) {
                refreshForegroundState(refreshConfig = true, lifecycleGeneration = lifecycleGeneration)
            } else {
                refreshAppList(
                    scrollAddableToTop = false,
                    lifecycleGeneration = lifecycleGeneration
                )
            }
        }
        settledHealthRefresh = refreshTask
        mainHandler.postDelayed(refreshTask, delay)
    }

    private fun cancelSettledHealthRefresh() {
        settledHealthRefresh?.let(mainHandler::removeCallbacks)
        settledHealthRefresh = null
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

    private fun daemonRuntimeLabel(): String {
        val version = daemonRuntime.versionName?.takeIf { it.isNotBlank() }
        return when {
            version != null -> "Rust 版 $version"
            else -> "Rust 版"
        }
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
        if (usageGuideBinding != null || moduleWarningShown || environmentLoading || !hasRoot ||
            pendingModuleUpdate || moduleCompatible) return
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
        if (usageGuideBinding != null || startupUpdateCheckStarted || activityDestroyed ||
            !hasRoot || pendingModuleUpdate) return
        startupUpdateCheckStarted = true
        thread(name = "QixiaThreadsStartupUpdateCheck") {
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

    private fun renderEnvironmentOverview() {
        val status = binding.statusSection
        val overlay = hasOverlay()
        val usage = ForegroundDetector.hasUsageAccess(this)

        status.overlayState.text = if (overlay) "已授予" else "未授予"
        setStatusDot(status.dotOverlay, if (overlay) R.color.status_ok else R.color.status_warn)
        status.usageState.text = if (usage) "已授予" else "未授予"
        setStatusDot(status.dotUsage, if (usage) R.color.status_ok else R.color.status_warn)

        if (environmentLoading) {
            status.overviewState.text = "正在检测"
            setStatusDot(status.dotOverview, R.color.status_warn)
            status.overviewState.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            status.rootState.text = "检查中"
            status.daemonState.text = "检查中"
            status.foregroundHelperState.text = "检查中"
            setStatusDot(status.dotRoot, R.color.status_warn)
            setStatusDot(status.dotDaemon, R.color.status_warn)
            setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            return
        }

        status.rootState.text = if (hasRoot) "可用" else "不可用"
        setStatusDot(status.dotRoot, if (hasRoot) R.color.status_ok else R.color.status_off)

        val daemonReady = hasRoot && !pendingModuleUpdate && moduleCompatible && daemonRunning
        when {
            !hasRoot -> {
                status.daemonState.text = "未知"
                setStatusDot(status.dotDaemon, R.color.status_off)
            }
            pendingModuleUpdate -> {
                status.daemonState.text = "待重启"
                setStatusDot(status.dotDaemon, R.color.status_warn)
            }
            !moduleCompatible -> {
                status.daemonState.text = "模块需更新"
                setStatusDot(status.dotDaemon, R.color.status_warn)
            }
            daemonRunning -> {
                status.daemonState.text = daemonRuntimeLabel()
                setStatusDot(status.dotDaemon, R.color.status_ok)
            }
            else -> {
                status.daemonState.text = "未运行"
                setStatusDot(status.dotDaemon, R.color.status_warn)
            }
        }

        val helper = foregroundHelperStatus.state
        val helperReady = daemonReady && helper?.available == true && helper.mode != "poll"
        when {
            !hasRoot -> {
                status.foregroundHelperState.text = "未知"
                setStatusDot(status.dotForegroundHelper, R.color.status_off)
            }
            pendingModuleUpdate -> {
                status.foregroundHelperState.text = "待重启"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            !moduleCompatible -> {
                status.foregroundHelperState.text = "模块需更新"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            helper?.available == true && helper.mode == "poll" -> {
                status.foregroundHelperState.text = "轮询中"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            helper?.available == true -> {
                status.foregroundHelperState.text = "运行中"
                setStatusDot(status.dotForegroundHelper, R.color.status_ok)
            }
            helper?.status == "error" -> {
                status.foregroundHelperState.text = "错误"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            helper?.status == "empty" -> {
                status.foregroundHelperState.text = "无任务"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            helper?.ageMs != null -> {
                status.foregroundHelperState.text = "状态过期"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            foregroundHelperStatus.startRequested -> {
                status.foregroundHelperState.text = "启动中"
                setStatusDot(status.dotForegroundHelper, R.color.status_warn)
            }
            else -> {
                status.foregroundHelperState.text = "不可用"
                setStatusDot(status.dotForegroundHelper, R.color.status_off)
            }
        }

        val readyCount = listOf(overlay, usage, hasRoot, daemonReady, helperReady).count { it }
        status.overviewState.text = when {
            readyCount == 5 -> "全部正常"
            else -> "${5 - readyCount} 项需要处理"
        }
        val overviewColor = when {
            readyCount == 5 -> R.color.status_ok
            !hasRoot -> R.color.status_off
            else -> R.color.status_warn
        }
        setStatusDot(status.dotOverview, overviewColor)
        status.overviewState.setTextColor(ContextCompat.getColor(this, overviewColor))
    }

    private fun setStatusDot(dot: View, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        if (dot is android.widget.ImageView) {
            dot.imageTintList = android.content.res.ColorStateList.valueOf(color)
        } else {
            dot.background?.mutate()?.setTint(color)
        }
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

    private fun setupAutoStartCalibration() {
        val section = binding.appSection
        setAutoStartCalibrationSwitchChecked(autoStartCalibrationEnabled)
        section.autoStartCalibrationSwitch.setOnCheckedChangeListener { _, checked ->
            if (autoStartCalibrationSwitchUpdating) return@setOnCheckedChangeListener
            if (checked) {
                setAutoStartCalibrationSwitchChecked(false)
                showAutoStartCalibrationWarning()
                return@setOnCheckedChangeListener
            }
            saveAutoStartCalibrationEnabled(checked)
        }
        section.autoStartCalibrationRow.setOnClickListener {
            if (section.autoStartCalibrationSwitch.isEnabled) {
                section.autoStartCalibrationSwitch.toggle()
            }
        }
    }

    private fun restoreModuleUpdateDialogIfNeeded() {
        val update = ModuleUpdateDialog.activeUpdate(this) ?: return
        startupUpdateCheckStarted = true
        startupUpdateDialogShowing = true
        binding.root.post {
            if (activityDestroyed || isFinishing || isDestroyed) return@post
            val shown = ModuleUpdateDialog.show(
                activity = this,
                update = update
            ) {
                startupUpdateDialogShowing = false
                (supportFragmentManager.findFragmentByTag(
                    topLevelFragmentTag(R.id.navEnvironment)
                ) as? EnvironmentFragment)?.onModuleUpdateSessionDismissed()
            }
            if (!shown && ModuleUpdateDialog.activeUpdate(this) == null) {
                startupUpdateDialogShowing = false
            }
        }
    }

    private fun setAutoStartCalibrationSwitchChecked(checked: Boolean) {
        autoStartCalibrationSwitchUpdating = true
        binding.appSection.autoStartCalibrationSwitch.isChecked = checked
        autoStartCalibrationSwitchUpdating = false
    }

    private fun saveAutoStartCalibrationEnabled(
        enabled: Boolean,
        delayMs: Long = autoStartCalibrationDelayMs
    ) {
        autoStartCalibrationEnabled = enabled
        autoStartCalibrationDelayMs = AutoStartCalibrationDelay.normalize(delayMs)
        setAutoStartCalibrationSwitchChecked(enabled)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_AUTO_START_CALIBRATION, enabled)
            .putLong(PREF_AUTO_START_CALIBRATION_DELAY_MS, autoStartCalibrationDelayMs)
            .apply()
    }

    private fun showAutoStartCalibrationWarning(
        restoredDraft: String? = null,
        restoredRemainingMs: Long? = null
    ) {
        if (autoStartCalibrationWarningDialog?.isShowing == true) return
        autoStartCalibrationWarningTimer?.cancel()
        val view = DialogAutoStartCalibrationWarningBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val countdownDuration = restoredRemainingMs
            ?.coerceIn(0L, AUTO_START_CALIBRATION_WARNING_MS)
            ?: AUTO_START_CALIBRATION_WARNING_MS
        var warningCountdownFinished = countdownDuration == 0L
        var parsedDelayMs: Long? = autoStartCalibrationDelayMs

        fun renderDelay() {
            val raw = view.autoCalibrationDelayInput.text?.toString()?.trim().orEmpty()
            parsedDelayMs = AutoStartCalibrationDelay.parse(raw)
            view.autoCalibrationDelayBox.error = when {
                raw.isEmpty() -> "请输入延时毫秒数"
                raw.toLongOrNull() == null -> "只支持输入整数毫秒"
                parsedDelayMs == null ->
                    "延时范围为 0-${AutoStartCalibrationDelay.MAX_DELAY_MS} 毫秒"
                else -> null
            }
            view.autoCalibrationDelaySummary.text = parsedDelayMs?.let {
                AutoStartCalibrationDelay.summary(it)
            } ?: "输入有效延时后会显示实际启动时间"
            view.autoCalibrationWarningConfirm.isEnabled =
                warningCountdownFinished && parsedDelayMs != null
        }

        autoStartCalibrationWarningDraft = restoredDraft ?: autoStartCalibrationDelayMs.toString()
        view.autoCalibrationDelayInput.setText(autoStartCalibrationWarningDraft)
        view.autoCalibrationDelayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                autoStartCalibrationWarningDraft = s?.toString().orEmpty()
                renderDelay()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderDelay()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            autoStartCalibrationWarningDialog = dialog
            dialog.behavior.apply {
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isHideable = false
                isDraggable = false
            }
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                if (resources.configuration.smallestScreenWidthDp >= 600) {
                    val params = sheet.layoutParams
                    params.width = minOf(dp(560f), resources.displayMetrics.widthPixels - dp(32f))
                    if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }
                    sheet.layoutParams = params
                }
            }
            view.autoCalibrationWarningConfirm.isEnabled = warningCountdownFinished && parsedDelayMs != null
            view.autoCalibrationWarningCancel.isEnabled = warningCountdownFinished
            view.autoCalibrationWarningConfirm.setOnClickListener {
                val delayMs = parsedDelayMs ?: return@setOnClickListener
                saveAutoStartCalibrationEnabled(true, delayMs)
                dialog.dismiss()
            }
            view.autoCalibrationWarningCancel.setOnClickListener {
                saveAutoStartCalibrationEnabled(false)
                dialog.dismiss()
            }
            if (warningCountdownFinished) {
                autoStartCalibrationWarningDeadline = 0L
                view.autoCalibrationWarningConfirm.text = "继续开启"
                renderDelay()
            } else {
                autoStartCalibrationWarningDeadline =
                    SystemClock.elapsedRealtime() + countdownDuration
                autoStartCalibrationWarningTimer = object : CountDownTimer(
                    countdownDuration,
                    1_000L
                ) {
                    override fun onTick(millisUntilFinished: Long) {
                        val seconds = ((millisUntilFinished + 999L) / 1_000L).coerceAtLeast(1L)
                        view.autoCalibrationWarningConfirm.text = "继续开启（${seconds} 秒）"
                    }

                    override fun onFinish() {
                        warningCountdownFinished = true
                        autoStartCalibrationWarningDeadline = 0L
                        view.autoCalibrationWarningConfirm.text = "继续开启"
                        view.autoCalibrationWarningCancel.isEnabled = true
                        renderDelay()
                    }
                }.also { it.start() }
            }
        }
        dialog.setOnDismissListener {
            autoStartCalibrationWarningTimer?.cancel()
            autoStartCalibrationWarningTimer = null
            autoStartCalibrationWarningDialog = null
            if (!isChangingConfigurations) {
                autoStartCalibrationWarningDeadline = 0L
                autoStartCalibrationWarningDraft = null
            }
        }
        dialog.show()
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

    /** 下拉刷新：重新读取配置文件并更新应用列表 */
    private fun refreshAppList(
        scrollAddableToTop: Boolean = true,
        lifecycleGeneration: Long? = null
    ) {
        if (configMutationInFlight > 0) {
            return
        }
        val generation = appListRequests.next()
        if (!canUseModuleFeatures()) {
            appListsLoading = false
            addableAppsLoading = false
            appLists = AppLists()
            buildAppList()
            binding.appRefresh.isRefreshing = false
            return
        }
        val previousAddable = appLists.addable
        val previousAddableLoading = addableAppsLoading
        val previousHealth = ruleHealth
        val rootAvailable = hasRoot
        addableAppsLoading = true
        if (appTab == AppTab.ADD && previousAddable.isEmpty()) {
            buildAppList()
        }
        appListRefreshesInFlight.incrementAndGet()
        thread {
            try {
            val config = if (rootAvailable) {
                ConfigReader.readPackagesOrNull()
            } else {
                ConfigReader.ConfigPackages(emptyList(), emptyList())
            }
            if (config == null) {
                runOnUiThreadIfAppListCurrent(generation, lifecycleGeneration) {
                    addableAppsLoading = previousAddableLoading
                    binding.appRefresh.isRefreshing = false
                    buildAppList()
                }
                return@thread
            }
            val health = if (rootAvailable) {
                DaemonBridge.readRuleHealthOrNull() ?: previousHealth
            } else {
                emptyMap()
            }
            val resolvedNames = resolveProcessComponentNames(config, rootAvailable)
            val visibleLists = buildConfiguredLists(config, resolvedNames, health).copy(addable = previousAddable)
            runOnUiThreadIfAppListCurrent(generation, lifecycleGeneration) {
                lastAppsPageRefreshAt = SystemClock.elapsedRealtime()
                appListsLoading = false
                updateRuleHealthSnapshot(health)
                processNames = resolvedNames
                appLists = visibleLists
                buildAppList()
            }

            val fullLists = buildAppLists(config, resolvedNames, health)
            runOnUiThreadIfAppListCurrent(generation, lifecycleGeneration) {
                addableAppsLoading = false
                appLists = fullLists
                buildAppList()
                if (scrollAddableToTop && appTab == AppTab.ADD) {
                    binding.appSection.appRecycler.stopScroll()
                    binding.appSection.appRecycler.post {
                        binding.appSection.appRecycler.scrollToPosition(0)
                    }
                }
                binding.appRefresh.isRefreshing = false
            }
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "app list refresh failed", error)
                runOnUiThreadIfAppListCurrent(generation, lifecycleGeneration) {
                    appListsLoading = false
                    addableAppsLoading = false
                    binding.appRefresh.isRefreshing = false
                    buildAppList()
                    toast("应用列表刷新失败，请稍后重试")
                }
            } finally {
                appListRefreshesInFlight.decrementAndGet()
            }
        }
    }

    private fun buildAppList() {
        binding.root.post(::maybeRestoreRuleDraft)
        val a = binding.appSection
        val blocked = blockedState()
        if (blocked != null) {
            a.appTitle.text = "应用功能不可用"
            a.appTitle.visibility = View.VISIBLE
            a.appCount.text = ""
            a.appTabs.visibility = View.GONE
            a.autoStartCalibrationRow.visibility = View.GONE
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
        a.appTitle.visibility = View.GONE
        a.appTabs.visibility = View.VISIBLE
        a.autoStartCalibrationRow.visibility = if (appTab == AppTab.PENDING) View.VISIBLE else View.GONE
        a.appSearchBox.visibility = if (supportsAppSearch()) View.VISIBLE else View.GONE
        a.configuredFilterRow.visibility = if (appTab == AppTab.CONFIGURED) View.VISIBLE else View.GONE
        a.appCount.text = ""
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

    private fun maybeRestoreRuleDraft() {
        if (ruleDraftRestoreStarted || activeRuleDraftProvider != null || activityDestroyed ||
            isFinishing || isDestroyed || blockedState() != null || !hasRoot) {
            return
        }
        val restore = ruleDraftHolder.restore ?: return
        val entry = appLists.configured.firstOrNull { candidate ->
            candidate.pkg == restore.entryPkg || restore.entryPkg in candidate.configPkgs
        } ?: return
        ruleDraftRestoreStarted = true
        selectTopLevelPage(R.id.navApps)
        selectAppTab(AppTab.CONFIGURED)
        showConfiguredRulesDialog(
            entry = entry,
            targets = restore.targets,
            lines = restore.originalLines,
            allowedCpus = restore.allowedCpus,
            health = ruleHealth,
            restore = restore
        )
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
        item.configPkg.text = configuredAppMeta(entry)
        item.root.contentDescription = "管理 ${item.configName.text}"
        val healthUi = configuredAppHealth(entry)
        bindHealthHint(item.configHealth, healthUi.label, healthUi.description)
        bindEntryIcon(item.configIcon, entry)
        item.root.setOnClickListener { showConfiguredAppManageSheet(entry) }
        item.configManage.setOnClickListener { showConfiguredAppManageSheet(entry) }
    }

    private fun configuredAppMeta(entry: AppEntry): String {
        return when (entry.component) {
            ComponentKind.APP -> "${entry.pkg} · ${entry.ruleCount} 条规则"
            ComponentKind.SYSTEM_COMPONENT -> "系统组件 · ${entry.ruleCount} 条规则"
            ComponentKind.MISSING_APP -> "未安装/配置残留 · ${entry.ruleCount} 条规则"
        }
    }

    private fun configuredAppHealth(entry: AppEntry): ConfiguredAppHealthUi {
        val hasMissedRules = entry.missedRuleCount > 0
        val hasPendingReviewRules = entry.pendingReviewRuleCount > 0
        val missedKindLabel = ruleHealthKindLabel(entry.missedRuleKinds)
        val pendingKindLabel = ruleHealthKindLabel(entry.pendingReviewRuleKinds)
        val combinedKindLabel = ruleHealthKindLabel(
            entry.missedRuleKinds + entry.pendingReviewRuleKinds
        )
        val appHealthLabel = when {
            hasMissedRules && hasPendingReviewRules -> "${combinedKindLabel}状态需检查"
            hasMissedRules -> "${missedKindLabel}可能无效"
            hasPendingReviewRules -> "${pendingKindLabel}未发现 · 将复查"
            else -> null
        }
        val appHealthDescription = when {
            hasMissedRules && hasPendingReviewRules ->
                "${entry.missedRuleCount} 条${missedKindLabel}规则连续两次未检测到目标，可能无效；" +
                    "${entry.pendingReviewRuleCount} 条${pendingKindLabel}规则首次未检测到目标，下次启动时再次检查"
            hasMissedRules ->
                "${entry.missedRuleCount} 条${missedKindLabel}规则连续两次未检测到目标，可能无效"
            hasPendingReviewRules ->
                "${entry.pendingReviewRuleCount} 条${pendingKindLabel}规则首次未检测到目标，下次启动时再次检查"
            else -> null
        }
        return ConfiguredAppHealthUi(appHealthLabel, appHealthDescription)
    }

    private fun showConfiguredAppManageSheet(entry: AppEntry) {
        val view = DialogConfiguredAppManageBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view.root)

        val displayName = if (entry.installed) entry.label else entry.pkg
        view.configuredManageName.text = displayName
        view.configuredManagePkg.text = configuredAppMeta(entry)
        view.configuredManageRulesDescription.text = "当前共 ${entry.ruleCount} 条规则"
        bindEntryIcon(view.configuredManageIcon, entry)
        val healthUi = configuredAppHealth(entry)
        bindHealthHint(view.configuredManageHealth, healthUi.label, healthUi.description)

        val usable = canUseModuleFeatures()
        view.configuredManageRules.isEnabled = usable
        view.configuredManageRules.alpha = if (usable) 1f else 0.48f
        view.configuredManageDelete.isEnabled = usable
        view.configuredManageDelete.alpha = if (usable) 1f else 0.48f
        view.configuredManageRules.setOnClickListener {
            if (!usable) return@setOnClickListener
            dialog.dismiss()
            binding.root.post { showConfiguredRules(entry) }
        }
        view.configuredManageDelete.setOnClickListener {
            if (!usable) return@setOnClickListener
            dialog.dismiss()
            binding.root.post { confirmDeleteConfig(entry) }
        }
        view.configuredManageClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.post {
                    runCatching {
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                    }.getOrNull()?.apply {
                        isFitToContents = true
                        skipCollapsed = true
                        state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    }
                }
                if (resources.configuration.smallestScreenWidthDp >= 600) {
                    val params = sheet.layoutParams
                    params.width = minOf(dp(560f), resources.displayMetrics.widthPixels - dp(32f))
                    if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }
                    sheet.layoutParams = params
                }
            }
        }
        dialog.show()
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
            android.util.Log.d("QixiaThreads", "延后检查 ${config.autoPackages.size} 个待校准应用的历史 .log")
            for (pkg in config.autoPackages) {
                try {
                    DatabaseMigrator.migrateIfNeeded(appContext, pkg)
                } catch (e: Exception) {
                    android.util.Log.e("QixiaThreads", "导入 $pkg 失败: ${e.message}")
                }
            }
            android.util.Log.d("QixiaThreads", "延后历史 .log 检查完成")
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

    /** 手机保持贴边底栏；平板/折叠屏把规则面板限制在 560dp 并居中。 */
    private fun applyResponsiveRuleSheetWidth(
        dialog: BottomSheetDialog,
        expand: Boolean = false
    ) {
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val availableWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }
        val params = sheet.layoutParams
        params.width = if (availableWidth >= dp(600f)) {
            minOf(dp(560f), (availableWidth - dp(32f)).coerceAtLeast(dp(320f)))
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (params is CoordinatorLayout.LayoutParams) {
            // BottomSheetBehavior 会自行计算纵向偏移，容器必须保持顶部锚定。
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        sheet.layoutParams = params
        if (expand) {
            dialog.behavior.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
    }

    private fun bindHealthHint(view: TextView, label: String?, description: String?) {
        view.text = label.orEmpty()
        view.contentDescription = description
        view.tooltipText = description
        if (label == null) {
            view.setCompoundDrawables(null, null, null, null)
            view.visibility = View.GONE
            return
        }
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_warning)?.mutate()
        val iconSize = dp(13f)
        icon?.setBounds(0, 0, iconSize, iconSize)
        view.setCompoundDrawables(icon, null, null, null)
        view.compoundDrawablePadding = dp(3f)
        view.visibility = View.VISIBLE
    }

    private fun buildRuleTargetText(target: String, healthLabel: String?): CharSequence {
        if (healthLabel == null) return target

        val text = SpannableStringBuilder(target)
        val healthStart = text.length
        text.append('\uFFFC')
        text.setSpan(
            InlineHealthSpan(
                icon = ContextCompat.getDrawable(this, R.drawable.ic_warning)?.mutate(),
                label = healthLabel,
                iconSize = dp(12f),
                leadingSpace = dp(3f),
                iconTextSpace = dp(3f),
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    9.5f,
                    resources.displayMetrics
                ),
                textColor = ContextCompat.getColor(this, R.color.status_warn)
            ),
            healthStart,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    private class InlineHealthSpan(
        private val icon: Drawable?,
        private val label: String,
        private val iconSize: Int,
        private val leadingSpace: Int,
        private val iconTextSpace: Int,
        private val textSize: Float,
        private val textColor: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fontMetrics: Paint.FontMetricsInt?
        ): Int {
            val labelPaint = Paint(paint).apply {
                this.textSize = this@InlineHealthSpan.textSize
                color = textColor
            }
            val iconWidth = if (icon == null) 0 else iconSize + iconTextSpace
            return leadingSpace + iconWidth + kotlin.math.ceil(labelPaint.measureText(label).toDouble()).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val centerY = (top + bottom) / 2f
            var drawX = x + leadingSpace
            icon?.let { drawable ->
                val iconTop = (centerY - iconSize / 2f).toInt()
                drawable.setBounds(
                    drawX.toInt(),
                    iconTop,
                    drawX.toInt() + iconSize,
                    iconTop + iconSize
                )
                drawable.draw(canvas)
                drawX += iconSize + iconTextSpace
            }
            val labelPaint = Paint(paint).apply {
                textSize = this@InlineHealthSpan.textSize
                color = textColor
            }
            val metrics = labelPaint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, drawX, baseline, labelPaint)
        }
    }

    private fun ruleHealthKindLabel(kinds: Set<RuleHealthKind>): String {
        return when {
            RuleHealthKind.THREAD in kinds && RuleHealthKind.CHILD_PROCESS in kinds -> "线程/子进程"
            RuleHealthKind.THREAD in kinds -> "线程"
            RuleHealthKind.CHILD_PROCESS in kinds -> "子进程"
            else -> "规则"
        }
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
            val entry = items[position]
            var handled = false
            if (payloads.contains(payloadIcon)) {
                when (holder) {
                    is AddHolder -> bindEntryIcon(holder.binding.addIcon, entry)
                    is ConfiguredHolder -> bindEntryIcon(holder.binding.configIcon, entry)
                    is NormalHolder -> bindEntryIcon(holder.binding.itemIcon, entry)
                }
                handled = true
            }
            if (handled) return
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
                "守护进程未运行",
                "请确认模块已启用并重启设备\n仍异常可在「设置」中查看守护进程日志"
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
        names: Set<String> = processNames,
        health: Map<String, DaemonBridge.RuleHealth> = ruleHealth
    ): AppLists {
        val base = buildConfiguredLists(config, names, health)
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
        names: Set<String> = processNames,
        health: Map<String, DaemonBridge.RuleHealth> = ruleHealth
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
                val appHealth = health.values.filter {
                    it.key in config.ruleHealthKeys && configOwnerName(it.owner) == pkg
                }
                val missedRules = appHealth.filter {
                    it.status == DaemonBridge.RuleHealthStatus.MISSED
                }
                val pendingReviewRules = appHealth.filter {
                    it.status == DaemonBridge.RuleHealthStatus.PENDING && it.missCount > 0
                }
                val missedRuleKinds = missedRules.mapNotNull { ruleHealthKind(it) }.toSet()
                val pendingReviewRuleKinds = pendingReviewRules.mapNotNull { ruleHealthKind(it) }.toSet()
                appEntry(
                    pkg,
                    names,
                    groupPkgs,
                    ruleCount,
                    missedRules.size,
                    pendingReviewRules.size,
                    missedRuleKinds,
                    pendingReviewRuleKinds
                )
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
        ruleCount: Int = 0,
        missedRuleCount: Int = 0,
        pendingReviewRuleCount: Int = 0,
        missedRuleKinds: Set<RuleHealthKind> = emptySet(),
        pendingReviewRuleKinds: Set<RuleHealthKind> = emptySet()
    ): AppEntry {
        val installed = isInstalled(pkg)
        return AppEntry(
            pkg = pkg,
            label = appLabel(pkg),
            installed = installed,
            component = componentKind(pkg, installed, names),
            installTime = installTime(pkg),
            configPkgs = configPkgs.distinct(),
            ruleCount = ruleCount,
            missedRuleCount = missedRuleCount,
            pendingReviewRuleCount = pendingReviewRuleCount,
            missedRuleKinds = missedRuleKinds,
            pendingReviewRuleKinds = pendingReviewRuleKinds
        )
    }

    private fun ruleHealthKind(health: DaemonBridge.RuleHealth): RuleHealthKind? {
        return when (health.kind.uppercase(Locale.ROOT)) {
            "T" -> RuleHealthKind.THREAD
            "P" -> RuleHealthKind.CHILD_PROCESS
            else -> null
        }
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
            .flatMapTo(LinkedHashSet()) { name ->
                listOf(name, configOwnerName(name))
            }
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

    /** 启动目标应用并显示悬浮球，把目标包名传给服务用于校准 */
    private fun startAppWithBall(pkg: String) {
        android.util.Log.d("QixiaThreads", "startAppWithBall: pkg=$pkg")
        if (!canUseModuleFeatures()) {
            android.util.Log.d("QixiaThreads", "startAppWithBall blocked: ${blockedState()?.title ?: "模块不可用"}")
            toast(blockedState()?.title ?: "模块不可用")
            buildAppList()
            return
        }
        if (!hasOverlay()) {
            android.util.Log.d("QixiaThreads", "startAppWithBall blocked: overlay permission missing")
            toast("请先授予悬浮窗权限")
            return
        }
        if (!ForegroundDetector.hasUsageAccess(this)) {
            android.util.Log.d("QixiaThreads", "startAppWithBall blocked: usage access missing")
            toast("请先授予使用情况访问权限")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingFloatingLaunchPkg = pkg
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        launchAppWithBall(pkg)
    }

    private fun launchAppWithBall(pkg: String) {
        val launchPkg = packageLookupName(pkg)
        val launch = packageManager.getLaunchIntentForPackage(launchPkg)
        val svc = Intent(this, FloatingBallService::class.java)
            .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
            .putExtra(FloatingBallService.EXTRA_LAUNCH_PKG, launchPkg)
            .putExtra(
                FloatingBallService.EXTRA_AUTO_START_CALIBRATION,
                autoStartCalibrationEnabled
            )
            .putExtra(
                FloatingBallService.EXTRA_AUTO_START_DELAY_MS,
                autoStartCalibrationDelayMs
            )
            .putExtra(FloatingBallService.EXTRA_MANUAL_LAUNCH, launch == null)
        try {
            android.util.Log.d("QixiaThreads", "startAppWithBall start floating service: pkg=$pkg")
            startForegroundService(svc)
        } catch (e: Exception) {
            android.util.Log.e("QixiaThreads", "startAppWithBall service failed: $pkg ${e.message}")
            toast("悬浮球启动失败，请检查后台运行和悬浮窗权限")
            return
        }

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                android.util.Log.d("QixiaThreads", "startAppWithBall launch: launchPkg=$launchPkg configPkg=$pkg")
                startActivity(launch)
            } catch (e: Exception) {
                android.util.Log.e("QixiaThreads", "startAppWithBall launch failed: $launchPkg ${e.message}")
                FloatingBallSessionState.markExpectedStop(this, "launch_failed")
                stopService(Intent(this, FloatingBallService::class.java))
                toast("启动 $launchPkg 失败")
            }
        } else {
            android.util.Log.d("QixiaThreads", "startAppWithBall manual launch required: $launchPkg")
            toast("悬浮球已开启，请手动进入 $launchPkg")
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
        onUsageGuideAutoAddStarted()
        val generation = beginConfigMutation()
        val previousLists = appLists
        val previousProcessNames = processNames
        val healthSnapshot = ruleHealth
        val rootAvailable = hasRoot
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
            try {
                val ok = DaemonBridge.addAutoPackage(pkg)
                val config = if (ok) ConfigReader.readPackagesOrNull() else null
                val resolvedNames = config?.let {
                    resolveProcessComponentNames(it, rootAvailable)
                } ?: previousProcessNames
                val visibleLists = config?.let {
                    buildConfiguredLists(it, resolvedNames, healthSnapshot).copy(
                        addable = optimisticLists.addable
                    )
                } ?: optimisticLists
                finishConfigMutation(generation) {
                    addableAppsLoading = false
                    if (ok) {
                        processNames = resolvedNames
                        appLists = visibleLists
                        buildAppList()
                        onUsageGuideAutoAddFinished(pkg, success = true)
                    } else {
                        appLists = previousLists
                        buildAppList()
                        toast("添加配置失败，请检查 Root 或模块权限")
                        onUsageGuideAutoAddFinished(pkg, success = false)
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "add config failed: $pkg", error)
                finishConfigMutation(generation) {
                    appLists = previousLists
                    buildAppList()
                    toast("添加配置失败，请重试")
                    onUsageGuideAutoAddFinished(pkg, success = false)
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
        applyResponsiveRuleSheetWidth(dialog)

        thread {
            val result = runCatching {
                targets.flatMap { DaemonBridge.readPkgConfigLines(it) }
            }
            runOnUiThreadIfAlive {
                if (!dialog.isShowing) return@runOnUiThreadIfAlive
                view.deleteRules.text = result.fold(
                    onSuccess = { rules ->
                        if (rules.isEmpty()) {
                            "未读取到当前规则；确认后仍会删除这些配置项在 applist.conf 中的所有配置行。"
                        } else {
                            rules.joinToString("\n")
                        }
                    },
                    onFailure = { error ->
                        android.util.Log.e("QixiaThreads", "read rules before delete failed", error)
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
        val generation = beginConfigMutation()
        val previousLists = appLists
        val previousProcessNames = processNames
        val previousHealth = ruleHealth
        val rootAvailable = hasRoot
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
            try {
                val ok = DaemonBridge.deleteConfigPackages(pkgs)
                val config = if (ok) ConfigReader.readPackagesOrNull() else null
                val resolvedNames = config?.let {
                    resolveProcessComponentNames(it, rootAvailable)
                } ?: previousProcessNames
                val latestHealth = if (ok) {
                    DaemonBridge.readRuleHealthOrNull() ?: previousHealth
                } else {
                    previousHealth
                }
                val updatedHealth = config?.let { current ->
                    latestHealth.filterKeys { it in current.ruleHealthKeys }
                } ?: previousHealth
                val visibleLists = config?.let {
                    buildAppLists(it, resolvedNames, updatedHealth)
                } ?: appLists
                finishConfigMutation(generation) {
                    addableAppsLoading = false
                    if (ok) {
                        updateRuleHealthSnapshot(updatedHealth)
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
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "delete config failed: $pkgs", error)
                finishConfigMutation(generation) {
                    appLists = previousLists
                    buildAppList()
                    toast("删除配置失败，请重试")
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
        val generation = ruleDialogRequests.next()
        val lifecycleGeneration = lifecycleRequests.current()
        val healthRevisionAtLoad = ruleHealthRevision
        val healthSnapshot = ruleHealth
        thread {
            try {
                val lines = DaemonBridge.readPkgRulesOrNull(targets)
                    ?: error("applist.conf read failed")
                val allowedCpus = DaemonBridge.readConfigAllowedCpus()
                val health = DaemonBridge.readRuleHealthOrNull() ?: healthSnapshot
                runOnUiThreadIfAlive {
                    rulesLoadingPackages.remove(entry.pkg)
                    if (!ruleDialogRequests.isCurrent(generation)) return@runOnUiThreadIfAlive
                    if (!activityResumed || !lifecycleRequests.isCurrent(lifecycleGeneration)) {
                        return@runOnUiThreadIfAlive
                    }
                    val currentHealth = if (ruleHealthRevision == healthRevisionAtLoad) {
                        updateRuleHealthSnapshot(health)
                        health
                    } else {
                        ruleHealth
                    }
                    showConfiguredRulesDialog(
                        entry,
                        targets,
                        lines,
                        allowedCpus,
                        currentHealth
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("QixiaThreads", "read configured rules failed: ${entry.pkg}", e)
                runOnUiThreadIfAlive {
                    rulesLoadingPackages.remove(entry.pkg)
                    if (!ruleDialogRequests.isCurrent(generation)) return@runOnUiThreadIfAlive
                    if (!activityResumed || !lifecycleRequests.isCurrent(lifecycleGeneration)) {
                        return@runOnUiThreadIfAlive
                    }
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
        health: Map<String, DaemonBridge.RuleHealth>,
        restore: ConfigRulesRestore? = null
    ) {
                val draftLines = restore?.draftLines ?: lines.mapIndexed { index, line ->
                    RuleDraftLine(index, line)
                }
                val rules = draftLines.mapNotNull { draft ->
                    parseEditableConfigRule(draft.line, draft.sourceIndex)
                }.toMutableList()
                if (rules.size != draftLines.size) {
                    toast("部分规则格式无法解析，请检查 applist.conf")
                    return
                }
                val initialRuleSnapshot = lines.map(String::trim).sorted()
                var ruleSearchQuery = restore?.searchQuery.orEmpty()
                var ruleFilter = restore?.filter ?: ConfigRuleFilter.ALL
                var rulesSaving = false
                var healthResetting = false
                var currentHealth = health
                val expandedChildOwners = restore?.expandedChildOwners
                    ?.toCollection(linkedSetOf()) ?: linkedSetOf()

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
                val ruleDraftToken = Any()
                activeRuleDraftToken = ruleDraftToken
                activeRuleDraftProvider = {
                    ConfigRulesRestore(
                        entryPkg = entry.pkg,
                        targets = targets.toList(),
                        originalLines = lines.toList(),
                        draftLines = rules.map { RuleDraftLine(it.sourceIndex, it.asLine()) },
                        allowedCpus = allowedCpus.toSet(),
                        searchQuery = ruleSearchQuery,
                        filter = ruleFilter,
                        expandedChildOwners = expandedChildOwners.toSet(),
                        editor = activeRuleEditorDraftProvider?.invoke()
                    )
                }
                val adapter = ConfigRuleAdapter(
                    onEdit = { item ->
                        if (!rulesSaving) {
                            val index = findEditableRuleIndex(rules, item)
                            if (index >= 0) {
                                showConfigRuleEditor(
                                    rules[index],
                                    targets,
                                    rules,
                                    allowedCpus
                                ) { updated ->
                                     rules[index] = updated.single()
                                    showRulesError(view, null)
                                    renderRules()
                                }
                            }
                        }
                    },
                    onDelete = { item ->
                        if (!rulesSaving) {
                            item.rule?.let { rule ->
                                confirmDeleteConfigRule(rule) {
                                    val index = findEditableRuleIndex(rules, item)
                                    if (index >= 0) {
                                        rules.removeAt(index)
                                        showRulesError(view, null)
                                        renderRules()
                                    }
                                }
                            }
                        }
                    },
                    onExpand = { item ->
                        if (!rulesSaving && item.kind == ConfigRuleRowKind.CHILD_PROCESS_GROUP) {
                            if (!expandedChildOwners.add(item.owner)) {
                                expandedChildOwners.remove(item.owner)
                            }
                            renderRules()
                        }
                    }
                )
                view.rulesList.adapter = adapter
                view.rulesList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                    private val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                    private var activeHolder: ConfigRuleAdapter.Holder? = null
                    private var downX = 0f
                    private var downY = 0f
                    private var dragging = false

                    private fun begin(event: MotionEvent, recyclerView: RecyclerView) {
                        downX = event.x
                        downY = event.y
                        dragging = false
                        activeHolder = recyclerView.findChildViewUnder(event.x, event.y)?.let {
                            recyclerView.getChildViewHolder(it) as? ConfigRuleAdapter.Holder
                        }?.takeIf { adapter.canReveal(it.bindingAdapterPosition) }
                    }

                    private fun update(event: MotionEvent, recyclerView: RecyclerView): Boolean {
                        val holder = activeHolder ?: return false
                        val deltaX = event.x - downX
                        val deltaY = event.y - downY
                        if (!dragging) {
                            if (kotlin.math.abs(deltaY) > touchSlop &&
                                kotlin.math.abs(deltaY) >= kotlin.math.abs(deltaX)
                            ) {
                                activeHolder = null
                                return false
                            }
                            if (deltaX >= -touchSlop ||
                                kotlin.math.abs(deltaX) <= kotlin.math.abs(deltaY)
                            ) {
                                return false
                            }
                            dragging = true
                            recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                            adapter.closeOtherReveal(holder.bindingAdapterPosition, recyclerView)
                        }
                        adapter.applySwipeOffset(holder, deltaX)
                        return true
                    }

                    private fun finish(recyclerView: RecyclerView, cancelled: Boolean) {
                        val holder = activeHolder
                        if (holder != null && dragging) {
                            val position = holder.bindingAdapterPosition
                            val revealDistance = dp(RULE_SWIPE_REVEAL_DP).toFloat()
                            val shouldReveal = !cancelled &&
                                holder.binding.ruleForeground.translationX <= -revealDistance * 0.35f
                            if (shouldReveal && position != RecyclerView.NO_POSITION) {
                                adapter.revealDelete(position, holder, recyclerView)
                            } else {
                                adapter.animateSwipeState(holder, revealed = false)
                            }
                        }
                        recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
                        activeHolder = null
                        dragging = false
                    }

                    override fun onInterceptTouchEvent(
                        recyclerView: RecyclerView,
                        event: MotionEvent
                    ): Boolean {
                        return when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                begin(event, recyclerView)
                                false
                            }
                            MotionEvent.ACTION_MOVE -> update(event, recyclerView)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                finish(recyclerView, event.actionMasked == MotionEvent.ACTION_CANCEL)
                                false
                            }
                            else -> dragging
                        }
                    }

                    override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> update(event, recyclerView)
                            MotionEvent.ACTION_UP -> finish(recyclerView, cancelled = false)
                            MotionEvent.ACTION_CANCEL -> finish(recyclerView, cancelled = true)
                        }
                    }
                })

                renderRules = {
                    renderConfigRules(
                        view,
                        rules,
                        ruleSearchQuery,
                        ruleFilter,
                        rulesSaving,
                        rulesSaving || healthResetting,
                        currentHealth,
                        expandedChildOwners,
                        adapter
                    )
                    val hasMissedRules = hasMissedRuleHealth(rules, currentHealth)
                    view.rulesResetHealth.visibility = if (hasMissedRules || healthResetting) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    view.rulesResetHealth.isEnabled = hasMissedRules &&
                        !rulesSaving && !healthResetting
                    view.rulesResetHealth.text = if (healthResetting) {
                        "正在提交重新检测"
                    } else {
                        "重新检测异常规则"
                    }
                }

                val healthObserver: (Map<String, DaemonBridge.RuleHealth>) -> Unit = { latest ->
                    currentHealth = latest
                    if (dialog.isShowing) renderRules()
                }
                activeRuleHealthObserver = healthObserver
                dialog.setOnDismissListener {
                    if (activeRuleHealthObserver === healthObserver) {
                        activeRuleHealthObserver = null
                    }
                    if (activeRuleDraftToken === ruleDraftToken) {
                        activeRuleDraftProvider = null
                        activeRuleDraftToken = null
                        activeRuleEditorDraftProvider = null
                        activeRuleEditorToken = null
                        if (!isChangingConfigurations) {
                            ruleDraftHolder.restore = null
                        }
                    }
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
                if (ruleSearchQuery.isNotEmpty()) view.rulesSearch.setText(ruleSearchQuery)
                view.rulesFilterGroup.check(
                    when (ruleFilter) {
                        ConfigRuleFilter.MAIN -> R.id.rulesFilterMain
                        ConfigRuleFilter.CHILD -> R.id.rulesFilterChild
                        ConfigRuleFilter.THREAD -> R.id.rulesFilterThread
                        ConfigRuleFilter.ALL -> R.id.rulesFilterAll
                    }
                )
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

                view.rulesResetHealth.setOnClickListener {
                    if (rulesSaving || healthResetting ||
                        !hasMissedRuleHealth(rules, currentHealth)) {
                        return@setOnClickListener
                    }
                    healthResetting = true
                    renderRules()
                    thread {
                        val ok = DaemonBridge.requestRuleHealthReset(entry.pkg)
                        runOnUiThreadIfAlive {
                            if (!dialog.isShowing) return@runOnUiThreadIfAlive
                            healthResetting = false
                            if (ok) {
                                val pendingHealth = DaemonBridge.markRuleHealthResetPending(
                                    ruleHealth,
                                    entry.pkg
                                )
                                currentHealth = pendingHealth
                                updateRuleHealthSnapshot(pendingHealth)
                                markConfiguredRuleHealthPending(entry.pkg)
                                toast("已设为待检测，下次启动应用时重新观察")
                                scheduleSettledHealthRefresh()
                            } else {
                                renderRules()
                                toast("重新检测请求失败，请检查 Root 或模块状态")
                            }
                        }
                    }
                }

                view.rulesAdd.setOnClickListener {
                    if (rulesSaving) return@setOnClickListener
                    showConfigRuleEditor(
                        null,
                        targets,
                        rules,
                        allowedCpus
                    ) { added ->
                        rules.addAll(added)
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
                 applyResponsiveRuleSheetWidth(dialog, expand = true)
                restore?.editor?.let { savedEditor ->
                    view.root.post {
                        if (!dialog.isShowing || isFinishing || isDestroyed) return@post
                        val draftIndex = savedEditor.draftIndex
                        val currentRule = draftIndex?.let(rules::getOrNull)
                        showConfigRuleEditor(
                            current = currentRule,
                            targets = targets,
                            existingRules = rules,
                            allowedCpus = allowedCpus,
                            restore = savedEditor
                        ) { updated ->
                            if (currentRule != null && rules.getOrNull(draftIndex) === currentRule) {
                                rules[draftIndex] = updated.single()
                            } else {
                                rules.addAll(updated)
                            }
                            showRulesError(view, null)
                            renderRules()
                        }
                    }
                }
                scheduleSettledHealthRefresh()
     }

    private fun parseEditableConfigRule(line: String, sourceIndex: Int?): EditableConfigRule? {
        val rule = RuleSyntax.parseLegacyRule(line) ?: return null
        return EditableConfigRule(sourceIndex, rule.owner, rule.thread, rule.cpus)
    }

    private fun startupRuleOutputFormatName(format: CalibPolicy.RuleOutputFormat?): String {
        return when (format) {
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK -> "原作者区块格式"
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK -> "扩展区块格式"
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK -> "类型标签区块"
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK -> "自然语句区块"
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK -> "分类嵌套区块"
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK -> "函数式格式"
            CalibPolicy.RuleOutputFormat.YAML -> "YAML 风格"
            CalibPolicy.RuleOutputFormat.COMPACT_HEADER_BLOCK,
            CalibPolicy.RuleOutputFormat.SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_SEPARATE_FALLBACK_BLOCK,
            CalibPolicy.RuleOutputFormat.EXTENDED_BLOCK -> "原作者区块格式"
            CalibPolicy.RuleOutputFormat.LEGACY,
            null -> "旧版单行格式"
        }
    }

    private fun findEditableRuleIndex(
        rules: List<EditableConfigRule>,
        target: ConfigRuleListItem
    ): Int {
        val targetRule = target.rule ?: return -1
        val targetIndex = target.listIndex ?: -1
        val preferred = rules.getOrNull(targetIndex)
        if (preferred === targetRule ||
            (targetRule.sourceIndex != null && preferred?.sourceIndex == targetRule.sourceIndex)) {
            return targetIndex
        }
        val sourceIndex = targetRule.sourceIndex
        return if (sourceIndex != null) {
            rules.indexOfFirst { it.sourceIndex == sourceIndex }
        } else {
            rules.indexOfFirst { it === targetRule }
        }
    }

    private fun renderConfigRules(
        view: DialogConfigRulesBinding,
        rules: MutableList<EditableConfigRule>,
        searchQuery: String,
        filter: ConfigRuleFilter,
        saving: Boolean,
        interactionsLocked: Boolean,
        health: Map<String, DaemonBridge.RuleHealth>,
        expandedChildOwners: Set<String>,
        adapter: ConfigRuleAdapter
    ) {
        view.rulesCount.text = if (rules.isEmpty()) "暂无规则" else "${rules.size} 条规则"
        view.rulesSave.isEnabled = rules.isNotEmpty() && !interactionsLocked
        view.rulesSave.text = if (saving) "保存中" else "保存"
        view.rulesClose.isEnabled = !interactionsLocked
        view.rulesAdd.isEnabled = !interactionsLocked
        view.rulesSearch.isEnabled = !interactionsLocked
        for (index in 0 until view.rulesFilterGroup.childCount) {
            view.rulesFilterGroup.getChildAt(index).isEnabled = !interactionsLocked
        }

        val normalizedQuery = searchQuery.lowercase(Locale.ROOT)
        val cpuBoundsByIndex = rules.indices.associateWith { index ->
            RuleConfigLogic.parseCpuBounds(rules[index].cpus)
        }
        val indexedRules = rules.withIndex().toList()
        val displayItems = mutableListOf<ConfigRuleListItem>()
        val mainRules = indexedRules.filterNot { it.value.owner.contains(':') }
            .filter { indexedRule ->
                val rule = indexedRule.value
                val typeMatches = when (filter) {
                    ConfigRuleFilter.ALL -> true
                    ConfigRuleFilter.MAIN -> rule.thread == null
                    ConfigRuleFilter.CHILD -> false
                    ConfigRuleFilter.THREAD -> rule.thread != null
                }
                typeMatches && (normalizedQuery.isEmpty() ||
                    rule.asLine().lowercase(Locale.ROOT).contains(normalizedQuery))
            }
            .sortedWith(configRuleDisplayComparator(cpuBoundsByIndex))
        mainRules.forEach { indexed ->
            displayItems += configRuleItem(indexed, health)
        }

        indexedRules.filter { it.value.owner.contains(':') }
            .groupBy { it.value.owner }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<IndexedValue<EditableConfigRule>>>> {
                    it.key.lowercase(Locale.ROOT)
                }.thenBy { it.key }
            )
            .forEach { (owner, ownerRules) ->
                val fallback = ownerRules.firstOrNull { it.value.thread == null }
                val threads = ownerRules.filter { it.value.thread != null }
                    .sortedWith(configRuleDisplayComparator(cpuBoundsByIndex))
                val filterMatches = when (filter) {
                    ConfigRuleFilter.ALL, ConfigRuleFilter.CHILD -> true
                    ConfigRuleFilter.THREAD -> threads.isNotEmpty()
                    ConfigRuleFilter.MAIN -> false
                }
                val matchingThreads = if (normalizedQuery.isEmpty()) threads else threads.filter {
                    it.value.asLine().lowercase(Locale.ROOT).contains(normalizedQuery)
                }
                val groupMatchesQuery = normalizedQuery.isEmpty() ||
                    owner.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    fallback?.value?.asLine()?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
                    matchingThreads.isNotEmpty()
                if (!filterMatches || !groupMatchesQuery) return@forEach

                val expanded = owner in expandedChildOwners ||
                    (normalizedQuery.isNotEmpty() && matchingThreads.isNotEmpty())
                displayItems += ConfigRuleListItem(
                    stableKey = "group:$owner",
                    listIndex = fallback?.index,
                    rule = fallback?.value,
                    health = fallback?.let { indexed ->
                        DaemonBridge.ruleHealthKey(indexed.value.owner, null)?.let(health::get)
                    },
                    kind = ConfigRuleRowKind.CHILD_PROCESS_GROUP,
                    owner = owner,
                    childCount = threads.size,
                    expanded = expanded
                )
                if (expanded) {
                    val visibleThreads = if (normalizedQuery.isEmpty()) threads else matchingThreads
                    visibleThreads.forEachIndexed { index, indexed ->
                        displayItems += configRuleItem(
                            indexed,
                            health,
                            ConfigRuleRowKind.CHILD_THREAD,
                            isLastChild = index == visibleThreads.lastIndex
                        )
                    }
                }
            }
        val showTools = rules.size >= RULE_TOOLS_THRESHOLD ||
            searchQuery.isNotEmpty() || filter != ConfigRuleFilter.ALL
        view.rulesTools.visibility = if (showTools) View.VISIBLE else View.GONE
        view.rulesFilterSummary.visibility = if (showTools) View.VISIBLE else View.GONE
        view.rulesFilterSummary.text = "显示 ${displayItems.size} 项 / ${rules.size} 条规则"
        view.rulesEmpty.text = when {
            rules.isEmpty() -> "暂无绑定规则"
            displayItems.isEmpty() -> "没有匹配的规则"
            else -> ""
        }
        view.rulesEmpty.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
        view.rulesList.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
        adapter.interactionsEnabled = !interactionsLocked
        adapter.resetReveal()
        adapter.submitList(displayItems)
    }

    private fun hasMissedRuleHealth(
        rules: List<EditableConfigRule>,
        health: Map<String, DaemonBridge.RuleHealth>
    ): Boolean {
        return rules.any { rule ->
            val key = DaemonBridge.ruleHealthKey(rule.owner, rule.thread) ?: return@any false
            health[key]?.status == DaemonBridge.RuleHealthStatus.MISSED
        }
    }

    private fun markConfiguredRuleHealthPending(pkg: String) {
        val basePkg = configOwnerName(pkg)
        val configured = appLists.configured.map { candidate ->
            val sameApp = configOwnerName(candidate.pkg) == basePkg ||
                candidate.configPkgs.any { configOwnerName(it) == basePkg }
            if (sameApp) {
                candidate.copy(
                    missedRuleCount = 0,
                    pendingReviewRuleCount = 0,
                    missedRuleKinds = emptySet(),
                    pendingReviewRuleKinds = emptySet()
                )
            } else {
                candidate
            }
        }
        appLists = appLists.copy(configured = configured)
        buildAppList()
    }

    private fun configRuleItem(
        indexed: IndexedValue<EditableConfigRule>,
        health: Map<String, DaemonBridge.RuleHealth>,
        kind: ConfigRuleRowKind = ConfigRuleRowKind.RULE,
        isLastChild: Boolean = false
    ): ConfigRuleListItem {
        val rule = indexed.value
        val healthKey = DaemonBridge.ruleHealthKey(rule.owner, rule.thread)
        val identity = rule.sourceIndex?.let { "source:$it" }
            ?: "new:${rule.owner}\u0000${rule.thread.orEmpty()}"
        return ConfigRuleListItem(
            stableKey = identity,
            listIndex = indexed.index,
            rule = rule,
            health = healthKey?.let(health::get),
            kind = kind,
            owner = rule.owner,
            isLastChild = isLastChild
        )
    }

    private fun configRuleDisplayComparator(
        cpuBoundsByIndex: Map<Int, RuleConfigLogic.CpuBounds?>
    ): Comparator<IndexedValue<EditableConfigRule>> {
        fun group(rule: EditableConfigRule): Int = when {
            rule.thread != null -> 0
            rule.owner.contains(':') -> 1
            else -> 2
        }

        return Comparator { left, right ->
            val leftRule = left.value
            val rightRule = right.value
            val groupOrder = group(leftRule).compareTo(group(rightRule))
            if (groupOrder != 0) return@Comparator groupOrder

            if (leftRule.thread != null && rightRule.thread != null) {
                val leftBounds = cpuBoundsByIndex[left.index]
                val rightBounds = cpuBoundsByIndex[right.index]
                val startOrder = (rightBounds?.first ?: Int.MIN_VALUE)
                    .compareTo(leftBounds?.first ?: Int.MIN_VALUE)
                if (startOrder != 0) return@Comparator startOrder
                val endOrder = (rightBounds?.last ?: Int.MIN_VALUE)
                    .compareTo(leftBounds?.last ?: Int.MIN_VALUE)
                if (endOrder != 0) return@Comparator endOrder
            }

            val leftName = leftRule.thread ?: leftRule.owner
            val rightName = rightRule.thread ?: rightRule.owner
            val nameOrder = leftName.compareTo(rightName, ignoreCase = true)
            if (nameOrder != 0) nameOrder else left.index.compareTo(right.index)
        }
    }

    private inner class ConfigRuleAdapter(
        private val onEdit: (ConfigRuleListItem) -> Unit,
        private val onDelete: (ConfigRuleListItem) -> Unit,
        private val onExpand: (ConfigRuleListItem) -> Unit
    ) : ListAdapter<ConfigRuleListItem, ConfigRuleAdapter.Holder>(
        object : DiffUtil.ItemCallback<ConfigRuleListItem>() {
            override fun areItemsTheSame(
                oldItem: ConfigRuleListItem,
                newItem: ConfigRuleListItem
            ): Boolean = oldItem.stableKey == newItem.stableKey

            override fun areContentsTheSame(
                oldItem: ConfigRuleListItem,
                newItem: ConfigRuleListItem
            ): Boolean = oldItem == newItem
        }
    ) {
        private var revealedKey: String? = null
        private var revealedHolder: Holder? = null

        var interactionsEnabled: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                notifyItemRangeChanged(0, itemCount)
            }

        fun canReveal(position: Int): Boolean {
            return interactionsEnabled && position in 0 until itemCount &&
                getItem(position).rule != null && getItem(position).stableKey != revealedKey
        }

        fun isRevealed(position: Int): Boolean {
            return position in 0 until itemCount && getItem(position).stableKey == revealedKey
        }

        fun resetReveal() {
            val previousKey = revealedKey
            val previousHolder = revealedHolder
            revealedKey = null
            revealedHolder = null
            if (previousHolder != null) {
                animateSwipeState(previousHolder, revealed = false)
            } else if (previousKey != null) {
                currentList.indexOfFirst { it.stableKey == previousKey }
                    .takeIf { it >= 0 }
                    ?.let(::notifyItemChanged)
            }
        }

        fun closeOtherReveal(position: Int, recyclerView: RecyclerView) {
            val previousKey = revealedKey ?: return
            if (position in 0 until itemCount && getItem(position).stableKey == previousKey) {
                return
            }
            revealedKey = null
            val previousPosition = currentList.indexOfFirst { it.stableKey == previousKey }
            val previousHolder = revealedHolder ?: previousPosition
                .takeIf { it >= 0 }
                ?.let { recyclerView.findViewHolderForAdapterPosition(it) as? Holder }
            revealedHolder = null
            if (previousHolder != null) {
                animateSwipeState(previousHolder, revealed = false)
            } else if (previousPosition >= 0) {
                notifyItemChanged(previousPosition)
            }
        }

        fun revealDelete(position: Int, holder: Holder, recyclerView: RecyclerView) {
            if (!canReveal(position)) {
                if (position in 0 until itemCount) notifyItemChanged(position)
                return
            }
            for (index in 0 until recyclerView.childCount) {
                val otherHolder = recyclerView.getChildViewHolder(
                    recyclerView.getChildAt(index)
                ) as? Holder ?: continue
                if (otherHolder !== holder) {
                    animateSwipeState(otherHolder, revealed = false)
                }
            }
            revealedKey = getItem(position).stableKey
            revealedHolder = holder
            animateSwipeState(holder, revealed = true)
        }

        private fun closeReveal(item: ConfigRuleListItem, holder: Holder? = null) {
            if (revealedKey != item.stableKey) return
            revealedKey = null
            if (revealedHolder === holder) revealedHolder = null
            if (holder != null) {
                animateSwipeState(holder, revealed = false)
            } else {
                currentList.indexOfFirst { it.stableKey == item.stableKey }
                    .takeIf { it >= 0 }
                    ?.let(::notifyItemChanged)
            }
        }

        fun applySwipeOffset(holder: Holder, offset: Float) {
            val revealDistance = dp(RULE_SWIPE_REVEAL_DP).toFloat()
            val clamped = offset.coerceIn(-revealDistance, 0f)
            val foreground = holder.binding.ruleForeground
            val deleteAction = holder.binding.ruleDeleteAction
            foreground.animate().cancel()
            deleteAction.animate().cancel()
            foreground.translationX = clamped
            val progress = (-clamped / revealDistance).coerceIn(0f, 1f)
            deleteAction.visibility = if (progress > 0f) View.VISIBLE else View.INVISIBLE
            deleteAction.alpha = progress
            val scale = 0.82f + 0.18f * progress
            deleteAction.scaleX = scale
            deleteAction.scaleY = scale
            deleteAction.isEnabled = false
        }

        fun animateSwipeState(holder: Holder, revealed: Boolean) {
            val foreground = holder.binding.ruleForeground
            val deleteAction = holder.binding.ruleDeleteAction
            foreground.animate().cancel()
            deleteAction.animate().cancel()
            if (revealed) {
                deleteAction.visibility = View.VISIBLE
            }
            deleteAction.isEnabled = interactionsEnabled && revealed
            foreground.animate()
                .translationX(if (revealed) -dp(RULE_SWIPE_REVEAL_DP).toFloat() else 0f)
                .setDuration(RULE_SWIPE_SNAP_MS)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
            deleteAction.animate()
                .alpha(if (revealed) 1f else 0f)
                .scaleX(if (revealed) 1f else 0.82f)
                .scaleY(if (revealed) 1f else 0.82f)
                .setDuration(if (revealed) 160L else 120L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!revealed) deleteAction.visibility = View.INVISIBLE
                }
                .start()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemConfigRuleBinding.inflate(layoutInflater, parent, false))
        }

        override fun onViewRecycled(holder: Holder) {
            if (revealedHolder === holder) {
                revealedHolder = null
            }
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val listItem = getItem(position)
            val rule = listItem.rule
            val item = holder.binding
            val isGroup = listItem.kind == ConfigRuleRowKind.CHILD_PROCESS_GROUP
            val isChildThread = listItem.kind == ConfigRuleRowKind.CHILD_THREAD
            val isThread = isChildThread || rule?.thread != null
            val isChildProcess = isGroup || (rule?.thread == null && rule?.owner?.contains(':') == true)
            item.ruleType.text = when {
                isChildThread -> "线程"
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
            item.ruleType.visibility = if (isChildThread) View.GONE else View.VISIBLE
            val targetLabel = when {
                isGroup -> listItem.owner.substringAfter(':')
                isThread -> rule?.thread
                isChildProcess -> rule?.owner?.substringAfter(':')
                else -> "主进程"
            }
            item.ruleOwner.text = when {
                isGroup && listItem.childCount > 0 ->
                    "${listItem.owner} · ${listItem.childCount} 条子线程"
                isGroup -> listItem.owner
                isChildThread -> rule?.owner.orEmpty()
                else -> rule?.owner?.substringBefore(':').orEmpty()
            }
            val health = listItem.health
            val healthKindLabel = when {
                isChildThread -> "子线程"
                isThread -> "线程"
                else -> "子进程"
            }
            val healthLabel = when {
                health?.status == DaemonBridge.RuleHealthStatus.MISSED ->
                    "${healthKindLabel}可能无效"
                health?.status == DaemonBridge.RuleHealthStatus.PENDING &&
                    health.missCount > 0 -> "${healthKindLabel}未发现 · 将复查"
                else -> null
            }
            val healthDescription = when {
                health?.status == DaemonBridge.RuleHealthStatus.MISSED ->
                    "连续两次观察未检测到$healthKindLabel，规则可能无效"
                health?.status == DaemonBridge.RuleHealthStatus.PENDING &&
                    health.missCount > 0 ->
                        "首次观察未检测到$healthKindLabel，下次启动时再次检查"
                else -> null
            }
            item.ruleTarget.text = buildRuleTargetText(targetLabel.orEmpty(), healthLabel)
            item.ruleTarget.contentDescription = healthDescription?.let {
                "$targetLabel，$it"
            } ?: targetLabel
            item.ruleTarget.tooltipText = healthDescription
            item.ruleTarget.isSelected = true
            item.ruleCpus.text = rule?.cpus ?: "仅线程"
            item.ruleTarget.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (isChildThread) 12.5f else 13f
            )
            item.ruleOwner.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (isChildThread) 9f else 9.5f
            )
            item.ruleContent.setPaddingRelative(
                if (isChildThread) dp(10f) else dp(8f),
                item.ruleContent.paddingTop,
                dp(8f),
                item.ruleContent.paddingBottom
            )
            item.ruleContent.setBackgroundColor(Color.WHITE)
            item.ruleContent.minimumHeight = dp(if (isChildThread) 50f else 56f)

            val foregroundParams = item.ruleForeground.layoutParams as ViewGroup.MarginLayoutParams
            foregroundParams.marginStart = if (isChildThread) dp(28f) else 0
            item.ruleForeground.layoutParams = foregroundParams
            item.ruleForeground.setCardBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.card_bg)
            )
            item.ruleForeground.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            item.ruleForeground.setCardForegroundColor(
                ColorStateList.valueOf(Color.TRANSPARENT)
            )
            item.ruleForeground.strokeColor = ContextCompat.getColor(
                this@MainActivity,
                when {
                    isChildThread -> R.color.rule_child_stroke
                    else -> R.color.outline_soft
                }
            )
            item.ruleForeground.radius = dp(if (isChildThread) 6f else 8f).toFloat()
            item.ruleHierarchyGuide.visibility = if (isChildThread) View.VISIBLE else View.GONE
            item.ruleHierarchyLine.pivotY = 0f
            item.ruleHierarchyLine.scaleY = if (isChildThread && listItem.isLastChild) 0.5f else 1f

            val rootParams = item.root.layoutParams as? ViewGroup.MarginLayoutParams
            rootParams?.let {
                it.bottomMargin = dp(
                    when {
                        isGroup && listItem.expanded -> 1f
                        isChildThread && !listItem.isLastChild -> 1f
                        else -> 3f
                    }
                )
                item.root.layoutParams = it
            }
            val expandable = isGroup && listItem.childCount > 0
            item.ruleExpand.visibility = if (expandable) View.VISIBLE else View.GONE
            if (expandable) {
                item.ruleExpand.setImageResource(
                    if (listItem.expanded) R.drawable.ic_rule_collapse
                    else R.drawable.ic_rule_expand
                )
                item.ruleExpand.contentDescription =
                    if (listItem.expanded) "收起子线程" else "展开子线程"
            }
            if (expandable) {
                val action = if (listItem.expanded) "收起子线程" else "展开子线程"
                item.ruleTarget.contentDescription =
                    "${item.ruleTarget.contentDescription}，$action"
            }
            item.ruleEdit.visibility = if (rule != null) View.VISIBLE else View.GONE
            val revealed = revealedKey == listItem.stableKey
            item.ruleForeground.animate().cancel()
            item.ruleForeground.translationX = if (revealed) {
                -dp(RULE_SWIPE_REVEAL_DP).toFloat()
            } else {
                0f
            }
            item.ruleDeleteAction.animate().cancel()
            item.ruleDeleteAction.isEnabled = interactionsEnabled && rule != null && revealed
            item.ruleDeleteAction.visibility = if (rule != null && revealed) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            item.ruleDeleteAction.alpha = if (revealed) 1f else 0f
            item.ruleDeleteAction.scaleX = if (revealed) 1f else 0.82f
            item.ruleDeleteAction.scaleY = if (revealed) 1f else 0.82f
            if (revealed) {
                revealedHolder = holder
            } else if (revealedHolder === holder) {
                revealedHolder = null
            }
            item.root.isEnabled = interactionsEnabled
            item.root.isFocusable = interactionsEnabled && rule != null
            item.ruleEdit.isEnabled = interactionsEnabled && rule != null
            item.root.accessibilityDelegate = if (rule != null) {
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (interactionsEnabled) {
                            info.addAction(
                                AccessibilityNodeInfo.AccessibilityAction(
                                    R.id.ruleDeleteAction,
                                    "删除规则"
                                )
                            )
                        }
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: Bundle?
                    ): Boolean {
                        if (action == R.id.ruleDeleteAction && interactionsEnabled) {
                            closeReveal(listItem, holder)
                            onDelete(listItem)
                            return true
                        }
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
            } else {
                null
            }
            item.root.setOnKeyListener { _, keyCode, event ->
                if (interactionsEnabled && rule != null &&
                    event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
                ) {
                    closeReveal(listItem, holder)
                    onDelete(listItem)
                    true
                } else {
                    false
                }
            }
            item.ruleContent.setOnClickListener {
                if (!interactionsEnabled) return@setOnClickListener
                if (revealedKey == listItem.stableKey) {
                    closeReveal(listItem, holder)
                } else if (expandable) {
                    onExpand(listItem)
                }
            }
            item.ruleEdit.setOnClickListener {
                if (interactionsEnabled && rule != null) {
                    closeReveal(listItem, holder)
                    onEdit(listItem)
                }
            }
            item.ruleDeleteAction.setOnClickListener {
                if (interactionsEnabled && rule != null) {
                    closeReveal(listItem, holder)
                    onDelete(listItem)
                }
            }
        }

        inner class Holder(
            val binding: ItemConfigRuleBinding
        ) : RecyclerView.ViewHolder(binding.root)
    }

    private fun confirmDeleteConfigRule(rule: EditableConfigRule, onConfirm: () -> Unit) {
        val view = DialogDeleteConfigBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val childFallback = rule.thread == null && rule.owner.contains(':')
        view.deleteTitle.text = if (childFallback) "删除子进程兜底" else "删除规则"
        view.deletePkg.text = rule.owner
        view.deleteRules.text = if (childFallback) {
            "${rule.asLine()}\n\n此操作只删除子进程兜底，已有子线程规则会保留。"
        } else {
            rule.asLine()
        }
        if (childFallback) view.deleteConfirm.text = "仅删除兜底"
        view.deleteCancel.setOnClickListener { dialog.dismiss() }
        view.deleteConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.setContentView(view.root)
        dialog.show()
        applyResponsiveRuleSheetWidth(dialog)
    }

    private fun showDiscardRulesConfirm(
        title: String = "放弃未保存修改",
        message: String = "规则已经发生变化，但尚未保存。放弃后，本次新增、编辑或删除的内容将丢失。",
        continueText: String = "继续编辑",
        confirmText: String = "放弃修改",
        onDiscard: () -> Unit
    ) {
        val view = DialogDiscardRulesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        view.discardRulesTitle.text = title
        view.discardRulesMessage.text = message
        view.discardRulesContinue.text = continueText
        view.discardRulesConfirm.text = confirmText
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
        applyResponsiveRuleSheetWidth(dialog)
    }

    private fun showConfigRuleEditor(
        current: EditableConfigRule?,
        targets: List<String>,
        existingRules: List<EditableConfigRule>,
        allowedCpus: Set<Int>,
        restore: RuleEditorRestore? = null,
        onConfirm: (List<EditableConfigRule>) -> Unit
    ) {
        val view = DialogConfigRuleEditBinding.inflate(layoutInflater)
        val baseOwner = targets.firstOrNull()?.substringBefore(':').orEmpty()
        val threadOwner = current?.takeIf { it.thread != null }?.owner ?: baseOwner
        view.ruleEditTitle.text = if (current == null) "新增规则" else "编辑规则"
        view.ruleEditPkg.text = baseOwner
        view.ruleFixedOwnerInput.setText(baseOwner)
        view.ruleChildInput.setText(
            restore?.childSuffix ?: current?.owner
                ?.takeIf { current.thread == null && it.startsWith("$baseOwner:") }
                ?.substringAfter(':')
                .orEmpty()
        )
        val currentIsMain = current?.thread == null && current?.owner == baseOwner
        val mainRuleExists = existingRules.any {
            it !== current && it.thread == null && it.owner == baseOwner
        }
        view.ruleTypeMain.visibility = if (mainRuleExists && !currentIsMain) View.GONE else View.VISIBLE
        val initialType = restore?.checkedType ?: when {
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
        if (restore != null) {
            restore.cpuSelections.forEach { (type, cpus) ->
                cpuSelections[type]?.addAll(cpus.filter { it in allowedCpus })
            }
        } else {
            cpuSelections.getValue(initialType).addAll(
                parseCpuSet(current?.cpus.orEmpty()).filter { it in allowedCpus }
            )
        }
        val cpuBoxes = linkedMapOf<Int, MaterialCheckBox>()
        var activeCpuType = initialType
        var suppressCpuChange = false

        data class ThreadEditor(
            val binding: ItemThreadRuleEditorBinding,
            var owner: String,
            val cpus: MutableSet<Int>
        )

        val threadEditors = mutableListOf<ThreadEditor>()
        var threadHistoryTarget: ThreadEditor? = null
        var activeHistoryPicker: BottomSheetDialog? = null
        val editorClosed = AtomicBoolean(false)
        val wildcardRequests = RequestGeneration()

        fun showCpuWarning(message: String) {
            view.ruleCpuWarning.text = message
            view.ruleCpuWarning.visibility = View.VISIBLE
        }

        val sortedAllowedCpus = allowedCpus.sorted()
        val cpuListTooLarge = sortedAllowedCpus.size > MAX_EDITOR_CPU_COUNT
        val editorCpus = if (cpuListTooLarge) emptyList() else sortedAllowedCpus
        view.ruleCpuGrid.columnCount = minOf(4, editorCpus.size.coerceAtLeast(1))
        for (cpu in editorCpus) {
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
        if (allowedCpus.isEmpty() || cpuListTooLarge) {
            view.ruleEditConfirm.isEnabled = false
        }

        fun showCpuSelection(type: Int) {
            activeCpuType = type
            val selected = cpuSelections.getValue(type)
            suppressCpuChange = true
            cpuBoxes.forEach { (cpu, box) -> box.isChecked = cpu in selected }
            suppressCpuChange = false
            view.ruleCpuSummary.text = if (selected.isEmpty()) "未选择" else formatCpuSet(selected)
            if (cpuListTooLarge) {
                view.ruleCpuSummary.text = "不可用"
                showCpuWarning("CPU 核心列表异常，请重新打开后重试")
            } else if (allowedCpus.isEmpty()) {
                view.ruleCpuSummary.text = "不可用"
                showCpuWarning("CPU 核心读取失败，请重新打开后重试")
            } else {
                view.ruleCpuWarning.visibility = View.GONE
            }
        }

        fun updateThreadEditorLabels() {
            threadEditors.forEachIndexed { index, editor ->
                editor.binding.threadRuleDivider.visibility =
                    if (index == 0) View.GONE else View.VISIBLE
                editor.binding.threadRuleLabel.text = "线程 ${index + 1}"
                editor.binding.threadRuleOwner.text = if (editor.owner == baseOwner) {
                    "主进程"
                } else {
                    val suffix = editor.owner.removePrefix(baseOwner).removePrefix(":")
                        .ifBlank { editor.owner }
                    "子进程 · $suffix"
                }
                editor.binding.threadRuleRemove.visibility =
                    if (current == null && threadEditors.size > 1) View.VISIBLE else View.GONE
            }
            val owners = threadEditors.map { it.owner }.distinct()
            view.ruleFixedOwnerInput.setText(
                owners.singleOrNull() ?: "已选择 ${owners.size} 个进程"
            )
        }

        fun removeThreadEditor(editor: ThreadEditor) {
            if (threadEditors.size <= 1 || editor !in threadEditors) return
            threadEditors.remove(editor)
            view.ruleThreadGroups.removeView(editor.binding.root)
            updateThreadEditorLabels()
            view.ruleEditError.visibility = View.GONE
        }

        fun addThreadEditor(
            owner: String,
            name: String,
            initialCpus: Set<Int>,
            requestFocus: Boolean = false
        ) {
            val item = ItemThreadRuleEditorBinding.inflate(
                layoutInflater,
                view.ruleThreadGroups,
                false
            )
            val selectedCpus = initialCpus.filterTo(linkedSetOf()) { it in allowedCpus }
            val editor = ThreadEditor(item, owner, selectedCpus)
            item.threadRuleName.setText(name)
            item.threadRuleCpuGrid.columnCount = minOf(4, editorCpus.size.coerceAtLeast(1))
            editorCpus.forEach { cpu ->
                val box = MaterialCheckBox(this).apply {
                    text = "CPU$cpu"
                    textSize = 15f
                    minHeight = dp(42f)
                    setPadding(0, 0, dp(8f), 0)
                    isChecked = cpu in selectedCpus
                    setOnCheckedChangeListener { button, checked ->
                        if (checked) {
                            selectedCpus.add(cpu)
                        } else if (selectedCpus.size <= 1) {
                            button.isChecked = true
                            item.threadRuleCpuError.visibility = View.VISIBLE
                            return@setOnCheckedChangeListener
                        } else {
                            selectedCpus.remove(cpu)
                        }
                        item.threadRuleCpuSummary.text =
                            if (selectedCpus.isEmpty()) "未选择" else formatCpuSet(selectedCpus)
                        item.threadRuleCpuError.visibility = View.GONE
                        view.ruleEditError.visibility = View.GONE
                    }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, dp(1f), 0, dp(1f))
                }
                item.threadRuleCpuGrid.addView(box, params)
            }
            item.threadRuleCpuSummary.text =
                if (selectedCpus.isEmpty()) "未选择" else formatCpuSet(selectedCpus)
            item.threadRuleName.addTextChangedListener(object : TextWatcher {
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
                    item.threadRuleNameBox.error = null
                    view.ruleEditError.visibility = View.GONE
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
            item.threadRuleNameBox.setEndIconOnClickListener {
                threadHistoryTarget = editor
                view.ruleThreadHistory.performClick()
            }
            item.threadRuleRemove.setOnClickListener { removeThreadEditor(editor) }
            threadEditors.add(editor)
            view.ruleThreadGroups.addView(item.root)
            updateThreadEditorLabels()
            if (requestFocus) {
                item.threadRuleName.post {
                    item.threadRuleName.requestFocus()
                    val inputMethod = getSystemService(Context.INPUT_METHOD_SERVICE) as
                        android.view.inputmethod.InputMethodManager
                    inputMethod.showSoftInput(
                        item.threadRuleName,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }
        }

        fun resetThreadEditors(targets: List<Pair<String, String>>) {
            val previousCpus = threadEditors.mapNotNull { editor ->
                val name = editor.binding.threadRuleName.text?.toString().orEmpty().trim()
                name.takeIf(String::isNotEmpty)?.let { (editor.owner to it) to editor.cpus.toSet() }
            }.toMap()
            val inheritedCpus = threadEditors.firstOrNull()?.cpus
                ?.toSet()
                ?: cpuSelections.getValue(R.id.ruleTypeThread).toSet()
            threadEditors.clear()
            view.ruleThreadGroups.removeAllViews()
            targets.distinct().forEach { (owner, name) ->
                if (name.isNotEmpty()) {
                    addThreadEditor(owner, name, previousCpus[owner to name] ?: inheritedCpus)
                }
            }
            if (threadEditors.isEmpty()) addThreadEditor(threadOwner, "", inheritedCpus)
            view.ruleEditError.visibility = View.GONE
        }

        fun applySelectedThreadTargets(targets: List<Pair<String, String>>) {
            if (editorClosed.get()) return
            val targetSet = targets.toSet()
            val namedEditors = threadEditors.mapNotNull { editor ->
                val name = editor.binding.threadRuleName.text?.toString().orEmpty().trim()
                name.takeIf(String::isNotEmpty)?.let { editor.owner to it }
            }
            val inheritedCpus = threadEditors.firstOrNull()?.cpus
                ?.toSet()
                ?: cpuSelections.getValue(R.id.ruleTypeThread).toSet()
            val losesNamedInput = namedEditors.any { it !in targetSet }
            val losesUnnamedCpuEdits = threadEditors.withIndex().any { (index, editor) ->
                val name = editor.binding.threadRuleName.text?.toString().orEmpty().trim()
                name.isEmpty() && index > 0 && editor.cpus.toSet() != inheritedCpus
            }
            if (!losesNamedInput && !losesUnnamedCpuEdits) {
                resetThreadEditors(targets)
                return
            }
            showDiscardRulesConfirm(
                title = "替换当前线程列表",
                message = "历史多选会替换当前未包含在所选结果中的线程；名称相同的线程保留各自核心，新线程继承线程 1 的核心。",
                continueText = "返回检查",
                confirmText = "确认替换"
            ) {
                if (!editorClosed.get()) resetThreadEditors(targets)
            }
        }

        fun resolveSelectedThreadTargets(
            selectedCandidates: List<RuleHistoryCandidate>,
            loadedCandidates: List<RuleHistoryCandidate>,
            onResolved: (List<Pair<String, String>>) -> Unit
        ) {
            val request = wildcardRequests.next()
            if (selectedCandidates.size > 8) toast("正在整理所选线程")
            thread(name = "QixiaThreads-rule-wildcard") {
                val suggestions = RuleHistoryCandidates.collectThreadWildcardSuggestions(
                    selectedCandidates,
                    loadedCandidates
                )
                runOnUiThreadIfAlive ui@{
                    if (editorClosed.get() || !wildcardRequests.isCurrent(request)) return@ui
                    if (suggestions.isEmpty()) {
                        onResolved(
                            RuleHistoryCandidates.resolveThreadTargets(
                                selectedCandidates,
                                emptyList()
                            )
                        )
                        return@ui
                    }
                    if (selectedCandidates.size == 1) {
                        val owned = suggestions.first()
                        showThreadWildcardSuggestion(
                            suggestion = owned.suggestion,
                            onSelect = { selectedName ->
                                val applied = if (selectedName == owned.suggestion.pattern) {
                                    listOf(owned)
                                } else {
                                    emptyList()
                                }
                                onResolved(
                                    RuleHistoryCandidates.resolveThreadTargets(
                                        selectedCandidates,
                                        applied
                                    )
                                )
                            },
                            onClose = {
                                onResolved(
                                    RuleHistoryCandidates.resolveThreadTargets(
                                        selectedCandidates,
                                        emptyList()
                                    )
                                )
                            }
                        )
                        return@ui
                    }
                    showThreadWildcardBatchSuggestions(
                        baseOwner = baseOwner,
                        selectedCount = selectedCandidates.size,
                        suggestions = suggestions
                    ) { applied ->
                        onResolved(
                            RuleHistoryCandidates.resolveThreadTargets(
                                selectedCandidates,
                                applied
                            )
                        )
                    }
                }
            }
        }

        fun selectHistoryCandidate(type: Int, targetEditor: ThreadEditor? = null) {
            val multiSelect = current == null && type == R.id.ruleTypeThread && targetEditor == null
            val excludedTargets = existingRules
                .asSequence()
                .filter { it !== current }
                .map { it.owner to it.thread }
                .toMutableSet()
            if (type == R.id.ruleTypeThread && targetEditor != null) {
                threadEditors.asSequence()
                    .filter { it !== targetEditor }
                    .map {
                        it.owner to it.binding.threadRuleName.text?.toString().orEmpty().trim()
                    }
                    .filter { it.second.isNotEmpty() }
                    .forEach(excludedTargets::add)
            }
            activeHistoryPicker = showRuleHistoryPicker(
                baseOwner = baseOwner,
                type = type,
                ownerHint = threadOwner,
                multiSelect = multiSelect,
                isOwnerActive = { !editorClosed.get() },
                loadCandidates = {
                    val kind = if (type == R.id.ruleTypeChild) {
                        RuleHistoryKind.CHILD_PROCESS
                    } else {
                        RuleHistoryKind.THREAD
                    }
                    RuleHistoryCandidates.build(
                        baseOwner,
                        QixiaThreadsDbHelper.getInstance(applicationContext)
                            .getRuleHistoryRecordsByPackage(baseOwner)
                    ).filter { candidate ->
                        candidate.kind == kind &&
                            (kind != RuleHistoryKind.THREAD || current?.thread == null ||
                                candidate.owner == threadOwner) &&
                            (candidate.owner to candidate.thread) !in excludedTargets
                    }
                }
            ) historySelected@{ selectedCandidates, loadedCandidates ->
                if (editorClosed.get()) return@historySelected
                val candidate = selectedCandidates.firstOrNull() ?: return@historySelected
                when (candidate.kind) {
                    RuleHistoryKind.CHILD_PROCESS -> {
                        view.ruleChildInput.setText(
                            candidate.owner.removePrefix(baseOwner).removePrefix(":")
                        )
                        view.ruleChildBox.error = null
                    }
                    RuleHistoryKind.THREAD -> {
                        if (multiSelect) {
                            resolveSelectedThreadTargets(
                                selectedCandidates,
                                loadedCandidates,
                                ::applySelectedThreadTargets
                            )
                            return@historySelected
                        }
                        val sameOwnerThreadCandidates = loadedCandidates.filter {
                            it.kind == RuleHistoryKind.THREAD && it.owner == candidate.owner
                        }
                        val suggestion = RuleHistoryCandidates.suggestThreadWildcard(
                            candidate,
                            sameOwnerThreadCandidates
                        )
                        fun applySingleThread(name: String) {
                            if (editorClosed.get()) return
                            val editor = targetEditor ?: threadEditors.firstOrNull() ?: return
                            editor.owner = candidate.owner
                            editor.binding.threadRuleName.setText(name)
                            editor.binding.threadRuleNameBox.error = null
                            updateThreadEditorLabels()
                            view.ruleEditError.visibility = View.GONE
                        }
                        if (suggestion == null) {
                            applySingleThread(candidate.thread.orEmpty())
                        } else {
                            showThreadWildcardSuggestion(suggestion) { selectedName ->
                                applySingleThread(selectedName)
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
            view.ruleFixedOwnerBox.visibility =
                if (childMode || threadMode) View.GONE else View.VISIBLE
            if (threadMode) updateThreadEditorLabels() else view.ruleFixedOwnerInput.setText(baseOwner)
            view.ruleChildBox.visibility = if (childMode) View.VISIBLE else View.GONE
            view.ruleThreadArea.visibility = if (threadMode) View.VISIBLE else View.GONE
            view.ruleCpuHeader.visibility = if (threadMode) View.GONE else View.VISIBLE
            view.ruleCpuGridContainer.visibility = if (threadMode) View.GONE else View.VISIBLE
            view.ruleThreadAdd.visibility =
                if (threadMode && current == null) View.VISIBLE else View.GONE
            view.ruleChildBox.error = null
            view.ruleChildBox.isEndIconVisible = childMode
            view.ruleCpuWarning.visibility = View.GONE
            if (!threadMode) showCpuSelection(checkedId)
        }

        view.ruleChildBox.setEndIconOnClickListener {
            selectHistoryCandidate(R.id.ruleTypeChild)
        }
        view.ruleThreadHistory.text = if (current == null) "历史多选" else "历史选择"
        view.ruleThreadHistory.setOnClickListener {
            val target = threadHistoryTarget
            threadHistoryTarget = null
            selectHistoryCandidate(R.id.ruleTypeThread, target)
        }
        view.ruleThreadAdd.setOnClickListener {
            val inherited = threadEditors.lastOrNull()?.cpus?.toSet()
                ?: cpuSelections.getValue(R.id.ruleTypeThread).toSet()
            addThreadEditor(baseOwner, "", inherited, requestFocus = true)
        }
        if (restore?.threads?.isNotEmpty() == true) {
            restore.threads.forEach { saved ->
                addThreadEditor(saved.owner, saved.name, saved.cpus)
            }
        } else {
            addThreadEditor(
                owner = threadOwner,
                name = current?.thread.orEmpty(),
                initialCpus = cpuSelections.getValue(R.id.ruleTypeThread)
            )
        }
        view.ruleTypeGroup.check(initialType)
        updateType(initialType)
        view.ruleTypeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) updateType(checkedId)
        }

        val editorToken = Any()
        activeRuleEditorToken = editorToken
        activeRuleEditorDraftProvider = {
            RuleEditorRestore(
                sourceIndex = current?.sourceIndex,
                draftIndex = current?.let { rule ->
                    existingRules.indexOfFirst { it === rule }.takeIf { it >= 0 }
                },
                checkedType = view.ruleTypeGroup.checkedButtonId,
                childSuffix = view.ruleChildInput.text?.toString().orEmpty(),
                cpuSelections = cpuSelections.mapValues { it.value.toSet() },
                threads = threadEditors.map { editor ->
                    ThreadEditorRestore(
                        owner = editor.owner,
                        name = editor.binding.threadRuleName.text?.toString().orEmpty(),
                        cpus = editor.cpus.toSet()
                    )
                }
            )
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
        dialog.setOnDismissListener {
            editorClosed.set(true)
            wildcardRequests.next()
            activeHistoryPicker?.takeIf { it.isShowing }?.dismiss()
            activeHistoryPicker = null
            if (activeRuleEditorToken === editorToken && !isChangingConfigurations) {
                activeRuleEditorDraftProvider = null
                activeRuleEditorToken = null
            }
        }
        view.ruleEditCancel.setOnClickListener { dialog.dismiss() }
        view.ruleEditConfirm.setOnClickListener {
            view.ruleChildBox.error = null
            threadEditors.forEach {
                it.binding.threadRuleNameBox.error = null
                it.binding.threadRuleCpuError.visibility = View.GONE
            }
            view.ruleCpuWarning.visibility = View.GONE
            view.ruleEditError.visibility = View.GONE

            val checkedType = view.ruleTypeGroup.checkedButtonId
            val cpus = RuleConfigLogic.formatCpuRangeList(cpuSelections.getValue(checkedType))
            val isChild = checkedType == R.id.ruleTypeChild
            val isThread = checkedType == R.id.ruleTypeThread
            val childSuffix = view.ruleChildInput.text?.toString().orEmpty()
                .trim()
                .removePrefix("$baseOwner:")
                .removePrefix(":")
            val threadEntries = threadEditors.map { editor ->
                Triple(
                    editor.owner,
                    editor.binding.threadRuleName.text?.toString().orEmpty().trim(),
                    RuleConfigLogic.formatCpuRangeList(editor.cpus)
                )
            }
            val threadNames = threadEntries.map { it.second }
            val owner = when {
                isChild -> "$baseOwner:$childSuffix"
                isThread -> threadOwner
                else -> baseOwner
            }
            when {
                baseOwner.isEmpty() ||
                    (isThread && threadEntries.any { !RuleConfigLogic.ownerFitsNativeBuffer(it.first) }) ||
                    (!isChild && !isThread && !RuleConfigLogic.ownerFitsNativeBuffer(owner)) -> {
                    view.ruleEditError.text = "未识别到当前应用主进程"
                    view.ruleEditError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                isChild && childSuffix.isEmpty() -> {
                    view.ruleChildBox.error = "请输入子进程后缀"
                    return@setOnClickListener
                }
                isChild && !RuleConfigLogic.ownerFitsNativeBuffer(owner) -> {
                    view.ruleChildBox.error = "完整子进程名称不能超过 127 字节"
                    return@setOnClickListener
                }
                isChild && childSuffix.any { it.isWhitespace() || it == '{' || it == '}' || it == '=' } -> {
                    view.ruleChildBox.error = "子进程后缀不能包含空格或 { } ="
                    return@setOnClickListener
                }
                isThread && threadNames.any(String::isEmpty) -> {
                    threadEditors.firstOrNull {
                        it.binding.threadRuleName.text?.toString().orEmpty().trim().isEmpty()
                    }?.binding?.threadRuleNameBox?.error = "请输入线程名称"
                    return@setOnClickListener
                }
                isThread && threadNames.any { name ->
                    name.length > 31 || !RuleConfigLogic.threadFitsNativeBuffer(name) ||
                        name.any { it == '{' || it == '}' || it == '=' }
                } -> {
                    threadEditors.firstOrNull { editor ->
                        val name = editor.binding.threadRuleName.text?.toString().orEmpty().trim()
                        name.length > 31 || !RuleConfigLogic.threadFitsNativeBuffer(name) ||
                            name.any { it == '{' || it == '}' || it == '=' }
                    }?.binding?.threadRuleNameBox?.error =
                        "线程名称不能超过 31 个字符或包含 { } ="
                    return@setOnClickListener
                }
                isThread && threadEntries.any { it.third.isEmpty() } -> {
                    threadEditors.firstOrNull { it.cpus.isEmpty() }
                        ?.binding?.threadRuleCpuError?.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                !isThread && cpus.isEmpty() -> {
                    showCpuWarning("至少选择一个核心")
                    return@setOnClickListener
                }
            }

            val newRules = if (isThread) {
                threadEntries.mapIndexed { index, (targetOwner, threadName, threadCpus) ->
                    EditableConfigRule(
                        sourceIndex = if (current != null && index == 0) current.sourceIndex else null,
                        owner = targetOwner,
                        thread = threadName,
                        cpus = threadCpus
                    )
                }
            } else {
                listOf(EditableConfigRule(current?.sourceIndex, owner, null, cpus))
            }
            val duplicate = newRules.any { rule ->
                existingRules.any {
                    it !== current && it.owner == rule.owner && it.thread == rule.thread
                }
            } || newRules.map { it.owner to it.thread }.distinct().size != newRules.size
            if (duplicate) {
                view.ruleEditError.text = "规则已存在"
                view.ruleEditError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val check = DaemonBridge.validateConfigRulesForPackages(
                targets,
                newRules.joinToString("\n") { it.asLine() },
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
                    onConfirm(newRules)
                    dialog.dismiss()
                    return@setOnClickListener
                }
            }
            view.ruleEditError.visibility = View.VISIBLE
        }
        dialog.show()
        applyResponsiveRuleSheetWidth(dialog, expand = true)
    }

    private fun showRuleHistoryPicker(
        baseOwner: String,
        type: Int,
        ownerHint: String,
        multiSelect: Boolean = false,
        isOwnerActive: () -> Boolean = { true },
        loadCandidates: () -> List<RuleHistoryCandidate>,
        onSelect: (List<RuleHistoryCandidate>, List<RuleHistoryCandidate>) -> Unit
    ): BottomSheetDialog {
        val view = DialogRuleHistoryPickerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val cancelled = AtomicBoolean(false)
        var candidates: List<RuleHistoryCandidate> = emptyList()
        val selected = linkedSetOf<String>()
        val adapter = RuleHistoryAdapter(baseOwner, multiSelect, selected) { candidate ->
            if (!isOwnerActive()) {
                dialog.dismiss()
            } else if (!multiSelect) {
                val loadedSnapshot = candidates
                dialog.dismiss()
                mainHandler.post {
                    if (isOwnerActive()) onSelect(listOf(candidate), loadedSnapshot)
                }
            } else {
                view.ruleHistoryConfirm.visibility = View.VISIBLE
                view.ruleHistoryConfirm.text = "添加选中线程（${selected.size}）"
                view.ruleHistoryConfirm.isEnabled = selected.isNotEmpty()
            }
        }
        fun pickerTitle(): String = when {
            multiSelect -> "选择线程（可多选）"
            type == R.id.ruleTypeChild -> "选择历史子进程"
            ownerHint == baseOwner -> "选择主进程线程"
            else -> {
                val suffix = ownerHint.removePrefix(baseOwner).ifBlank { ownerHint }
                "选择 $suffix 线程"
            }
        }
        view.ruleHistoryTitle.text = pickerTitle()
        view.ruleHistoryMeta.text = if (multiSelect) {
            "正在读取最近 3 次校准记录 · 最多选择 $MAX_RULE_HISTORY_SELECTION 个"
        } else {
            "正在读取最近 3 次校准记录"
        }
        view.ruleHistoryList.layoutManager = LinearLayoutManager(this)
        view.ruleHistoryList.adapter = adapter
        view.ruleHistoryList.itemAnimator = null
        view.ruleHistoryList.visibility = View.GONE
        view.ruleHistoryEmpty.visibility = View.GONE
        view.ruleHistoryLoading.visibility = View.VISIBLE
        view.ruleHistorySearchBox.isEnabled = false
        if (multiSelect) {
            view.ruleHistoryConfirm.visibility = View.VISIBLE
            view.ruleHistoryConfirm.text = "添加选中线程（0）"
            view.ruleHistoryConfirm.isEnabled = false
        }

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
            val summary = if (filtered.size == candidates.size) {
                "最近记录去重后 ${candidates.size} 个候选"
            } else {
                "匹配 ${filtered.size} / ${candidates.size} 个候选"
            }
            view.ruleHistoryMeta.text = if (multiSelect) {
                "$summary · 最多选择 $MAX_RULE_HISTORY_SELECTION 个"
            } else {
                summary
            }
            view.ruleHistoryEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            view.ruleHistoryList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        var pendingFilter: Runnable? = null
        view.ruleHistorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingFilter?.let(mainHandler::removeCallbacks)
                pendingFilter = Runnable {
                    if (!cancelled.get() && dialog.isShowing) renderFilter()
                }.also { mainHandler.postDelayed(it, 120L) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.ruleHistoryCancel.setOnClickListener { dialog.dismiss() }
        view.ruleHistoryConfirm.setOnClickListener {
            val chosen = candidates.filter {
                "${it.kind}|${it.owner}|${it.thread.orEmpty()}" in selected
            }
            if (chosen.isNotEmpty()) {
                val loadedSnapshot = candidates
                dialog.dismiss()
                mainHandler.post {
                    if (isOwnerActive()) onSelect(chosen, loadedSnapshot)
                }
            }
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        dialog.setOnDismissListener {
            cancelled.set(true)
            pendingFilter?.let(mainHandler::removeCallbacks)
        }
        dialog.show()
        applyResponsiveRuleSheetWidth(dialog)

        thread(name = "QixiaThreads-rule-history") {
            val result = runCatching(loadCandidates)
            runOnUiThreadIfAlive {
                if (cancelled.get() || !dialog.isShowing || !isOwnerActive()) {
                    if (dialog.isShowing) dialog.dismiss()
                    return@runOnUiThreadIfAlive
                }
                view.ruleHistoryLoading.visibility = View.GONE
                view.ruleHistorySearchBox.isEnabled = true
                result.fold(
                    onSuccess = { loaded ->
                        candidates = loaded.take(RuleHistoryCandidates.MAX_EDITOR_CANDIDATES)
                        view.ruleHistoryEmpty.text = "没有可用的历史记录"
                        renderFilter()
                    },
                    onFailure = { error ->
                        android.util.Log.e(
                            "QixiaThreads",
                            "read rule history candidates failed: $baseOwner",
                            error
                        )
                        view.ruleHistoryMeta.text = "历史记录读取失败"
                        view.ruleHistoryEmpty.text = "读取失败，请返回后重试"
                        view.ruleHistoryEmpty.visibility = View.VISIBLE
                        view.ruleHistoryList.visibility = View.GONE
                    }
                )
            }
        }
        return dialog
    }

    private fun showThreadWildcardBatchSuggestions(
        baseOwner: String,
        selectedCount: Int,
        suggestions: List<OwnedThreadWildcardSuggestion>,
        onSelect: (List<OwnedThreadWildcardSuggestion>) -> Unit
    ) {
        val view = DialogThreadWildcardBatchBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val checkedKeys = linkedSetOf<String>()

        fun keyOf(owned: OwnedThreadWildcardSuggestion): String =
            "${owned.owner}\u0000${owned.suggestion.pattern}"

        fun updateAction() {
            view.wildcardBatchApply.text = "应用所选建议（${checkedKeys.size}）"
        }

        view.wildcardBatchMeta.text =
            "已选 $selectedCount 个线程，发现 ${suggestions.size} 组通配符建议"
        suggestions.forEach { owned ->
            val item = ItemThreadWildcardChoiceBinding.inflate(
                layoutInflater,
                view.wildcardBatchList,
                false
            )
            val key = keyOf(owned)
            checkedKeys.add(key)
            item.wildcardChoicePattern.text = owned.suggestion.pattern
            val scope = if (owned.owner == baseOwner) {
                "主进程"
            } else {
                "子进程 ${owned.owner.removePrefix(baseOwner).removePrefix(":")}"
            }
            val preview = owned.suggestion.matchedNames.take(2).joinToString("、")
            val remaining = owned.suggestion.matchedNames.size - 2
            item.wildcardChoiceMeta.text = buildString {
                append(scope)
                append(" · 匹配 ${owned.suggestion.matchedNames.size} 个：")
                append(preview)
                if (remaining > 0) append(" 等 $remaining 个")
            }
            item.wildcardChoiceCheck.isChecked = true
            item.wildcardChoiceCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) checkedKeys.add(key) else checkedKeys.remove(key)
                updateAction()
            }
            item.root.setOnClickListener {
                item.wildcardChoiceCheck.isChecked = !item.wildcardChoiceCheck.isChecked
            }
            view.wildcardBatchList.addView(item.root)
        }
        view.wildcardBatchScroll.layoutParams = view.wildcardBatchScroll.layoutParams.apply {
            height = minOf(dp(310f), dp((suggestions.size * 68).toFloat()))
        }
        updateAction()

        fun finish(applied: List<OwnedThreadWildcardSuggestion>) {
            dialog.dismiss()
            mainHandler.post { onSelect(applied) }
        }

        view.wildcardBatchClose.setOnClickListener { finish(emptyList()) }
        view.wildcardBatchKeepExact.setOnClickListener { finish(emptyList()) }
        view.wildcardBatchApply.setOnClickListener {
            finish(suggestions.filter { keyOf(it) in checkedKeys })
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                if (resources.configuration.smallestScreenWidthDp >= 600) {
                    val params = sheet.layoutParams
                    params.width = minOf(dp(560f), resources.displayMetrics.widthPixels - dp(32f))
                    if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }
                    sheet.layoutParams = params
                }
            }
        }
        dialog.show()
        applyResponsiveRuleSheetWidth(dialog, expand = true)
    }

    private fun showThreadWildcardSuggestion(
        suggestion: ThreadWildcardSuggestion,
        onClose: (() -> Unit)? = null,
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
            dialog.dismiss()
            mainHandler.post { onSelect(name) }
        }

        view.wildcardKeepExact.setOnClickListener { finish(suggestion.exactName) }
        view.wildcardUsePattern.setOnClickListener { finish(suggestion.pattern) }
        view.wildcardClose.setOnClickListener {
            dialog.dismiss()
            onClose?.let { callback -> mainHandler.post(callback) }
        }
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
        applyResponsiveRuleSheetWidth(dialog, expand = true)
    }

    private inner class RuleHistoryAdapter(
        private val baseOwner: String,
        private val multiSelect: Boolean,
        private val selected: MutableSet<String>,
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
            val key = "${candidate.kind}|${candidate.owner}|${candidate.thread.orEmpty()}"
            holder.binding.root.setCardBackgroundColor(
                ContextCompat.getColor(this@MainActivity, R.color.card_bg)
            )
            holder.binding.root.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            holder.binding.root.setCardForegroundColor(
                ColorStateList.valueOf(Color.TRANSPARENT)
            )
            holder.binding.candidateCheck.visibility = if (multiSelect) View.VISIBLE else View.GONE
            holder.binding.candidateCheck.setOnCheckedChangeListener(null)
            holder.binding.candidateCheck.isChecked = key in selected
            lateinit var checkListener: CompoundButton.OnCheckedChangeListener
            checkListener = CompoundButton.OnCheckedChangeListener { button, checked ->
                if (checked && key !in selected && selected.size >= MAX_RULE_HISTORY_SELECTION) {
                    button.setOnCheckedChangeListener(null)
                    button.isChecked = false
                    button.setOnCheckedChangeListener(checkListener)
                    toast("一次最多选择 $MAX_RULE_HISTORY_SELECTION 个线程")
                    return@OnCheckedChangeListener
                }
                if (checked) selected.add(key) else selected.remove(key)
                onSelect(candidate)
            }
            holder.binding.candidateCheck.setOnCheckedChangeListener(checkListener)
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
            holder.binding.root.setOnClickListener {
                if (multiSelect) {
                    holder.binding.candidateCheck.isChecked = !holder.binding.candidateCheck.isChecked
                } else {
                    onSelect(candidate)
                }
            }
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
        return RuleConfigLogic.formatCpuRangeList(cpus).ifEmpty { "未知" }
    }

    private fun parseCpuSet(ranges: String): Set<Int> {
        return RuleConfigLogic.parseCpuRangeList(ranges).orEmpty()
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
        val generation = beginConfigMutation()
        val healthSnapshot = ruleHealth
        val processNamesSnapshot = processNames
        thread {
            try {
                val allowedCpus = DaemonBridge.readConfigAllowedCpus().takeIf { it.isNotEmpty() }
                val result = DaemonBridge.replaceConfigRulesPreservingLayout(
                    pkgs = targets,
                    expectedOriginalLines = expectedOriginalLines,
                    replacements = replacements,
                    addedLines = addedLines,
                    allowedCpus = allowedCpus
                )
                val ok = result == DaemonBridge.ConfigReplaceResult.SUCCESS
                val config = if (ok) ConfigReader.readPackagesOrNull() else null
                val latestHealth = if (ok) {
                    DaemonBridge.readRuleHealthOrNull() ?: healthSnapshot
                } else {
                    healthSnapshot
                }
                val updatedHealth = config?.let { current ->
                    latestHealth.filterKeys { it in current.ruleHealthKeys }
                } ?: healthSnapshot
                val fullLists = config?.let {
                    buildAppLists(it, processNamesSnapshot, updatedHealth)
                }
                finishConfigMutation(generation) {
                    addableAppsLoading = false
                    if (ok) {
                        updateRuleHealthSnapshot(updatedHealth)
                        if (fullLists != null) {
                            appLists = fullLists
                            buildAppList()
                        } else {
                            refreshAppList()
                        }
                        dialog.dismiss()
                        toast("配置已按所选规则格式保存")
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
            } catch (error: Exception) {
                android.util.Log.e("QixiaThreads", "save config rules failed: $targets", error)
                finishConfigMutation(generation) {
                    onFailed()
                    showRulesError(view, "保存失败，请重试")
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
