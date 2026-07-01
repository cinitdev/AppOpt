package top.suto.appopt

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object ModuleUpdater {
    private const val MODULE_PROP = "/data/adb/modules/AppOpt/module.prop"
    private const val PENDING_MODULE_PROP = "/data/adb/modules_update/AppOpt/module.prop"
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    private const val INSTALL_TIMEOUT_SECONDS = 180L
    private const val IN_APP_UPDATE_ENV = "APPOPT_IN_APP_UPDATE"
    private const val IN_APP_UPDATE_MARKER_ENTRY = "config/app/.appopt_in_app_update"
    private const val IN_APP_UPDATE_FLAG_PATH = "/data/adb/appopt_in_app_update"

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

    sealed class CheckResult {
        data class UpdateAvailable(val update: UpdateInfo) : CheckResult()
        data class NoUpdate(
            val message: String,
            val localVersion: String? = null,
            val localVersionCode: Int? = null,
            val remoteVersion: String? = null,
            val remoteVersionCode: Int? = null
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
        fun onFailure(message: String)
    }

    interface InstallCallback {
        fun onProgress(message: String, percent: Int?)
        fun onLog(text: String) = Unit
        fun onSuccess(message: String)
        fun onFailure(message: String)
    }

    fun checkForUpdate(): CheckResult {
        if (!DaemonBridge.hasRoot()) {
            return CheckResult.Failed("请先授予 Root 权限")
        }

        val localText = DaemonBridge.readRootFile(MODULE_PROP)
            ?: return CheckResult.Failed("未检测到 AppOpt 模块")
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
        if (pending != null && pending.versionCode >= remote.versionCode) {
            return CheckResult.NoUpdate(
                message = "新版本已刷入，重启后生效",
                localVersion = local.version,
                localVersionCode = local.versionCode,
                remoteVersion = remote.version,
                remoteVersionCode = remote.versionCode
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

    fun downloadModule(context: Context, update: UpdateInfo, callback: DownloadCallback) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        fun progress(message: String, percent: Int? = null) {
            mainHandler.post { callback.onProgress(message, percent) }
        }

        thread(name = "AppOptModuleUpdater") {
            try {
                progress("准备下载模块", 0)
                val zip = downloadWithManager(appContext, update) { message, percent ->
                    progress(message, percent)
                }
                val markedZip = markZipForInAppUpdate(zip)
                progress("下载完成，准备刷入", 100)
                mainHandler.post { callback.onSuccess(markedZip.absolutePath) }
            } catch (e: UpdateException) {
                mainHandler.post { callback.onFailure(e.message ?: "更新失败") }
            } catch (_: Exception) {
                mainHandler.post { callback.onFailure("更新失败，请稍后重试") }
            }
        }
    }

    fun installDownloadedModule(
        zipPath: String,
        callback: InstallCallback,
        inAppUpdate: Boolean = true,
        prepareDelayMs: Long = 0L,
        context: Context? = null
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        fun progress(message: String, percent: Int? = null) {
            mainHandler.post { callback.onProgress(message, percent) }
        }

        fun log(text: String) {
            mainHandler.post { callback.onLog(text) }
        }

        var installZip: File? = null
        fun installFailureMessage(message: String): String {
            val zip = installZip?.takeIf { it.exists() } ?: return message
            if (!inAppUpdate) return message
            val manualZip = retainOriginalZipForManualInstall(context, zip, true, ::log)
            return "$message\n模块已保存到：$manualZip\n请在 Root 管理器中手动刷入"
        }

        thread(name = "AppOptModuleInstaller") {
            try {
                val zip = File(zipPath)
                installZip = zip
                if (!zip.exists()) {
                    mainHandler.post { callback.onFailure("模块 zip 不存在：$zipPath") }
                    return@thread
                }

                progress("正在检测模块管理器", null)
                val manager = detectRootManager()
                if (manager == null) {
                    val manualZip = retainOriginalZipForManualInstall(context, zip, inAppUpdate, ::log)
                    mainHandler.post {
                        callback.onFailure(
                            "没有检测到可用的模块管理器\n模块已保存到：$manualZip\n请手动刷入"
                        )
                    }
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
                if (result.success) {
                    cleanupUpdateZips(zip, inAppUpdate, ::log)
                    val message = if (inAppUpdate) {
                        "模块已刷入，重启后生效；App 将在重启后自动更新"
                    } else {
                        "模块已刷入，重启后生效"
                    }
                    mainHandler.post { callback.onSuccess(message) }
                } else {
                    val manualZip = retainOriginalZipForManualInstall(context, zip, inAppUpdate, ::log)
                    mainHandler.post {
                        callback.onFailure(
                            "刷入失败，原始模块已保存到：$manualZip\n请在 Root 管理器中手动刷入"
                        )
                    }
                }
            } catch (e: UpdateException) {
                val message = installFailureMessage(e.message ?: "刷入失败")
                mainHandler.post { callback.onFailure(message) }
            } catch (_: Exception) {
                val message = installFailureMessage("刷入失败")
                mainHandler.post { callback.onFailure(message) }
            }
        }
    }

    fun detectRootManagerLabel(): String? {
        return detectRootManager()?.label
    }

    fun retainDownloadedModuleForManualInstall(
        context: Context?,
        zipPath: String,
        inAppUpdate: Boolean = true
    ): String {
        val zip = File(zipPath)
        if (!zip.exists()) return zipPath
        return retainOriginalZipForManualInstall(context, zip, inAppUpdate) {}
    }

    fun renderMarkdown(markdown: String): Spanned {
        val builder = SpannableStringBuilder()
        var inCodeBlock = false

        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            val start = builder.length
            when {
                inCodeBlock -> {
                    builder.append(line.ifBlank { " " }).append('\n')
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(Color.parseColor("#1A1A2E")), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("# ") -> {
                    appendInlineMarkdown(builder, line.removePrefix("# ").trim())
                    builder.append('\n')
                    setHeadingSpan(builder, start, 1.22f)
                }
                line.startsWith("## ") -> {
                    appendInlineMarkdown(builder, line.removePrefix("## ").trim())
                    builder.append('\n')
                    setHeadingSpan(builder, start, 1.14f)
                }
                line.startsWith("### ") -> {
                    appendInlineMarkdown(builder, line.removePrefix("### ").trim())
                    builder.append('\n')
                    setHeadingSpan(builder, start, 1.08f)
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val item = line.trimStart().drop(2).trim()
                    builder.append("• ")
                    appendInlineMarkdown(builder, item)
                    builder.append('\n')
                    builder.setSpan(LeadingMarginSpan.Standard(0, 28), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.isBlank() -> builder.append('\n')
                else -> {
                    appendInlineMarkdown(builder, line)
                    builder.append('\n')
                }
            }
        }
        return builder
    }

    private fun setHeadingSpan(builder: SpannableStringBuilder, start: Int, size: Float) {
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(size), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendInlineMarkdown(builder: SpannableStringBuilder, text: String) {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end > index + 2) {
                        val start = builder.length
                        builder.append(text.substring(index + 2, end))
                        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = end + 2
                    } else {
                        builder.append(text[index])
                        index++
                    }
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index + 1) {
                        val start = builder.length
                        builder.append(text.substring(index + 1, end))
                        builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(ForegroundColorSpan(Color.parseColor("#1A1A2E")), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = end + 1
                    } else {
                        builder.append(text[index])
                        index++
                    }
                }
                text[index] == '[' -> {
                    val labelEnd = text.indexOf("](", index)
                    val urlEnd = if (labelEnd > index) text.indexOf(')', labelEnd + 2) else -1
                    if (labelEnd > index && urlEnd > labelEnd + 2) {
                        val label = text.substring(index + 1, labelEnd)
                        val url = text.substring(labelEnd + 2, urlEnd)
                        val start = builder.length
                        builder.append(label)
                        builder.setSpan(URLSpan(url), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(ForegroundColorSpan(Color.parseColor("#5B5BD6")), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = urlEnd + 1
                    } else {
                        builder.append(text[index])
                        index++
                    }
                }
                else -> {
                    builder.append(text[index])
                    index++
                }
            }
        }
    }

    private fun downloadWithManager(
        context: Context,
        update: UpdateInfo,
        onProgress: (String, Int?) -> Unit
    ): File {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw UpdateException("系统下载服务不可用")
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException("下载目录不可用")
        }

        val target = File(
            dir,
            "AppOpt-${safeFilePart(update.remoteVersion)}-${update.remoteVersionCode}-${System.currentTimeMillis()}.zip"
        )
        val request = DownloadManager.Request(Uri.parse(update.zipUrl))
            .setTitle("AppOpt ${update.remoteVersion}")
            .setDescription("正在下载模块更新")
            .setMimeType("application/zip")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))

        val id = manager.enqueue(request)
        val query = DownloadManager.Query().setFilterById(id)
        while (true) {
            manager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            onProgress("下载完成，准备刷入", 100)
                            return target.takeIf { it.exists() } ?: downloadedFile(cursor) ?: target
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw UpdateException("下载失败：${downloadReason(reason)}")
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
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                throw UpdateException("下载已中断")
            }
        }
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
            "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/${source.name}"
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
        val result = DaemonBridge.runRootCommand(
            """
            if [ -x '/data/adb/ksud' ]; then
                printf 'kernelsu'
            elif [ -x '/data/adb/magisk/magisk' ]; then
                printf 'magisk'
            elif [ -x '/data/adb/apd' ]; then
                printf 'apatch'
            fi
            """.trimIndent()
        )
        return when (result.output.trim()) {
            "kernelsu" -> RootManager(
                label = "KernelSU",
                installCommand = { zip, inAppUpdate ->
                    inAppInstallCommand(
                        "/data/adb/ksud module install ${shellQuote(zip)} 2>&1",
                        inAppUpdate
                    )
                },
                displayCommand = { zip, inAppUpdate ->
                    inAppDisplayCommand("/data/adb/ksud module install $zip", inAppUpdate)
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
            setRequestProperty("User-Agent", "AppOpt/${DaemonBridge.REQUIRED_MODULE_VERSION_NAME}")
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

    private class UpdateException(message: String) : Exception(message)
}
