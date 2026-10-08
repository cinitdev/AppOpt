package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.DaemonBridge
import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdater

class ModuleUpdateCardStateTest {
    private val environment = EnvironmentUiState(loading = false,
        moduleVersion = DaemonBridge.ModuleVersion("1.8.6", 186, null, ""))
    private val idle = ModuleUpdateDownloadViewModel.State()
    private fun update() = ModuleUpdater.UpdateInfo("1.8.6", 186, "v1.8.7", 187,
        "https://example.invalid/module.zip", null, "测试更新", false)

    @Test fun displaysVerifiedLocalAndRemoteVersions() {
        val result = ModuleUpdater.CheckResult.NoUpdate("已是最新版本", "v1.8.6", 186, "1.8.6", 186)
        val card = moduleUpdateCardState(environment, UpdateUiState(moduleResult = result), idle)
        assertEquals("v1.8.6 (186)", card.localVersion)
        assertEquals("v1.8.6 (186)", card.remoteVersion)
        assertEquals("1.8.6", card.remoteVersionName)
        assertEquals(186, card.remoteVersionCode)
        assertEquals(ModuleUpdateTone.SUCCESS, card.tone)
    }

    @Test fun networkFailureNeverCopiesLocalVersionIntoRemoteVersion() {
        val card = moduleUpdateCardState(environment,
            UpdateUiState(moduleResult = ModuleUpdater.CheckResult.Failed("网络不可用")), idle)
        assertEquals("v1.8.6 (186)", card.localVersion)
        assertEquals("未获取", card.remoteVersion)
        assertNull(card.remoteVersionName)
        assertNull(card.remoteVersionCode)
        assertEquals("网络不可用", card.status)
        assertEquals(ModuleUpdateTone.ERROR, card.tone)
    }

    @Test fun unsupportedUpdatesDoNotClaimTheModuleIsCurrent() {
        val card = moduleUpdateCardState(environment,
            UpdateUiState(moduleResult = ModuleUpdater.CheckResult.NoUpdate("当前模块不支持在线更新")), idle)
        assertEquals("未获取", card.remoteVersion)
        assertEquals(ModuleUpdateTone.INFO, card.tone)
    }

    @Test fun pendingInstallTakesPriorityOverCheckingAndAvailableUpdate() {
        val card = moduleUpdateCardState(environment.copy(pendingModuleUpdate = true),
            UpdateUiState(checkingModule = true, moduleResult = ModuleUpdater.CheckResult.UpdateAvailable(update())), idle)
        assertEquals("模块已刷入，重启设备后生效", card.status)
        assertEquals(ModuleUpdateTone.WARNING, card.tone)
    }

    @Test fun pendingInstallFromCheckResultIsAlsoAWarning() {
        val result = ModuleUpdater.CheckResult.NoUpdate("新版本已刷入，重启后生效", "1.8.6", 186, "1.8.7", 187)
        val card = moduleUpdateCardState(environment, UpdateUiState(moduleResult = result), idle)
        assertEquals(ModuleUpdateTone.WARNING, card.tone)
        assertEquals(result.message, card.status)
    }

    @Test fun resumedDownloadShowsItsOwnVersionAndProgressState() {
        val download = ModuleUpdateDownloadViewModel.State(update = update(),
            stage = ModuleUpdateDownloadViewModel.Stage.DOWNLOADING, status = "下载中 42%", percent = 42)
        val card = moduleUpdateCardState(environment,
            UpdateUiState(moduleResult = ModuleUpdater.CheckResult.Failed("网络不可用")), download)
        assertEquals("v1.8.7 (187)", card.remoteVersion)
        assertEquals("1.8.7", card.remoteVersionName)
        assertEquals(187, card.remoteVersionCode)
        assertEquals("下载中 42%", card.status)
    }

    @Test fun reinstallingTheSameVersionStillRequiresReboot() {
        val result = ModuleUpdater.CheckResult.NoUpdate("新版本已刷入，重启后生效",
            "1.8.6", 186, "1.8.6", 186, pendingReboot = true)
        val card = moduleUpdateCardState(environment, UpdateUiState(moduleResult = result), idle)
        assertEquals(ModuleUpdateTone.WARNING, card.tone)
        assertEquals(result.message, card.status)
    }

    @Test fun manualRefreshKeepsKnownVersionsWhileShowingCheckingState() {
        val card = moduleUpdateCardState(environment,
            UpdateUiState(checkingModule = true, moduleResult = ModuleUpdater.CheckResult.UpdateAvailable(update())), idle)
        assertEquals("v1.8.7 (187)", card.remoteVersion)
        assertEquals("正在读取云端版本信息…", card.status)
    }
}
