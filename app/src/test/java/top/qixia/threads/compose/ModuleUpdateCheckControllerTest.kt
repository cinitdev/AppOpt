package top.qixia.threads.compose

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.DaemonBridge
import top.qixia.threads.ModuleUpdater

class ModuleUpdateCheckControllerTest {
    private val ready = EnvironmentUiState(
        loading = false,
        hasRoot = true,
        moduleVersion = DaemonBridge.ModuleVersion("2.0.0", 200, null, ""),
        moduleCompatible = true,
        daemonRuntime = DaemonBridge.DaemonRuntime(true)
    )

    private fun available() = ModuleUpdater.CheckResult.UpdateAvailable(
        ModuleUpdater.UpdateInfo("2.0.0", 200, "2.0.1", 201,
            "https://example.invalid/module.zip", null, "测试更新说明", false)
    )

    private suspend fun awaitResult(controller: ModuleUpdateCheckController): UpdateUiState =
        withTimeout(5_000L) {
            controller.state.first { !it.checkingModule && it.moduleResult != null }
        }

    @Test(timeout = 10_000L)
    fun unavailableStartupConditionsDoNotConsumeTheCheckOpportunity() = runBlocking {
        val calls = AtomicInteger()
        val controller = ModuleUpdateCheckController(this) { calls.incrementAndGet(); available() }
        for (environment in listOf(
            ready.copy(loading = true),
            ready.copy(hasRoot = false),
            ready.copy(moduleVersion = null),
            ready.copy(pendingModuleUpdate = true)
        )) {
            controller.checkOnStartup(environment)
            assertEquals(UpdateUiState(), controller.state.value)
            assertEquals(0, calls.get())
        }

        controller.checkOnStartup(ready)
        assertTrue(awaitResult(controller).startupPromptPending)
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000L)
    fun anOldModuleWithStoppedDaemonCanStillCheckForItsUpgrade() = runBlocking {
        val calls = AtomicInteger()
        val controller = ModuleUpdateCheckController(this) { calls.incrementAndGet(); available() }
        val oldModule = ready.copy(
            moduleVersion = DaemonBridge.ModuleVersion("1.8.6", 186, null, ""),
            moduleCompatible = false,
            daemonRuntime = DaemonBridge.DaemonRuntime(false)
        )
        assertFalse(oldModule.featuresAvailable)

        controller.checkOnStartup(oldModule)

        assertTrue(awaitResult(controller).startupPromptPending)
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000L)
    fun aBlockedCheckerLeavesTheCallingCoroutineResponsive() = runBlocking {
        val callerThread = Thread.currentThread()
        val entered = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        val controller = ModuleUpdateCheckController(this) {
            entered.complete(Thread.currentThread())
            check(release.await(5, TimeUnit.SECONDS)) { "测试未及时释放检查器" }
            available()
        }
        try {
            controller.checkOnStartup(ready)
            assertTrue(controller.state.value.checkingModule)
            val checkerThread = withTimeout(5_000L) { entered.await() }
            assertNotSame(callerThread, checkerThread)
            // 检查器仍在等待时，调用方能够继续处理挂起与恢复。
            yield()
            assertEquals(1L, release.count)
            assertNull(controller.state.value.moduleResult)
            assertTrue(controller.state.value.checkingModule)
        } finally {
            release.countDown()
        }
        assertTrue(awaitResult(controller).startupPromptPending)
    }

    @Test(timeout = 10_000L)
    fun startupAndManualChecksShareOneInFlightRequest() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val controller = ModuleUpdateCheckController(this) {
            calls.incrementAndGet()
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "测试未及时释放检查器" }
            available()
        }
        try {
            controller.checkOnStartup(ready)
            withTimeout(5_000L) { entered.await() }
            repeat(10) {
                controller.checkManually()
                controller.checkOnStartup(ready)
            }
            assertEquals(1, calls.get())
            assertTrue(controller.state.value.checkingModule)
        } finally {
            release.countDown()
        }
        assertTrue(awaitResult(controller).startupPromptPending)
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000L)
    fun anEarlierManualCheckAlsoBlocksAConcurrentStartupCheckWithoutAddingAPrompt() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val controller = ModuleUpdateCheckController(this) {
            calls.incrementAndGet()
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "测试未及时释放检查器" }
            available()
        }
        try {
            controller.checkManually()
            withTimeout(5_000L) { entered.await() }
            repeat(10) { controller.checkOnStartup(ready); controller.checkManually() }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
        assertFalse(awaitResult(controller).startupPromptPending)
        controller.checkOnStartup(ready)
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000L)
    fun dismissingTheStartupPromptPreventsItFromReturningOnLaterRefreshes() = runBlocking {
        val calls = AtomicInteger()
        val update = available()
        val controller = ModuleUpdateCheckController(this) { calls.incrementAndGet(); update }
        controller.checkOnStartup(ready)
        assertTrue(awaitResult(controller).startupPromptPending)

        controller.dismissPrompt()
        repeat(10) {
            // 模拟切后台后重新读取环境，以及首页下拉刷新。
            controller.checkOnStartup(ready.copy(loading = true))
            controller.checkOnStartup(ready)
        }
        yield()

        assertEquals(1, calls.get())
        assertFalse(controller.state.value.checkingModule)
        assertFalse(controller.state.value.startupPromptPending)
        assertEquals(update, controller.state.value.moduleResult)
    }

    @Test(timeout = 10_000L)
    fun noUpdateAndFailuresStaySilentButAllowAnExplicitRetry() = runBlocking {
        val initialChecks = listOf<() -> ModuleUpdater.CheckResult>(
            { ModuleUpdater.CheckResult.NoUpdate("已是最新版本") },
            { ModuleUpdater.CheckResult.NoUpdate("新版本已刷入，重启后生效", pendingReboot = true) },
            { ModuleUpdater.CheckResult.Failed("网络不可用") },
            { throw IOException("测试连接失败") }
        )
        for (initialCheck in initialChecks) {
            val calls = AtomicInteger()
            val controller = ModuleUpdateCheckController(this) {
                if (calls.incrementAndGet() == 1) initialCheck() else available()
            }
            controller.checkOnStartup(ready)
            val first = awaitResult(controller)
            assertFalse(first.startupPromptPending)
            assertFalse(first.moduleResult is ModuleUpdater.CheckResult.UpdateAvailable)
            repeat(3) { controller.checkOnStartup(ready) }
            assertEquals(1, calls.get())

            controller.checkManually()
            val retried = awaitResult(controller)

            assertEquals(2, calls.get())
            assertTrue(retried.moduleResult is ModuleUpdater.CheckResult.UpdateAvailable)
            assertFalse(retried.startupPromptPending)
        }
    }

    @Test(timeout = 10_000L)
    fun aNewControllerChecksAgainOnANewLaunch() = runBlocking {
        val calls = AtomicInteger()
        val checker = { calls.incrementAndGet(); available() }
        val firstLaunch = ModuleUpdateCheckController(this, checker)
        firstLaunch.checkOnStartup(ready)
        assertTrue(awaitResult(firstLaunch).startupPromptPending)
        firstLaunch.dismissPrompt()
        firstLaunch.checkOnStartup(ready)
        assertEquals(1, calls.get())

        val nextLaunch = ModuleUpdateCheckController(this, checker)
        nextLaunch.checkOnStartup(ready)

        assertTrue(awaitResult(nextLaunch).startupPromptPending)
        assertEquals(2, calls.get())
        assertFalse(firstLaunch.state.value.startupPromptPending)
    }
}
