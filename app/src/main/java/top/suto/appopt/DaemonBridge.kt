package top.suto.appopt

import android.net.LocalServerSocket
import android.os.SystemClock
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * 与 C 守护进程的 IPC 桥接。
 *
 * 守护进程运行在 /data/adb/modules/AppOpt/ 下, 该目录普通应用无权限读写,
 * 因此命令/状态文件的读写全部通过 root (su) 执行; 高频数据和守护验证走 socket。
 *
 * 校准协议:
 *   App  -> 守护:  写 calibrate.cmd, 内容 "start <pkg>" / "stop <pkg>"
 *   守护 -> App:   写 calibrate.state, 内容 "idle" / "sampling <pkg>" / "done <pkg>"
 *
 * FPS 协议:
 *   App  -> 守护:  写 fps.cmd, 内容 "start <pkg> [socket token]" / "stop"
 *   守护 -> App:   优先通过 Android 本地 socket 推送 FPS; socket 不可用时回退 fps 文件。
 *
 * 守护验证:
 *   App  -> su:    创建一次性本地 socket, 通过 root helper 把 socket 名和随机 token 发给守护
 *   守护 -> App:   反连这个一次性 socket 并回传 token/版本/PID, 避免 App 直连 root daemon 被 SELinux 拦截。
 */
object DaemonBridge {

    private const val MODULE_DIR = "/data/adb/modules/AppOpt"
    private const val MODULE_UPDATE_DIR = "/data/adb/modules_update/AppOpt"
    private const val CONFIG_DIR = "$MODULE_DIR/config"
    private const val BIN_FILE = "$CONFIG_DIR/bin/AppOpt"
    private const val BIN_RS_FILE = "$CONFIG_DIR/bin/AppOptRs"
    private const val RS_FALLBACK_FILE = "$CONFIG_DIR/.appopt_use_c_daemon"
    private const val LOG_DIR = "$MODULE_DIR/logs"
    private const val UPDATE_CONFIG_DIR = "$MODULE_UPDATE_DIR/config"
    private const val CMD_FILE = "$CONFIG_DIR/calibrate.cmd"
    private const val STATE_FILE = "$CONFIG_DIR/calibrate.state"
    private const val CONFIG_FILE = "$CONFIG_DIR/applist.conf"
    private const val RULE_HEALTH_FILE = "$CONFIG_DIR/rule_health.tsv"
    private const val CONFIG_LOCK_DIR = "$CONFIG_DIR/applist.conf.lock"
    private const val POLICY_FILE = "$CONFIG_DIR/calib_policy.conf"
    private const val POLICY_LOCK_DIR = "$CONFIG_DIR/calib_policy.conf.lock"
    private const val POLICY_UPDATE_FILE = "$UPDATE_CONFIG_DIR/calib_policy.conf"
    private const val HISTORY_DIR = "$MODULE_DIR/history"
    private const val LOG_FILE = "$LOG_DIR/AppOpt.log"
    private const val FPS_CMD_FILE = "$CONFIG_DIR/fps.cmd"
    private const val FOREGROUND_TASK_STATE_FILE = "$CONFIG_DIR/foreground_task.state"
    private const val FOREGROUND_HELPER_SCRIPT = "$CONFIG_DIR/tools/appopt_foreground_helper.sh"
    private const val FOREGROUND_TASK_MAX_AGE_MS = 12_000L
    private const val DAEMON_SOCKET_CALLBACK_PREFIX = "appopt.callback top.suto.appopt v1 "
    private const val ROOT_TIMEOUT_SECONDS = 15L
    const val REQUIRED_MODULE_VERSION_CODE = 178
    const val REQUIRED_MODULE_VERSION_NAME = "1.7.8"
    private val configMutationLock = Any()

    /** 检测设备是否有可用 root；首次调用可能触发 Magisk 授权弹窗。 */
    fun hasRoot(): Boolean = runAsRoot("id -u").trim() == "0"

    fun hasPendingModuleUpdate(): Boolean {
        return runAsRoot("[ -d '$MODULE_UPDATE_DIR' ] && printf 1 || printf 0")
            .trim() == "1"
    }

    data class ModuleVersion(
        val versionName: String,
        val versionCode: Int,
        val binaryVersionName: String?,
        val raw: String
    )

    data class RootCommandResult(
        val output: String,
        val success: Boolean
    )

    data class TopAppState(
        val targetTopApp: Boolean,
        val pid: Int?,
        val scanned: Int,
        val packages: List<String>,
        val backend: String = "cgroup-top"
    )

    data class TaskForegroundState(
        val available: Boolean,
        val status: String,
        val mode: String,
        val packageName: String?,
        val activityName: String?,
        val taskId: Int?,
        val displayId: Int?,
        val visiblePackages: List<String>,
        val ageMs: Long?,
        val generation: Long?,
        val reason: String,
        val selection: String,
        val error: String,
        val raw: String
    )

    data class DaemonRuntime(
        val running: Boolean,
        val kind: String? = null,
        val versionName: String? = null,
        val pid: Int? = null,
        val raw: String = ""
    ) {
        val kindLabel: String?
            get() = when (kind?.lowercase(Locale.ROOT)) {
                "rust", "rs", "appoptrs" -> "Rust 版"
                "c", "native", "appopt" -> "C 版"
                else -> null
            }
    }

    enum class RuleHealthStatus {
        VALID,
        MISSED,
        PENDING
    }

    data class RuleHealth(
        val kind: String,
        val owner: String,
        val target: String?,
        val status: RuleHealthStatus,
        val missCount: Int,
        val firstObservedAt: Long,
        val lastMatchedAt: Long,
        val lastCheckedAt: Long,
        val ruleLine: String
    ) {
        val key: String
            get() = ruleHealthKey(kind, owner, target)
    }

    fun readRuleHealth(): Map<String, RuleHealth> = readRuleHealthOrNull().orEmpty()

    fun readRuleHealthOrNull(): Map<String, RuleHealth>? {
        val result = readRootCommandResult(
            "if [ -f '$RULE_HEALTH_FILE' ]; then cat '$RULE_HEALTH_FILE' || exit 1; " +
                "elif [ ! -e '$RULE_HEALTH_FILE' ]; then :; else exit 1; fi"
        )
        if (!result.success) return null
        val content = result.output
        if (content.isBlank()) return emptyMap()
        return parseRuleHealth(content)
    }

    internal fun parseRuleHealth(text: String): Map<String, RuleHealth> {
        val result = LinkedHashMap<String, RuleHealth>()
        for (raw in text.lineSequence()) {
            val parts = raw.split('\t')
            if (parts.size < 9) continue
            val kind = parts[0]
            val owner = unescapeRuleHealthField(parts[1])
            val target = unescapeRuleHealthField(parts[2]).ifBlank { null }
            val status = when (parts[3]) {
                "valid" -> RuleHealthStatus.VALID
                "missed" -> RuleHealthStatus.MISSED
                else -> RuleHealthStatus.PENDING
            }
            val health = RuleHealth(
                kind = kind,
                owner = owner,
                target = target,
                status = status,
                missCount = parts[4].toIntOrNull() ?: 0,
                firstObservedAt = parts[5].toLongOrNull() ?: 0L,
                lastMatchedAt = parts[6].toLongOrNull() ?: 0L,
                lastCheckedAt = parts[7].toLongOrNull() ?: 0L,
                ruleLine = unescapeRuleHealthField(
                    parts.drop(if (parts.size >= 11) 10 else 8).joinToString("\t")
                )
            )
            result[health.key] = health
        }
        return result
    }

