package top.qixia.threads

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import top.qixia.threads.compose.QixiaThreadsRepository
import java.util.concurrent.TimeUnit
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.QixiaThreadsViewModel
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertEquals

/** 需显式启用的设备计时测试，只读取运行状态，不改动应用、规则或历史。 */
class StartupLatencyTest {
    @Suppress("DEPRECATION")
    @Test fun bulkPackageMetadataMatchesDirectLookup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val own = manager.getInstalledPackages(0).single { it.packageName == context.packageName }
        val direct = manager.getPackageInfo(context.packageName, 0)
        assertEquals("Bulk installation timestamp", direct.firstInstallTime, own.firstInstallTime)
        assertEquals("Bulk update timestamp", direct.lastUpdateTime, own.lastUpdateTime)
    }

    private fun <T> measure(name: String, action: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        val result = action()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "PERF $name=${SystemClock.elapsedRealtime() - started} ms\n")
        })
        return result
    }

    @Test fun readOnlyDeviceTimings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("benchmark") == "true")
        repeat(3) { index ->
            measure("raw_su_$index") {
                val process = ProcessBuilder("su", "-c", "printf ready").start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    assertTrue(process.inputStream.bufferedReader().readText() == "ready")
                } finally { process.destroy() }
            }
            measure("wrapped_su_$index") {
                val result = DaemonBridge.runRootCommand("printf ready")
                assertTrue(result.toString(), result.success && result.output == "ready")
            }
        }
        measure("root") { assertTrue(DaemonBridge.hasRoot()) }
        measure("daemon") { assertTrue(DaemonBridge.readDaemonRuntime().running) }
        measure("automatic") { assertTrue(DaemonBridge.readAutomaticAffinity().supported) }
        measure("helper") { assertTrue(DaemonBridge.ensureTaskForegroundHelper()) }
        val raw = measure("read_config") { DaemonBridge.readConfigRawOrNull().orEmpty() }
        val config = measure("parse_config") { ConfigReader.parsePackages(raw, RuleConfigLogic.readPresentCpuSet()) }
        val health = measure("read_health") { DaemonBridge.readRuleHealthOrNull().orEmpty() }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val repository = QixiaThreadsRepository(app)
        measure("configured_models") {
            repository.javaClass.getDeclaredMethod("buildConfiguredApps", ConfigReader.ConfigPackages::class.java,
                Map::class.java, Map::class.java).apply { isAccessible = true }
                .invoke(repository, config, health, emptyMap<String, Set<Int>>())
        }
        measure("dashboard") {
            repository.loadDashboard { name, ms ->
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "PERF stage_$name=$ms ms\n")
                })
            }
        }
        measure("catalogue") {
            repository.loadInstalledApps { name, ms ->
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "PERF stage_$name=$ms ms\n")
                })
            }
        }
    }

    /** 需显式启用：只添加或移除测试包，不删除会话或用户规则。 */
    @Test fun committedUiActionsAndRefreshRace() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mutations") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val pkg = instrumentation.context.packageName
        val original = requireNotNull(DaemonBridge.readConfigRawOrNull())
        val beforeRules = RuleSyntax.parse(original).rules
        val beforeAuto = DaemonBridge.readAutomaticAffinity().packages
        assumeTrue(beforeRules.none { it.owner == pkg || it.owner.startsWith("$pkg:") } && pkg !in beforeAuto)
        val store = ViewModelStore()
        lateinit var model: QixiaThreadsViewModel
        fun awaitState(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue("Timed out: ${model.state.value.environment}", condition())
        }
        fun current() = model.state.value.applications.configured.single { it.packageName == pkg }
        try {
            measure("ui_startup") {
                instrumentation.runOnMainSync {
                    model = QixiaThreadsViewModel(app)
                    store.put("timing", model)
                }
                awaitState { !model.state.value.environment.loading }
                assertTrue(model.state.value.environment.featuresAvailable)
            }
            measure("ui_add_commit") {
                instrumentation.runOnMainSync { model.addApplication(AppItemModel(pkg, "测试应用", true, null)) }
                awaitState { model.state.value.applications.configured.any { it.packageName == pkg } }
            }
            measure("ui_enable_commit") {
                instrumentation.runOnMainSync {
                    model.refreshDashboard()
                    model.setAutomaticAffinity(current(), true)
                }
                awaitState { current().automaticAffinityEnabled }
            }
            assertTrue(pkg in DaemonBridge.readAutomaticAffinity().packages)
            awaitState { !model.state.value.refreshing }
            assertTrue("A stale refresh undid the successful toggle", current().automaticAffinityEnabled)
            measure("ui_disable_commit") {
                instrumentation.runOnMainSync { model.setAutomaticAffinity(current(), false) }
                awaitState { !current().automaticAffinityEnabled }
            }
            assertTrue(pkg !in DaemonBridge.readAutomaticAffinity().packages)
            measure("ui_delete_commit") {
                instrumentation.runOnMainSync { model.deleteApplication(current()) }
                awaitState { model.state.value.applications.configured.none { it.packageName == pkg } }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            assertTrue(DaemonBridge.setAutomaticAffinity(pkg, false))
            assertTrue(DaemonBridge.deleteConfigPackages(listOf(pkg)))
        }
        assertEquals(beforeRules, RuleSyntax.parse(requireNotNull(DaemonBridge.readConfigRawOrNull())).rules)
        assertEquals(beforeAuto, DaemonBridge.readAutomaticAffinity().packages)
    }
}
