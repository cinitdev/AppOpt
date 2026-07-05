package top.suto.appopt

import android.net.LocalServerSocket
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
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
    private const val LOG_DIR = "$MODULE_DIR/logs"
    private const val UPDATE_CONFIG_DIR = "$MODULE_UPDATE_DIR/config"
    private const val CMD_FILE = "$CONFIG_DIR/calibrate.cmd"
    private const val STATE_FILE = "$CONFIG_DIR/calibrate.state"
    private const val CONFIG_FILE = "$CONFIG_DIR/applist.conf"
    private const val POLICY_FILE = "$CONFIG_DIR/calib_policy.conf"
    private const val POLICY_LOCK_DIR = "$CONFIG_DIR/calib_policy.conf.lock"
    private const val POLICY_UPDATE_FILE = "$UPDATE_CONFIG_DIR/calib_policy.conf"
    private const val HISTORY_DIR = "$MODULE_DIR/history"
    private const val LOG_FILE = "$LOG_DIR/AppOpt.log"
    private const val FPS_CMD_FILE = "$CONFIG_DIR/fps.cmd"
    private const val DAEMON_SOCKET_CALLBACK_PREFIX = "appopt.callback top.suto.appopt v1 "
    private const val ROOT_TIMEOUT_SECONDS = 15L
    const val REQUIRED_MODULE_VERSION_CODE = 175
    const val REQUIRED_MODULE_VERSION_NAME = "1.7.5"

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

    fun readRootFile(path: String): String? {
        val out = runAsRoot("cat ${shellQuote(path)} 2>/dev/null")
        return if (out.isNotErrored()) out else null
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
        val cmd = """
            prop="$MODULE_DIR/module.prop"
            prop_code=
            prop_version=
            bin_version=
            [ -f "${'$'}prop" ] && prop_code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            [ -f "${'$'}prop" ] && prop_version=${'$'}(sed -n 's/^version=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            if [ -x '$BIN_FILE' ]; then
                bin_out=$('$BIN_FILE' -v 2>/dev/null)
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
    fun isDaemonRunning(): Boolean = verifyDaemonSocketReverse()

    private fun verifyDaemonSocketReverse(): Boolean {
        val socketName = "appopt_verify_${android.os.Process.myPid()}_${System.nanoTime()}"
        val token = UUID.randomUUID().toString().replace("-", "")
        val callbackOk = AtomicReference(false)
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
                        callbackOk.set(isValidDaemonCallback(line, token))
                    }
                } catch (_: Exception) {
                    callbackOk.set(false)
                }
            }, "AppOptDaemonVerify").apply {
                isDaemon = true
                start()
            }

            val rootThread = Thread({
                runAsRoot("'$BIN_FILE' --ping-daemon '$socketName' '$token' 2>/dev/null")
            }, "AppOptDaemonPing").apply {
                isDaemon = true
                start()
            }

            acceptThread.join(3000)
            val ok = callbackOk.get() == true
            if (!ok) {
                try { localServer.close() } catch (_: Exception) {}
                acceptThread.join(300)
            }
            rootThread.join(500)
            ok
        } catch (_: Exception) {
            false
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun isValidDaemonCallback(line: String, token: String): Boolean {
        return line.startsWith(DAEMON_SOCKET_CALLBACK_PREFIX) &&
            line.contains("token=$token") &&
            line.contains("version=") &&
            line.contains("pid=") &&
            callbackVersionCode(line)?.let { it >= REQUIRED_MODULE_VERSION_CODE } == true
    }

    private fun callbackVersionCode(line: String): Int? {
        val version = Regex("""(?:^|\s)version=([^\s]+)""")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return versionNameToCode(version)
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
        val cmd = buildString {
            append(shellQuote(BIN_FILE))
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

        val result = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val owner = configLineOwner(line) ?: continue
            val value = line.substringAfter('=', "").trim()
            val belongsToApp = owner == target || owner.startsWith("$target:")
            if (belongsToApp && !value.equals("auto", ignoreCase = true)) {
                result.add(line)
            }
        }
        return sortConfigRuleLines(result)
    }

    fun readPkgRules(pkgs: Collection<String>): List<String> {
        val text = readConfigRaw()
        if (text.isBlank()) return emptyList()
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return emptyList()
        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()
        val result = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val owner = configLineOwner(line) ?: continue
            val value = line.substringAfter('=', "").trim()
            if ((owner in targetSet || configGroupName(owner) in targetGroups) &&
                !value.equals("auto", ignoreCase = true)) {
                result.add(line)
            }
        }
        return result
    }

    /** 读取某包名的全部配置行，包括 pkg=auto 占位。 */
    fun readPkgConfigLines(pkg: String): List<String> {
        val text = readConfigRaw()
        if (text.isBlank()) return emptyList()
        val result = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (lineBelongsToPackage(line, pkg)) result.add(line)
        }
        return result
    }

    /** 读取 applist.conf 原始内容；读不到时返回空字符串。 */
    fun readConfigRaw(): String {
        val out = runAsRoot("cat $CONFIG_FILE 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /** 自动校准策略文件内容及来源状态。 */
    data class PolicyFile(
        val content: String,
        val lockedByPendingUpdate: Boolean,
        val path: String,
        val exists: Boolean
    )

    /**
     * 读取自动校准策略文件。
     * 如果 /data/adb/modules_update/AppOpt/config/calib_policy.conf 存在，说明模块更新已刷入但未重启，
     * 这时读取待生效文件并锁定 UI，避免用户改完后重启又被更新目录覆盖。
     */
    fun readCalibPolicyRaw(): PolicyFile {
        val hasPending = runAsRoot("[ -f '$POLICY_UPDATE_FILE' ] && printf 1 || printf 0")
            .trim() == "1"
        val path = if (hasPending) POLICY_UPDATE_FILE else POLICY_FILE
        val exists = runAsRoot("[ -f '$path' ] && printf 1 || printf 0")
            .trim() == "1"
        val out = runAsRoot("cat '$path' 2>/dev/null")
        return PolicyFile(
            content = if (out.isNotErrored()) out else "",
            lockedByPendingUpdate = hasPending,
            path = path,
            exists = exists
        )
    }

    /** 写入当前生效模块目录的自动校准策略；存在待生效更新时拒绝写入。 */
    fun writeCalibPolicyRaw(content: String): Boolean {
        val locked = runAsRoot("[ -f '$POLICY_UPDATE_FILE' ] && printf 1 || printf 0")
            .trim() == "1"
        if (locked) return false
        return writePolicyFileAsRoot(content)
    }

    /** 把应用追加为 pkg=auto，占位后可在“待校准”里启动采样。 */
    fun addAutoPackage(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        if (safe.isBlank()) return false
        val blocks = parseConfigBlocks(readConfigRaw())
        val group = configGroupName(safe)
        if (blocks.any { safe in it.owners || it.group == group }) {
            return writeFileAsRoot(CONFIG_FILE, formatConfigBlocks(blocks))
        }
        blocks.add(ConfigBlock(group, mutableListOf("$safe=auto"), mutableSetOf(safe)))
        return writeFileAsRoot(CONFIG_FILE, formatConfigBlocks(blocks))
    }

    /** 批量删除 applist.conf 中多个包名或进程名的全部配置行。 */
    fun deleteConfigPackages(pkgs: Collection<String>): Boolean {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return false
        val raw = readConfigRaw()
        if (raw.isBlank()) return true
        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()
        val kept = parseConfigBlocks(raw)
            .filterNot { block -> block.group in targetGroups || block.owners.any { it in targetSet } }
        return writeFileAsRoot(CONFIG_FILE, formatConfigBlocks(kept))
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

        for (line in editedText.lineSequence().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val owner = configLineOwner(line)
            val value = line.substringAfter("=", "").trim()
            if (owner == null || value.isEmpty()) {
                invalid.add(line)
                continue
            }
            val cpus = parseConfigRuleCpusStrict(value)
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

    /** 替换指定应用/子进程的配置规则; 只接受属于 targets 的规则行, 避免误改其他应用。 */
    fun replaceConfigRules(
        pkgs: Collection<String>,
        editedText: String,
        allowedCpus: Set<Int>? = null
    ): Boolean {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return false

        val presentCpus = allowedCpus ?: readConfigAllowedCpus().takeIf { it.isNotEmpty() }
        val check = validateConfigRulesForPackages(targets, editedText, presentCpus)
        if (!check.ok) return false
        val newLines = sortConfigRuleLines(check.validLines)
        if (newLines.isEmpty()) return false

        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()
        val blocks = parseConfigBlocks(readConfigRaw())
            .filterNot { block -> block.group in targetGroups || block.owners.any { it in targetSet } }
            .toMutableList()
        val group = configGroupName(targets.first())
        val owners = newLines.mapNotNull { configLineOwner(it) }.toMutableSet()
        blocks.add(ConfigBlock(group, newLines.toMutableList(), owners))
        return writeFileAsRoot(CONFIG_FILE, formatConfigBlocks(blocks))
    }

    enum class ConfigReplaceResult {
        SUCCESS,
        SOURCE_CHANGED,
        INVALID,
        WRITE_FAILED
    }

    /**
     * 按打开编辑器时的规则序号替换当前应用规则，保留原文件中的注释、空行和规则顺序。
     * 写入前会核对原始规则快照，避免覆盖其他进程在编辑期间产生的修改。
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

        val raw = readConfigRaw()
        if (raw.isBlank()) return ConfigReplaceResult.SOURCE_CHANGED
        val targetSet = targets.toSet()
        val targetGroups = targets.map { configGroupName(it) }.toSet()

        fun isEditableTargetRule(rawLine: String): Boolean {
            val line = rawLine.trim()
            val owner = configLineOwner(line) ?: return false
            val value = line.substringAfter('=', "").trim()
            return !value.equals("auto", ignoreCase = true) &&
                (owner in targetSet || configGroupName(owner) in targetGroups)
        }

        val rawLines = raw.lineSequence().toList()
        val currentOriginalLines = rawLines.asSequence()
            .filter(::isEditableTargetRule)
            .map(String::trim)
            .toList()
        if (currentOriginalLines != expectedOriginalLines.map(String::trim)) {
            return ConfigReplaceResult.SOURCE_CHANGED
        }

        val output = ArrayList<String>(rawLines.size + addedLines.size)
        var sourceIndex = 0
        var additionsInserted = false
        for (rawLine in rawLines) {
            if (!isEditableTargetRule(rawLine)) {
                output.add(rawLine)
                continue
            }

            replacements[sourceIndex]?.let(output::add)
            sourceIndex++
            if (sourceIndex == expectedOriginalLines.size && !additionsInserted) {
                output.addAll(addedLines)
                additionsInserted = true
            }
        }
        if (sourceIndex != expectedOriginalLines.size) {
            return ConfigReplaceResult.SOURCE_CHANGED
        }
        if (!additionsInserted) output.addAll(addedLines)

        val content = output.joinToString("\n").trimEnd() + "\n"
        return if (writeFileAsRoot(CONFIG_FILE, content)) {
            ConfigReplaceResult.SUCCESS
        } else {
            ConfigReplaceResult.WRITE_FAILED
        }
    }

    private data class ConfigBlock(
        val group: String,
        val lines: MutableList<String>,
        val owners: MutableSet<String>
    )

    /**
     * 把 applist.conf 规整成“同一应用一块, 不同应用之间空一行”。
     *
     * 这样无论原文件尾部有没有换行, 添加/删除配置都不会把两个应用挤在一起,
     * 也不会因为重复操作不断累积多余空行。
     */
    private fun parseConfigBlocks(text: String): MutableList<ConfigBlock> {
        val blocks = mutableListOf<ConfigBlock>()
        val pendingComments = mutableListOf<String>()
        var current: ConfigBlock? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val owner = configLineOwner(line)
            if (owner == null) {
                pendingComments.add(line)
                continue
            }

            val group = configGroupName(owner)
            val block = if (current?.group == group) {
                current!!
            } else {
                ConfigBlock(group, mutableListOf(), mutableSetOf()).also {
                    blocks.add(it)
                    current = it
                }
            }
            if (pendingComments.isNotEmpty()) {
                block.lines.addAll(pendingComments)
                pendingComments.clear()
            }
            block.lines.add(line)
            block.owners.add(owner)
        }

        if (pendingComments.isNotEmpty()) {
            val block = current ?: ConfigBlock("", mutableListOf(), mutableSetOf()).also { blocks.add(it) }
            block.lines.addAll(pendingComments)
        }
        return blocks
    }

    private fun formatConfigBlocks(blocks: List<ConfigBlock>): String {
        val text = blocks
            .map { block -> block.lines.joinToString("\n") }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return if (text.isBlank()) "" else "$text\n"
    }

    private fun sortConfigRuleLines(lines: List<String>): List<String> {
        return lines.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { if (configRuleIsFallback(it.value)) 1 else 0 }
                    .thenByDescending { configRuleMaxCpu(it.value) }
                    .thenByDescending { configRuleMinCpu(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    private fun configRuleIsFallback(line: String): Boolean {
        val left = line.substringBefore("=").trim()
        return !left.contains("{") && !left.contains(":")
    }

    private fun configRuleMaxCpu(line: String): Int {
        return parseConfigRuleCpus(line).maxOrNull() ?: -1
    }

    private fun configRuleMinCpu(line: String): Int {
        return parseConfigRuleCpus(line).minOrNull() ?: -1
    }

    private fun parseConfigRuleCpus(line: String): Set<Int> {
        return parseConfigRuleCpusStrict(line.substringAfter("=", "").trim()).orEmpty()
    }

    private fun parseConfigRuleCpusStrict(value: String): Set<Int>? {
        val cpus = linkedSetOf<Int>()
        if (value.isBlank()) return null
        fun parseCpuToken(text: String): Int? {
            if (text.isEmpty()) return null
            if (text.length > 1 && text.startsWith("0")) return null
            if (!text.all { it.isDigit() }) return null
            return text.toIntOrNull()?.takeIf { it >= 0 && it <= 1024 }
        }
        val token = value.trim()
        if (token.contains(",")) return null
        val dash = token.indexOf('-')
        if (dash >= 0) {
            if (token.indexOf('-', dash + 1) >= 0) return null
            val start = parseCpuToken(token.substring(0, dash)) ?: return null
            val end = parseCpuToken(token.substring(dash + 1)) ?: return null
            if (start >= end) return null
            for (cpu in start..end) {
                cpus.add(cpu)
            }
        } else {
            val cpu = parseCpuToken(token) ?: return null
            cpus.add(cpu)
        }
        return cpus
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
        val cpus = linkedSetOf<Int>()
        for (part in text.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-')
            if (dash >= 0) {
                val start = token.substring(0, dash).toIntOrNull()
                val end = token.substring(dash + 1).toIntOrNull()
                if (start != null && end != null && start <= end) {
                    for (cpu in start..end) cpus.add(cpu)
                }
            } else {
                token.toIntOrNull()?.let { cpus.add(it) }
            }
        }
        return cpus
    }

    private fun configLineOwner(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        var key = line.substring(0, eq).trim()
        val brace = key.indexOf('{')
        if (brace >= 0) key = key.substring(0, brace).trim()
        return key.ifBlank { null }
    }

    private fun configGroupName(pkg: String): String {
        val base = pkg.substringBefore(':')
        return if (base != pkg && base.contains('.')) base else pkg
    }

    /**
     * 判断 applist.conf 的一行是否属于指定包名/进程名。
     * 支持 pkg=...、pkg{thread}=... 和 pkg:child=...; 注释行和空行不会匹配。
     */
    private fun lineBelongsToPackage(rawLine: String, pkg: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return false
        val eq = line.indexOf('=')
        if (eq <= 0) return false
        var key = line.substring(0, eq).trim()
        val brace = key.indexOf('{')
        if (brace >= 0) key = key.substring(0, brace).trim()
        return key == pkg
    }

    private const val HISTORY_IMPORT_SUFFIX = ".appopt-importing"

    private fun safeHistoryPackage(pkg: String): String {
        return pkg.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == ':' || ch == '-') ch else '_'
        }.joinToString("")
    }

    /** 读取某包名的原版历史 .log 内容；读不到时返回空字符串。 */
    fun readHistory(pkg: String): String {
        val safe = safeHistoryPackage(pkg)
        val out = runAsRoot("cat ${shellQuote("$HISTORY_DIR/$safe.log")} 2>/dev/null")
        return if (out.isNotErrored()) out else ""
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

    /** 仅删除已经成功入库的认领文件，不会触碰 C 后续生成的新 .log。 */
    fun completeHistoryImport(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        return runAsRoot("rm -f $claim; true").isNotErrored()
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

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    /**
     * 以 root 写入文件。
     * 内容先 base64，再通过 heredoc 在设备端还原，避免 shell 转义和长文本换行问题。
     */
    private fun writeFileAsRoot(path: String, content: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val parent = path.substringBeforeLast("/", "")
        val mkdir = if (parent.isNotBlank()) "mkdir -p '$parent'; " else ""
        val cmd = "${mkdir}base64 -d > '$path' << 'EOF_BASE64'\n$b64\nEOF_BASE64"
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
