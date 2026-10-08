package top.qixia.threads.compose

import top.qixia.threads.ModuleUpdateDownloadViewModel
import top.qixia.threads.ModuleUpdater

internal enum class ModuleUpdateTone { INFO, SUCCESS, WARNING, ERROR }

internal data class ModuleUpdateCardState(
    val localVersion: String,
    val remoteVersion: String,
    val remoteVersionName: String?,
    val remoteVersionCode: Int?,
    val status: String,
    val tone: ModuleUpdateTone
)

internal fun moduleUpdateCardState(
    environment: EnvironmentUiState,
    updates: UpdateUiState,
    download: ModuleUpdateDownloadViewModel.State
): ModuleUpdateCardState {
    val result = updates.moduleResult
    val session = download.update.takeIf { download.hasSession }
    val local = when (result) {
        is ModuleUpdater.CheckResult.UpdateAvailable -> result.update.localVersion to result.update.localVersionCode
        is ModuleUpdater.CheckResult.NoUpdate -> result.localVersion to result.localVersionCode
        is ModuleUpdater.CheckResult.Failed -> result.localVersion to result.localVersionCode
        null -> null
    }
    val remote = when {
        session != null -> session.remoteVersion to session.remoteVersionCode
        result is ModuleUpdater.CheckResult.UpdateAvailable -> result.update.remoteVersion to result.update.remoteVersionCode
        result is ModuleUpdater.CheckResult.NoUpdate -> result.remoteVersion to result.remoteVersionCode
        result is ModuleUpdater.CheckResult.Failed -> result.remoteVersion to result.remoteVersionCode
        else -> null
    }
    val (status, tone) = when {
        environment.pendingModuleUpdate -> "模块已刷入，重启设备后生效" to ModuleUpdateTone.WARNING
        session != null -> download.status.ifBlank { "有未完成的模块更新，点击查看详情" } to
            if (download.stage == ModuleUpdateDownloadViewModel.Stage.FAILED) ModuleUpdateTone.ERROR else ModuleUpdateTone.INFO
        updates.checkingModule -> "正在读取云端版本信息…" to ModuleUpdateTone.INFO
        result is ModuleUpdater.CheckResult.UpdateAvailable -> "发现新版本，可查看更新内容" to ModuleUpdateTone.INFO
        result is ModuleUpdater.CheckResult.Failed -> result.message to ModuleUpdateTone.ERROR
        result is ModuleUpdater.CheckResult.NoUpdate -> result.message to when {
            result.pendingReboot -> ModuleUpdateTone.WARNING
            result.remoteVersionCode != null && result.localVersionCode != null &&
                result.remoteVersionCode > result.localVersionCode -> ModuleUpdateTone.WARNING
            result.remoteVersion != null -> ModuleUpdateTone.SUCCESS
            else -> ModuleUpdateTone.INFO
        }
        else -> "启动后在后台检查更新，也可手动刷新" to ModuleUpdateTone.INFO
    }
    return ModuleUpdateCardState(
        localVersion = moduleVersionText(local?.first ?: environment.moduleVersion?.versionName,
            local?.second ?: environment.moduleVersion?.versionCode,
            if (environment.loading || updates.checkingModule) "读取中…" else "未检测到"),
        remoteVersion = moduleVersionText(remote?.first, remote?.second,
            if (updates.checkingModule) "读取中…" else "未获取"),
        remoteVersionName = remote?.first?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotEmpty() },
        remoteVersionCode = remote?.second,
        status = status,
        tone = tone
    )
}

private fun moduleVersionText(name: String?, code: Int?, fallback: String): String {
    val version = name?.trim()?.takeIf { it.isNotEmpty() } ?: return code?.let { "版本号 $it" } ?: fallback
    val label = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
    return if (code != null) "$label ($code)" else label
}
