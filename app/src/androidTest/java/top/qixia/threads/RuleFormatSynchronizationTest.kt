package top.qixia.threads

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.qixia.threads.compose.QixiaThreadsRepository
import top.qixia.threads.compose.QixiaThreadsViewModel

/** 仅在模拟器上显式启用后修改真实模块配置。
 * 逐字节恢复原文件，不导入历史、草稿或采集记录。 */
@RunWith(AndroidJUnit4::class)
class RuleFormatSynchronizationTest {
    companion object {
        private const val CONFIG = "/data/adb/modules/QixiaThreads/config"
        private const val RULES = "$CONFIG/applist.conf"
        private const val POLICY = "$CONFIG/calib_policy.conf"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backups = linkedMapOf<String, String>()
    private lateinit var repository: QixiaThreadsRepository
    private lateinit var app: Application
    private lateinit var topology: String
    private lateinit var firstCpu: String
    private lateinit var lastCpu: String
    private var originalHistoryEnabled = false
    private var store: ViewModelStore? = null

    @Before fun prepare() {
        assumeTrue("Requires -e rule_format_mutations true",
            InstrumentationRegistry.getArguments().getString("rule_format_mutations") == "true")
        assumeTrue("Never mutate rules on a physical device", Build.HARDWARE in setOf("goldfish", "ranchu") ||
            Build.PRODUCT.startsWith("sdk_gphone") || Build.PRODUCT.startsWith("sdk_phone"))
        app = instrumentation.targetContext.applicationContext as Application
        repository = QixiaThreadsRepository(app)
        val before = repository.loadPolicy()
        assumeTrue("Root and an active, writable module are required", before.editable)
        assumeTrue("Do not run while a module update is pending", !DaemonBridge.hasPendingModuleUpdate())
        val files = DaemonBridge.runRootCommand(
            "[ -f '$RULES' ] && [ ! -L '$RULES' ] && [ -f '$POLICY' ] && [ ! -L '$POLICY' ]")
        assumeTrue("Both original configuration files must exist", files.success)
        val cpus = RuleConfigLogic.readPresentCpuSet().orEmpty().sorted()
        assumeTrue("CPU topology is required", cpus.isNotEmpty())
        firstCpu = cpus.first().toString()
        lastCpu = cpus.last().toString()
        topology = before.policy.detectedTopologyBlock.ifBlank {
            "# QixiaThreads detected CPU topology begin\n" +
                "detected_complete=1\ndetected_cpus=${cpus.joinToString(",")}\n" +
                "detected_group_0=${cpus.joinToString(",")}\n# QixiaThreads detected CPU topology end"
        }
        originalHistoryEnabled = before.autoHistoryEnabled
        val token = UUID.randomUUID().toString()
        for (path in listOf(RULES, POLICY)) {
            val backup = "$path.rule-format-test-$token.bak"
            root("cp -p '$path' '$backup' && cmp -s '$path' '$backup'")
            backups[path] = backup
        }
    }

    @After fun restore() {
        store?.let { value -> instrumentation.runOnMainSync { value.clear() } }
        store = null
        // 即使一份文件恢复失败，也尝试恢复另一份；失败的备份保留用于恢复。
        val failures = backups.mapNotNull { (path, backup) ->
            val result = DaemonBridge.runRootCommand(
                "cp -p '$backup' '$path' && cmp -s '$backup' '$path' && rm -f '$backup'")
            if (result.success) null else "$path (backup retained at $backup): ${result.output}"
        }
        backups.clear()
        assertTrue("Configuration restoration failed: ${failures.joinToString()}", failures.isEmpty())
    }

    @Test fun savingTheAlreadySelectedLegacyFormatConvertsAnExternallyCopiedFunctionFile() {
        installPolicy(CalibPolicy.RuleOutputFormat.LEGACY)
        val current = repository.loadPolicy().policy
        // 外部编辑器也可能生成 CRLF 换行且末尾无换行符的文件。
        val copied = (functionRules() + "\ncom.ruleformat.legacy{Thread-[0-9]*}=$firstCpu")
            .replace("\n", "\r\n")
        externalCopy(RULES, copied)
        assertEquals(CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK, RuleFormatConverter.detectFormat(readRules())!!.format)

        repository.savePolicy(current, current)

        val output = readRules()
        assertEquals(CalibPolicy.RuleOutputFormat.LEGACY, RuleFormatConverter.detectFormat(output)!!.format)
        assertRuleSemantics(copied, output)
        assertPreservedPolicy(CalibPolicy.RuleOutputFormat.LEGACY)
        assertIdempotent(output)
    }

    @Test fun synchronizationUsesPolicyInBothDirectionsAndPreservesWildcardPriorityAndSubprocesses() {
        val mixed = functionRules() + "\ncom.ruleformat.other{Thread-?}=$firstCpu\n"
        val legacy = RuleFormatConverter.convert(mixed, CalibPolicy.RuleOutputFormat.LEGACY).conversion!!.content
        for (target in listOf(CalibPolicy.RuleOutputFormat.LEGACY, CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK)) {
            installPolicy(target)
            val copied = if (target == CalibPolicy.RuleOutputFormat.LEGACY) mixed else legacy
            externalCopy(RULES, copied)
            val result = DaemonBridge.synchronizeRuleOutputFormat()
            assertTrue("$target synchronization failed: $result", result.success)
            assertTrue("A different external syntax must be converted", result.changed)
            assertEquals(target, result.format)
            val output = readRules()
            assertEquals(target, RuleFormatConverter.detectFormat(output)!!.format)
            assertRuleSemantics(copied, output)
            assertPreservedPolicy(target)
            assertIdempotent(output)

            // 旧入口也不得让策略跟随外部复制的语法改变。
            externalCopy(RULES, copied)
            val oldEntry = DaemonBridge.detectAndApplyRuleOutputFormat()
            assertTrue("Compatibility entry failed: $oldEntry", oldEntry.success)
            assertEquals(target, oldEntry.format)
            assertRuleSemantics(copied, readRules())
            assertPreservedPolicy(target)
        }
    }

    @Test fun restoringDefaultsWhileAlreadyDefaultStillConvertsExternalRules() {
        installPolicy(CalibPolicy.RuleOutputFormat.LEGACY)
        externalCopy(RULES, RuleFormatConverter.convert(functionRules(), CalibPolicy.RuleOutputFormat.LEGACY).conversion!!.content)
        lateinit var model: QixiaThreadsViewModel
        instrumentation.runOnMainSync {
            // 仅跳过启动流程，避免它导入真实历史。
            model = QixiaThreadsViewModel(app, autoRefreshOnInit = false)
            store = ViewModelStore().also { it.put("rule-format", model) }
            model.loadSettings(force = true)
        }
        awaitState(model) { !model.state.value.settings.loading }
        assertTrue(model.state.value.settings.editable)
        val saved = model.state.value.settings.savedPolicy
        assertEquals(CalibPolicy.parse(saved.detectedTopologyBlock), saved)
        val copied = functionRules()
        externalCopy(RULES, copied) // 在设置加载后复制，此时已保存策略仍等于默认值。

        instrumentation.runOnMainSync {
            model.restoreDefaultPolicy()
            assertTrue("Equal settings must still synchronize the external rule file", model.state.value.settings.saving)
        }
        awaitState(model) { !model.state.value.settings.saving }
        assertEquals(saved, model.state.value.settings.savedPolicy)
        assertEquals(CalibPolicy.RuleOutputFormat.LEGACY, RuleFormatConverter.detectFormat(readRules())!!.format)
        assertRuleSemantics(copied, readRules())
        assertPreservedPolicy(CalibPolicy.RuleOutputFormat.LEGACY)
    }

    private fun installPolicy(format: CalibPolicy.RuleOutputFormat) {
        externalCopy(POLICY, CalibPolicy.DEFAULT.copy(ruleOutputFormat = format, detectedTopologyBlock = topology).toConfigText() +
            "auto_history_version=1\nauto_history_enabled=${if (originalHistoryEnabled) 1 else 0}\n" +
            "keep_all_cores_online=0\nfuture_rule_format_test=retain\n")
    }

    private fun functionRules() = """
        # Preserve thread order and duplicate rules within each owner.
        app(com.ruleformat.regression, $firstCpu) {
            thread(RenderThread, $lastCpu)
            thread(Binder:*, $lastCpu)
            thread(Thread-[0-9]*, $firstCpu)
            thread(Thread-?, $lastCpu)
            thread(Binder:*, $lastCpu)
            process(worker, $firstCpu) {
                thread(thread-shared-*, $lastCpu)
                thread(Binder:*, $firstCpu)
            }
        }
        com.ruleformat.regression=$lastCpu
    """.trimIndent() + "\n"

    private fun assertRuleSemantics(before: String, after: String) {
        val original = RuleSyntax.parse(before)
        val converted = RuleSyntax.parse(after)
        assertTrue(original.rules.isNotEmpty())
        assertTrue("Source fixture contains unsupported syntax", original.segments.all { it.valid })
        assertTrue("Conversion generated unsupported syntax", converted.segments.all { it.valid })
        // 进程兜底规则的位置可以变化，通配顺序和重复项必须保留。
        assertEquals(original.rules.groupBy { it.owner to (it.thread == null) },
            converted.rules.groupBy { it.owner to (it.thread == null) })
    }

    private fun assertPreservedPolicy(format: CalibPolicy.RuleOutputFormat) {
        val raw = DaemonBridge.readCalibPolicyRaw()
        assertTrue(raw.readSuccess)
        val policy = CalibPolicy.parse(raw.content)
        assertEquals(format, policy.ruleOutputFormat)
        assertEquals(topology, policy.detectedTopologyBlock)
        assertEquals(DaemonBridge.AutomaticHistoryState(true, originalHistoryEnabled), DaemonBridge.parseAutomaticHistory(raw.content))
        assertTrue(raw.content.lineSequence().any { it == "keep_all_cores_online=0" })
        assertTrue(raw.content.lineSequence().any { it == "future_rule_format_test=retain" })
    }

    private fun assertIdempotent(expectedRules: String) {
        val policy = DaemonBridge.readCalibPolicyRaw().content
        val result = DaemonBridge.synchronizeRuleOutputFormat()
        assertTrue("Repeat synchronization failed: $result", result.success)
        assertFalse("Canonical content must not be rewritten on every refresh", result.changed)
        assertEquals(expectedRules, readRules())
        assertEquals(policy, DaemonBridge.readCalibPolicyRaw().content)
    }

    private fun readRules() = requireNotNull(DaemonBridge.readConfigRawOrNull())

    private fun externalCopy(path: String, content: String) {
        require(path == RULES || path == POLICY)
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // 故意绕过转换，复现外部复制配置文件的情形。
        root("printf '%s' '$encoded' | base64 -d > '$path'")
    }

    private fun root(command: String) {
        val result = DaemonBridge.runRootCommand(command)
        assertTrue("Root command failed: ${result.output}", result.success)
    }

    private fun awaitState(model: QixiaThreadsViewModel, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out: ${model.state.value.settings}; ${model.state.value.message}", predicate())
    }
}
