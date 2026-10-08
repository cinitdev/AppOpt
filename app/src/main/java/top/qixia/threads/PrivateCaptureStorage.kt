package top.qixia.threads

import android.app.Application
import android.content.Context
import java.io.File

/** 注册实际用户的凭据加密私有文件目录，不创建收件目录。 */
internal object PrivateCaptureStorage {
    const val REGISTRATION = "/data/adb/modules/QixiaThreads/config/state/app_storage.conf"
    @Volatile private var context: Context? = null
    fun initialize(value: Context) { context = value.applicationContext }
    val root: File get() = File(checkNotNull(context) { "Application storage unavailable" }.filesDir, "capture")
    val history: File get() = File(root, "history")
    val automatic: File get() = File(root, "auto_history")
    val drafts: File get() = File(root, "calibration_drafts")

    fun register(): Boolean {
        val app = context ?: return false
        val files = app.filesDir.canonicalFile
        val uid = app.applicationInfo.uid
        val value = "version=1\nuid=$uid\nfiles=${files.path}\n"
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        return DaemonBridge.runRootCommand("""
            [ -d '${REGISTRATION.substringBeforeLast('/')}' ] || exit 1
            [ -d ${quote(files.path)} ] || exit 1
            [ "${'$'}(stat -c %u ${quote(files.path)})" = '$uid' ] || exit 1
            desired=${quote(value.trimEnd())}
            [ "${'$'}(cat '$REGISTRATION' 2>/dev/null)" = "${'$'}desired" ] && exit 0
            temporary='$REGISTRATION.'${'$'}${'$'}'.tmp'
            trap 'rm -f "${'$'}temporary"' EXIT
            umask 077
            printf '%s\n' "${'$'}desired" > "${'$'}temporary" || exit 1
            mv -f "${'$'}temporary" '$REGISTRATION'
        """.trimIndent()).success
    }
}

class QixiaThreadsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivateCaptureStorage.initialize(this)
        SceneBlacklistSync.initialize(this)
    }
}
