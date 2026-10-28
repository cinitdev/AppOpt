package top.qixia.threads.compose

import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import qixia.refresh.RefreshPolicy
import top.qixia.threads.DaemonBridge
import java.io.StringReader
import java.util.Properties

data class RefreshApp(val packageName: String, val name: String)
data class RefreshRateUiState(
    val loading: Boolean = true, val saving: Boolean = false, val available: Boolean = false,
    val policy: RefreshPolicy = RefreshPolicy.parse(""), val rates: List<Float> = emptyList(),
    val runtime: Map<String, String> = emptyMap(), val apps: List<RefreshApp> = emptyList(),
    val message: String? = null
)

/** 进入页面才读取，修改单项原子保存；不增加首页启动工作量。 */
class RefreshRateSettingsController(private val app: Application, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RefreshRateUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun load() {
        if (mutable.value.saving || job?.isActive == true) return
        job = scope.launch {
            mutable.update { it.copy(loading = true, message = null) }
            mutable.value = withContext(Dispatchers.IO) { read() }
        }
    }
    private fun readRates(): List<Float> = runCatching {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        RefreshPolicy.displayRates(display?.supportedModes.orEmpty()
            .filter { it.physicalWidth == mode?.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }.toFloatArray()).toList()
    }.getOrDefault(emptyList())
    private fun withRuntimeRates(rates: List<Float>, runtime: Map<String,String>): List<Float> {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return rates
        val mode = display.mode
        val age = runtime["updated_elapsed_ms"]?.toLongOrNull()?.let { android.os.SystemClock.elapsedRealtime() - it }
        if (runtime["enabled"] != "1" || age == null || age !in 0..60_000 ||
            runtime["resolution"] != "${mode.physicalWidth}x${mode.physicalHeight}") return rates
        val combined = rates.toMutableList()
        runtime["rates"].orEmpty().removePrefix("[").removeSuffix("]").split(',').forEach { raw ->
            raw.trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { hz ->
                if (combined.none { kotlin.math.abs(it-hz) < .1f }) combined.add(hz)
            }
        }
        return RefreshPolicy.displayRates(combined.toFloatArray()).toList()
    }
    private fun read(): RefreshRateUiState {
        val rates = readRates()
        val result = DaemonBridge.runRootCommand("""
            if [ ! -d '$DIR' ]; then exit 1; fi
            cat '$DIR/refresh_rate.conf' 2>/dev/null
            echo '__QIXIA_REFRESH_STATE__'
            cat '$DIR/state/refresh_rate.state' 2>/dev/null || true
        """.trimIndent(), 5)
        val parts = result.output.split("__QIXIA_REFRESH_STATE__", limit = 2)
        val runtime = properties(parts.getOrNull(1).orEmpty())
        val apps = runCatching {
            launchableAppCatalog(app).map { RefreshApp(it.packageName, it.label) }
        }.getOrDefault(emptyList())
        return RefreshRateUiState(false, available = result.success && runtime["version"] == "1",
            policy = RefreshPolicy.parse(parts.firstOrNull().orEmpty()), rates = withRuntimeRates(rates,runtime), runtime = runtime, apps = apps,
            message = if (!result.success) "无法读取刷新率配置，请检查 Root 授权。" else if (runtime["version"] != "1") "刷新率服务尚未就绪，请确认新版模块已启动。" else null)
    }
    fun change(key: String, value: String?) {
        if (mutable.value.saving || !mutable.value.available) return
        val next = runCatching { mutable.value.policy.with(key,value) }.getOrElse {
            mutable.update { s -> s.copy(message = it.message) }; return
        }
        mutable.update { it.copy(saving = true, message = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { DaemonBridge.runRootCommand(mutation(key,value),5) }
            if (!result.success) {
                mutable.update { it.copy(saving = false, message = "保存失败，设置未改变：${result.output.take(180)}") }; return@launch
            }
            mutable.update { it.copy(saving = false, policy = next) }
            delay(400); refreshRuntime()
        }
    }
    suspend fun refreshRuntime() {
        val (rates, result) = withContext(Dispatchers.IO) {
            readRates() to DaemonBridge.runRootCommand("cat '$DIR/state/refresh_rate.state'",3)
        }
        mutable.update {
            val runtime = if (result.success) properties(result.output) else it.runtime
            it.copy(rates = withRuntimeRates(rates,runtime), runtime = runtime)
        }
    }
    companion object {
        private const val DIR = "/data/adb/modules/QixiaThreads/config"
        private fun properties(raw: String): Map<String,String> {
            val p = Properties().apply { runCatching { load(StringReader(raw)) } }
            return p.stringPropertyNames().associateWith(p::getProperty)
        }
        internal fun mutation(key: String, value: String?): String {
            require(RefreshPolicy.valid(key, value ?: if (key.startsWith("app.")) "-2,-2" else RefreshPolicy.DEFAULTS[key] ?: "0"))
            val append = if (value == null) ":" else "printf '%s\\n' '$key=$value' >> \"\${tmp}\" || exit 6"
            // key/value 已由共享格式限定为无引号、换行或 Shell 符号的标量。
            return """
                file='$DIR/refresh_rate.conf'
                lock='$DIR/refresh_rate.conf.lock'
                [ -d '$DIR' ] || exit 1
                [ ! -f '/data/adb/modules/QixiaThreads/update' ] || exit 2
                mkdir "${'$'}lock" 2>/dev/null || exit 3
                tmp="${'$'}file.tmp.${'$'}${'$'}"
                trap 'rm -f "${'$'}tmp"; rmdir "${'$'}lock"' EXIT
                [ -f "${'$'}file" ] || touch "${'$'}file" || exit 4
                awk -v key='$key' 'index(${'$'}0,key "=") != 1 { print }' "${'$'}file" > "${'$'}tmp" || exit 5
                $append
                chmod 600 "${'$'}tmp" && mv -f "${'$'}tmp" "${'$'}file"
            """.trimIndent()
        }
    }
}
