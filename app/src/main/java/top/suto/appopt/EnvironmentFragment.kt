package top.suto.appopt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import top.suto.appopt.databinding.FragmentEnvironmentBinding

class EnvironmentFragment : TopLevelFragment() {
    private var _binding: FragmentEnvironmentBinding? = null
    private val binding: FragmentEnvironmentBinding
        get() = checkNotNull(_binding)
    private var viewGeneration = 0
    private var refreshGeneration = 0
    private var refreshInFlight = false
    private var refreshPending = false
    private var lastRefreshFinishedAt = 0L
    private var updateGeneration = 0
    private var updateBusy = false
    private var diagnosticBusy = false
    private var cachedUpdateInfo: ModuleUpdater.UpdateInfo? = null
    private var cachedUpdateResult: ModuleUpdater.CheckResult? = null
    private var updateCheckStarted = false

    private data class ForegroundSnapshot(
        val state: DaemonBridge.TaskForegroundState,
        val startRequested: Boolean
    )

    private data class EnvironmentSnapshot(
        val root: Boolean,
        val pendingUpdate: Boolean,
        val version: DaemonBridge.ModuleVersion?,
        val compatible: Boolean,
        val runtime: DaemonBridge.DaemonRuntime,
        val foreground: ForegroundSnapshot?
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnvironmentBinding.inflate(inflater, container, false)
        viewGeneration++
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prepareTopLevelPage(binding.environmentHeader)

        binding.environmentOverlayButton.setOnClickListener { requestOverlay() }
        binding.environmentUsageButton.setOnClickListener { requestUsageAccess() }
        binding.environmentRefreshButton.setOnClickListener { refreshEnvironment(force = true) }
        binding.environmentRefresh.setOnRefreshListener { refreshEnvironment(force = true) }
        binding.environmentDiagnosticRow.setOnClickListener { exportDiagnosticPackage() }
        binding.environmentUpdateButton.setOnClickListener {
            cachedUpdateInfo?.let(::showModuleUpdateDialog) ?: checkModuleUpdate(manual = true)
        }
        bindPermissionState()
    }

    override fun onTopLevelPageSelected() {
        if (_binding == null) return
        refreshEnvironment()
        cachedUpdateResult?.let(::bindUpdateResult)
        if (updateBusy) {
            setUpdateBusy(true)
            setUpdateStatus("正在获取远程更新信息")
            binding.environmentRemoteVersion.text = "获取中"
            return
        }
        if (!updateCheckStarted) {
            updateCheckStarted = true
            checkModuleUpdate(manual = false)
        }
    }

