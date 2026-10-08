package top.qixia.threads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.AppManageSheet
import top.qixia.threads.compose.ui.ApplicationsScreen
import top.qixia.threads.compose.ui.RuleEditorScreen

/** 使用内存模型验证系统进程入口，不启动组件或改动真实规则。 */
class SystemComponentUiTest {
    @get:Rule val compose = createComposeRule()
    private val component = AppItemModel("surfaceflinger", "surfaceflinger", false, null,
        ruleCount = 2, cpuSummary = "CPU 4-7", componentKind = AppComponentKind.SYSTEM_COMPONENT)
    private var launches = 0
    private var edits = 0
    private var deletes = 0
    private var automaticChanges = 0

    private fun showApplications(app: AppItemModel, hideMissing: Boolean, tab: ApplicationTab = ApplicationTab.CONFIGURED) {
        val state = mutableStateOf(QixiaThreadsUiState(
            applications = ApplicationsUiState(loading = false, configured = listOf(app),
                selectedTab = tab, hideMissing = hideMissing),
            environment = EnvironmentUiState(loading = false, hasRoot = true, moduleCompatible = true,
                automaticAffinitySupported = true, daemonRuntime = DaemonBridge.DaemonRuntime(true))))
        compose.setContent {
            QixiaThreadsTheme {
                ApplicationsScreen(state.value, PaddingValues(0.dp), {}, {},
                    onSelectTab = { state.value = state.value.copy(applications = state.value.applications.copy(selectedTab = it)) },
                    onSearch = {}, onToggleHideMissing = {}, onAdd = {},
                    onDelete = { deletes++ }, onLaunch = { launches++ }, onEditRules = { edits++ },
                    onAutoStartChange = { _, _ -> }, onAutomaticAffinity = { _, _ -> automaticChanges++ })
            }
        }
    }

    @Test fun hideMissingKeepsSystemComponentAndItsManagementEntry() {
        showApplications(component.copy(averageFps = 60f), hideMissing = true)
        compose.onNodeWithText("系统组件 · 2 条规则 · CPU 4-7").assertIsDisplayed()
        compose.onNodeWithContentDescription("surfaceflinger 图标").assertIsDisplayed()
        compose.onNodeWithText("未安装 ·", substring = true).assertDoesNotExist()
        compose.onNodeWithText("上次 60.0 FPS").assertDoesNotExist()
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("系统组件管理").assertIsDisplayed()
        compose.onNodeWithContentDescription("查看与编辑规则").performClick()
        compose.runOnIdle {
            assertEquals(1, edits)
            assertEquals(0, launches)
            assertFalse(component.installed)
        }
    }

    @Test fun stalePendingComponentOpensManagementInsteadOfLaunchingCalibration() {
        showApplications(component.copy(state = AppRuleState.PENDING), hideMissing = true,
            tab = ApplicationTab.LIBRARY)
        compose.onNodeWithText("管理").performScrollTo().performClick()
        compose.onNodeWithText("系统组件管理").assertIsDisplayed()
        compose.onNodeWithText("启动悬浮校准").assertDoesNotExist()
        compose.onNodeWithText("删除组件配置").performClick()
        compose.onNodeWithText("删除组件配置？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, deletes); assertEquals(0, launches) }
        compose.onNodeWithText("删除配置").performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(0, launches) }
    }

