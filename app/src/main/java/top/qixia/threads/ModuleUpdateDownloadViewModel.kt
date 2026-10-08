package top.qixia.threads

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import kotlin.concurrent.thread

/**
 * 承载模块下载与交接状态。Activity 因旋转重建时 ViewModel 会继续持有下载任务，
 * 新的更新 BottomSheet 只需重新观察状态，不会取消任务或删除已下载产物。
 */
class ModuleUpdateDownloadViewModel(application: Application) : AndroidViewModel(application) {

    enum class Stage {
        IDLE,
        RESUME_READY,
        DOWNLOADING,
        DETECTING_MANAGER,
        READY_TO_INSTALL,
        RETAINING_MANUAL,
        MANUAL_READY,
        FAILED,
        HANDED_OFF
    }

    data class State(
        val update: ModuleUpdater.UpdateInfo? = null,
        val stage: Stage = Stage.IDLE,
        val status: String = "",
        val percent: Int? = null,
        val zipPath: String? = null,
        val managerLabel: String? = null,
        val manualPath: String? = null,
        val installAuthorized: Boolean = false
    ) {
        val hasSession: Boolean
            get() = update != null && stage != Stage.IDLE && stage != Stage.HANDED_OFF
    }

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadHandle: ModuleUpdater.DownloadHandle? = null
    private var operationId = 0L
    private var dialogAttached = false

    init {
        restorePersistedSession(application)
    }

    fun activeUpdate(): ModuleUpdater.UpdateInfo? =
        mutableState.value?.takeIf(State::hasSession)?.update

    fun attachDialog(): Boolean {
        if (dialogAttached) return false
        dialogAttached = true
        return true
    }

    fun detachDialog() {
        dialogAttached = false
    }

    fun resumeAuthorizedSession(context: Context) {
        val current = mutableState.value ?: return
        val update = current.update ?: return
        if (current.stage == Stage.RESUME_READY && current.installAuthorized) {
            beginDownload(context.applicationContext, update, installAuthorized = true)
        }
    }

    fun authorizeInstall(context: Context) {
        val current = mutableState.value ?: return
        val update = current.update ?: return
        ModuleUpdater.authorizeDownloadAndInstall(context.applicationContext, update)
        mutableState.value = current.copy(installAuthorized = true)
    }

    fun start(context: Context, update: ModuleUpdater.UpdateInfo) {
        val existing = mutableState.value ?: State()
        if (sameUpdate(existing.update, update)) {
            when (existing.stage) {
                Stage.RESUME_READY -> {
                    ModuleUpdater.authorizeDownloadAndInstall(context, update)
                    beginDownload(context.applicationContext, update, installAuthorized = true)
                    return
                }
                Stage.DOWNLOADING,
                Stage.DETECTING_MANAGER,
                Stage.READY_TO_INSTALL,
                Stage.RETAINING_MANUAL -> return
                else -> Unit
            }
        }
        cancelSession(discardArtifact = true)
        val appContext = context.applicationContext
        ModuleUpdater.authorizeDownloadAndInstall(appContext, update)
        beginDownload(appContext, update, installAuthorized = true)
    }

    private fun beginDownload(
        appContext: Context,
        update: ModuleUpdater.UpdateInfo,
        installAuthorized: Boolean
    ) {
        if (downloadHandle != null) return
        val id = ++operationId
        mutableState.value = State(
            update = update,
            stage = Stage.DOWNLOADING,
            status = "准备下载模块",
            percent = 0,
            installAuthorized = installAuthorized
        )
        downloadHandle = ModuleUpdater.downloadModule(
            appContext,
            update,
            object : ModuleUpdater.DownloadCallback {
                override fun onProgress(message: String, percent: Int?) {
                    if (id != operationId) return
                    updateState {
                        copy(
                            stage = Stage.DOWNLOADING,
                            status = message,
                            percent = percent
                        )
                    }
                }

                override fun onSuccess(zipPath: String) {
                    if (id != operationId) return
                    downloadHandle = null
                    updateState {
                        copy(
                            stage = Stage.DETECTING_MANAGER,
                            status = "下载完成，正在检测模块管理器",
                            percent = 100,
                            zipPath = zipPath
                        )
                    }
                    detectManager(appContext, update, zipPath, id)
                }

                override fun onFailure(message: String, recoverableZipPath: String?) {
                    if (id != operationId) return
                    downloadHandle = null
                    if (recoverableZipPath != null) {
                        retainForManualInstall(
                            appContext,
                            update,
                            recoverableZipPath,
                            message,
                            id
                        )
                    } else {
                        updateState {
                            copy(
                                stage = Stage.FAILED,
                                status = message,
                                percent = null,
                                zipPath = null
                            )
                        }
                    }
                }
            }
        )
    }

    fun retry(context: Context) {
        val current = mutableState.value ?: return
        val update = current.update ?: return
        if (current.stage == Stage.FAILED) {
            val appContext = context.applicationContext
            ModuleUpdater.authorizeDownloadAndInstall(appContext, update)
            beginDownload(appContext, update, installAuthorized = true)
            return
        }
        cancelSession(discardArtifact = true)
        start(context, update)
    }

