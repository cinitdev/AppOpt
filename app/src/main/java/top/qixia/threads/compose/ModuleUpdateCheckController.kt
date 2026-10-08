package top.qixia.threads.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.qixia.threads.ModuleUpdater

/** 启动检查与手动检查共用一个任务；只读取版本，不下载或刷入。 */
internal class ModuleUpdateCheckController(
    private val scope: CoroutineScope,
    private val checker: () -> ModuleUpdater.CheckResult
) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private var checkedThisSession = false

    fun checkOnStartup(environment: EnvironmentUiState) {
        // 首次授权尚未完成时保留检查机会，旧模块和停止的守护也可以检查更新。
        if (checkedThisSession || environment.loading || !environment.hasRoot ||
            environment.moduleVersion == null || environment.pendingModuleUpdate) return
        check(promptWhenAvailable = true)
    }

    fun checkManually() = check(promptWhenAvailable = false)

    fun dismissPrompt() {
        _state.update { it.copy(startupPromptPending = false) }
    }

    private fun check(promptWhenAvailable: Boolean) {
        if (_state.value.checkingModule) return
        checkedThisSession = true
        _state.update { it.copy(checkingModule = true, startupPromptPending = false) }
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { checker() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ModuleUpdater.CheckResult.Failed("版本信息读取失败，请重试")
            }
            // 无更新或联网失败不打断首页，仍可在设置中查看状态并手动重试。
            _state.value = UpdateUiState(
                moduleResult = result,
                startupPromptPending = promptWhenAvailable && result is ModuleUpdater.CheckResult.UpdateAvailable
            )
        }
    }
}
