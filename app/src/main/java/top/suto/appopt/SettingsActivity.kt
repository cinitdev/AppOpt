package top.suto.appopt

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivitySettingsBinding
import top.suto.appopt.databinding.DialogPolicyModeBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var lockedByPendingUpdate = false
    private var hasRoot = false
    private var moduleVersion: DaemonBridge.ModuleVersion? = null
    private var firstResume = true
    private var updateBusy = false
    private var cachedUpdateInfo: ModuleUpdater.UpdateInfo? = null
    private var policyEditable = false
    private var suppressPolicyChange = false
    private var currentWildcardGroup = CalibPolicy.WildcardGroup.MAX_MEMBER
    private var currentDetectedTopologyBlock = ""
    private var availableCpus: List<Int> = (0..7).toList()
    private val bestCores = linkedSetOf<Int>()
    private val highCores = linkedSetOf<Int>()
    private val midCores = linkedSetOf<Int>()
    private val fallbackCores = linkedSetOf<Int>()
    private var autoSaveRunnable: Runnable? = null
    private var coreWarningRunnable: Runnable? = null
    private var coreWarningView: TextView? = null
    private var saveSeq = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val MIN_MODULE_VERSION_CODE = DaemonBridge.REQUIRED_MODULE_VERSION_CODE
        const val MIN_MODULE_VERSION_NAME = DaemonBridge.REQUIRED_MODULE_VERSION_NAME
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsHistoryRow.setOnClickListener {
            startActivity(Intent(this, HistoryListActivity::class.java))
        }
        binding.settingsLogRow.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.settingsUpdateButton.setOnClickListener {
            cachedUpdateInfo?.let { update ->
                showModuleUpdateDialog(update)
            } ?: checkModuleUpdate(manual = true)
        }
        binding.settingsHelpRow.setOnClickListener { showHelp() }
        binding.wildcardModeRow.setOnClickListener {
            if (policyEditable) showWildcardModeDialog()
        }

        setupAutoSave()
        binding.resetPolicy.setOnClickListener {
            showResetPolicyConfirm()
        }

        setPolicyInputsEnabled(false)
        setPolicyStatus("正在读取策略")
        setUpdateStatus("正在获取远程更新信息")
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                loadPolicy()
                checkModuleUpdate(manual = false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            loadPolicy()
        }
    }

    private fun loadPolicy() {
        cancelAutoSave()
        policyEditable = false
        setPolicyStatus("正在读取策略")
        setPolicyInputsEnabled(false)
        thread {
            val root = DaemonBridge.hasRoot()
            val version = if (root) DaemonBridge.readModuleVersion() else null
            val file = if (root) DaemonBridge.readCalibPolicyRaw() else null
            val rawPolicy = file?.content?.takeIf { it.isNotBlank() }
            val policy = rawPolicy
                ?.takeIf { it.isNotBlank() }
                ?.let { CalibPolicy.parse(it) }
                ?: CalibPolicy.DEFAULT
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hasRoot = root
                moduleVersion = version
                binding.updateLocalVersion.text = version?.let {
                    versionLabel(it.versionName, it.versionCode)
                } ?: "未知"
                lockedByPendingUpdate = file?.lockedByPendingUpdate == true
                val moduleOk = version?.versionCode?.let { it >= MIN_MODULE_VERSION_CODE } == true
                val moduleLabel = version?.let { "${it.versionName} (${it.versionCode})" }
                bindPolicy(policy)
                binding.policyLockedNotice.visibility =
                    if (lockedByPendingUpdate || (root && !moduleOk)) View.VISIBLE else View.GONE
                binding.policyLockedNotice.text = when {
                    lockedByPendingUpdate ->
                        "模块更新待重启，当前刷入的模块尚未生效，重启后才能修改自动校准策略"
                    root && version == null ->
                        "未检测到兼容的 AppOpt 模块，请刷入 v$MIN_MODULE_VERSION_NAME ($MIN_MODULE_VERSION_CODE) 或更高版本模块"
                    root && !moduleOk ->
                        "当前模块版本 $moduleLabel 低于 App 要求，请刷入 v$MIN_MODULE_VERSION_NAME ($MIN_MODULE_VERSION_CODE) 或更高版本模块"
                    else -> binding.policyLockedNotice.text
                }
                setPolicyStatus(when {
                    !root -> "需要 Root 权限读取和保存策略"
                    version == null -> "未检测到模块版本，策略已锁定"
                    !moduleOk -> "当前模块版本 $moduleLabel，低于要求 v$MIN_MODULE_VERSION_NAME ($MIN_MODULE_VERSION_CODE)，策略已锁定"
                    lockedByPendingUpdate -> "读取待生效更新配置：${file?.path.orEmpty()}"
                    file?.exists == false -> "策略文件不存在，可点击恢复默认重新生成；修改任意设置也会重新创建"
                    file?.content.isNullOrBlank() -> "策略文件为空或读取失败，修改后会自动保存当前策略"
                    else -> "当前配置：${file?.path.orEmpty()}，修改后自动保存"
                })
                policyEditable = root && moduleOk && !lockedByPendingUpdate
                setPolicyInputsEnabled(policyEditable)
            }
        }
    }

    private fun bindPolicy(policy: CalibPolicy) {
        suppressPolicyChange = true
        try {
            clearPolicyInputErrors()
            currentDetectedTopologyBlock = policy.detectedTopologyBlock
            currentWildcardGroup = policy.wildcardGroup

            val topology = parseDetectedTopology(currentDetectedTopologyBlock)
            availableCpus = availableCpuList(policy, topology)
            renderTopologySummary(topology)

            binding.bestAvgInput.setText(policy.bestAvg.formatOne())
            binding.bestMaxInput.setText(policy.bestMax.formatOne())
            binding.highAvgInput.setText(policy.highAvg.formatOne())
            binding.highMaxInput.setText(policy.highMax.formatOne())
            binding.midAvgInput.setText(policy.midAvg.formatOne())
            binding.midMaxInput.setText(policy.midMax.formatOne())
            binding.maxRulesInput.setText(policy.maxThreadRules.toString())

            setCoreSelection(bestCores, resolveCores(policy.bestCores, topology["big"] ?: CalibPolicy.DEFAULT_BEST_CORES))
            setCoreSelection(highCores, resolveCores(policy.highCores, topology["middle_high"] ?: topology["middle"] ?: CalibPolicy.DEFAULT_HIGH_CORES))
            setCoreSelection(midCores, resolveCores(policy.midCores, topology["middle"] ?: CalibPolicy.DEFAULT_MID_CORES))
            setCoreSelection(
                fallbackCores,
                resolveCores(policy.fallbackCores, topology["nonbig"] ?: CalibPolicy.DEFAULT_FALLBACK_CORES)
            )
            renderCoreSelectors()
            updateWildcardModeText()
        } finally {
            suppressPolicyChange = false
        }
    }

    private fun clearPolicyInputErrors() {
        listOf(
            binding.bestAvgLayout,
            binding.bestMaxLayout,
            binding.highAvgLayout,
            binding.highMaxLayout,
            binding.midAvgLayout,
            binding.midMaxLayout,
            binding.maxRulesLayout
        ).forEach {
            it.error = null
            it.isErrorEnabled = false
        }
    }

    private fun percent(input: EditText, layout: TextInputLayout): Double? {
        val v = input.text?.toString()?.trim()?.toIntOrNull()
        setInputError(layout, if (v == null || v < 0 || v > 100) "0-100 整数" else null)
        return if (layout.error == null) v?.toDouble() else null
    }

    private fun ruleCount(input: EditText, layout: TextInputLayout): Int? {
        val v = input.text?.toString()?.trim()?.toIntOrNull()
        setInputError(layout, if (v == null || v !in 1..12) "1-12" else null)
        return if (layout.error == null) v else null
    }

    private fun setInputError(layout: TextInputLayout, message: String?) {
        layout.error = message
        layout.isErrorEnabled = message != null
    }

    private fun readPolicyFromInputs(): CalibPolicy? {
        val bestAvg = percent(binding.bestAvgInput, binding.bestAvgLayout)
        val bestMax = percent(binding.bestMaxInput, binding.bestMaxLayout)
        val highAvg = percent(binding.highAvgInput, binding.highAvgLayout)
        val highMax = percent(binding.highMaxInput, binding.highMaxLayout)
        val midAvg = percent(binding.midAvgInput, binding.midAvgLayout)
        val midMax = percent(binding.midMaxInput, binding.midMaxLayout)
        val maxRules = ruleCount(binding.maxRulesInput, binding.maxRulesLayout)
        if (listOf(bestAvg, bestMax, highAvg, highMax, midAvg, midMax, maxRules).any { it == null }) {
            return null
        }
        if (listOf(bestCores, highCores, midCores, fallbackCores).any { it.isEmpty() }) {
            return null
        }
        return CalibPolicy(
            bestAvg = bestAvg!!,
            bestMax = bestMax!!,
            bestCores = formatCpuSet(bestCores),
            highAvg = highAvg!!,
            highMax = highMax!!,
            highCores = formatCpuSet(highCores),
            midAvg = midAvg!!,
            midMax = midMax!!,
            midCores = formatCpuSet(midCores),
            maxThreadRules = maxRules!!,
            wildcardGroup = currentWildcardGroup,
            fallbackCores = formatCpuSet(fallbackCores),
            detectedTopologyBlock = currentDetectedTopologyBlock
        ).normalized()
    }

    private fun setupAutoSave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressPolicyChange) schedulePolicySave()
            }
        }
        listOf(
            binding.bestAvgInput,
            binding.bestMaxInput,
            binding.highAvgInput,
            binding.highMaxInput,
            binding.midAvgInput,
            binding.midMaxInput,
            binding.maxRulesInput
        ).forEach { it.addTextChangedListener(watcher) }
    }

    private fun schedulePolicySave(delayMs: Long = 650L) {
        if (!policyEditable || suppressPolicyChange) return
        autoSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        val seq = ++saveSeq
        val runnable = Runnable {
            val policy = readPolicyFromInputs() ?: return@Runnable
            savePolicy(policy, seq)
        }
        autoSaveRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoSave() {
        autoSaveRunnable?.let { mainHandler.removeCallbacks(it) }
        autoSaveRunnable = null
    }

    private fun showResetPolicyConfirm() {
        if (!policyEditable) return
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复默认策略")
            .setMessage("会把自动校准策略恢复为默认阈值和默认核心分配，并立即写入配置文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                restoreModuleDefaultPolicy()
            }
            .show()
    }

    private fun restoreModuleDefaultPolicy() {
        val policy = moduleDefaultPolicy()
        setPolicyStatus("默认策略已生成，正在保存")
        setPolicyInputsEnabled(false)
        bindPolicy(policy)
        val moduleOk = moduleVersion?.versionCode?.let { it >= MIN_MODULE_VERSION_CODE } == true
        policyEditable = hasRoot && moduleOk && !lockedByPendingUpdate
        setPolicyInputsEnabled(policyEditable)
        val seq = ++saveSeq
        savePolicy(policy, seq, successMessage = "已恢复默认策略")
    }

    private fun moduleDefaultPolicy(): CalibPolicy {
        val topology = parseDetectedTopology(currentDetectedTopologyBlock)
        return CalibPolicy.DEFAULT.copy(
            bestCores = topology["big"] ?: CalibPolicy.DEFAULT_BEST_CORES,
            highCores = topology["middle_high"] ?: topology["middle"] ?: CalibPolicy.DEFAULT_HIGH_CORES,
            midCores = topology["middle"] ?: CalibPolicy.DEFAULT_MID_CORES,
            fallbackCores = topology["nonbig"] ?: CalibPolicy.DEFAULT_FALLBACK_CORES,
            detectedTopologyBlock = currentDetectedTopologyBlock
        ).normalized()
    }

    private fun savePolicy(policy: CalibPolicy, seq: Int, successMessage: String? = null) {
        if (!hasRoot) {
            toast("请先授予 Root 权限")
            return
        }
        if (lockedByPendingUpdate) {
            toast("模块更新待重启，当前不能修改策略")
            return
        }
        thread {
            val ok = DaemonBridge.writeCalibPolicyRaw(policy.toConfigText())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (seq != saveSeq) return@runOnUiThread
                if (ok) {
                    successMessage?.let {
                        setPolicyStatus("$it，修改后自动保存")
                        toast(it)
                    }
                } else {
                    toast("自动保存失败，请检查 Root 或模块状态")
                }
            }
        }
    }

    private fun checkModuleUpdate(manual: Boolean) {
        if (updateBusy) {
            if (manual) toast("正在处理更新")
            return
        }
        setUpdateBusy(true)
        setUpdateStatus("正在获取远程更新信息")
        binding.updateRemoteVersion.text = "获取中"
        if (manual) toast("正在检查更新")
        thread {
            val result = ModuleUpdater.checkForUpdate()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                bindUpdateResult(result)
                when (result) {
                    is ModuleUpdater.CheckResult.UpdateAvailable -> {
                        if (manual) {
                            showModuleUpdateDialog(result.update)
                        } else {
                            setUpdateBusy(false)
                        }
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
        binding.settingsUpdateRow.isEnabled = !busy
        binding.settingsUpdateButton.isEnabled = !busy
        binding.settingsUpdateRow.alpha = if (busy) 0.55f else 1f
    }

    private fun bindUpdateResult(result: ModuleUpdater.CheckResult) {
        when (result) {
            is ModuleUpdater.CheckResult.UpdateAvailable -> {
                cachedUpdateInfo = result.update
                binding.updateLocalVersion.text = versionLabel(
                    result.update.localVersion,
                    result.update.localVersionCode
                )
                binding.updateRemoteVersion.text = versionLabel(
                    result.update.remoteVersion,
                    result.update.remoteVersionCode
                )
                setUpdateStatus("发现新版本，可查看更新日志并刷入")
                binding.settingsUpdateButton.text = "查看更新"
            }
            is ModuleUpdater.CheckResult.NoUpdate -> {
                cachedUpdateInfo = null
                binding.updateLocalVersion.text = versionLabel(result.localVersion, result.localVersionCode)
                binding.updateRemoteVersion.text = versionLabel(result.remoteVersion, result.remoteVersionCode)
                setUpdateStatus(result.message)
                binding.settingsUpdateButton.text = "检查更新"
            }
            is ModuleUpdater.CheckResult.Failed -> {
                cachedUpdateInfo = null
                binding.updateLocalVersion.text = versionLabel(result.localVersion, result.localVersionCode)
                binding.updateRemoteVersion.text = versionLabel(result.remoteVersion, result.remoteVersionCode)
                setUpdateStatus(result.message)
                binding.settingsUpdateButton.text = "重试"
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
        ModuleUpdateDialog.show(this, update) {
            setUpdateBusy(false)
        }
    }

    private fun setPolicyInputsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.55f
        val inputs = listOf(
            binding.bestAvgInput,
            binding.bestMaxInput,
            binding.highAvgInput,
            binding.highMaxInput,
            binding.midAvgInput,
            binding.midMaxInput,
            binding.maxRulesInput
        )
        for (input in inputs) {
            input.isEnabled = enabled
            input.alpha = alpha
        }
        binding.wildcardModeRow.isEnabled = enabled
        binding.wildcardModeRow.alpha = alpha
        setCoreGridEnabled(binding.bestCoresGrid, enabled, alpha)
        setCoreGridEnabled(binding.highCoresGrid, enabled, alpha)
        setCoreGridEnabled(binding.midCoresGrid, enabled, alpha)
        setCoreGridEnabled(binding.fallbackCoresGrid, enabled, alpha)
        binding.resetPolicy.isEnabled = enabled
    }

    private fun updateWildcardModeText() {
        when (currentWildcardGroup) {
            CalibPolicy.WildcardGroup.MAX_MEMBER -> {
                binding.wildcardModeValue.text = "平均取最高"
                binding.wildcardModeDesc.text = "Job.worker 1 / 2 这类相似线程合成一组，AVG 取最忙单线程，MAX 取最高峰值"
            }
            CalibPolicy.WildcardGroup.SUM -> {
                binding.wildcardModeValue.text = "平均相加"
                binding.wildcardModeDesc.text = "Job.worker 1 / 2 这类相似线程合成一组，AVG 相加，MAX 取最高峰值"
            }
        }
    }

    private fun showWildcardModeDialog() {
        val view = DialogPolicyModeBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        val maxCurrent = currentWildcardGroup == CalibPolicy.WildcardGroup.MAX_MEMBER
        view.modeMaxMemberTitle.text = if (maxCurrent) "平均负载取最高（当前）" else "平均负载取最高"
        view.modeSumTitle.text = if (!maxCurrent) "平均负载相加（当前）" else "平均负载相加"
        view.modeMaxMember.setOnClickListener {
            dialog.dismiss()
            setWildcardMode(CalibPolicy.WildcardGroup.MAX_MEMBER)
        }
        view.modeSum.setOnClickListener {
            dialog.dismiss()
            setWildcardMode(CalibPolicy.WildcardGroup.SUM)
        }
        view.modeCancel.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view.root)
        dialog.show()
    }

    private fun setWildcardMode(mode: CalibPolicy.WildcardGroup) {
        if (currentWildcardGroup == mode) return
        currentWildcardGroup = mode
        updateWildcardModeText()
        schedulePolicySave(delayMs = 0)
    }

    private fun renderCoreSelectors() {
        renderCoreGrid(binding.bestCoresGrid, binding.bestCoresWarning, bestCores)
        renderCoreGrid(binding.highCoresGrid, binding.highCoresWarning, highCores)
        renderCoreGrid(binding.midCoresGrid, binding.midCoresWarning, midCores)
        renderCoreGrid(binding.fallbackCoresGrid, binding.fallbackCoresWarning, fallbackCores)
        val alpha = if (policyEditable) 1f else 0.55f
        setCoreGridEnabled(binding.bestCoresGrid, policyEditable, alpha)
        setCoreGridEnabled(binding.highCoresGrid, policyEditable, alpha)
        setCoreGridEnabled(binding.midCoresGrid, policyEditable, alpha)
        setCoreGridEnabled(binding.fallbackCoresGrid, policyEditable, alpha)
    }

    private fun renderTopologySummary(topology: Map<String, String>) {
        val entries = listOf(
            "最高性能" to topology["big"],
            "高性能" to topology["middle_high"],
            "主性能" to topology["middle"],
            "低性能" to topology["low"],
            "非最高" to topology["nonbig"],
            "全部" to topology["all"]
        ).filter { !it.second.isNullOrBlank() }

        binding.topologySummaryGrid.removeAllViews()
        if (entries.isEmpty()) {
            binding.topologySummaryPanel.visibility = View.GONE
            return
        }

        val clusters = topology["clusters"]?.toIntOrNull()
        binding.topologySummaryDesc.text = if (clusters != null && clusters > 0) {
            "按最大频率识别为 ${clusters} 个性能簇，同频核心不会被拆开"
        } else {
            "按最大频率识别性能档位，同频核心不会被拆开"
        }
        binding.topologySummaryPanel.visibility = View.VISIBLE
        binding.topologySummaryGrid.columnCount = 2

        for ((label, cores) in entries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_topology_chip)
                setPadding(10.dp, 8.dp, 10.dp, 8.dp)
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 11.5f
                    includeFontPadding = false
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "CPU ${cores.orEmpty()}"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                })
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 8.dp, 8.dp)
            }
            binding.topologySummaryGrid.addView(item, params)
        }
    }

    private fun renderCoreGrid(grid: GridLayout, warning: TextView, selected: MutableSet<Int>) {
        grid.removeAllViews()
        warning.visibility = View.GONE
        grid.columnCount = minOf(4, availableCpus.size.coerceAtLeast(1))
        for (cpu in availableCpus) {
            val box = MaterialCheckBox(this).apply {
                text = "CPU$cpu"
                textSize = 15f
                isChecked = selected.contains(cpu)
                minHeight = 44.dp
                setPadding(0, 0, 8.dp, 0)
                setOnCheckedChangeListener { button, checked ->
                    if (suppressPolicyChange) return@setOnCheckedChangeListener
                    val next = selected.toMutableSet()
                    if (checked) {
                        next.add(cpu)
                    } else if (selected.size <= 1) {
                        suppressPolicyChange = true
                        button.isChecked = true
                        suppressPolicyChange = false
                        showCoreWarning(warning, "每一档至少选择一个核心")
                        return@setOnCheckedChangeListener
                    } else {
                        next.remove(cpu)
                    }
                    if (!isContinuousSelection(next)) {
                        suppressPolicyChange = true
                        button.isChecked = !checked
                        suppressPolicyChange = false
                        showCoreWarning(warning, "核心范围必须连续，例如 0-3、4-7，不能跳选")
                        return@setOnCheckedChangeListener
                    }
                    selected.clear()
                    selected.addAll(next.sorted())
                    warning.visibility = View.GONE
                    schedulePolicySave()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 2.dp, 0, 2.dp)
            }
            grid.addView(box, params)
        }
    }

    private fun setCoreGridEnabled(grid: GridLayout, enabled: Boolean, alpha: Float) {
        grid.alpha = alpha
        for (i in 0 until grid.childCount) {
            grid.getChildAt(i).isEnabled = enabled
        }
    }

    private fun setCoreSelection(target: MutableSet<Int>, ranges: String) {
        target.clear()
        target.addAll(parseCpuRanges(ranges))
        target.retainAll(availableCpus.toSet())
        if (target.isEmpty()) {
            target.add(availableCpus.lastOrNull() ?: 0)
        }
        makeSelectionContinuous(target)
    }

    private fun resolveCores(cores: String, defaultCores: String): String {
        return CalibPolicy.normalizeCoresOrNull(cores) ?: defaultCores
    }

    private fun availableCpuList(policy: CalibPolicy, topology: Map<String, String>): List<Int> {
        val detected = topology["all"]?.let { parseCpuRanges(it) }.orEmpty()
        if (detected.isNotEmpty()) return detected.sorted()

        val fromPolicy = listOf(
            policy.bestCores,
            policy.highCores,
            policy.midCores,
            policy.fallbackCores
        ).flatMap {
            CalibPolicy.normalizeCoresOrNull(it)?.let { ranges -> parseCpuRanges(ranges) } ?: emptyList()
        }
        if (fromPolicy.isNotEmpty()) {
            val max = fromPolicy.maxOrNull() ?: 7
            return (0..max.coerceAtLeast(7)).toList()
        }
        return (0..7).toList()
    }

    private fun parseDetectedTopology(block: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in block.lineSequence()) {
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            when (key) {
                "detected_clusters" -> map["clusters"] = value
                "detected_low", "detected_little" -> map["low"] = value
                "detected_top", "detected_big" -> map["big"] = value
                "detected_high", "detected_middle_high" -> map["middle_high"] = value
                "detected_main", "detected_middle" -> map["middle"] = value
                "detected_non_top", "detected_nonbig" -> map["nonbig"] = value
                "detected_all" -> map["all"] = value
            }
        }
        return map
    }

    private fun parseCpuRanges(ranges: String): Set<Int> {
        val out = linkedSetOf<Int>()
        for (part in ranges.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            if (dash >= 0) {
                val start = p.substring(0, dash).toIntOrNull()
                val end = p.substring(dash + 1).toIntOrNull()
                if (start != null && end != null) {
                    val range = if (start <= end) start..end else end..start
                    out.addAll(range)
                }
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun formatCpuSet(cpus: Set<Int>): String {
        val sorted = cpus.sorted()
        if (sorted.isEmpty()) return ""
        val start = sorted.first()
        val end = sorted.last()
        return if (start == end) start.toString() else "$start-$end"
    }

    private fun isContinuousSelection(cpus: Set<Int>): Boolean {
        if (cpus.isEmpty()) return false
        val sorted = cpus.sorted()
        return sorted.last() - sorted.first() + 1 == sorted.size
    }

    private fun makeSelectionContinuous(target: MutableSet<Int>) {
        if (isContinuousSelection(target)) return
        val sorted = target.sorted()
        if (sorted.isEmpty()) return
        val min = sorted.first()
        val max = sorted.last()
        val available = availableCpus.toSet()
        target.clear()
        for (cpu in min..max) {
            if (cpu in available) target.add(cpu)
        }
    }

    private fun showHelp() {
        val view = layoutInflater.inflate(R.layout.section_help, binding.root, false)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.show()
    }

    private fun Double.formatOne(): String {
        val rounded = kotlin.math.round(this * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun setPolicyStatus(text: String) {
        binding.policyStatus.setTextColor(getColor(R.color.text_secondary))
        binding.policyStatus.text = text
    }

    private fun setUpdateStatus(text: String) {
        binding.updateStatus.setTextColor(getColor(R.color.text_secondary))
        binding.updateStatus.text = text
    }

    private fun showCoreWarning(view: TextView, message: String) {
        coreWarningRunnable?.let { mainHandler.removeCallbacks(it) }
        if (coreWarningView !== view) {
            coreWarningView?.visibility = View.GONE
        }
        coreWarningView = view
        view.text = message
        view.visibility = View.VISIBLE
        val hide = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            view.visibility = View.GONE
            coreWarningRunnable = null
            if (coreWarningView === view) coreWarningView = null
        }
        coreWarningRunnable = hide
        mainHandler.postDelayed(hide, 3500L)
    }

    private fun toast(msg: String) {
        AppToast.show(this, msg)
    }

    override fun onDestroy() {
        cancelAutoSave()
        coreWarningRunnable?.let { mainHandler.removeCallbacks(it) }
        coreWarningRunnable = null
        coreWarningView = null
        super.onDestroy()
    }
}