    private fun refreshEnvironment(force: Boolean = false) {
        if (_binding == null) return
        bindPermissionState()
        if (refreshInFlight) {
            if (force) refreshPending = true
            return
        }
        if (!force && lastRefreshFinishedAt > 0L &&
            android.os.SystemClock.elapsedRealtime() - lastRefreshFinishedAt < REFRESH_INTERVAL_MS) {
            return
        }
        refreshInFlight = true
        val generation = ++refreshGeneration
        val currentViewGeneration = viewGeneration
        binding.environmentRefresh.isRefreshing = true
        binding.environmentRefreshButton.isEnabled = false
        binding.environmentRefreshButton.alpha = 0.48f
        bindLoadingState()
        thread(name = "AppOptEnvironment") {
            val result = runCatching {
                val root = DaemonBridge.hasRoot()
                val pendingUpdate = if (root) DaemonBridge.hasPendingModuleUpdate() else false
                val version = if (root && !pendingUpdate) DaemonBridge.readModuleVersion() else null
                val compatible = version?.versionCode?.let {
                    it >= DaemonBridge.REQUIRED_MODULE_VERSION_CODE
                } == true
                val runtime = if (root && compatible && !pendingUpdate) {
                    DaemonBridge.readDaemonRuntime()
                } else {
                    DaemonBridge.DaemonRuntime(running = false)
                }
                val foreground = if (root && compatible && !pendingUpdate) {
                    readForegroundState()
                } else {
                    null
                }
                EnvironmentSnapshot(root, pendingUpdate, version, compatible, runtime, foreground)
            }
            runOnUiThread {
                if (currentViewGeneration != viewGeneration || generation != refreshGeneration ||
                    isFinishing || isDestroyed) return@runOnUiThread
                bindPermissionState()
                result.onSuccess { snapshot ->
                    bindRootState(snapshot.root)
                    bindDaemonState(
                        snapshot.root,
                        snapshot.pendingUpdate,
                        snapshot.compatible,
                        snapshot.runtime
                    )
                    bindForegroundState(
                        snapshot.root,
                        snapshot.pendingUpdate,
                        snapshot.compatible,
                        snapshot.foreground
                    )
                    binding.environmentModuleVersion.text = when {
                        !snapshot.root -> "需要 Root 权限"
                        snapshot.pendingUpdate -> "更新待重启"
                        snapshot.version == null -> "未检测到"
                        else -> "${snapshot.version.versionName} (${snapshot.version.versionCode})"
                    }
                }.onFailure { error ->
                    android.util.Log.e("AppOpt", "刷新运行环境失败", error)
                    setStatus(binding.environmentDotRoot, binding.environmentRootState, "检查失败", R.color.status_warn)
                    setStatus(binding.environmentDotDaemon, binding.environmentDaemonState, "检查失败", R.color.status_warn)
                    setStatus(binding.environmentDotForeground, binding.environmentForegroundState, "检查失败", R.color.status_warn)
                    binding.environmentModuleVersion.text = "检查失败"
                }
                binding.environmentRefresh.isRefreshing = false
                binding.environmentRefreshButton.isEnabled = true
                binding.environmentRefreshButton.alpha = 1f
                refreshInFlight = false
                lastRefreshFinishedAt = android.os.SystemClock.elapsedRealtime()
                if (refreshPending) {
                    refreshPending = false
                    refreshEnvironment(force = true)
                }
            }
        }
    }

    private fun bindLoadingState() {
        setStatus(binding.environmentDotRoot, binding.environmentRootState, "检查中", R.color.status_warn)
        setStatus(binding.environmentDotDaemon, binding.environmentDaemonState, "检查中", R.color.status_warn)
        setStatus(binding.environmentDotForeground, binding.environmentForegroundState, "检查中", R.color.status_warn)
        binding.environmentModuleVersion.text = "检查中"
    }

    private fun bindPermissionState() {
        val overlay = Settings.canDrawOverlays(requireContext())
        binding.environmentOverlayButton.visibility = if (overlay) View.GONE else View.VISIBLE
        setStatus(
            binding.environmentDotOverlay,
            binding.environmentOverlayState,
            if (overlay) "已授予" else "未授予",
            if (overlay) R.color.status_ok else R.color.status_warn
        )

        val usage = ForegroundDetector.hasUsageAccess(requireContext())
        binding.environmentUsageButton.visibility = if (usage) View.GONE else View.VISIBLE
        setStatus(
            binding.environmentDotUsage,
            binding.environmentUsageState,
            if (usage) "已授予" else "未授予",
            if (usage) R.color.status_ok else R.color.status_warn
        )
    }

    private fun bindRootState(root: Boolean) {
        setStatus(
            binding.environmentDotRoot,
            binding.environmentRootState,
            if (root) "可用" else "不可用",
            if (root) R.color.status_ok else R.color.status_off
        )
    }

    private fun bindDaemonState(
        root: Boolean,
        pendingUpdate: Boolean,
        compatible: Boolean,
        runtime: DaemonBridge.DaemonRuntime
    ) {
        val (text, color) = when {
            !root -> "未知" to R.color.status_off
            pendingUpdate -> "待重启" to R.color.status_warn
            !compatible -> "模块需更新" to R.color.status_warn
            runtime.running -> daemonLabel(runtime) to R.color.status_ok
            else -> "未运行" to R.color.status_warn
        }
        setStatus(binding.environmentDotDaemon, binding.environmentDaemonState, text, color)
    }