    @Test fun componentManagementOnlyOffersRulesAndRemovalEvenWithStaleAppMetadata() {
        compose.setContent {
            QixiaThreadsTheme {
                AppManageSheet(component.copy(state = AppRuleState.PENDING,
                    automaticAffinityEnabled = true, averageFps = 60f), enabled = true, onDismiss = {},
                    onLaunch = { launches++ }, onEditRules = { edits++ },
                    onAutomaticAffinity = { automaticChanges++ }, automaticSupported = true,
                    onDelete = { deletes++ })
            }
        }
        compose.onNodeWithText("系统组件").assertIsDisplayed()
        compose.onNodeWithText("应用未安装").assertDoesNotExist()
        compose.onNodeWithContentDescription("自动分配线程核心").assertDoesNotExist()
        compose.onNodeWithText("打开应用").assertDoesNotExist()
        compose.onNodeWithText("启动悬浮校准").assertDoesNotExist()
        compose.onNodeWithText("FPS", substring = true).assertDoesNotExist()
        compose.onNodeWithText("上次运行平均").assertDoesNotExist()
        compose.onNodeWithText("规则与检测已暂停", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("查看与编辑规则").performClick()
        compose.onNodeWithText("删除组件配置").performClick()
        compose.runOnIdle {
            assertEquals(1, edits)
            assertEquals(1, deletes)
            assertEquals(0, launches)
            assertEquals(0, automaticChanges)
        }
    }

    @Test fun actualMissingApplicationStillShowsMissingAndCannotLaunch() {
        showApplications(AppItemModel("example.missing", "未安装示例", false, null, ruleCount = 1),
            hideMissing = false)
        compose.onNodeWithText("未安装 · 1 条规则").assertIsDisplayed()
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("应用未安装").assertIsDisplayed()
        compose.onNodeWithText("启动悬浮校准").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, launches) }
    }

    @Test fun automaticSwitchStillControlsAllocationAndHasNoEfficiencyOption() {
        val app = mutableStateOf(AppItemModel("com.game", "测试游戏", true, null, ruleCount = 3))
        compose.setContent {
            QixiaThreadsTheme {
                AppManageSheet(app.value, enabled = true, onDismiss = {}, onLaunch = {}, onEditRules = {},
                    onAutomaticAffinity = { app.value = app.value.withAutomaticAffinity(it) },
                    automaticSupported = true, onDelete = {})
            }
        }
        compose.onNodeWithContentDescription("自动分配能效优先").assertDoesNotExist()
        compose.onNodeWithContentDescription("自动分配线程核心").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("自动分配能效优先").assertDoesNotExist()
        compose.onNodeWithText("平均负载达到 5%", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, app.value.ruleCount) }
        compose.onNodeWithContentDescription("自动分配线程核心").performClick().assertIsOff()
        compose.onNodeWithContentDescription("自动分配能效优先").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, app.value.ruleCount); assertFalse(app.value.automaticAffinityEnabled) }
    }

    @Test fun componentRulesRemainEditableAndReportObservedHealthWithoutMissingAppWarning() {
        val lines = listOf("surfaceflinger{RenderEngine}=7", "surfaceflinger=4-7")
        val health = DaemonBridge.RuleHealth("thread", "surfaceflinger", "RenderEngine",
            DaemonBridge.RuleHealthStatus.VALID, 0, 1, 2, 2, lines.first())
        val state = mutableStateOf(RuleEditorUiState(component.copy(automaticAffinityEnabled = true),
            lines, lines.joinToString("\n"), (0..7).toSet(), health = mapOf(health.key to health)))
        compose.setContent {
            QixiaThreadsTheme {
                RuleEditorScreen(state.value, { state.value = state.value.copy(draft = it) }, {}, {}, {})
            }
        }
        compose.onNodeWithText("系统组件").assertIsDisplayed()
        compose.onNodeWithText("线程 · 已匹配").assertIsDisplayed()
        compose.onNodeWithText("主进程 · 系统进程已识别").assertIsDisplayed()
        compose.onNodeWithText("应用未安装", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已暂停", substring = true).assertDoesNotExist()
        compose.onNodeWithText("承接主进程内未单独匹配的线程，无需线程名检查").assertIsDisplayed()
        compose.onNodeWithText("RenderEngine").performClick()
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement("6")
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle {
            assertEquals("6", RuleSyntax.parse(state.value.draft).rules.first().cpus)
            assertFalse(state.value.app.installed)
        }
    }
}