    /** 返回 true 表示当前产物已由刷入页面接管。 */
    fun claimForInstall(zipPath: String): Boolean {
        val current = mutableState.value ?: return false
        if (current.stage != Stage.READY_TO_INSTALL || current.zipPath != zipPath ||
            !current.installAuthorized
        ) return false
        mutableState.value = current.copy(stage = Stage.HANDED_OFF)
        return true
    }

    fun handoffFailed(context: Context, message: String) {
        val current = mutableState.value ?: return
        val update = current.update ?: return
        val zipPath = current.zipPath ?: return
        val id = ++operationId
        retainForManualInstall(context.applicationContext, update, zipPath, message, id)
    }

    fun cancelSession(discardArtifact: Boolean = true) {
        operationId++
        downloadHandle?.cancel()
        downloadHandle = null
        val current = mutableState.value ?: State()
        if (discardArtifact && current.stage != Stage.MANUAL_READY &&
            current.stage != Stage.HANDED_OFF) {
            current.zipPath?.takeIf { File(it).exists() }?.let(ModuleUpdater::discardDownloadedModule)
            current.update?.let {
                ModuleUpdater.cancelPersistedDownload(getApplication(), it)
            }
        }
        ModuleUpdater.clearPersistedDownload(getApplication(), current.update)
        mutableState.value = State()
    }

    private fun detectManager(
        context: Context,
        update: ModuleUpdater.UpdateInfo,
        zipPath: String,
        id: Long
    ) {
        thread(name = "QixiaThreadsUpdateManagerDetect") {
            val managerLabel = ModuleUpdater.detectRootManagerLabel()
            mainHandler.post {
                if (id != operationId) return@post
                if (managerLabel == null) {
                    retainForManualInstall(
                        context,
                        update,
                        zipPath,
                        "没有检测到可用的模块管理器，请手动刷入",
                        id
                    )
                } else {
                    ModuleUpdater.persistDownloadPhase(
                        context,
                        update,
                        phase = ModuleUpdater.DOWNLOAD_PHASE_READY,
                        targetPath = zipPath,
                        percent = 100
                    )
                    updateState {
                        copy(
                            stage = Stage.READY_TO_INSTALL,
                            status = "检测到 $managerLabel，准备刷入模块",
                            percent = 100,
                            zipPath = zipPath,
                            managerLabel = managerLabel
                        )
                    }
                }
            }
        }
    }

    private fun retainForManualInstall(
        context: Context,
        update: ModuleUpdater.UpdateInfo,
        zipPath: String,
        message: String,
        id: Long
    ) {
        mutableState.value = State(
            update = update,
            stage = Stage.RETAINING_MANUAL,
            status = "正在保存模块 zip 到 Download",
            percent = 100,
            zipPath = zipPath
        )
        thread(name = "QixiaThreadsUpdateRetain") {
            val manualPath = ModuleUpdater.retainDownloadedModuleForManualInstall(
                context,
                zipPath
            )
            mainHandler.post {
                if (id != operationId) return@post
                ModuleUpdater.persistDownloadPhase(
                    context,
                    update,
                    phase = ModuleUpdater.DOWNLOAD_PHASE_MANUAL,
                    targetPath = manualPath,
                    percent = 100
                )
                mutableState.value = State(
                    update = update,
                    stage = Stage.MANUAL_READY,
                    status = "$message\n模块已保存到：$manualPath",
                    percent = 100,
                    zipPath = null,
                    manualPath = manualPath,
                    installAuthorized = false
                )
            }
        }
    }

    private fun updateState(update: State.() -> State) {
        mutableState.value = (mutableState.value ?: State()).update()
    }

    private fun sameUpdate(
        first: ModuleUpdater.UpdateInfo?,
        second: ModuleUpdater.UpdateInfo
    ): Boolean = first?.remoteVersionCode == second.remoteVersionCode &&
        first.zipUrl == second.zipUrl

    private fun restorePersistedSession(context: Context) {
        val session = ModuleUpdater.readPersistedDownloadSession(context) ?: return
        val targetExists = session.targetPath?.let { File(it).exists() } == true
        mutableState.value = when (session.phase) {
            ModuleUpdater.DOWNLOAD_PHASE_READY -> {
                if (!targetExists) {
                    ModuleUpdater.clearPersistedDownload(context, session.update)
                    State()
                } else {
                    State(
                        update = session.update,
                        stage = Stage.READY_TO_INSTALL,
                        status = "下载已完成，准备继续刷入",
                        percent = 100,
                        zipPath = session.targetPath,
                        installAuthorized = session.installAuthorized
                    )
                }
            }
            ModuleUpdater.DOWNLOAD_PHASE_MANUAL -> State(
                update = session.update,
                stage = Stage.MANUAL_READY,
                status = "模块已保存，可在 Root 管理器中手动刷入",
                percent = 100,
                manualPath = session.targetPath,
                installAuthorized = false
            )
            ModuleUpdater.DOWNLOAD_PHASE_FAILED -> State(
                update = session.update,
                stage = Stage.FAILED,
                status = "上次下载未完成，可点击重试",
                installAuthorized = false
            )
            else -> State(
                update = session.update,
                stage = Stage.RESUME_READY,
                status = "检测到未完成的下载，正在准备恢复",
                percent = session.percent,
                installAuthorized = session.installAuthorized
            )
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        cancelSession(discardArtifact = true)
        super.onCleared()
    }
}