    private fun bindForegroundState(
        root: Boolean,
        pendingUpdate: Boolean,
        compatible: Boolean,
        snapshot: ForegroundSnapshot?
    ) {
        val state = snapshot?.state
        val (text, color) = when {
            !root -> "未知" to R.color.status_off
            pendingUpdate -> "待重启" to R.color.status_warn
            !compatible -> "模块需更新" to R.color.status_warn
            state?.available == true && state.mode == "poll" -> "轮询中" to R.color.status_warn
            state?.available == true -> "运行中" to R.color.status_ok
            state?.status == "error" -> "错误" to R.color.status_warn
            state?.status == "empty" -> "无任务" to R.color.status_warn
            state?.ageMs != null -> "状态过期" to R.color.status_warn
            snapshot?.startRequested == true -> "启动中" to R.color.status_warn
            else -> "不可用" to R.color.status_off
        }
        setStatus(binding.environmentDotForeground, binding.environmentForegroundState, text, color)
    }

    private fun readForegroundState(): ForegroundSnapshot {
        var state = DaemonBridge.readTaskForegroundState()
        if (state.available) return ForegroundSnapshot(state, startRequested = false)
        val started = DaemonBridge.ensureTaskForegroundHelper()
        if (started) {
            repeat(3) {
                if (state.available) return ForegroundSnapshot(state, startRequested = true)
                try {
                    Thread.sleep(160L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return ForegroundSnapshot(state, startRequested = true)
                }
                state = DaemonBridge.readTaskForegroundState()
            }
        }
        return ForegroundSnapshot(state, startRequested = started)
    }

    private fun daemonLabel(runtime: DaemonBridge.DaemonRuntime): String {
        val version = runtime.versionName?.takeIf { it.isNotBlank() }
        return when {
            version != null -> "Rust 版 $version"
            else -> "Rust 版"
        }
    }

    private fun setStatus(dot: View, state: android.widget.TextView, text: String, colorRes: Int) {
        state.text = text
        dot.background?.mutate()?.setTint(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
        )
    }

    private fun requestUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                AppToast.show(requireContext(), "请在系统设置 > 使用情况访问 中授予本应用权限")
            }
        }
    }

    private fun exportDiagnosticPackage() {
        if (diagnosticBusy) {
            toast("正在导出诊断包")
            return
        }
        diagnosticBusy = true
        binding.environmentDiagnosticRow.isEnabled = false
        binding.environmentDiagnosticRow.alpha = 0.55f
        toast("正在导出诊断包")
        val currentViewGeneration = viewGeneration
        thread(name = "AppOptDiagnosticExport") {
            val result = DiagnosticExporter.export(appContext)
            runOnUiThread {
                if (currentViewGeneration != viewGeneration || _binding == null ||
                    isFinishing || isDestroyed) return@runOnUiThread
                diagnosticBusy = false
                binding.environmentDiagnosticRow.isEnabled = true
                binding.environmentDiagnosticRow.alpha = 1f
                result.fold(
                    onSuccess = { toast("已导出到 $it") },
                    onFailure = { toast("导出失败: ${it.message ?: "无法写入 Download"}") }
                )
            }
        }
    }

    private fun checkModuleUpdate(manual: Boolean) {
        if (_binding == null) return
        if (updateBusy) {
            if (manual) toast("正在处理更新")
            return
        }
        setUpdateBusy(true)
        setUpdateStatus("正在获取远程更新信息")
        binding.environmentRemoteVersion.text = "获取中"
        if (manual) toast("正在检查更新")
        val generation = ++updateGeneration
        thread(name = "AppOptUpdateCheck") {
            val result = runCatching { ModuleUpdater.checkForUpdate() }
                .onFailure { android.util.Log.e("AppOpt", "检查模块更新失败", it) }
                .getOrElse { ModuleUpdater.CheckResult.Failed("检查更新失败") }
            activity?.runOnUiThread {
                if (generation != updateGeneration) return@runOnUiThread
                cachedUpdateResult = result
                updateBusy = false
                if (_binding == null || isFinishing || isDestroyed) return@runOnUiThread
                bindUpdateResult(result)
                when (result) {
                    is ModuleUpdater.CheckResult.UpdateAvailable -> {
                        if (manual) showModuleUpdateDialog(result.update) else setUpdateBusy(false)
                    }
                    is ModuleUpdater.CheckResult.NoUpdate -> {
                        setUpdateBusy(false)
                        if (manual) toast(result.message)
                    }
                    is ModuleUpdater.CheckResult.Failed -> {
                        setUpdateBusy(false)
                        if (manual) toast(result.message)
                    }
                }
            }
        }
    }

    private fun setUpdateBusy(busy: Boolean) {
        updateBusy = busy
        binding.environmentUpdateButton.isEnabled = !busy
        binding.environmentUpdateButton.alpha = if (busy) 0.55f else 1f
    }

    private fun bindUpdateResult(result: ModuleUpdater.CheckResult) {
        when (result) {
            is ModuleUpdater.CheckResult.UpdateAvailable -> {
                cachedUpdateInfo = result.update
                binding.environmentModuleVersion.text = versionLabel(
                    result.update.localVersion,
                    result.update.localVersionCode
                )
                binding.environmentRemoteVersion.text = versionLabel(
                    result.update.remoteVersion,
                    result.update.remoteVersionCode
                )
                setUpdateStatus("发现新版本，可查看更新日志并刷入")
                binding.environmentUpdateButton.text = "查看更新"
            }
            is ModuleUpdater.CheckResult.NoUpdate -> {
                cachedUpdateInfo = null
                binding.environmentModuleVersion.text =
                    versionLabel(result.localVersion, result.localVersionCode)
                binding.environmentRemoteVersion.text =
                    versionLabel(result.remoteVersion, result.remoteVersionCode)
                setUpdateStatus(result.message)
                binding.environmentUpdateButton.text = "检查更新"
            }
            is ModuleUpdater.CheckResult.Failed -> {
                cachedUpdateInfo = null
                binding.environmentModuleVersion.text =
                    versionLabel(result.localVersion, result.localVersionCode)
                binding.environmentRemoteVersion.text =
                    versionLabel(result.remoteVersion, result.remoteVersionCode)
                setUpdateStatus(result.message)
                binding.environmentUpdateButton.text = "重试"
            }
        }
    }

    private fun versionLabel(version: String?, code: Int?): String {
        val name = version?.takeIf { it.isNotBlank() }
        return when {
            name != null && code != null -> "$name ($code)"
            name != null -> name
            code != null -> code.toString()
            else -> "未知"
        }
    }

    private fun showModuleUpdateDialog(update: ModuleUpdater.UpdateInfo) {
        setUpdateBusy(true)
        val currentViewGeneration = viewGeneration
        ModuleUpdateDialog.show(requireActivity() as AppCompatActivity, update) {
            if (currentViewGeneration == viewGeneration && _binding != null) {
                setUpdateBusy(false)
            }
        }
    }

    private fun setUpdateStatus(text: String) {
        binding.environmentUpdateStatus.setTextColor(getColor(R.color.text_secondary))
        binding.environmentUpdateStatus.text = text
    }

    private fun toast(message: String) {
        AppToast.show(requireContext(), message)
    }

    override fun onDestroyView() {
        refreshGeneration++
        refreshInFlight = false
        refreshPending = false
        diagnosticBusy = false
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 3_000L
    }
}
