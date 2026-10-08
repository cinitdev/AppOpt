package top.qixia.threads

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import top.qixia.threads.compose.MainDestination
import top.qixia.threads.compose.QixiaThreadsRepository
import top.qixia.threads.compose.QixiaThreadsViewModel

/** 需显式启用的模拟器回归测试，保留规则、记录开关和原策略。 */
class ImmediateSettingsTest {
    @Test fun selectionAndDefaultsPersistWithoutSaveAndNavigationCannotDiscardThem() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("settings_mutations") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repository = QixiaThreadsRepository(app)
        val before = repository.loadPolicy()
        assumeTrue(before.editable && before.policy.cpusetName == CalibPolicy.DEFAULT_CPUSET_NAME)
        val rules = RuleSyntax.parse(requireNotNull(DaemonBridge.readConfigRawOrNull())).rules
        val history = DaemonBridge.readAutomaticHistory()
        val store = ViewModelStore()
        lateinit var model: QixiaThreadsViewModel
        fun awaitState(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue("Timed out: ${model.state.value.settings}", condition())
        }
        try {
            instrumentation.runOnMainSync {
                model = QixiaThreadsViewModel(app)
                store.put("settings", model)
            }
            awaitState { !model.state.value.environment.loading }
            instrumentation.runOnMainSync { model.loadSettings(force = true) }
            awaitState { !model.state.value.settings.loading }
            val selected = if (before.policy.ruleOutputFormat == CalibPolicy.RuleOutputFormat.YAML)
                CalibPolicy.RuleOutputFormat.NESTED_BLOCK else CalibPolicy.RuleOutputFormat.YAML
            instrumentation.runOnMainSync {
                model.updatePolicy { it.copy(ruleOutputFormat = selected) }
                assertTrue(model.state.value.settings.saving)
                model.onDestinationShown(MainDestination.HOME)
                model.loadSettings(force = true) // 不得覆盖正在保存的选项。
            }
            awaitState { !model.state.value.settings.saving }
            assertEquals(selected, repository.loadPolicy().policy.ruleOutputFormat)
            assertEquals(selected, model.state.value.settings.savedPolicy.ruleOutputFormat)
            instrumentation.runOnMainSync { model.restoreDefaultPolicy() }
            awaitState { !model.state.value.settings.saving }
            assertEquals(CalibPolicy.RuleOutputFormat.LEGACY, repository.loadPolicy().policy.ruleOutputFormat)
            val committed = model.state.value.settings.policy
            instrumentation.runOnMainSync { model.updatePolicy { it.copy(cpusetName = "../invalid") } }
            assertEquals(committed, model.state.value.settings.policy)
            assertFalse(model.state.value.settings.saving)
            assertEquals(history, DaemonBridge.readAutomaticHistory())
            // 格式转换可能将进程兜底规则放在线程规则之后。
            // 需保留各所属进程内的线程顺序（通配优先级）、重复规则和核心掩码。
            assertEquals(rules.groupBy { it.owner to (it.thread == null) },
                RuleSyntax.parse(requireNotNull(DaemonBridge.readConfigRawOrNull())).rules
                    .groupBy { it.owner to (it.thread == null) })
            assertEquals(before.policy.detectedTopologyBlock, repository.loadPolicy().policy.detectedTopologyBlock)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            repository.savePolicy(before.policy, repository.loadPolicy().policy)
        }
    }
}
