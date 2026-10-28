package top.qixia.threads

/** 复用安装器的热更新事务；页面退出不打断文件切换或回退。 */
internal object ModuleHotUpdate {
    private const val ACTIVE = "/data/adb/modules/QixiaThreads"
    private const val STAGED = "/data/adb/modules_update/QixiaThreads"
    private const val STATUS = "$ACTIVE/config/state/module_update.state"
    private const val JOURNAL = "/data/adb/qixia_hot_update/status"
    private const val SCRIPT = "config/tools/qixia_hot_update.sh"

    data class Result(val applied: Boolean, val message: String)

    fun currentRequest(): String = fields(DaemonBridge.runRootCommand(
        "cat '$JOURNAL' 2>/dev/null || cat '$STATUS'", timeoutSeconds = 5L
    ).output)["request"].orEmpty()

    fun apply(versionCode: Int, progress: (String) -> Unit): Result {
        if (isApplied(versionCode)) return Result(true, "模块已热更新生效，无需重启手机")
        val launch = DaemonBridge.runRootCommand("sh '$STAGED/$SCRIPT' --queue 2>&1", timeoutSeconds = 10L)
        val request = fields(launch.output)["request"].orEmpty()
        if (!launch.success || request.isBlank()) {
            // 安装器可能刚完成收尾并删除暂存脚本，此时直接验证当前服务。
            if (isApplied(versionCode)) return Result(true, "模块已热更新生效，无需重启手机")
            launch.output.trim().takeIf { it.isNotEmpty() }?.let(progress)
            return Result(false, "模块已刷入；无法启动热更新，重启后生效")
        }
        var lastMessage = ""
        repeat(120) {
            // 运行中读取事务进度；临时目录清理后从模块状态恢复最终结果。
            val result = DaemonBridge.runRootCommand("cat '$JOURNAL' 2>/dev/null || cat '$STATUS'", timeoutSeconds = 5L)
            val state = fields(result.output)
            if (result.success && state["request"] == request) {
                val message = state["reason"].orEmpty()
                if (message.isNotBlank() && message != lastMessage) {
                    progress(message)
                    lastMessage = message
                }
                when (state["phase"]) {
                    "applied" -> if (isApplied(versionCode)) {
                        return Result(true, "模块已热更新生效，无需重启手机")
                    } else return Result(false, "新版运行检查未通过，请重启完成更新")
                    "deferred", "rollback_failed" -> return Result(false, "$message；重启后生效")
                    "failed" -> return Result(false, message.ifBlank { "热更新未完成，请查看刷入日志" })
                }
            }
            Thread.sleep(1000L)
        }
        return Result(false, "热更新尚未确认完成，请稍后重新查看安装结果；必要时重启生效")
    }

    fun isApplied(versionCode: Int): Boolean {
        val result = DaemonBridge.runRootCommand(appliedCheckScript(versionCode), timeoutSeconds = 6L)
        return result.success && result.output.lineSequence().any { it == "applied=1" }
    }

    internal fun appliedCheckScript(versionCode: Int) = """
        [ ! -e '$STAGED' ] && [ ! -e '$ACTIVE/update' ] &&
        [ ! -e '$ACTIVE/disable' ] && [ ! -e '$ACTIVE/remove' ] &&
        [ "${'$'}(sed -n 's/^phase=//p' '$STATUS')" = applied ] &&
        [ "${'$'}(sed -n 's/^version=//p' '$STATUS')" = '$versionCode' ] &&
        [ "${'$'}(sed -n 's/^versionCode=//p' '$ACTIVE/module.prop')" = '$versionCode' ] &&
        [ "${'$'}(sed -n 's/^boot=//p' '$STATUS')" = "${'$'}(cat /proc/sys/kernel/random/boot_id)" ] &&
        [ "${'$'}(sed -n 's/^build=//p' '$STATUS')" = "${'$'}(sha256sum '$ACTIVE/config/bin/QiXiaRs' | cut -d ' ' -f 1)" ] &&
        '$ACTIVE/config/bin/QiXiaRs' --check-daemon && printf '\napplied=1\n'
    """.trimIndent()

    fun hasPendingApp(): Boolean = DaemonBridge.runRootCommand(
        "test -f '$ACTIVE/config/app/app.prop' && test ! -e '$STAGED'", timeoutSeconds = 5L
    ).success

    fun queueAppInstall(callerPid: Int): Boolean {
        require(callerPid > 1)
        return DaemonBridge.runRootCommand(
            "sh '$ACTIVE/$SCRIPT' --queue-app '$callerPid'", timeoutSeconds = 5L
        ).success
    }

    private fun fields(text: String): Map<String, String> = text.lineSequence().mapNotNull {
        val separator = it.indexOf('=')
        if (separator > 0) it.substring(0, separator) to it.substring(separator + 1) else null
    }.toMap()
}
