package top.qixia.threads

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.ModuleUpdateDownloadViewModel.Stage
import top.qixia.threads.compose.UpdateUiState
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.ModuleUpdatePromptHost

/** 只验证界面和内存回调，不下载、刷入或修改设备配置。 */
class ModuleUpdatePromptTest {
    @get:Rule val compose = createComposeRule()
    private val update = ModuleUpdater.UpdateInfo("v2.0.0", 200, "v2.0.1", 201,
        "https://example.invalid/module.zip", null, "## 本次更新\n\n**修复** 更新提醒。", false)
    private val updates = mutableStateOf(UpdateUiState(
        moduleResult = ModuleUpdater.CheckResult.UpdateAvailable(update), startupPromptPending = true))
    private val download = mutableStateOf(ModuleUpdateDownloadViewModel.State())
    private val allowed = mutableStateOf(true)
    private val open = mutableStateOf(false)
    private val owner = TestLifecycleOwner()
    private var consumed = 0
    private var downloads = 0
    private var retries = 0
    private var cancellations = 0
    private var installs = 0

    private fun show(lifecycleState: Lifecycle.State = Lifecycle.State.RESUMED) {
        compose.runOnUiThread { owner.registry.currentState = lifecycleState }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                QixiaThreadsTheme {
                    TextButton(onClick = { open.value = true }) { Text("设置查看更新") }
                    ModuleUpdatePromptHost(updates.value, download.value, open.value,
                        onOpenChange = { open.value = it }, startupAllowed = allowed.value,
                        onConsumeStartupPrompt = {
                            if (updates.value.startupPromptPending) consumed++
                            updates.value = updates.value.copy(startupPromptPending = false)
                        },
                        onBegin = { assertEquals(update, it); downloads++ },
                        onRetry = { retries++ }, onCancel = { cancellations++ }, onInstall = { installs++ })
                }
            }
        }
    }

    private fun assertNoAutomaticAction() {
        compose.runOnIdle {
            assertEquals(0, downloads)
            assertEquals(0, retries)
            assertEquals(0, cancellations)
            assertEquals(0, installs)
        }
    }

    @Test fun availableUpdateOpensOneSheetAndWaitsForExplicitDownload() {
        show()
        compose.onAllNodesWithContentDescription("关闭更新详情").assertCountEquals(1)
        compose.onNodeWithText("更新 QixiaThreads").assertIsDisplayed()
        compose.onNodeWithText("下载模块").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, consumed)
            assertFalse(updates.value.startupPromptPending)
        }
        assertNoAutomaticAction()
        compose.onNodeWithText("下载模块").performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(0, installs) }
    }

    @Test fun startupWaitsForGlobalBlockersAndResumedLifecycle() {
        allowed.value = false
        show(Lifecycle.State.STARTED)
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle { assertTrue(updates.value.startupPromptPending); allowed.value = true }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, consumed); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithContentDescription("关闭更新详情").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, consumed) }
        assertNoAutomaticAction()
    }

    @Test fun dismissingDoesNotPromptAgainAndSettingsCanReopenTheSameSheet() {
        show()
        compose.onNodeWithContentDescription("关闭更新详情").performClick()
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle {
            allowed.value = false
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle {
            allowed.value = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.onNodeWithText("设置查看更新").performClick()
        compose.onAllNodesWithContentDescription("关闭更新详情").assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, consumed) }
        assertNoAutomaticAction()
    }

    @Test fun existingDownloadConsumesStartupPromptWithoutOpeningOrInstalling() {
        allowed.value = false
        download.value = ModuleUpdateDownloadViewModel.State(update, Stage.DOWNLOADING, percent = 40)
        show()
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, consumed)
            assertFalse(updates.value.startupPromptPending)
            allowed.value = true
            download.value = download.value.copy(stage = Stage.READY_TO_INSTALL)
        }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.onNodeWithText("设置查看更新").performClick()
        compose.onNodeWithText("开始刷入").assertIsDisplayed()
        assertNoAutomaticAction()
        compose.onNodeWithText("开始刷入").performClick()
        compose.runOnIdle { assertEquals(1, installs); assertEquals(0, downloads) }
    }

    @Test fun manualOpeningWhileStartupIsBlockedConsumesPromptOnClose() {
        allowed.value = false
        show()
        compose.onNodeWithText("设置查看更新").performClick()
        compose.onAllNodesWithContentDescription("关闭更新详情").assertCountEquals(1)
        compose.onNodeWithContentDescription("关闭更新详情").performClick()
        compose.runOnIdle { allowed.value = true }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, consumed) }
        assertNoAutomaticAction()
    }

    @Test fun handingOffClosesTheSharedSheetAndDoesNotReopenOnReturn() {
        download.value = ModuleUpdateDownloadViewModel.State(update, Stage.READY_TO_INSTALL)
        show()
        compose.onNodeWithText("设置查看更新").performClick()
        compose.onNodeWithContentDescription("关闭更新详情").assertIsDisplayed()
        compose.runOnIdle { download.value = download.value.copy(stage = Stage.HANDED_OFF) }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(open.value)
            download.value = ModuleUpdateDownloadViewModel.State()
        }
        compose.onNodeWithContentDescription("关闭更新详情").assertDoesNotExist()
        assertNoAutomaticAction()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