    fun ruleHealthKey(kind: String, owner: String, target: String?): String {
        return "${kind.uppercase(Locale.ROOT)}\t${owner.trim()}\t${target.orEmpty().trim()}"
    }

    fun ruleHealthKey(owner: String, thread: String?): String? {
        return when {
            thread != null -> ruleHealthKey("T", owner, thread)
            owner.contains(':') -> ruleHealthKey("P", owner, null)
            else -> null
        }
    }

    internal fun unescapeRuleHealthField(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch != '\\' || index >= value.length) {
                output.append(ch)
                continue
            }
            when (val escaped = value[index++]) {
                't' -> output.append('\t')
                'n' -> output.append('\n')
                '\\' -> output.append('\\')
                else -> output.append('\\').append(escaped)
            }
        }
        return output.toString()
    }
    fun readRootFile(path: String): String? {
        val result = readRootCommandResult("cat ${shellQuote(path)} 2>/dev/null")
        return result.output.takeIf { result.success }
    }

    private fun readRootCommandResult(
        cmd: String,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS
    ): RootCommandResult {
        return runAsRootStreaming(cmd, timeoutSeconds) { }
    }

    fun runRootCommand(cmd: String, timeoutSeconds: Long = 15L): RootCommandResult {
        val out = runAsRoot(cmd, timeoutSeconds.coerceAtLeast(1L))
        return RootCommandResult(
            output = out.substringBefore(ERR_MARK),
            success = out.isNotErrored()
        )
    }

    fun runRootCommandStreaming(
        cmd: String,
        timeoutSeconds: Long = 15L,
        onOutput: (String) -> Unit
    ): RootCommandResult {
        return runAsRootStreaming(cmd, timeoutSeconds.coerceAtLeast(1L), onOutput)
    }

    /**
     * 读取已刷入模块版本。兼容性判断只看 module.prop 的 versionCode；
     * C 二进制 `AppOpt -v` 仅作为辅助显示信息, 不决定模块版本是否合格。
     */
    fun readModuleVersion(): ModuleVersion? {
        val binSelect = daemonBinarySelectShell()
        val cmd = """
            prop="$MODULE_DIR/module.prop"
            prop_code=
            prop_version=
            bin_version=
            [ -f "${'$'}prop" ] && prop_code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            [ -f "${'$'}prop" ] && prop_version=${'$'}(sed -n 's/^version=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            daemon_bin=${'$'}($binSelect)
            if [ -x "${'$'}daemon_bin" ]; then
                bin_out=$("${'$'}daemon_bin" -v 2>/dev/null)
                bin_version=${'$'}(printf '%s\n' "${'$'}bin_out" | sed -n 's/.*AppOpt 版本[[:space:]]*//p' | tail -n 1)
            fi
            printf 'propCode=%s\npropVersion=%s\nbinVersion=%s\n' "${'$'}prop_code" "${'$'}prop_version" "${'$'}bin_version"
        """.trimIndent()
        val out = runAsRoot(cmd)
        if (!out.isNotErrored()) return null
        val values = out.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
            }
            .toMap()
        val binVersion = values["binVersion"].orEmpty().removePrefix("v").trim()
        val propVersion = values["propVersion"].orEmpty().removePrefix("v").trim()
        val code = values["propCode"]?.toIntOrNull() ?: return null
        val name = propVersion.takeIf { it.isNotBlank() }
            ?: binVersion.takeIf { it.isNotBlank() }
            ?: code.toString()
        return ModuleVersion(
            versionName = name,
            versionCode = code,
            binaryVersionName = binVersion.takeIf { it.isNotBlank() },
            raw = out
        )
    }

    /**
     * 检测当前运行的守护进程是否确实是本 App 可交互的 AppOpt。
     *
     * 这里不使用 pgrep 判断进程名，因为开源后二改版本也可能叫 AppOpt。
     * 只有守护进程能按随机 token 反连 App 的一次性 socket，才认为验证通过。
     */
    fun isDaemonRunning(): Boolean = readDaemonRuntime().running

    fun readDaemonRuntime(): DaemonRuntime {
        val runtime = verifyDaemonSocketReverse()
        if (!runtime.running || runtime.kind != null) return runtime
        val inferredKind = inferDaemonKindByPid(runtime.pid)
        return if (inferredKind != null) runtime.copy(kind = inferredKind) else runtime
    }

    private fun verifyDaemonSocketReverse(): DaemonRuntime {
        val socketName = "appopt_verify_${android.os.Process.myPid()}_${System.nanoTime()}"
        val token = UUID.randomUUID().toString().replace("-", "")
        val callbackRuntime = AtomicReference<DaemonRuntime?>(null)
        var server: LocalServerSocket? = null

        return try {
            val localServer = LocalServerSocket(socketName)
            server = localServer

            val acceptThread = Thread({
                try {
                    localServer.accept().use { socket ->
                        socket.soTimeout = 2500
                        val line = BufferedReader(
                            InputStreamReader(socket.inputStream, Charsets.UTF_8)
                        ).readLine()?.trim().orEmpty()
                        callbackRuntime.set(parseDaemonCallback(line, token))
                    }
                } catch (_: Exception) {
                    callbackRuntime.set(null)
                }
            }, "AppOptDaemonVerify").apply {
                isDaemon = true
                start()
            }

            val rootThread = Thread({
                val binSelect = daemonBinarySelectShell()
                runAsRoot("daemon_bin=\$($binSelect); \"\$daemon_bin\" --ping-daemon '$socketName' '$token' 2>/dev/null")
            }, "AppOptDaemonPing").apply {
                isDaemon = true
                start()
            }

            acceptThread.join(3000)
            val runtime = callbackRuntime.get()
            if (runtime?.running != true) {
                try { localServer.close() } catch (_: Exception) {}
                acceptThread.join(300)
            }
            rootThread.join(500)
            runtime ?: DaemonRuntime(running = false)
        } catch (_: Exception) {
            DaemonRuntime(running = false)
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun parseDaemonCallback(line: String, token: String): DaemonRuntime? {
        if (!line.startsWith(DAEMON_SOCKET_CALLBACK_PREFIX)) return null
        if (callbackField(line, "token") != token) return null
        val version = callbackField(line, "version")?.removePrefix("v") ?: return null
        val pid = callbackField(line, "pid")?.toIntOrNull() ?: return null
        val versionCode = versionNameToCode(version) ?: return null
        if (versionCode < REQUIRED_MODULE_VERSION_CODE) return null
        val kind = callbackField(line, "kind")
            ?: callbackField(line, "impl")
            ?: callbackField(line, "name")
        return DaemonRuntime(
            running = true,
            kind = normalizeDaemonKind(kind),
            versionName = version,
            pid = pid,
            raw = line
        )
    }

    private fun callbackField(line: String, key: String): String? {
        val prefix = "$key="
        return line.splitToSequence(' ')
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.isNotBlank() }
    }

    private fun inferDaemonKindByPid(pid: Int?): String? {
        if (pid == null || pid <= 0) return null
        val out = runAsRoot("cat /proc/$pid/comm 2>/dev/null")
        if (!out.isNotErrored()) return null
        return normalizeDaemonKind(out.substringBefore('\n').trim())
    }

    private fun normalizeDaemonKind(kind: String?): String? {
        return when (kind?.trim()?.lowercase(Locale.ROOT)) {
            "rust", "rs", "appoptrs" -> "rust"
            "c", "native", "appopt" -> "c"
            else -> null
        }
    }

    /**
     * 批量判断非 APK 配置项是否对应正在运行的系统进程。
     * 用于 UI 区分“系统组件”和“未安装/配置残留”。匹配流程是 pidof + pgrep -x
     * 获取候选 PID，然后反查 /proc/<pid>/comm 与 cmdline 做精确确认。
     */
    fun findRunningProcessNames(names: Collection<String>): Set<String> {
        val targets = names.map { it.trim().replace("'", "") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return emptySet()

        val targetArgs = targets.joinToString(" ") { "'$it'" }
        val cmd = """
            nl='
            '
            targets="${'$'}nl"
            for target in $targetArgs; do
                targets="${'$'}targets${'$'}target${'$'}nl"
            done
            found="${'$'}nl"

            contains_line() {
                haystack="${'$'}1"
                needle="${'$'}2"
                case "${'$'}haystack" in
                    *"${'$'}nl${'$'}needle${'$'}nl"*) return 0 ;;
                    *) return 1 ;;
                esac
            }

            emit_if_target() {
                name="${'$'}1"
                [ -z "${'$'}name" ] && return
                contains_line "${'$'}targets" "${'$'}name" || return
                contains_line "${'$'}found" "${'$'}name" && return
                printf '%s\n' "${'$'}name"
                found="${'$'}found${'$'}name${'$'}nl"
            }

            verify_pid_target() {
                pid="${'$'}1"
                target="${'$'}2"
                case "${'$'}pid" in
                    ''|*[!0-9]*) return 1 ;;
                esac

                comm=${'$'}(cat "/proc/${'$'}pid/comm" 2>/dev/null)
                first=${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/cmdline" 2>/dev/null | head -n 1)
                base=${'$'}{first##*/}

                if [ "${'$'}comm" = "${'$'}target" ] ||
                   [ "${'$'}first" = "${'$'}target" ] ||
                   [ "${'$'}base" = "${'$'}target" ]; then
                    emit_if_target "${'$'}target"
                    return 0
                fi
                return 1
            }

            for target in $targetArgs; do
                pids="${'$'}(pidof "${'$'}target" 2>/dev/null) ${'$'}(pgrep -x "${'$'}target" 2>/dev/null)"
                for pid in ${'$'}pids; do
                    verify_pid_target "${'$'}pid" "${'$'}target" && break
                done
            done
            true
        """.trimIndent()
        val out = runAsRoot(cmd)
        val clean = out.substringBefore(ERR_MARK)
        return clean.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun readTopAppState(pkg: String): TopAppState {
        val safePkg = cleanCommandArg(pkg.substringBefore(':'), allowColon = false)
        if (safePkg.isBlank()) {
            return TopAppState(false, null, 0, emptyList(), backend = "invalid")
        }
        val binSelect = daemonBinarySelectShell()
        val cmd = buildString {
            append("daemon_bin=$(")
            append(binSelect)
            append("); \"\$daemon_bin\"")
            append(" --app-state ")
            append(shellQuote(safePkg))
        }
        val out = runAsRoot(cmd, timeoutSeconds = 5L).substringBefore(ERR_MARK)
        val values = out.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        if (values.isEmpty()) return TopAppState(false, null, 0, emptyList(), backend = "empty")
        val packages = values["packages"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return TopAppState(
            targetTopApp = values["target_top_app"] == "1" || values["top_app"] == "1",
            pid = values["pid"]?.toIntOrNull()?.takeIf { it > 0 },
            scanned = values["scanned"]?.toIntOrNull() ?: 0,
            packages = packages,
            backend = "cgroup-top"
        )
    }

    fun ensureTaskForegroundHelper(): Boolean {
        return runAsRoot(
            "[ -f ${shellQuote(FOREGROUND_HELPER_SCRIPT)} ] && " +
                "sh ${shellQuote(FOREGROUND_HELPER_SCRIPT)} start >/dev/null 2>&1"
        ).isNotErrored()
    }

    fun readTaskForegroundState(): TaskForegroundState {
        val raw = runAsRoot("cat ${shellQuote(FOREGROUND_TASK_STATE_FILE)} 2>/dev/null")
            .substringBefore(ERR_MARK)
        return parseTaskForegroundState(raw, SystemClock.elapsedRealtime())
    }

    internal fun parseTaskForegroundState(raw: String, elapsedNowMs: Long): TaskForegroundState {
        val values = raw.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to
                    line.substring(index + 1).trim()
            }
            .toMap()
        val updatedElapsed = values["updated_elapsed_ms"]?.toLongOrNull()
        val age = updatedElapsed?.let {
            if (it <= 0L || elapsedNowMs < it) null else elapsedNowMs - it
        }
        val status = values["status"].orEmpty()
        val packageName = values["focused_package"].orEmpty().ifBlank { null }
        val available = status == "ok" && packageName != null &&
            age != null && age <= FOREGROUND_TASK_MAX_AGE_MS
        return TaskForegroundState(
            available = available,
            status = status.ifBlank { "missing" },
            mode = values["mode"].orEmpty(),
            packageName = packageName,
            activityName = values["focused_activity"].orEmpty().ifBlank { null },
            taskId = values["focused_task_id"]?.toIntOrNull()?.takeIf { it > 0 },
            displayId = values["focused_display_id"]?.toIntOrNull()?.takeIf { it >= 0 },
            visiblePackages = values["visible_packages"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            ageMs = age,
            generation = values["generation"]?.toLongOrNull(),
            reason = values["reason"].orEmpty(),
            selection = values["selection"].orEmpty(),
            error = values["error"].orEmpty(),
            raw = raw
        )
    }

    fun readFocusedPackage(): String? {
        val cmd = """
            {
                dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow|FocusedWindow' | head -n 12
                dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity|mFocusedApp' | head -n 12
            } | sed -nE \
                -e 's/.* u[0-9]+ ([A-Za-z0-9_][A-Za-z0-9_.]+)\/.*/\1/p' \
                -e 's/.* ([A-Za-z0-9_][A-Za-z0-9_.]+)\/[A-Za-z0-9_.$]+.*/\1/p' \
                | head -n 1
        """.trimIndent()
        return runAsRoot(cmd, timeoutSeconds = 3L)
            .substringBefore(ERR_MARK)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /** 下发开始线程负载采样命令，并等待守护进程进入 sampling 状态。 */
    fun startCalibration(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val safe = cleanCommandArg(pkg, allowColon = true)
        if (safe.isBlank()) return false
        val wrote = runAsRoot("mkdir -p '$CONFIG_DIR'; printf '%s' 'start $safe' > $CMD_FILE").isNotErrored()
        return wrote && waitForStatePackage("sampling", safe, timeoutMs = 2500)
    }

    /** 下发停止采样命令，守护进程随后生成规则并回写 applist.conf。 */
    fun stopCalibration(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        return runAsRoot("mkdir -p '$CONFIG_DIR'; printf '%s' 'stop $safe' > $CMD_FILE").isNotErrored()
    }

    /**
     * 通知守护进程开始真实帧率监测。
     * socketName/socketToken 存在时优先使用 App 创建的本地 socket 推送 FPS；
     * socket 不可用时，守护进程仍会回退写入 app 私有 fps 文件。
     */
    fun startFpsMonitor(pkg: String, socketName: String? = null, socketToken: String? = null): Boolean {
        if (pkg.isBlank()) return false
        val safePkg = cleanCommandArg(pkg, allowColon = true)
        if (safePkg.isBlank()) return false
        val safeSocket = socketName?.let { cleanCommandArg(it, allowColon = false) }.orEmpty()
        val safeToken = socketToken?.let { cleanCommandArg(it, allowColon = false) }.orEmpty()
        val cmd = if (safeSocket.isNotBlank() && safeToken.isNotBlank()) {
            "start $safePkg $safeSocket $safeToken"
        } else {
            "start $safePkg"
        }
        return runAsRoot("mkdir -p '$CONFIG_DIR'; printf '%s' '$cmd' > $FPS_CMD_FILE").isNotErrored()
    }

    /** 通知守护进程停止帧率监测。 */
    fun stopFpsMonitor(): Boolean {
        return runAsRoot("mkdir -p '$CONFIG_DIR'; printf '%s' 'stop' > $FPS_CMD_FILE").isNotErrored()
    }

    /** 读取守护进程当前状态；读不到时返回空字符串。 */
    fun readState(): String {
        return runAsRoot("cat $STATE_FILE 2>/dev/null").trim()
    }

    /** 读取本次开机以来的守护进程日志，只取最后 maxLines 行避免 UI 解析过大文件。 */
    fun readDaemonLog(maxLines: Int = 500): String {
        val out = runAsRoot("tail -n $maxLines $LOG_FILE 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /**
     * 等待守护进程完成校准。
     * 返回值含义：null=超时；ok=成功；short=采样不足；
     * no_load=负载不足；write_fail=写回失败。
     */
    fun waitDone(pkg: String, timeoutMs: Long = 4000): String? {
        val expected = cleanCommandArg(pkg, allowColon = true)
        if (expected.isBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val st = readState()
            val donePkg = statePackage(st, "done")
            if (donePkg == expected) {
                // 状态格式: "done pkg" 或 "done pkg;reason=xxx"。
                val parts = st.split(";")
                if (parts.size > 1) {
                    val reasonPart = parts.firstOrNull { it.startsWith("reason=") }
                    return reasonPart?.substringAfter("reason=") ?: "ok"
                }
                return "ok"
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private fun waitForStatePackage(prefix: String, pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (statePackage(readState(), prefix) == pkg) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun statePackage(state: String, prefix: String): String? {
        val marker = "$prefix "
        if (!state.startsWith(marker)) return null
        return state.substring(marker.length)
            .substringBefore(";")
            .trim()
            .ifBlank { null }
    }

    /**
     * 读取某应用当前生效的全部规则行, 包含主进程、线程和 :子进程规则。
     * 会过滤掉 pkg=auto 占位，只返回真正生成或手写的核心绑定规则。
     */
    fun readPkgRules(pkg: String): List<String> {
        val target = pkg.replace("'", "").trim()
        if (target.isEmpty()) return emptyList()
        val text = readConfigRaw()
        if (text.isBlank()) return emptyList()

        val result = RuleSyntax.parse(text).rules.asSequence()
            .filter { it.owner == target || it.owner.startsWith("$target:") }
            .filterNot { it.thread == null && it.cpus.equals("auto", ignoreCase = true) }
            .map(RuleSyntax.Rule::canonicalLine)
            .toList()
        return sortConfigRuleLines(result)
    }

    fun readPkgRules(pkgs: Collection<String>): List<String> =
        readPkgRulesOrNull(pkgs).orEmpty()

    fun readPkgRulesOrNull(pkgs: Collection<String>): List<String>? {
        val text = readConfigRawOrNull() ?: return null
        if (text.isBlank()) return emptyList()
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return emptyList()
        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()
        return RuleSyntax.parse(text).rules.asSequence()
            .filter { it.owner in targetSet || configGroupName(it.owner) in targetGroups }
            .filterNot { it.thread == null && it.cpus.equals("auto", ignoreCase = true) }
            .map(RuleSyntax.Rule::canonicalLine)
            .toList()
    }

    /** 读取某包名的全部配置行，包括 pkg=auto 占位。 */
    fun readPkgConfigLines(pkg: String): List<String> {
        val text = readConfigRaw()
        if (text.isBlank()) return emptyList()
        return RuleSyntax.parse(text).rules.asSequence()
            .filter { it.owner == pkg }
            .map(RuleSyntax.Rule::canonicalLine)
            .toList()
    }

    /** 读取 applist.conf 原始内容；文件不存在视为空，Root/IO 失败返回 null。 */
    fun readConfigRawOrNull(): String? {
        val result = readRootCommandResult(
            "if [ -f '$CONFIG_FILE' ]; then cat '$CONFIG_FILE' || exit 1; " +
                "elif [ ! -e '$CONFIG_FILE' ]; then :; else exit 1; fi"
        )
        return result.output.takeIf { result.success }
    }

    /** 兼容无需区分失败原因的旧调用。 */
    fun readConfigRaw(): String = readConfigRawOrNull().orEmpty()

    /** 自动校准策略文件内容及来源状态。 */
    data class PolicyFile(
        val content: String,
        val lockedByPendingUpdate: Boolean,
        val path: String,
        val exists: Boolean,
        val readSuccess: Boolean
    )

    /**
     * 读取自动校准策略文件。
     * 如果 /data/adb/modules_update/AppOpt/config/calib_policy.conf 存在，说明模块更新已刷入但未重启，
     * 这时读取待生效文件并锁定 UI，避免用户改完后重启又被更新目录覆盖。
     */
    fun readCalibPolicyRaw(): PolicyFile {
        val pendingResult = readRootCommandResult(
            "if [ -f '$POLICY_UPDATE_FILE' ]; then printf 1; " +
                "elif [ ! -e '$POLICY_UPDATE_FILE' ]; then printf 0; else exit 1; fi"
        )
        if (!pendingResult.success) {
            return PolicyFile("", false, POLICY_FILE, exists = false, readSuccess = false)
        }
        val hasPending = pendingResult.output.trim() == "1"
        val path = if (hasPending) POLICY_UPDATE_FILE else POLICY_FILE
        val existsMarker = "__APPOPT_POLICY_EXISTS_${UUID.randomUUID()}__"
        val missingMarker = "__APPOPT_POLICY_MISSING_${UUID.randomUUID()}__"
        val result = readRootCommandResult(
            "if [ -f '$path' ]; then printf '%s\\n' '$existsMarker'; cat '$path' || exit 1; " +
                "elif [ ! -e '$path' ]; then printf '%s\\n' '$missingMarker'; else exit 1; fi"
        )
        if (!result.success) {
            return PolicyFile("", hasPending, path, exists = false, readSuccess = false)
        }
        val markerEnd = result.output.indexOf('\n')
        if (markerEnd < 0) {
            return PolicyFile("", hasPending, path, exists = false, readSuccess = false)
        }
        val marker = result.output.substring(0, markerEnd).trimEnd('\r')
        return when (marker) {
            existsMarker -> PolicyFile(
                content = result.output.substring(markerEnd + 1),
                lockedByPendingUpdate = hasPending,
                path = path,
                exists = true,
                readSuccess = true
            )
            missingMarker -> PolicyFile("", hasPending, path, exists = false, readSuccess = true)
            else -> PolicyFile("", hasPending, path, exists = false, readSuccess = false)
        }
    }

    /** 写入当前生效模块目录的自动校准策略；存在待生效更新时拒绝写入。 */
    fun writeCalibPolicyRaw(content: String): Boolean {
        val pendingResult = readRootCommandResult(
            "if [ -f '$POLICY_UPDATE_FILE' ]; then printf 1; " +
                "elif [ ! -e '$POLICY_UPDATE_FILE' ]; then printf 0; else exit 1; fi"
        )
        if (!pendingResult.success || pendingResult.output.trim() != "0") return false
        return writePolicyFileAsRoot(content)
    }

    enum class RuleOutputFormatApplyStatus {
        SUCCESS,
        INVALID_CONFIG,
        CONFIG_WRITE_FAILED,
        POLICY_WRITE_FAILED,
        ROLLBACK_FAILED
    }

    data class RuleOutputFormatApplyResult(
        val status: RuleOutputFormatApplyStatus,
        val format: CalibPolicy.RuleOutputFormat? = null,
        val ruleCount: Int = 0,
        val groupCount: Int = 0,
        val changed: Boolean = false,
        val mixed: Boolean = false,
        val detail: String? = null
    ) {
        val success: Boolean
            get() = status == RuleOutputFormatApplyStatus.SUCCESS
    }

    /**
     * 在配置锁内转换现有规则并保存策略。策略写入失败时恢复原始 applist.conf，
     * 避免现有规则格式与后续 C/Rust 校准生成格式不一致。
     */
    fun applyRuleOutputFormat(
        format: CalibPolicy.RuleOutputFormat,
        policyContent: String
    ): RuleOutputFormatApplyResult {
        val lockFailure = RuleOutputFormatApplyResult(
            RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
            detail = "无法获取规则配置锁"
        )
        return withConfigMutation(lockFailure) { token ->
            val raw = readConfigRawForMutation() ?: return@withConfigMutation lockFailure.copy(
                detail = "读取 applist.conf 失败"
            )
            val result = RuleFormatConverter.convert(raw, format)
            val conversion = result.conversion ?: return@withConfigMutation RuleOutputFormatApplyResult(
                RuleOutputFormatApplyStatus.INVALID_CONFIG,
                detail = result.error
            )

            if (conversion.changed && !writeConfigFileLocked(conversion.content, token)) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
                    detail = "写入 applist.conf 失败"
                )
            }

            if (writeCalibPolicyRaw(policyContent)) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.SUCCESS,
                    format = format,
                    ruleCount = conversion.ruleCount,
                    groupCount = conversion.groupCount,
                    changed = conversion.changed
                )
            }

            val rollbackOk = !conversion.changed || writeConfigFileLocked(raw, token)
            RuleOutputFormatApplyResult(
                status = if (rollbackOk) {
                    RuleOutputFormatApplyStatus.POLICY_WRITE_FAILED
                } else {
                    RuleOutputFormatApplyStatus.ROLLBACK_FAILED
                },
                ruleCount = conversion.ruleCount,
                groupCount = conversion.groupCount,
                changed = conversion.changed,
                detail = if (rollbackOk) {
                    "策略保存失败，现有规则已恢复"
                } else {
                    "策略保存失败，且现有规则恢复失败"
                }
            )
        }
    }

    /** App 首次启动时识别现有规则写法，并让校准生成格式跟随该写法。 */
    fun detectAndApplyRuleOutputFormat(): RuleOutputFormatApplyResult {
        val lockFailure = RuleOutputFormatApplyResult(
            RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
            detail = "无法获取规则配置锁"
        )
        return withConfigMutation(lockFailure) { _ ->
            val raw = readConfigRawForMutation() ?: return@withConfigMutation lockFailure.copy(
                detail = "读取 applist.conf 失败"
            )
            val policyFile = readCalibPolicyRaw()
            if (!policyFile.readSuccess) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    RuleOutputFormatApplyStatus.POLICY_WRITE_FAILED,
                    detail = "校准策略文件读取失败，已保留原规则"
                )
            }
            val currentPolicy = if (policyFile.exists) {
                CalibPolicy.parse(policyFile.content)
            } else {
                CalibPolicy.DEFAULT
            }
            val detection = RuleFormatConverter.detectFormat(raw)
                ?: return@withConfigMutation RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.SUCCESS,
                    format = currentPolicy.ruleOutputFormat,
                    ruleCount = RuleSyntax.parse(raw).rules.size
                )
            if (detection.format == currentPolicy.ruleOutputFormat) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.SUCCESS,
                    format = detection.format,
                    ruleCount = detection.ruleCount,
                    mixed = detection.mixed
                )
            }
            val updatedPolicyContent = if (policyFile.exists) {
                updatePolicyValuePreservingText(
                    policyFile.content,
                    "rule_output_format",
                    detection.format.wire
                )
            } else {
                currentPolicy.copy(ruleOutputFormat = detection.format).toConfigText()
            }
            if (writeCalibPolicyRaw(updatedPolicyContent)) {
                RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.SUCCESS,
                    format = detection.format,
                    ruleCount = detection.ruleCount,
                    changed = true,
                    mixed = detection.mixed
                )
            } else {
                RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.POLICY_WRITE_FAILED,
                    format = currentPolicy.ruleOutputFormat,
                    ruleCount = detection.ruleCount,
                    detail = "识别到现有规则格式，但策略保存失败"
                )
            }
        }
    }

    /** 把应用追加为 pkg=auto，占位后可在“待校准”里启动采样。 */
    fun addAutoPackage(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        if (safe.isBlank() || !RuleConfigLogic.ownerFitsNativeBuffer(safe)) return false
        return withConfigMutation(false) { token ->
            val raw = readConfigRawForMutation() ?: return@withConfigMutation false
            val document = RuleSyntax.parse(raw)
            val group = configGroupName(safe)
            if (document.rules.any { it.owner == safe || configGroupName(it.owner) == group } ||
                document.segments.any { it.ownerHint?.let(::configGroupName) == group }) {
                return@withConfigMutation true
            }
            val prefix = raw.trimEnd()
            val content = if (prefix.isEmpty()) "$safe=auto\n" else "$prefix\n\n$safe=auto\n"
            writeConfigFileLocked(content, token)
        }
    }

    /** 批量删除 applist.conf 中多个包名或进程名的全部配置行。 */
    fun deleteConfigPackages(pkgs: Collection<String>): Boolean {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return false
        return withConfigMutation(false) { token ->
            val raw = readConfigRawForMutation() ?: return@withConfigMutation false
            if (raw.isBlank()) return@withConfigMutation true
            val targetSet = targets.toSet()
            val targetGroups = targets.map { configGroupName(it) }.toSet()
            val document = RuleSyntax.parse(raw)
            val keptLines = document.segments
                .filterNot { segment ->
                    val hintedOwner = segment.ownerHint
                    (hintedOwner != null &&
                        (hintedOwner in targetSet || configGroupName(hintedOwner) in targetGroups)) ||
                        segment.rules.any {
                            it.owner in targetSet || configGroupName(it.owner) in targetGroups
                        }
                }
                .flatMap(RuleSyntax.Segment::rawLines)
            val content = keptLines.joinToString("\n").trimEnd().let {
                if (it.isEmpty()) "" else "$it\n"
            }
            writeConfigFileLocked(content, token)
        }
    }

    data class ConfigRuleValidation(
        val validLines: List<String>,
        val invalidLines: List<String>,
        val foreignLines: List<String>,
        val invalidCoreLines: List<String>
    ) {
        val ok: Boolean
            get() = validLines.isNotEmpty() &&
                invalidLines.isEmpty() &&
                foreignLines.isEmpty() &&
                invalidCoreLines.isEmpty()
    }

    /** 校验编辑后的规则是否只属于当前应用/同组子进程。 */
    fun validateConfigRulesForPackages(
        pkgs: Collection<String>,
        editedText: String,
        allowedCpus: Set<Int>? = null
    ): ConfigRuleValidation {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) {
            return ConfigRuleValidation(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()
        val valid = ArrayList<String>()
        val invalid = ArrayList<String>()
        val foreign = ArrayList<String>()
        val invalidCore = ArrayList<String>()

        val document = RuleSyntax.parse(editedText)
        document.segments.asSequence()
            .filterNot(RuleSyntax.Segment::valid)
            .map { it.rawLines.joinToString("\n").trim() }
            .filter(String::isNotEmpty)
            .forEach(invalid::add)

        for (rule in document.rules) {
            val line = rule.canonicalLine
            val owner = rule.owner
            if (!RuleConfigLogic.ownerFitsNativeBuffer(owner) ||
                (rule.thread != null && !RuleConfigLogic.threadFitsNativeBuffer(rule.thread))) {
                invalid.add(line)
                continue
            }
            val cpus = parseConfigRuleCpusStrict(rule.cpus)
            if (cpus == null || (allowedCpus != null && !allowedCpus.containsAll(cpus))) {
                invalidCore.add(line)
                continue
            }
            if (owner in targetSet || configGroupName(owner) in targetGroups) {
                valid.add(line)
            } else {
                foreign.add(line)
            }
        }

        return ConfigRuleValidation(
            sortConfigRuleLines(valid.distinct()),
            invalid.distinct(),
            foreign.distinct(),
            invalidCore.distinct()
        )
    }

    enum class ConfigReplaceResult {
        SUCCESS,
        SOURCE_CHANGED,
        INVALID,
        WRITE_FAILED
    }

    /**
     * 按打开编辑器时的规则序号替换当前应用规则，保留原文件中的注释和应用顺序。
     * 写入前会核对原始规则快照，最后按校准策略选择的格式统一写回。
     */
    fun replaceConfigRulesPreservingLayout(
        pkgs: Collection<String>,
        expectedOriginalLines: List<String>,
        replacements: Map<Int, String>,
        addedLines: List<String>,
        allowedCpus: Set<Int>? = null
    ): ConfigReplaceResult {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty() || replacements.keys.any { it !in expectedOriginalLines.indices }) {
            return ConfigReplaceResult.INVALID
        }

        val finalLines = expectedOriginalLines.indices.mapNotNull(replacements::get) + addedLines
        if (finalLines.isEmpty()) return ConfigReplaceResult.INVALID
        val presentCpus = allowedCpus ?: readConfigAllowedCpus().takeIf { it.isNotEmpty() }
        val check = validateConfigRulesForPackages(
            targets,
            finalLines.joinToString("\n"),
            presentCpus
        )
        if (!check.ok) return ConfigReplaceResult.INVALID

        return withConfigMutation(ConfigReplaceResult.WRITE_FAILED) { token ->
            val raw = readConfigRawForMutation()
                ?: return@withConfigMutation ConfigReplaceResult.WRITE_FAILED
            if (raw.isBlank()) return@withConfigMutation ConfigReplaceResult.SOURCE_CHANGED
            val targetSet = targets.toSet()
            val targetGroups = targets.map { configGroupName(it) }.toSet()

            fun isEditableTargetRule(rule: RuleSyntax.Rule): Boolean {
                return !(rule.thread == null && rule.cpus.equals("auto", ignoreCase = true)) &&
                    (rule.owner in targetSet || configGroupName(rule.owner) in targetGroups)
            }

            val document = RuleSyntax.parse(raw)
            val currentOriginalLines = document.rules.asSequence()
                .filter(::isEditableTargetRule)
                .map(RuleSyntax.Rule::canonicalLine)
                .toList()
            if (currentOriginalLines != expectedOriginalLines.map(String::trim)) {
                return@withConfigMutation ConfigReplaceResult.SOURCE_CHANGED
            }

            val output = ArrayList<String>()
            var sourceIndex = 0
            var additionsInserted = false
            for (segment in document.segments) {
                val editableRules = segment.rules.filter(::isEditableTargetRule)
                if (editableRules.isEmpty()) {
                    output.addAll(segment.rawLines)
                    continue
                }

                // 先保留区块及规则旁的注释，再展开成统一规则；写入前会重新转换为用户选择的格式。
                output.addAll(RuleFormatConverter.preservedTrivia(segment.rawLines))
                for (rule in segment.rules) {
                    if (isEditableTargetRule(rule)) {
                        replacements[sourceIndex]?.let(output::add)
                        sourceIndex++
                    } else {
                        output.add(rule.canonicalLine)
                    }
                }
                if (sourceIndex == expectedOriginalLines.size && !additionsInserted) {
                    output.addAll(addedLines)
                    additionsInserted = true
                }
            }
            if (sourceIndex != expectedOriginalLines.size) {
                return@withConfigMutation ConfigReplaceResult.SOURCE_CHANGED
            }
            if (!additionsInserted) output.addAll(addedLines)

            val expandedContent = output.joinToString("\n").trimEnd() + "\n"
            val policyFile = readCalibPolicyRaw()
            if (!policyFile.readSuccess) {
                return@withConfigMutation ConfigReplaceResult.WRITE_FAILED
            }
            val outputFormat = if (policyFile.exists) {
                CalibPolicy.parse(policyFile.content).ruleOutputFormat
            } else {
                CalibPolicy.DEFAULT.ruleOutputFormat
            }
            val formatted = RuleFormatConverter.convert(expandedContent, outputFormat).conversion
                ?: return@withConfigMutation ConfigReplaceResult.INVALID
            if (writeConfigFileLocked(formatted.content, token)) {
                ConfigReplaceResult.SUCCESS
            } else {
                ConfigReplaceResult.WRITE_FAILED
            }
        }
    }

    internal fun sortConfigRuleLines(lines: List<String>): List<String> {
        data class SortableRule(
            val index: Int,
            val line: String,
            val fallback: Boolean,
            val cpuBounds: RuleConfigLogic.CpuBounds?
        )

        return lines.mapIndexed { index, line ->
            SortableRule(
                index = index,
                line = line,
                fallback = configRuleIsFallback(line),
                cpuBounds = RuleConfigLogic.cpuBoundsFromRuleLine(line)
            )
        }
            .sortedWith(
                compareBy<SortableRule> { if (it.fallback) 1 else 0 }
                    .thenByDescending { it.cpuBounds?.first ?: -1 }
                    .thenByDescending { it.cpuBounds?.last ?: -1 }
                    .thenBy { it.index }
            )
            .map { it.line }
    }

    private fun configRuleIsFallback(line: String): Boolean {
        val left = line.substringBefore("=").trim()
        return !left.contains("{") && !left.contains(":")
    }

    private fun parseConfigRuleCpusStrict(value: String): Set<Int>? {
        return RuleConfigLogic.parseCpuRangeList(value)?.takeIf { it.isNotEmpty() }
    }

    fun readPresentCpus(): Set<Int> {
        val out = runAsRoot("cat /sys/devices/system/cpu/present 2>/dev/null")
        return parseCpuRangeList(out.trim())
    }

    fun readConfigAllowedCpus(): Set<Int> {
        val present = readPresentCpus()
        if (present.isNotEmpty()) return present

        val policy = readCalibPolicyRaw().content
        for (line in policy.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("detected_all=")) {
                return parseCpuRangeList(trimmed.substringAfter("=").trim())
            }
        }
        return emptySet()
    }

    private fun parseCpuRangeList(text: String): Set<Int> {
        return RuleConfigLogic.parseCpuRangeList(text).orEmpty()
    }

    private fun configGroupName(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.contains('.')) base else pkg
    }

    internal fun updatePolicyValuePreservingText(raw: String, key: String, value: String): String {
        val pattern = Regex(
            "(?m)^([ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*)" +
                "[^#\\r\\n]*?([ \\t]*(?:#.*)?)(\\r?)$"
        )
        var found = false
        val updated = pattern.replace(raw) { match ->
            found = true
            match.groupValues[1] + value + match.groupValues[2] + match.groupValues[3]
        }
        if (found) return updated
        val separator = if (updated.isEmpty() || updated.endsWith('\n')) "" else "\n"
        return "$updated$separator$key=$value\n"
    }

    private const val HISTORY_IMPORT_SUFFIX = ".appopt-importing"

    private fun safeHistoryPackage(pkg: String): String {
        return pkg.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == ':' || ch == '-') ch else '_'
        }.joinToString("")
    }

    /** 原子认领一份历史文件，避免导入完成时误删 C 刚写入的新会话。 */
    fun claimHistoryImport(pkg: String): String {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return ""
        val source = shellQuote("$HISTORY_DIR/$safe.log")
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        val out = runAsRoot(
            "if [ -f $claim ]; then cat $claim; " +
                "elif [ -f $source ] && mv $source $claim; then cat $claim; fi; true"
        )
        return if (out.isNotErrored()) out.substringBefore(ERR_MARK) else ""
    }

    /** 删除已经成功入库的认领文件；history 为空时一并清理目录。 */
    fun completeHistoryImport(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        val out = runAsRoot(
            "if rm -f $claim; then " +
                "rmdir '$HISTORY_DIR' 2>/dev/null || true; printf 1; " +
                "else printf 0; fi"
        )
        return out.isNotErrored() && out.substringBefore(ERR_MARK).trim() == "1"
    }

    /** 删除某包名的整份历史 .log 文件。 */
    fun deleteHistory(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val source = shellQuote("$HISTORY_DIR/$safe.log")
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        return runAsRoot("rm -f $source $claim; rmdir '$HISTORY_DIR' 2>/dev/null; true").isNotErrored()
    }

    /** history 目录下的一份历史记录文件概要。 */
    data class HistoryEntry(val pkg: String, val mtime: Long)

    /**
     * 枚举 history 目录下的 .log 文件。
     * 只通过一次 su 调用获取文件名和 mtime，避免历史列表打开时频繁创建 root 进程。
     */
    fun listHistoryEntries(): List<HistoryEntry> {
        val out = runAsRoot(
            "for f in $HISTORY_DIR/*.log $HISTORY_DIR/*.log$HISTORY_IMPORT_SUFFIX; " +
                "do [ -e \"\$f\" ] && stat -c '%Y %n' \"\$f\"; done 2>/dev/null; true"
        )
        val clean = out.substringBefore(ERR_MARK)
        if (!out.isNotErrored() && clean.isBlank()) {
            android.util.Log.w("AppOpt", "history list: root 枚举失败")
            return emptyList()
        }
        val list = ArrayList<HistoryEntry>()
        for (raw in clean.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val mtime = line.substring(0, sp).toLongOrNull() ?: continue
            val full = line.substring(sp + 1).trim()
            val name = full.substringAfterLast('/')
            val normalizedName = name.removeSuffix(HISTORY_IMPORT_SUFFIX)
            if (!normalizedName.endsWith(".log")) continue
            val pkg = normalizedName.removeSuffix(".log")
            if (pkg.isNotEmpty()) list.add(HistoryEntry(pkg, mtime))
        }
        val entries = list.groupBy { it.pkg }
            .map { (pkg, entries) -> HistoryEntry(pkg, entries.maxOf { it.mtime }) }
            .sortedByDescending { it.mtime }
        android.util.Log.d("AppOpt", "history list: 枚举到 ${entries.size} 个应用历史文件")
        return entries
    }

    private const val ERR_MARK = "__APPOPT_ERR__"

    private fun String.isNotErrored(): Boolean = !this.contains(ERR_MARK)

    private fun versionNameToCode(version: String): Int? {
        val nums = version.trim()
            .removePrefix("v")
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        if (nums.isEmpty()) return null
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 100 + minor * 10 + patch
    }

    private fun cleanCommandArg(value: String, allowColon: Boolean): String {
        val allowed = if (allowColon) Regex("[^A-Za-z0-9._:-]") else Regex("[^A-Za-z0-9._-]")
        return value.trim().replace("'", "").replace(allowed, "")
    }

    private fun daemonBinarySelectShell(): String {
        val rs = shellQuote(BIN_RS_FILE)
        val c = shellQuote(BIN_FILE)
        val fallback = shellQuote(RS_FALLBACK_FILE)
        return "[ -x $rs ] && [ ! -f $fallback ] && printf '%s' $rs || printf '%s' $c"
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    /** 同一 App 进程内串行修改，并与守护进程共享设备端锁。 */
    private fun <T> withConfigMutation(lockFailure: T, action: (String) -> T): T {
        return synchronized(configMutationLock) {
            val token = acquireConfigLock() ?: return@synchronized lockFailure
            try {
                action(token)
            } finally {
                releaseConfigLock(token)
            }
        }
    }

    private fun acquireConfigLock(): String? {
        val token = UUID.randomUUID().toString()
        val cmd = """
            lock='$CONFIG_LOCK_DIR'
            token='$token'
            mkdir -p '$CONFIG_DIR'
            waited=0
            while ! mkdir "${'$'}lock" 2>/dev/null; do
                now=${'$'}(date +%s)
                mtime=${'$'}(stat -c %Y "${'$'}lock" 2>/dev/null || printf 0)
                if [ "${'$'}mtime" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((mtime + 30))" ]; then
                    rm -f "${'$'}lock/owner"
                    rmdir "${'$'}lock" 2>/dev/null || true
                    continue
                fi
                waited=${'$'}((waited + 1))
                [ "${'$'}waited" -ge 5 ] && exit 1
                sleep 1
            done
            if ! printf '%s' "${'$'}token" > "${'$'}lock/owner"; then
                rmdir "${'$'}lock" 2>/dev/null || true
                exit 1
            fi
            printf '%s' "${'$'}token"
        """.trimIndent()
        val out = runAsRoot(cmd)
        return token.takeIf { out.isNotErrored() && out.substringBefore(ERR_MARK).trim() == token }
    }

    private fun releaseConfigLock(token: String) {
        val cmd = """
            lock='$CONFIG_LOCK_DIR'
            if [ "${'$'}(cat "${'$'}lock/owner" 2>/dev/null)" = '$token' ]; then
                rm -f "${'$'}lock/owner"
                rmdir "${'$'}lock" 2>/dev/null || true
            fi
        """.trimIndent()
        runAsRoot(cmd)
    }

    /** 锁内读取时区分“文件不存在”和“Root 读取失败”，避免把读取失败误当成空配置。 */
    private fun readConfigRawForMutation(): String? {
        val missingMarker = "__APPOPT_CONFIG_MISSING_${UUID.randomUUID()}__"
        val result = readRootCommandResult(
            "if [ -f '$CONFIG_FILE' ]; then cat '$CONFIG_FILE' || exit 1; " +
                "else printf '$missingMarker'; fi"
        )
        if (!result.success) return null
        val content = result.output
        return if (content.trimEnd('\r', '\n') == missingMarker) "" else content
    }

    /** 锁内原子写入 applist.conf，避免 App 与守护进程相互覆盖或留下半文件。 */
    private fun writeConfigFileLocked(content: String, token: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val tmp = "$CONFIG_FILE.app-$token.tmp"
        val cmd = """
            lock='$CONFIG_LOCK_DIR'
            target='$CONFIG_FILE'
            tmp='$tmp'
            [ "${'$'}(cat "${'$'}lock/owner" 2>/dev/null)" = '$token' ] || exit 1
            trap 'rm -f "${'$'}tmp"' EXIT
            if ! base64 -d > "${'$'}tmp" << 'EOF_BASE64'
            $b64
            EOF_BASE64
            then
                exit 1
            fi
            chmod 0644 "${'$'}tmp" 2>/dev/null || true
            chown 0:0 "${'$'}tmp" 2>/dev/null || true
            mv -f "${'$'}tmp" "${'$'}target" || exit 1
        """.trimIndent()
        return runAsRoot(cmd).isNotErrored()
    }

    /** 带简易锁写入 calib_policy.conf，避免 App 与守护进程同时改策略文件。 */
    private fun writePolicyFileAsRoot(content: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val cmd = """
            lock='$POLICY_LOCK_DIR'
            target='$POLICY_FILE'
            tmp='$POLICY_FILE.tmp'
            mkdir -p '$CONFIG_DIR'
            waited=0
            while ! mkdir "${'$'}lock" 2>/dev/null; do
                now=${'$'}(date +%s)
                mtime=${'$'}(stat -c %Y "${'$'}lock" 2>/dev/null || printf 0)
                if [ "${'$'}mtime" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((mtime + 30))" ]; then
                    rmdir "${'$'}lock" 2>/dev/null || true
                    continue
                fi
                waited=${'$'}((waited + 1))
                [ "${'$'}waited" -ge 5 ] && exit 1
                sleep 1
            done
            trap 'rm -f "${'$'}tmp"; rmdir "${'$'}lock" 2>/dev/null' EXIT
            base64 -d > "${'$'}tmp" << 'EOF_BASE64'
            $b64
            EOF_BASE64
            mv "${'$'}tmp" "${'$'}target"
        """.trimIndent()
        return runAsRoot(cmd).isNotErrored()
    }

    private val DEV_NULL = java.io.File("/dev/null")

    /** 等待 root 子进程结束，并读取 stdout；超时或非零退出会附加错误标记。 */
    private fun waitAndRead(process: Process, timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS): String {
        val outRef = AtomicReference("")
        val reader = Thread {
            outRef.set(process.inputStream.bufferedReader().readText())
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return ERR_MARK
        }

        reader.join(1000)
        val out = outRef.get()
        return if (process.exitValue() != 0) "$out$ERR_MARK" else out
    }

    private fun waitAndStream(
        process: Process,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
        onOutput: (String) -> Unit
    ): RootCommandResult {
        val out = StringBuilder()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        val chunk = "$line\n"
                        synchronized(out) {
                            out.append(chunk)
                        }
                        onOutput(chunk)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return RootCommandResult(
                output = synchronized(out) { out.toString() },
                success = false
            )
        }

        reader.join(1000)
        return RootCommandResult(
            output = synchronized(out) { out.toString() },
            success = process.exitValue() == 0
        )
    }

    /**
     * 通过 su -c 执行命令。
     * stderr 重定向到 /dev/null，避免无人读取的错误管道写满后卡住 root 子进程。
     */
    private fun runAsRoot(cmd: String, timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS): String {
        return try {
            // 把 stderr 重定向到 /dev/null: 没有无人读的 stderr 管道, 既避免
            // "stderr 写满管道缓冲(~64KB)->子进程阻塞写、父进程阻塞读 stdout"的死锁,
            // 又不像合并 stderr 那样污染 stdout(hasRoot 等按内容精确解析)。
            // (不用 Redirect.DISCARD: 那是 Java9+ API, Android 上不可用。)
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                waitAndRead(process, timeoutSeconds)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            // 某些 su 实现不支持 "su -c", 回退到管道写入方式
            runViaStdin(cmd, timeoutSeconds)
        }
    }

    /** 兼容不支持 su -c 的实现，退回到 stdin 写入命令。 */
    private fun runViaStdin(cmd: String, timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS): String {
        return try {
            val process = ProcessBuilder("su")
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("$cmd\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                waitAndRead(process, timeoutSeconds)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            "$ERR_MARK"
        }
    }

    private fun runAsRootStreaming(
        cmd: String,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
        onOutput: (String) -> Unit
    ): RootCommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                waitAndStream(process, timeoutSeconds, onOutput)
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            runViaStdinStreaming(cmd, timeoutSeconds, onOutput)
        }
    }

    private fun runViaStdinStreaming(
        cmd: String,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
        onOutput: (String) -> Unit
    ): RootCommandResult {
        return try {
            val process = ProcessBuilder("su")
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("$cmd\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                waitAndStream(process, timeoutSeconds, onOutput)
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            RootCommandResult(output = "", success = false)
        }
    }
}
