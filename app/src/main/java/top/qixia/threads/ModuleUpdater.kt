package top.qixia.threads

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object ModuleUpdater {
    private const val MODULE_DIR = "/data/adb/modules/QixiaThreads"
    private const val MODULE_PROP = "/data/adb/modules/QixiaThreads/module.prop"
    private const val PENDING_MODULE_DIR = "/data/adb/modules_update/QixiaThreads"
    private const val PENDING_MODULE_PROP = "/data/adb/modules_update/QixiaThreads/module.prop"
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30000
    private const val INSTALL_TIMEOUT_SECONDS = 180L
    private const val INSTALL_LOG_BATCH_MS = 200L
    private const val DOWNLOAD_TIMEOUT_MS = 30L * 60L * 1000L
    private const val DOWNLOAD_MISSING_LIMIT = 6
    private const val DOWNLOAD_PROVIDER_AUTHORITY = "downloads"
    private const val MAX_DOWNLOAD_REDIRECTS = 5
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    private const val TAG = "QixiaThreads"
    private const val UPDATE_PREFS = "qixia_module_update_state"
    private const val KEY_DOWNLOAD_KEY = "download.key"
    private const val KEY_DOWNLOAD_ATTEMPT = "download.attempt"
    private const val KEY_DOWNLOAD_ID = "download.id"
    private const val KEY_DOWNLOAD_TARGET = "download.target"
    private const val KEY_DOWNLOAD_PHASE = "download.phase"
    private const val KEY_DOWNLOAD_LOCAL_VERSION = "download.local_version"
    private const val KEY_DOWNLOAD_LOCAL_VERSION_CODE = "download.local_version_code"
    private const val KEY_DOWNLOAD_VERSION = "download.version"
    private const val KEY_DOWNLOAD_VERSION_CODE = "download.version_code"
    private const val KEY_DOWNLOAD_URL = "download.url"
    private const val KEY_DOWNLOAD_PERCENT = "download.percent"
    private const val KEY_DOWNLOAD_CHANGELOG = "download.changelog"
    private const val KEY_INSTALL_AUTHORIZED = "download.install_authorized"
    private const val KEY_INSTALL_KEY = "install.key"
    private const val KEY_INSTALL_STATE = "install.state"
    private const val KEY_INSTALL_UPDATED_AT = "install.updated_at"
    private const val KEY_INSTALL_VERSION_CODE = "install.version_code"
    private const val KEY_INSTALL_ZIP = "install.zip"
    private const val KEY_INSTALL_DIGEST = "install.digest"
    private const val KEY_INSTALL_BOOT_ID = "install.boot_id"
    private const val KEY_INSTALL_LOG = "install.log"
    private const val INSTALL_STATE_RUNNING = "running"
    private const val INSTALL_STATE_SUCCEEDED = "succeeded"
    private const val INSTALL_STATE_FAILED = "failed"
    private const val INSTALL_RUNNING_STALE_MS = (INSTALL_TIMEOUT_SECONDS + 60L) * 1000L
    const val DOWNLOAD_PHASE_DOWNLOADING = "downloading"
    const val DOWNLOAD_PHASE_READY = "ready"
    const val DOWNLOAD_PHASE_MANUAL = "manual"
    const val DOWNLOAD_PHASE_FAILED = "failed"
    private val updateStateLock = Any()
    private const val IN_APP_UPDATE_ENV = "QIXIA_IN_APP_UPDATE"
    private const val IN_APP_UPDATE_MARKER_ENTRY = "config/app/.qixia_in_app_update"
    private const val IN_APP_UPDATE_FLAG_PATH = "/data/adb/qixia_in_app_update"

    data class ModuleProp(
        val version: String,
        val versionCode: Int,
        val updateJson: String?
    )

    data class RemoteUpdate(
        val version: String,
        val versionCode: Int,
        val zipUrl: String,
        val changelogUrl: String?
    )

    data class UpdateInfo(
        val localVersion: String,
        val localVersionCode: Int,
        val remoteVersion: String,
        val remoteVersionCode: Int,
        val zipUrl: String,
        val changelogUrl: String?,
        val changelogText: String,
        val changelogLoadFailed: Boolean
    )

    /** 进程重建后恢复下载/等待刷入所需的最小状态。 */
    data class PersistedDownloadSession(
        val update: UpdateInfo,
        val phase: String,
        val targetPath: String?,
        val percent: Int?,
        val downloadId: Long?,
        val installAuthorized: Boolean
    )

    enum class InstallClaim {
        STARTED,
        ALREADY_RUNNING,
        PREVIOUSLY_FAILED
    }

    sealed class CheckResult {
        data class UpdateAvailable(val update: UpdateInfo) : CheckResult()
        data class NoUpdate(
            val message: String,
            val localVersion: String? = null,
            val localVersionCode: Int? = null,
            val remoteVersion: String? = null,
            val remoteVersionCode: Int? = null,
            val pendingReboot: Boolean = false
        ) : CheckResult()
        data class Failed(
            val message: String,
            val localVersion: String? = null,
            val localVersionCode: Int? = null,
            val remoteVersion: String? = null,
            val remoteVersionCode: Int? = null
        ) : CheckResult()
    }

    interface DownloadCallback {
        fun onProgress(message: String, percent: Int?)
        fun onSuccess(zipPath: String)
        fun onFailure(message: String, recoverableZipPath: String?)
    }

    class DownloadHandle internal constructor(
        internal val attempt: String = UUID.randomUUID().toString(),
        private val onCancel: (() -> Unit)? = null
    ) {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var artifact: File? = null

        val isCancelled: Boolean
            get() = cancelled.get()

        internal val artifactPath: String?
            get() = artifact?.takeIf(File::exists)?.absolutePath

        internal fun track(file: File) {
            artifact = file
            if (isCancelled) discardDownloadedModule(file.absolutePath)
        }

        internal fun rejectArtifact() {
            artifact?.let { discardDownloadedModule(it.absolutePath) }
            artifact = null
        }

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            artifact?.let { discardDownloadedModule(it.absolutePath) }
            onCancel?.invoke()
        }
    }

    interface InstallCallback {
        fun onProgress(message: String, percent: Int?)
        fun onLog(text: String) = Unit
        fun onSuccess(message: String, rebootRequired: Boolean)
        fun onFailure(message: String, retainedZipPath: String? = null)
    }

    fun checkForUpdate(): CheckResult {
        if (!DaemonBridge.hasRoot()) {
            return CheckResult.Failed("请先授予 Root 权限")
        }

        val localText = DaemonBridge.readRootFile(MODULE_PROP)
            ?: return CheckResult.Failed("未检测到 QixiaThreads 模块")
        val local = parseModuleProp(localText)
            ?: return CheckResult.Failed("无法读取本地模块版本")
        val updateJson = local.updateJson?.takeIf { it.isNotBlank() }
            ?: return CheckResult.NoUpdate(
                message = "当前模块不支持在线更新",
                localVersion = local.version,
                localVersionCode = local.versionCode
            )

        val remote = try {
            parseRemoteUpdate(fetchText(updateJson))
        } catch (_: Exception) {
            null
        } ?: return CheckResult.Failed(
            message = "远程更新信息读取失败",
            localVersion = local.version,
            localVersionCode = local.versionCode
        )

        val pending = DaemonBridge.readRootFile(PENDING_MODULE_PROP)
            ?.let { parseModuleProp(it) }
        if (pending != null && pending.versionCode >= remote.versionCode &&
            pendingModuleIsComplete(pending.versionCode)
        ) {
            return CheckResult.NoUpdate(
                message = "新版本已刷入，重启后生效",
                localVersion = local.version,
                localVersionCode = local.versionCode,
                remoteVersion = remote.version,
                remoteVersionCode = remote.versionCode,
                pendingReboot = true
            )
        }

        if (remote.versionCode <= local.versionCode) {
            return CheckResult.NoUpdate(
                message = "已是最新版本",
                localVersion = local.version,
                localVersionCode = local.versionCode,
                remoteVersion = remote.version,
                remoteVersionCode = remote.versionCode
            )
        }

        val zipUrl = remote.zipUrl.takeIf { it.isNotBlank() }
            ?: return CheckResult.Failed(
                message = "远程更新信息缺少模块下载链接",
                localVersion = local.version,
                localVersionCode = local.versionCode,
                remoteVersion = remote.version,
                remoteVersionCode = remote.versionCode
            )

        var changelogText = ""
        var changelogFailed = false
        val changelogUrl = remote.changelogUrl?.takeIf { it.isNotBlank() }
        if (changelogUrl == null) {
            changelogFailed = true
        } else {
            try {
                changelogText = fetchText(changelogUrl)
            } catch (_: Exception) {
                changelogFailed = true
            }
        }

        if (changelogFailed) {
            changelogText = "更新日志读取失败，可继续下载模块"
        }

        return CheckResult.UpdateAvailable(
            UpdateInfo(
                localVersion = local.version,
                localVersionCode = local.versionCode,
                remoteVersion = remote.version,
                remoteVersionCode = remote.versionCode,
                zipUrl = zipUrl,
                changelogUrl = remote.changelogUrl,
                changelogText = changelogText,
                changelogLoadFailed = changelogFailed
            )
        )
    }

    fun downloadModule(
        context: Context,
        update: UpdateInfo,
        callback: DownloadCallback
    ): DownloadHandle {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val attempt = beginDownloadAttempt(appContext, update)
        val handle = DownloadHandle(attempt) {
            cancelPersistedDownload(appContext, update, attempt)
        }
        persistDownloadPhase(
            appContext,
            update,
            phase = DOWNLOAD_PHASE_DOWNLOADING,
            targetPath = null,
            percent = 0,
            expectedAttempt = attempt
        )
        var lastPersistedPercent: Int? = 0
        var lastPersistedAt = SystemClock.elapsedRealtime()

        fun progress(message: String, percent: Int? = null) {
            if (!handle.isCancelled) {
                val now = SystemClock.elapsedRealtime()
                if (percent != lastPersistedPercent || now - lastPersistedAt >= 5_000L) {
                    persistDownloadPhase(
                        appContext,
                        update,
                        phase = DOWNLOAD_PHASE_DOWNLOADING,
                        targetPath = null,
                        percent = percent,
                        expectedAttempt = attempt
                    )
                    lastPersistedPercent = percent
                    lastPersistedAt = now
                }
                mainHandler.post {
                    if (!handle.isCancelled) callback.onProgress(message, percent)
                }
            }
        }

        thread(name = "QixiaThreadsModuleUpdater") {
            try {
                if (handle.isCancelled) throw DownloadCancelledException()
                progress("准备下载模块", 0)
                val zip = if (resumesDirectDownload(
                        persistedDownloadTarget(appContext, update, attempt),
                        persistedDownloadId(appContext, update, attempt))) {
                    downloadDirect(appContext, update, handle) { message, percent ->
                        progress(message, percent)
                    }
                } else try {
                    downloadWithManager(appContext, update, handle) { message, percent ->
                        progress(message, percent)
                    }
                } catch (e: SystemDownloadException) {
                    Log.w(TAG, "系统下载服务不可用，切换到内置下载", e)
                    progress("系统下载服务不可用，已切换内置下载", 0)
                    downloadDirect(appContext, update, handle) { message, percent ->
                        progress(message, percent)
                    }
                }
                handle.track(zip)
                if (handle.isCancelled) throw DownloadCancelledException()
                try {
                    validateModuleZip(zip, update)
                } catch (e: UpdateException) {
                    handle.rejectArtifact()
                    clearPersistedDownload(appContext, update, attempt)
                    throw e
                }
                val markedZip = markZipForInAppUpdate(zip)
                handle.track(markedZip)
                if (handle.isCancelled) throw DownloadCancelledException()
                progress("下载完成，准备刷入", 100)
                persistDownloadPhase(
                    appContext,
                    update,
                    phase = DOWNLOAD_PHASE_READY,
                    targetPath = markedZip.absolutePath,
                    percent = 100,
                    expectedAttempt = attempt
                )
                mainHandler.post {
                    if (!handle.isCancelled) callback.onSuccess(markedZip.absolutePath)
                }
            } catch (_: DownloadCancelledException) {
                handle.cancel()
            } catch (e: UpdateException) {
                Log.e(TAG, "模块更新下载失败", e)
                if (!handle.isCancelled) {
                    persistDownloadPhase(
                        appContext,
                        update,
                        phase = DOWNLOAD_PHASE_FAILED,
                        targetPath = handle.artifactPath,
                        percent = null,
                        expectedAttempt = attempt
                    )
                    mainHandler.post {
                        if (!handle.isCancelled) {
                            callback.onFailure(e.message ?: "更新失败", handle.artifactPath)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "模块更新下载失败", e)
                if (!handle.isCancelled) {
                    persistDownloadPhase(
                        appContext,
                        update,
                        phase = DOWNLOAD_PHASE_FAILED,
                        targetPath = handle.artifactPath,
                        percent = null,
                        expectedAttempt = attempt
                    )
                    mainHandler.post {
                        if (!handle.isCancelled) {
                            callback.onFailure("更新失败，请稍后重试", handle.artifactPath)
                        }
                    }
                }
            }
        }
        return handle
    }

    fun installDownloadedModule(
        zipPath: String,
        callback: InstallCallback,
        inAppUpdate: Boolean = true,
        prepareDelayMs: Long = 0L,
        context: Context? = null,
        expectedVersionCode: Int? = null
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val appContext = context?.applicationContext

        fun progress(message: String, percent: Int? = null) {
            mainHandler.post { callback.onProgress(message, percent) }
        }

        val pendingLog = StringBuilder()
        val completeLog = StringBuilder()
        val pendingLogLock = Any()
        var logPublishScheduled = false
        fun drainPendingLog(): String = synchronized(pendingLogLock) {
            pendingLog.toString().also { pendingLog.clear() }
        }
        val publishLogRunnable = Runnable {
            synchronized(pendingLogLock) { logPublishScheduled = false }
            drainPendingLog().takeIf { it.isNotEmpty() }?.let(callback::onLog)
        }
        fun log(text: String) {
            if (text.isEmpty()) return
            val shouldSchedule = synchronized(pendingLogLock) {
                pendingLog.append(text)
                BoundedInstallLog.append(completeLog, text)
                if (logPublishScheduled) false else {
                    logPublishScheduled = true
                    true
                }
            }
            if (shouldSchedule) mainHandler.postDelayed(publishLogRunnable, INSTALL_LOG_BATCH_MS)
        }

        fun flushLog() {
            mainHandler.removeCallbacks(publishLogRunnable)
            synchronized(pendingLogLock) { logPublishScheduled = false }
            val chunk = drainPendingLog()
            if (chunk.isNotEmpty()) mainHandler.post { callback.onLog(chunk) }
        }

        fun postSuccess(message: String, rebootRequired: Boolean = true) {
            flushLog()
            mainHandler.post { callback.onSuccess(message, rebootRequired) }
        }

        fun postFailure(message: String, retainedZipPath: String? = null) {
            flushLog()
            mainHandler.post { callback.onFailure(message, retainedZipPath) }
        }

        var installZip: File? = null
        var claimedInstallKey: String? = null

        fun markInstallFailed() {
            val key = claimedInstallKey ?: return
            appContext?.let { persistInstallState(it, key, INSTALL_STATE_FAILED) }
        }

        fun retainInstallFailure(message: String): Pair<String, String?> {
            val zip = installZip?.takeIf { it.exists() } ?: return message to null
            if (!inAppUpdate) return message to zip.absolutePath
            val manualZip = retainOriginalZipForManualInstall(context, zip, true, ::log)
            return "$message\n模块已保存到：$manualZip\n请在 Root 管理器中手动刷入" to manualZip
        }

        thread(name = "QixiaThreadsModuleInstaller") {
            try {
                val zip = File(zipPath)
                installZip = zip
                val targetVersionCode = expectedVersionCode?.takeIf { it > 0 }
                val completed = if (appContext != null && targetVersionCode != null) {
                    readCompletedInstall(appContext, zip, targetVersionCode)
                } else null

                // 安装成功后会删除下载文件；访问 ZIP 前先恢复已安装结果，
                // Android 重建此 Activity 时也遵循这一顺序。
                if (completed != null && targetVersionCode != null && pendingModuleIsComplete(targetVersionCode)) {
                    completed?.log?.takeIf(String::isNotBlank)?.let(::log)
                    log("\n- 已恢复刷入结果：模块已就绪，等待重启；不会重复刷入\n")
                    if (appContext != null) {
                        rememberCompletedInstall(appContext, zip, targetVersionCode,
                            completed?.bootId?.takeIf(String::isNotBlank) ?: currentBootId(),
                            synchronized(pendingLogLock) { completeLog.toString() })
                    }
                    if (zip.exists()) cleanupUpdateZips(zip, inAppUpdate, ::log)
                    postSuccess("模块已刷入，重启后生效；App 将在重启后自动更新")
                    return@thread
                }
                if (completed != null && completed.bootId.isNotBlank()) {
                    val bootId = currentBootId()
                    if (bootId.isNotBlank() && bootId != completed.bootId &&
                        appliedModuleIsComplete(targetVersionCode!!)
                    ) {
                        completed.log.takeIf(String::isNotBlank)?.let(::log)
                        log("\n- 已确认设备重启，模块更新已生效；不会重复刷入\n")
                        postSuccess("模块更新已生效", rebootRequired = false)
                        return@thread
                    }
                }
                if (!zip.exists()) {
                    postFailure("模块 zip 不存在：$zipPath", null)
                    return@thread
                }

                if (targetVersionCode != null) {
                    try {
                        validateModuleZip(zip, targetVersionCode)
                    } catch (e: UpdateException) {
                        throw InvalidModuleException(e.message ?: "模块 zip 校验失败")
                    }
                }

                if (appContext != null && targetVersionCode != null) {
                    val key = installIdentity(zip, targetVersionCode)
                    when (claimInstall(appContext, key, zip, targetVersionCode)) {
                        InstallClaim.STARTED -> claimedInstallKey = key
                        InstallClaim.ALREADY_RUNNING -> {
                            postFailure(
                                "检测到同一模块仍在刷入，已阻止重复执行 Root 安装命令",
                                zip.absolutePath
                            )
                            return@thread
                        }
                        InstallClaim.PREVIOUSLY_FAILED -> {
                            postFailure(
                                "上次刷入流程已中断，已阻止页面重建后自动重复刷入；请返回更新页面重新确认",
                                zip.absolutePath
                            )
                            return@thread
                        }
                    }
                }

                progress("正在检测模块管理器", null)
                val manager = detectRootManager()
                if (manager == null) {
                    markInstallFailed()
                    val manualZip = retainOriginalZipForManualInstall(context, zip, inAppUpdate, ::log)
                    postFailure(
                        "没有检测到可用的模块管理器\n模块已保存到：$manualZip\n请手动刷入",
                        manualZip
                    )
                    return@thread
                }

                val prepareMessage = if (prepareDelayMs > 0L) {
                    "检测到 ${manager.label}，准备刷入模块"
                } else {
                    "检测到 ${manager.label}，开始刷入模块"
                }
                progress(prepareMessage, null)
                log("检测到模块管理器：${manager.label}\n")
                if (inAppUpdate) {
                    log("已写入 App 内更新标记：$IN_APP_UPDATE_MARKER_ENTRY\n")
                    log("已创建 Root 临时更新标记：$IN_APP_UPDATE_FLAG_PATH\n")
                }
                log("${manager.displayCommand(zipPath, inAppUpdate)}\n\n")
                if (prepareDelayMs > 0L) {
                    try {
                        Thread.sleep(prepareDelayMs)
                    } catch (_: InterruptedException) {
                        throw UpdateException("刷入已中断")
                    }
                }

                progress("正在刷入模块", null)
                val result = DaemonBridge.runRootCommandStreaming(
                    manager.installCommand(zipPath, inAppUpdate),
                    INSTALL_TIMEOUT_SECONDS
                ) { chunk ->
                    if (chunk.isNotBlank()) {
                        log(chunk)
                    }
                }
                val pendingReady = targetVersionCode?.let(::waitForPendingModuleUpdate)
                    ?: (result.success && DaemonBridge.hasPendingModuleUpdate())
                if (result.success && pendingReady) {
                    if (appContext != null && targetVersionCode != null) {
                        rememberCompletedInstall(appContext, zip, targetVersionCode, currentBootId(),
                            synchronized(pendingLogLock) { completeLog.toString() })
                    }
                    cleanupUpdateZips(zip, inAppUpdate, ::log)
                    val message = if (inAppUpdate) {
                        "模块已刷入，重启后生效；App 将在重启后自动更新"
                    } else {
                        "模块已刷入，重启后生效"
                    }
                    postSuccess(message)
                } else {
                    markInstallFailed()
                    val manualZip = retainOriginalZipForManualInstall(context, zip, inAppUpdate, ::log)
                    val reason = if (result.success) {
                        "刷入命令已结束，但未检测到完整的待更新模块"
                    } else if (result.timedOut) {
                        "刷入超时，后台安装进程已终止"
                    } else {
                        "刷入失败"
                    }
                    postFailure(
                        "$reason，原始模块已保存到：$manualZip\n请在 Root 管理器中手动刷入",
                        manualZip
                    )
                }
            } catch (e: InvalidModuleException) {
                markInstallFailed()
                installZip?.takeIf(File::exists)?.let { discardDownloadedModule(it.absolutePath) }
                postFailure(e.message ?: "模块 zip 校验失败", null)
            } catch (e: UpdateException) {
                markInstallFailed()
                val (message, retainedPath) = retainInstallFailure(e.message ?: "刷入失败")
                postFailure(message, retainedPath)
            } catch (_: Exception) {
                markInstallFailed()
                val (message, retainedPath) = retainInstallFailure("刷入失败")
                postFailure(message, retainedPath)
            }
        }
    }

    fun detectRootManagerLabel(): String? {
        return detectRootManager()?.label
    }

    private fun waitForPendingModuleUpdate(expectedVersionCode: Int): Boolean {
        repeat(20) {
            if (pendingModuleIsComplete(expectedVersionCode)) return true
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun retainDownloadedModuleForManualInstall(
        context: Context?,
        zipPath: String,
        inAppUpdate: Boolean = true
    ): String {
        val zip = File(zipPath)
        if (!zip.exists()) return zipPath
        if (inAppUpdate && originalZipForInAppZip(zip) == null) {
            val publicPath = copyToPublicDownloads(context, zip) ?: return zip.absolutePath
            zip.delete()
            return publicPath
        }
        return retainOriginalZipForManualInstall(context, zip, inAppUpdate) {}
    }

    fun discardDownloadedModule(zipPath: String) {
        val zip = File(zipPath)
        originalZipForInAppZip(zip)?.delete()
        zip.delete()
    }

    private fun downloadWithManager(
        context: Context,
        update: UpdateInfo,
        handle: DownloadHandle,
        onProgress: (String, Int?) -> Unit
    ): File {
        val provider = try {
            context.packageManager.resolveContentProvider(DOWNLOAD_PROVIDER_AUTHORITY, 0)
        } catch (_: RuntimeException) {
            null
        }
        if (provider == null) {
            throw SystemDownloadException("未找到系统 downloads 提供程序")
        }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw SystemDownloadException("系统下载服务不可用")
        if (handle.isCancelled) throw DownloadCancelledException()
        var target = persistedDownloadTarget(context, update, handle.attempt)
            ?: createDownloadTarget(context, update, handle.attempt)
        var id = persistedDownloadId(context, update, handle.attempt)
        var query: DownloadManager.Query
        if (id == null || !downloadTaskExists(manager, id)) {
            id?.let { runCatching { manager.remove(it) } }
            clearPersistedDownloadTask(context, update, handle.attempt)
            target = createDownloadTarget(context, update, handle.attempt)
            val request = DownloadManager.Request(Uri.parse(update.zipUrl))
                .setTitle("QixiaThreads ${update.remoteVersion}")
                .setDescription("正在下载模块更新")
                .setMimeType("application/zip")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target))
            id = try {
                manager.enqueue(request)
            } catch (e: RuntimeException) {
                target.delete()
                throw SystemDownloadException("系统下载服务无法创建任务", e)
            }
            if (handle.isCancelled || !persistDownloadManagerTask(context, update, id, target, handle.attempt)) {
                runCatching { manager.remove(id) }
                target.delete()
                throw DownloadCancelledException()
            }
        }
        val activeId = id ?: throw SystemDownloadException("系统下载任务无效")
        query = DownloadManager.Query().setFilterById(activeId)
        val startedAt = SystemClock.elapsedRealtime()
        var missingCount = 0
        try {
            while (true) {
                if (handle.isCancelled) throw DownloadCancelledException()
                if (SystemClock.elapsedRealtime() - startedAt > DOWNLOAD_TIMEOUT_MS) {
                    throw UpdateException("下载超时，请检查网络后重试")
                }
                var found = false
                val cursor = try {
                    manager.query(query)
                } catch (e: RuntimeException) {
                    throw SystemDownloadException("系统下载任务查询失败", e)
                }
                cursor?.use {
                    if (cursor.moveToFirst()) {
                        found = true
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                onProgress("下载完成，准备刷入", 100)
                                val completed = target.takeIf { it.exists() }
                                    ?: downloadedFile(cursor)
                                    ?: throw UpdateException("下载完成，但未找到模块文件")
                                return preserveSuccessfulDownload(context, manager, activeId, completed)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                throw SystemDownloadException("系统下载失败：${downloadReason(reason)}")
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                val percent = downloadPercent(cursor)
                                onProgress("下载暂停，等待系统继续", percent)
                            }
                            DownloadManager.STATUS_PENDING -> onProgress("等待开始下载", 0)
                            DownloadManager.STATUS_RUNNING -> {
                                val percent = downloadPercent(cursor)
                                onProgress(if (percent != null) "下载中 $percent%" else "下载中", percent)
                            }
                        }
                    }
                }
                missingCount = if (found) 0 else missingCount + 1
                if (missingCount >= DOWNLOAD_MISSING_LIMIT) {
                    throw SystemDownloadException("系统下载任务已被移除")
                }
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UpdateException("下载已中断")
                }
            }
        } catch (e: Exception) {
            runCatching { manager.remove(activeId) }
            target.delete()
            clearPersistedDownloadTask(context, update, handle.attempt)
            throw e
        }
    }

    private fun downloadDirect(
        context: Context,
        update: UpdateInfo,
        handle: DownloadHandle,
        onProgress: (String, Int?) -> Unit
    ): File {
        if (handle.isCancelled) throw DownloadCancelledException()
        val target = persistedDownloadTarget(context, update, handle.attempt)
            ?: createDownloadTarget(context, update, handle.attempt)
        val partial = File(target.parentFile, "${target.name}.part")
        var connection: HttpURLConnection? = null
        val startedAt = SystemClock.elapsedRealtime()
        var completed = false

        try {
            if (target.exists() && target.length() > 0L) {
                return target
            }
            if (!persistDownloadManagerTask(context, update, null, target, handle.attempt)) {
                throw DownloadCancelledException()
            }
            val resumeOffset = partial.length().takeIf { it > 0L } ?: 0L
            connection = try {
                openDownloadConnection(update.zipUrl, resumeOffset)
            } catch (e: Exception) {
                if (resumeOffset > 0L) {
                    partial.delete()
                    openDownloadConnection(update.zipUrl, 0L)
                } else {
                    throw e
                }
            }
            val append = resumeOffset > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val total = connection.contentLengthLong.takeIf { it > 0L }?.let {
                if (append) it + resumeOffset else it
            }
            var downloaded = if (append) resumeOffset else 0L
            var lastPercent = -1
            var lastProgressAt = 0L
            if (!append && partial.exists()) partial.delete()
            onProgress("内置下载中", if (total != null) 0 else null)

            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        if (handle.isCancelled) throw DownloadCancelledException()
                        if (SystemClock.elapsedRealtime() - startedAt > DOWNLOAD_TIMEOUT_MS) {
                            throw UpdateException("下载超时，请检查网络后重试")
                        }

                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloaded += count

                        val now = SystemClock.elapsedRealtime()
                        val percent = total?.let {
                            ((downloaded * 100L) / it).toInt().coerceIn(0, 99)
                        }
                        if ((percent != null && percent != lastPercent) || now - lastProgressAt >= 1000L) {
                            onProgress(
                                if (percent != null) "内置下载中 $percent%" else "内置下载中",
                                percent
                            )
                            if (percent != null) lastPercent = percent
                            lastProgressAt = now
                        }
                    }
                }
            }

            if (!partial.exists() || partial.length() <= 0L) {
                throw UpdateException("下载完成，但模块文件为空")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            completed = true
            clearPersistedDownloadTask(context, update, handle.attempt)
            return target.takeIf { it.exists() && it.length() > 0L }
                ?: throw UpdateException("下载完成，但未找到模块文件")
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("内置下载失败：${e.message ?: "网络连接异常"}", e)
        } finally {
            connection?.disconnect()
            if (completed || handle.isCancelled) partial.delete()
            if (!target.exists() || target.length() <= 0L) target.delete()
        }
    }

    internal fun resumesDirectDownload(target: File?, managerId: Long?): Boolean =
        managerId == null && target != null &&
            File(target.parentFile, "${target.name}.part").let { it.isFile && it.length() > 0L }

    private fun openDownloadConnection(url: String, rangeStart: Long = 0L): HttpURLConnection {
        var currentUrl = URL(url)
        for (redirectCount in 0..MAX_DOWNLOAD_REDIRECTS) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "QixiaThreads/${DaemonBridge.REQUIRED_MODULE_VERSION_NAME}")
                setRequestProperty("Accept", "application/zip, application/octet-stream, */*")
                if (rangeStart > 0L) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw UpdateException("下载重定向缺少目标地址")
                }
                if (redirectCount >= MAX_DOWNLOAD_REDIRECTS) {
                    throw UpdateException("下载重定向次数过多")
                }
                currentUrl = URL(currentUrl, location)
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw UpdateException("下载失败：HTTP $code")
            }
            return connection
        }
        throw UpdateException("下载重定向次数过多")
    }

    private fun createDownloadTarget(context: Context, update: UpdateInfo, attempt: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException("下载目录不可用")
        }
        return File(
            dir,
            "QixiaThreads-${safeFilePart(update.remoteVersion)}-${update.remoteVersionCode}-$attempt.zip"
        )
    }

    private fun downloadPercent(cursor: android.database.Cursor): Int? {
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        if (total <= 0L) return null
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun downloadedFile(cursor: android.database.Cursor): File? {
        val index = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        if (index < 0) return null
        val uri = cursor.getString(index)?.takeIf { it.isNotBlank() } ?: return null
        val path = Uri.parse(uri).path ?: return null
        return File(path).takeIf { it.exists() }
    }

    /** DownloadManager.remove 会连同目标文件一起删除，先复制出 App 自己接管的 ZIP 再清理任务记录。 */
    private fun preserveSuccessfulDownload(
        context: Context,
        manager: DownloadManager,
        id: Long,
        source: File
    ): File {
        val parent = source.parentFile ?: throw UpdateException("下载目录不可用")
        val preserved = File(parent, "${source.nameWithoutExtension}-ready.zip")
        if (preserved.exists() && !preserved.delete()) {
            throw UpdateException("无法准备模块文件")
        }
        try {
            source.inputStream().buffered().use { input ->
                preserved.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            preserved.delete()
            throw UpdateException("保存下载的模块文件失败", e)
        }
        runCatching { manager.remove(id) }
            .onFailure { Log.w(TAG, "清理系统下载任务失败，保留任务记录", it) }
            .onSuccess { removed ->
                if (removed == 0) Log.w(TAG, "系统下载任务已完成但未能清理记录: $id")
            }
        clearPersistedDownloadById(context, id)
        return preserved
    }

    private fun markZipForInAppUpdate(zip: File): File {
        if (!zip.exists()) {
            throw UpdateException("模块 zip 不存在")
        }
        val parent = zip.parentFile ?: throw UpdateException("下载目录不可用")
        val name = zip.name.removeSuffix(".zip").removeSuffix(".ZIP")
        val marked = File(parent, "${name}-inapp.zip")
        if (marked.exists() && !marked.delete()) {
            throw UpdateException("无法准备 App 内更新模块 zip")
        }

        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                ZipOutputStream(marked.outputStream().buffered()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val normalizedName = entry.name.replace('\\', '/')
                        if (normalizedName != IN_APP_UPDATE_MARKER_ENTRY) {
                            val outEntry = ZipEntry(entry.name).apply {
                                time = entry.time
                                comment = entry.comment
                            }
                            output.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.closeEntry()
                        }
                        input.closeEntry()
                    }

                    output.putNextEntry(ZipEntry(IN_APP_UPDATE_MARKER_ENTRY).apply {
                        time = System.currentTimeMillis()
                    })
                    output.write("1\n".toByteArray(Charsets.UTF_8))
                    output.closeEntry()
                }
            }
        } catch (_: Exception) {
            marked.delete()
            throw UpdateException("写入 App 内更新标记失败，已取消刷入")
        }

        return marked.takeIf { it.exists() && it.length() > 0L }
            ?: throw UpdateException("写入 App 内更新标记失败，已取消刷入")
    }

    private fun cleanupUpdateZips(installZip: File, inAppUpdate: Boolean, log: (String) -> Unit) {
        if (!inAppUpdate) return
        var cleaned = false
        originalZipForInAppZip(installZip)?.let { original ->
            if (original.exists() && original.delete()) cleaned = true
        }
        if (installZip.exists() && installZip.delete()) cleaned = true
        if (cleaned) {
            log("\n- 已清理下载的模块临时文件\n")
        }
    }

    private fun retainOriginalZipForManualInstall(
        context: Context?,
        installZip: File,
        inAppUpdate: Boolean,
        log: (String) -> Unit
    ): String {
        if (!inAppUpdate) return installZip.absolutePath

        val original = originalZipForInAppZip(installZip)
        if (original == null || !original.exists()) {
            log("\n- 未找到原始模块，保留当前模块 zip：${installZip.absolutePath}\n")
            return installZip.absolutePath
        }

        val publicPath = copyToPublicDownloads(context, original)
        val manualPath = if (publicPath != null) {
            if (original.delete()) {
                log("\n- 原始模块已转移到：$publicPath\n")
            } else {
                log("\n- 原始模块已复制到：$publicPath\n")
                log("- 私有目录原始模块删除失败：${original.absolutePath}\n")
            }
            publicPath
        } else {
            log("\n- 原始模块转移到系统 Download 失败，已保留在：${original.absolutePath}\n")
            original.absolutePath
        }

        if (installZip.exists() && installZip.delete()) {
            log("- 已删除 App 内临时模块：${installZip.name}\n")
        }
        return manualPath
    }

    private fun originalZipForInAppZip(installZip: File): File? {
        val parent = installZip.parentFile ?: return null
        val name = installZip.name
        val suffix = "-inapp.zip"
        if (!name.lowercase(Locale.US).endsWith(suffix)) return null
        return File(parent, name.dropLast(suffix.length) + ".zip")
    }

    private fun copyToPublicDownloads(context: Context?, source: File): String? {
        if (!source.exists()) return null
        val appContext = context?.applicationContext ?: return copyToPublicDownloadsFile(source)
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        } ?: return copyToPublicDownloadsFile(source)

        return try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            } ?: throw UpdateException("无法写入系统 Download")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val finalName = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { source.name }
            "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/$finalName"
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            copyToPublicDownloadsFile(source)
        }
    }

    @Suppress("DEPRECATION")
    private fun copyToPublicDownloadsFile(source: File): String? {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) return null
            val target = uniqueFile(dir, source.name)
            source.copyTo(target, overwrite = false)
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (target.exists()) {
            target = File(dir, "$base-$index$ext")
            index++
        }
        return target
    }

    private fun updateIdentity(update: UpdateInfo): String =
        "${update.remoteVersionCode}|${update.zipUrl}"

    private fun updatePreferences(context: Context) =
        context.applicationContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

    /** 每个任务独立拥有持久状态和文件名，即使重试相同版本也不复用。 */
    internal fun beginDownloadAttempt(context: Context, update: UpdateInfo): String =
        synchronized(updateStateLock) {
            val attempt = UUID.randomUUID().toString()
            val prefs = updatePreferences(context)
            if (prefs.getString(KEY_DOWNLOAD_KEY, null) != updateIdentity(update)) {
                clearPersistedDownload(context)
            }
            prefs.edit().putString(KEY_DOWNLOAD_KEY, updateIdentity(update))
                .putString(KEY_DOWNLOAD_ATTEMPT, attempt).commit()
            attempt
        }

    private fun ownsDownload(prefs: android.content.SharedPreferences, update: UpdateInfo?, attempt: String?): Boolean =
        (update == null || prefs.getString(KEY_DOWNLOAD_KEY, null) == updateIdentity(update)) &&
            (attempt == null || prefs.getString(KEY_DOWNLOAD_ATTEMPT, null) == attempt)

    private fun persistDownloadManagerTask(
        context: Context,
        update: UpdateInfo,
        id: Long?,
        target: File,
        expectedAttempt: String
    ): Boolean = synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (!ownsDownload(prefs, update, expectedAttempt)) return@synchronized false
        prefs.edit()
            .putString(KEY_DOWNLOAD_KEY, updateIdentity(update))
            .putLong(KEY_DOWNLOAD_ID, id ?: -1L)
            .putString(KEY_DOWNLOAD_TARGET, target.absolutePath)
            .putString(KEY_DOWNLOAD_PHASE, DOWNLOAD_PHASE_DOWNLOADING)
            .putString(KEY_DOWNLOAD_VERSION, update.remoteVersion)
            .putInt(KEY_DOWNLOAD_VERSION_CODE, update.remoteVersionCode)
            .putString(KEY_DOWNLOAD_URL, update.zipUrl)
            .commit()
    }

    fun persistDownloadPhase(
        context: Context,
        update: UpdateInfo,
        phase: String,
        targetPath: String?,
        percent: Int?,
        expectedAttempt: String? = null
    ) = synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (expectedAttempt != null && !ownsDownload(prefs, update, expectedAttempt)) return@synchronized
        val editor = prefs.edit()
            .putString(KEY_DOWNLOAD_KEY, updateIdentity(update))
            .putString(KEY_DOWNLOAD_PHASE, phase)
            .putString(KEY_DOWNLOAD_LOCAL_VERSION, update.localVersion)
            .putInt(KEY_DOWNLOAD_LOCAL_VERSION_CODE, update.localVersionCode)
            .putString(KEY_DOWNLOAD_VERSION, update.remoteVersion)
            .putInt(KEY_DOWNLOAD_VERSION_CODE, update.remoteVersionCode)
            .putString(KEY_DOWNLOAD_URL, update.zipUrl)
            .putString(KEY_DOWNLOAD_CHANGELOG, update.changelogText)
        if (targetPath != null) editor.putString(KEY_DOWNLOAD_TARGET, targetPath)
        if (percent != null) editor.putInt(KEY_DOWNLOAD_PERCENT, percent)
        else editor.remove(KEY_DOWNLOAD_PERCENT)
        editor.commit()
    }

    fun readPersistedDownloadSession(context: Context): PersistedDownloadSession? {
        val prefs = updatePreferences(context)
        val key = prefs.getString(KEY_DOWNLOAD_KEY, null) ?: return null
        val versionCode = prefs.getInt(KEY_DOWNLOAD_VERSION_CODE, -1).takeIf { it > 0 }
            ?: return null
        val url = prefs.getString(KEY_DOWNLOAD_URL, null).orEmpty().takeIf { it.isNotBlank() }
            ?: return null
        val version = prefs.getString(KEY_DOWNLOAD_VERSION, null).orEmpty()
            .ifBlank { versionCode.toString() }
        if (key != "$versionCode|$url") {
            clearPersistedDownload(context, null)
            return null
        }
        val target = prefs.getString(KEY_DOWNLOAD_TARGET, null)
        val phase = prefs.getString(KEY_DOWNLOAD_PHASE, DOWNLOAD_PHASE_DOWNLOADING)
            ?: DOWNLOAD_PHASE_DOWNLOADING
        if (target.isNullOrBlank() && phase == DOWNLOAD_PHASE_READY) {
            clearPersistedDownload(context, null)
            return null
        }
        return PersistedDownloadSession(
            update = UpdateInfo(
                localVersion = prefs.getString(KEY_DOWNLOAD_LOCAL_VERSION, null).orEmpty()
                    .ifBlank { "未知" },
                localVersionCode = prefs.getInt(KEY_DOWNLOAD_LOCAL_VERSION_CODE, 0),
                remoteVersion = version,
                remoteVersionCode = versionCode,
                zipUrl = url,
                changelogUrl = null,
                changelogText = prefs.getString(KEY_DOWNLOAD_CHANGELOG, "").orEmpty(),
                changelogLoadFailed = false
            ),
            phase = phase,
            targetPath = target,
            percent = prefs.getInt(KEY_DOWNLOAD_PERCENT, -1).takeIf { it >= 0 },
            downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it > 0L },
            installAuthorized = prefs.getBoolean(KEY_INSTALL_AUTHORIZED, false)
        )
    }

    /** 记录用户已明确确认“下载并刷入”，仅供恢复同一更新会话使用。 */
    fun authorizeDownloadAndInstall(context: Context, update: UpdateInfo) {
        updatePreferences(context).edit()
            .putString(KEY_DOWNLOAD_KEY, updateIdentity(update))
            .putString(KEY_DOWNLOAD_VERSION, update.remoteVersion)
            .putString(KEY_DOWNLOAD_LOCAL_VERSION, update.localVersion)
            .putInt(KEY_DOWNLOAD_LOCAL_VERSION_CODE, update.localVersionCode)
            .putInt(KEY_DOWNLOAD_VERSION_CODE, update.remoteVersionCode)
            .putString(KEY_DOWNLOAD_URL, update.zipUrl)
            .putString(KEY_DOWNLOAD_CHANGELOG, update.changelogText)
            .putBoolean(KEY_INSTALL_AUTHORIZED, true)
            .commit()
    }

    fun clearPersistedDownload(context: Context, update: UpdateInfo? = null, expectedAttempt: String? = null) =
        synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (!ownsDownload(prefs, update, expectedAttempt)) return@synchronized
        prefs.edit()
            .remove(KEY_DOWNLOAD_KEY)
            .remove(KEY_DOWNLOAD_ATTEMPT)
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_TARGET)
            .remove(KEY_DOWNLOAD_PHASE)
            .remove(KEY_DOWNLOAD_LOCAL_VERSION)
            .remove(KEY_DOWNLOAD_LOCAL_VERSION_CODE)
            .remove(KEY_DOWNLOAD_VERSION)
            .remove(KEY_DOWNLOAD_VERSION_CODE)
            .remove(KEY_DOWNLOAD_URL)
            .remove(KEY_DOWNLOAD_PERCENT)
            .remove(KEY_DOWNLOAD_CHANGELOG)
            .remove(KEY_INSTALL_AUTHORIZED)
            .commit()
    }

    fun cancelPersistedDownload(context: Context, update: UpdateInfo, expectedAttempt: String? = null) {
        val appContext = context.applicationContext
        // 在与发布流程相同的锁内仅解绑当前任务；清理可能在重试开始后才结束，
        // 但旧任务的 ID 和独立路径都不能指向新任务。
        val owned = synchronized(updateStateLock) {
            val prefs = updatePreferences(appContext)
            if (!ownsDownload(prefs, update, expectedAttempt)) return
            val id = persistedDownloadId(appContext, update, expectedAttempt)
            val target = persistedDownloadTarget(appContext, update, expectedAttempt)
            clearPersistedDownload(appContext, update, expectedAttempt)
            id to target
        }
        owned.first?.let { id ->
            appContext.getSystemService(DownloadManager::class.java)?.let { manager ->
                runCatching { manager.remove(id) }
            }
        }
        owned.second?.let { target ->
            discardDownloadedModule(target.absolutePath)
            File(target.parentFile, "${target.name}.part").delete()
            File(target.parentFile, "${target.nameWithoutExtension}-ready.zip").delete()
            File(target.parentFile, "${target.nameWithoutExtension}-inapp.zip").delete()
        }
    }

    private fun persistedDownloadId(context: Context, update: UpdateInfo, expectedAttempt: String? = null): Long? =
        synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (!ownsDownload(prefs, update, expectedAttempt)) return@synchronized null
        prefs.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it > 0L }
    }

    private fun persistedDownloadTarget(context: Context, update: UpdateInfo, expectedAttempt: String? = null): File? =
        synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (!ownsDownload(prefs, update, expectedAttempt)) return@synchronized null
        prefs.getString(KEY_DOWNLOAD_TARGET, null)?.let(::File)
            ?.takeIf { it.parentFile?.canonicalPath ==
                (context.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.applicationContext.filesDir).canonicalPath
            }
    }

    private fun clearPersistedDownloadById(context: Context, id: Long) = synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) == id) {
            prefs.edit().remove(KEY_DOWNLOAD_ID).commit()
        }
    }

    internal fun clearPersistedDownloadTask(context: Context, update: UpdateInfo, expectedAttempt: String) =
        synchronized(updateStateLock) {
        val prefs = updatePreferences(context)
        if (!ownsDownload(prefs, update, expectedAttempt)) return@synchronized
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_TARGET)
            .remove(KEY_DOWNLOAD_PERCENT)
            .commit()
    }

    private fun downloadTaskExists(manager: DownloadManager, id: Long): Boolean {
        val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }
            .getOrNull() ?: return false
        return cursor.use { it.moveToFirst() }
    }

    private fun installIdentity(zip: File, versionCode: Int): String =
        sha256("$versionCode|${zip.canonicalPath}|${zip.length()}|${zip.lastModified()}")

    internal data class CompletedInstall(val bootId: String, val log: String)

    internal fun readCompletedInstall(context: Context, zip: File, versionCode: Int): CompletedInstall? =
        synchronized(updateStateLock) {
            val prefs = updatePreferences(context)
            if (prefs.getString(KEY_INSTALL_STATE, null) != INSTALL_STATE_SUCCEEDED ||
                prefs.getInt(KEY_INSTALL_VERSION_CODE, -1) != versionCode ||
                prefs.getString(KEY_INSTALL_ZIP, null)?.let { File(it).canonicalPath } != zip.canonicalPath
            ) return@synchronized null
            if (zip.exists() && prefs.getString(KEY_INSTALL_DIGEST, null) != fileDigest(zip)) {
                return@synchronized null
            }
            CompletedInstall(prefs.getString(KEY_INSTALL_BOOT_ID, "").orEmpty(),
                prefs.getString(KEY_INSTALL_LOG, "").orEmpty())
        }

    internal fun rememberCompletedInstall(context: Context, zip: File, versionCode: Int,
        bootId: String, log: String
    ) = synchronized(updateStateLock) {
        val bounded = StringBuilder().also { BoundedInstallLog.append(it, log) }.toString()
        val editor = updatePreferences(context).edit()
            .putString(KEY_INSTALL_KEY, installIdentity(zip, versionCode))
            .putString(KEY_INSTALL_STATE, INSTALL_STATE_SUCCEEDED)
            .putString(KEY_INSTALL_ZIP, zip.canonicalPath)
            .putInt(KEY_INSTALL_VERSION_CODE, versionCode)
            .putLong(KEY_INSTALL_UPDATED_AT, System.currentTimeMillis())
            .putString(KEY_INSTALL_BOOT_ID, bootId)
            .putString(KEY_INSTALL_LOG, bounded)
        if (zip.exists()) editor.putString(KEY_INSTALL_DIGEST, fileDigest(zip))
        editor.commit()
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun currentBootId(): String = DaemonBridge.runRootCommand(
        "cat /proc/sys/kernel/random/boot_id"
    ).let { if (it.success) it.output.trim() else "" }

    private fun appliedModuleIsComplete(expectedVersionCode: Int): Boolean {
        val result = DaemonBridge.runRootCommand(appliedModuleCheckScript(expectedVersionCode))
        return result.success && result.output.trim() == "1"
    }

    internal fun appliedModuleCheckScript(expectedVersionCode: Int): String = """
        dir='$MODULE_DIR'
        id=${'$'}(sed -n 's/^id=//p' "${'$'}dir/module.prop" 2>/dev/null | head -n 1)
        code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}dir/module.prop" 2>/dev/null | head -n 1)
        if [ "${'$'}id" = 'QixiaThreads' ] && [ "${'$'}code" -ge '$expectedVersionCode' ] 2>/dev/null &&
            [ ! -e '${PENDING_MODULE_DIR}' ] && [ ! -f "${'$'}dir/update" ] &&
            [ ! -f "${'$'}dir/disable" ] && [ ! -f "${'$'}dir/remove" ] &&
            [ -f "${'$'}dir/service.sh" ] && [ -x "${'$'}dir/config/bin/QiXiaRs" ] &&
            [ -f "${'$'}dir/config/ebpf/queuebuffer_probe.bpf.o" ] &&
            [ -f "${'$'}dir/config/ebpf/queuebuffer_probe_stats.bpf.o" ] &&
            [ -f "${'$'}dir/config/ebpf/queuebuffer_probe_perf.bpf.o" ]; then
            printf 1
        else
            printf 0
        fi
    """.trimIndent()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun claimInstall(
        context: Context,
        key: String,
        zip: File,
        versionCode: Int
    ): InstallClaim {
        synchronized(updateStateLock) {
            val prefs = updatePreferences(context)
            val currentKey = prefs.getString(KEY_INSTALL_KEY, null)
            val currentState = prefs.getString(KEY_INSTALL_STATE, null)
            val updatedAt = prefs.getLong(KEY_INSTALL_UPDATED_AT, 0L)
            val runningFresh = currentState == INSTALL_STATE_RUNNING && updatedAt > 0L &&
                System.currentTimeMillis().let { now ->
                    now >= updatedAt && now - updatedAt < INSTALL_RUNNING_STALE_MS
                }
            if (currentKey == key) {
                return when (currentState) {
                    // 调用方已经先复核待更新目录；走到这里说明 journal 的成功状态
                    // 已失去对应产物，不能再把它当作真实安装成功。
                    INSTALL_STATE_SUCCEEDED -> InstallClaim.PREVIOUSLY_FAILED
                    INSTALL_STATE_RUNNING -> if (runningFresh) {
                        InstallClaim.ALREADY_RUNNING
                    } else {
                        InstallClaim.PREVIOUSLY_FAILED
                    }
                    INSTALL_STATE_FAILED -> InstallClaim.PREVIOUSLY_FAILED
                    else -> InstallClaim.STARTED
                }.also {
                    if (it == InstallClaim.STARTED) {
                        persistInstallStateLocked(prefs, key, INSTALL_STATE_RUNNING, zip, versionCode)
                    }
                }
            }
            if (runningFresh) return InstallClaim.ALREADY_RUNNING
            persistInstallStateLocked(prefs, key, INSTALL_STATE_RUNNING, zip, versionCode)
            return InstallClaim.STARTED
        }
    }

    private fun persistInstallState(context: Context, key: String, state: String) {
        synchronized(updateStateLock) {
            persistInstallStateLocked(updatePreferences(context), key, state, null, null)
        }
    }

    private fun persistInstallStateLocked(
        prefs: android.content.SharedPreferences,
        key: String,
        state: String,
        zip: File?,
        versionCode: Int?
    ) {
        val editor = prefs.edit()
            .putString(KEY_INSTALL_KEY, key)
            .putString(KEY_INSTALL_STATE, state)
            .putLong(KEY_INSTALL_UPDATED_AT, System.currentTimeMillis())
        if (state != INSTALL_STATE_SUCCEEDED) {
            editor.remove(KEY_INSTALL_BOOT_ID).remove(KEY_INSTALL_LOG).remove(KEY_INSTALL_DIGEST)
        }
        zip?.let { editor.putString(KEY_INSTALL_ZIP, it.absolutePath) }
        versionCode?.let { editor.putInt(KEY_INSTALL_VERSION_CODE, it) }
        editor.commit()
    }

    private fun pendingModuleIsComplete(expectedVersionCode: Int): Boolean {
        val result = DaemonBridge.runRootCommand(pendingModuleCheckScript(expectedVersionCode))
        return result.success && result.output.trim() == "1"
    }

    internal fun pendingModuleCheckScript(expectedVersionCode: Int): String = """
            found=0
            for dir in '$PENDING_MODULE_DIR' '$MODULE_DIR'; do
                # 仅有当前生效副本，不能证明存在待生效更新。
                [ "${'$'}dir" = '$MODULE_DIR' ] && [ ! -f "${'$'}dir/update" ] && continue
                prop="${'$'}dir/module.prop"
                id=${'$'}(sed -n 's/^id=//p' "${'$'}prop" 2>/dev/null | head -n 1)
                code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
                if [ -f "${'$'}prop" ] && [ "${'$'}id" = 'QixiaThreads' ] && [ "${'$'}code" = '$expectedVersionCode' ] &&
                    [ -f "${'$'}dir/service.sh" ] &&
                    [ -f "${'$'}dir/config/bin/QiXiaRs" ] &&
                    [ -f "${'$'}dir/config/ebpf/queuebuffer_probe.bpf.o" ] &&
                    [ -f "${'$'}dir/config/ebpf/queuebuffer_probe_stats.bpf.o" ] &&
                    [ -f "${'$'}dir/config/ebpf/queuebuffer_probe_perf.bpf.o" ]; then
                    found=1
                    break
                fi
            done
            printf "${'$'}found"
        """.trimIndent()

    private fun validateModuleZip(zip: File, update: UpdateInfo) {
        validateModuleZip(zip, update.remoteVersionCode, update.remoteVersion)
    }

    private fun validateModuleZip(zip: File, expectedVersionCode: Int) {
        validateModuleZip(zip, expectedVersionCode, null)
    }

    private fun validateModuleZip(zip: File, expectedVersionCode: Int, expectedVersion: String?) {
        if (!zip.exists() || zip.length() <= 0L) {
            throw UpdateException("模块 zip 不存在或为空")
        }
        val required = linkedSetOf(
            "module.prop",
            "service.sh",
            "customize.sh",
            "META-INF/com/google/android/update-binary",
            "META-INF/com/google/android/updater-script"
        )
        val abi = currentAbiDirectory()
        required += "config/bin/$abi/QiXiaRs"
        required += "config/ebpf/$abi/queuebuffer_probe.bpf.o"
        required += "config/ebpf/$abi/queuebuffer_probe_stats.bpf.o"
        required += "config/ebpf/$abi/queuebuffer_probe_perf.bpf.o"

        var moduleProp: String? = null
        val entries = linkedSetOf<String>()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').removePrefix("./")
                    if (name.startsWith('/') || name.split('/').any { it == ".." }) {
                        throw UpdateException("模块 zip 包含非法路径：${entry.name}")
                    }
                    if (!entry.isDirectory) {
                        entries += name
                        if (name == "module.prop") {
                            if (entry.size > 128 * 1024L) {
                                throw UpdateException("模块 zip 的 module.prop 过大")
                            }
                            moduleProp = readLimitedText(input, 128 * 1024)
                        }
                    }
                    input.closeEntry()
                }
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("模块 zip 无法读取：${e.message ?: "压缩包损坏"}", e)
        }

        val missing = required.filterNot(entries::contains)
        if (missing.isNotEmpty()) {
            throw UpdateException("模块 zip 缺少必要文件：${missing.joinToString(", ")}")
        }
        val props = moduleProp?.let(::parseProps)
            ?: throw UpdateException("模块 zip 缺少 module.prop")
        if (props["id"] != "QixiaThreads") {
            throw UpdateException("模块 zip 的 id 不是 QixiaThreads")
        }
        val actualCode = props["versionCode"]?.toIntOrNull()
            ?: throw UpdateException("模块 zip 的 versionCode 无效")
        if (actualCode != expectedVersionCode) {
            throw UpdateException(
                "模块 zip 版本不匹配：目标 $expectedVersionCode，实际 $actualCode"
            )
        }
        val expectedName = expectedVersion?.removePrefix("v")?.trim().orEmpty()
        val actualName = props["version"]?.removePrefix("v")?.trim().orEmpty()
        if (expectedName.isNotBlank() && actualName.isNotBlank() && expectedName != actualName) {
            throw UpdateException("模块 zip 版本名称不匹配：目标 $expectedName，实际 $actualName")
        }
    }

    private fun currentAbiDirectory(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi == "arm64-v8a" -> "arm64-v8a"
            abi == "armeabi-v7a" || abi == "armeabi" -> "armeabi-v7a"
            abi == "x86_64" -> "x86_64"
            abi == "x86" -> "x86"
            else -> throw UpdateException("不支持的设备 ABI：$abi")
        }
    }

    /** API 31 没有可直接调用的 readNBytes；手动读取并限制 module.prop 大小。 */
    private fun readLimitedText(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw UpdateException("模块 zip 的 module.prop 过大")
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun downloadReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "无法继续下载"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "目标文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据异常"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常"
            DownloadManager.ERROR_UNKNOWN -> "未知错误"
            else -> "错误码 $reason"
        }
    }

    private fun detectRootManager(): RootManager? {
        val result = DaemonBridge.runRootCommand(rootManagerDetectionScript())
        val kernelSuPath = if (result.output.trim() == "kernelsu-bin") {
            "/data/adb/ksu/bin/ksud"
        } else {
            "/data/adb/ksud"
        }
        return when (result.output.trim()) {
            "kernelsu", "kernelsu-bin" -> RootManager(
                label = "KernelSU",
                installCommand = { zip, inAppUpdate ->
                    inAppInstallCommand(
                        "$kernelSuPath module install ${shellQuote(zip)} 2>&1",
                        inAppUpdate
                    )
                },
                displayCommand = { zip, inAppUpdate ->
                    inAppDisplayCommand("$kernelSuPath module install $zip", inAppUpdate)
                }
            )
            "magisk" -> RootManager(
                label = "Magisk",
                installCommand = { zip, inAppUpdate ->
                    inAppInstallCommand(
                        "/data/adb/magisk/magisk --install-module ${shellQuote(zip)} 2>&1",
                        inAppUpdate
                    )
                },
                displayCommand = { zip, inAppUpdate ->
                    inAppDisplayCommand("/data/adb/magisk/magisk --install-module $zip", inAppUpdate)
                }
            )
            "apatch" -> RootManager(
                label = "APatch",
                installCommand = { zip, inAppUpdate ->
                    inAppInstallCommand(
                        "/data/adb/apd module install ${shellQuote(zip)} 2>&1",
                        inAppUpdate
                    )
                },
                displayCommand = { zip, inAppUpdate ->
                    inAppDisplayCommand("/data/adb/apd module install $zip", inAppUpdate)
                }
            )
            else -> null
        }
    }

    internal fun rootManagerDetectionScript(): String = """
            # 优先使用为当前 shell 授予 Root 的提供方，不依据残留的可执行文件判断。
            root_version=${'$'}(su -v 2>/dev/null)
            case "${'$'}root_version" in
                *APatch*|*apatch*)
                    [ -x '/data/adb/apd' ] && printf 'apatch'
                    exit ;;
                *KernelSU*|*kernelsu*|*KSU*)
                    if [ -x '/data/adb/ksud' ]; then printf 'kernelsu'
                    elif [ -x '/data/adb/ksu/bin/ksud' ]; then printf 'kernelsu-bin'; fi
                    exit ;;
                *MAGISK*|*Magisk*|*magisk*)
                    [ -x '/data/adb/magisk/magisk' ] && printf 'magisk'
                    exit ;;
            esac
            if [ -x '/data/adb/ksud' ]; then
                printf 'kernelsu'
            elif [ -x '/data/adb/ksu/bin/ksud' ]; then
                printf 'kernelsu-bin'
            elif [ -x '/data/adb/magisk/magisk' ]; then
                printf 'magisk'
            elif [ -x '/data/adb/apd' ]; then
                printf 'apatch'
            fi
            """.trimIndent()

    private data class RootManager(
        val label: String,
        val installCommand: (String, Boolean) -> String,
        val displayCommand: (String, Boolean) -> String
    )

    private fun parseModuleProp(text: String): ModuleProp? {
        val props = parseProps(text)
        val versionCode = props["versionCode"]?.toIntOrNull() ?: return null
        return ModuleProp(
            version = props["version"].orEmpty().ifBlank { versionCode.toString() },
            versionCode = versionCode,
            updateJson = props["updateJson"]
        )
    }

    private fun parseProps(text: String): Map<String, String> {
        val props = linkedMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            props[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return props
    }

    private fun parseRemoteUpdate(text: String): RemoteUpdate? {
        val json = JSONObject(text)
        val versionCode = when (val value = json.opt("versionCode")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: -1
            else -> -1
        }
        val zipUrl = json.optString("zipUrl").trim()
        if (versionCode <= 0) return null
        return RemoteUpdate(
            version = json.optString("version").trim().ifBlank { versionCode.toString() },
            versionCode = versionCode,
            zipUrl = zipUrl,
            changelogUrl = json.optString("changelog").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "QixiaThreads/${DaemonBridge.REQUIRED_MODULE_VERSION_NAME}")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException("HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun inAppInstallCommand(command: String, inAppUpdate: Boolean): String {
        if (!inAppUpdate) return command
        return "trap 'rm -f $IN_APP_UPDATE_FLAG_PATH' EXIT; mkdir -p /data/adb; printf '1\\n' > $IN_APP_UPDATE_FLAG_PATH; $command; rc=\$?; rm -f $IN_APP_UPDATE_FLAG_PATH; exit \$rc"
    }

    private fun inAppDisplayCommand(command: String, inAppUpdate: Boolean): String {
        if (!inAppUpdate) return command
        return "$IN_APP_UPDATE_ENV=1 $command"
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun safeFilePart(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "update" }
    }

    private class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private class InvalidModuleException(message: String) : Exception(message)
    private class SystemDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private class DownloadCancelledException : Exception()
}
