package top.qixia.threads

import android.net.LocalServerSocket
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * 与 Rust 守护进程的 IPC 桥接。
 *
 * 守护进程运行在 /data/adb/modules/QixiaThreads/ 下, 该目录普通应用无权限读写,
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

    private const val MODULE_DIR = "/data/adb/modules/QixiaThreads"
    private const val MODULE_UPDATE_DIR = "/data/adb/modules_update/QixiaThreads"
    private const val CONFIG_DIR = "$MODULE_DIR/config"
    internal const val BIN_RS_FILE = "$CONFIG_DIR/bin/QiXiaRs"
    private const val LOG_DIR = "$MODULE_DIR/logs"
    private const val UPDATE_CONFIG_DIR = "$MODULE_UPDATE_DIR/config"
    private const val RUNTIME_STATE_DIR = "$CONFIG_DIR/state"
    private const val CMD_FILE = "$CONFIG_DIR/calibrate.cmd"
    private const val STATE_FILE = "$CONFIG_DIR/calibrate.state"
    private const val CONFIG_FILE = "$CONFIG_DIR/applist.conf"
    private const val RULE_HEALTH_FILE = "$RUNTIME_STATE_DIR/rule_health.tsv"
    private const val RULE_HEALTH_RESET_FILE = "$RUNTIME_STATE_DIR/rule_health.reset"
    private const val AUTO_AFFINITY_FILE = "$CONFIG_DIR/auto_affinity.conf"

    data class AutomaticHistoryState(val supported: Boolean = false, val enabled: Boolean = false)

    fun readAutomaticHistory(): AutomaticHistoryState {
        val result = readRootCommandResult("""
            [ "${'$'}(sed -n 's/^version=//p' '$CONFIG_DIR/auto_affinity.state')" = '1' ] || exit 1
            cat '$POLICY_FILE' || exit 1
        """.trimIndent())
        return if (result.success) parseAutomaticHistory(result.output) else AutomaticHistoryState()
    }

    internal fun parseAutomaticHistory(raw: String): AutomaticHistoryState {
        val lines = raw.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotEmpty).toList()
        val version = lines.filter { it.substringBefore('=').trim() == "auto_history_version" }
            .singleOrNull()?.substringAfter('=', "")?.trim()
        if (version != "1") return AutomaticHistoryState()
        if (lines.any { '=' !in it }) return AutomaticHistoryState(supported = true)
        val enabled = lines.asSequence()
            .filter { it.substringBefore('=').trim() == "auto_history_enabled" }
            .toList().singleOrNull()?.substringAfter('=')?.trim() == "1"
        return AutomaticHistoryState(supported = true, enabled = enabled)
    }

    /** 在共享策略锁内只修改历史记录开关，不删除已有记录。 */
    fun setAutomaticHistory(enabled: Boolean): Boolean = synchronized(configMutationLock) {
        if (hasPendingModuleUpdate() || !readAutomaticHistory().supported) return@synchronized false
        if (enabled && !PrivateCaptureStorage.register()) return@synchronized false
        val result = runRootCommand(automaticHistoryMutationScript(enabled))
        result.success && readAutomaticHistory().let { it.supported && it.enabled == enabled }
    }

    internal fun automaticHistoryMutationScript(enabled: Boolean): String = policyMutationScript("""
        [ ! -e '$POLICY_UPDATE_FILE' ] || exit 1
        [ -f "${'$'}target" ] || exit 1
        awk -v enabled='${if (enabled) 1 else 0}' '
        {
            clean=${'$'}0; sub(/#.*/, "", clean); split_at=index(clean, "=")
            key=substr(clean, 1, split_at-1); value=substr(clean, split_at+1)
            gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "", key)
            gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "", value)
            if (split_at && key == "auto_history_version") { versions++; version=value }
            if (split_at && key == "auto_history_enabled") {
                if (!written) print "auto_history_enabled=" enabled
                written=1; next
            }
            print
        }
        END {
            if (versions != 1 || version != "1") exit 1
            if (!written) print "auto_history_enabled=" enabled
        }' "${'$'}target" > "${'$'}tmp" || exit 1
        chmod 0644 "${'$'}tmp" || exit 1
        mv -f "${'$'}tmp" "${'$'}target" || exit 1
    """.trimIndent())

    data class AutomaticAffinityState(
        val supported: Boolean = false,
        val packages: Set<String> = emptySet(),
        val activePackage: String = "",
        val state: String = "idle",
        val detail: String = "等待所选游戏进入前台"
    )

    fun readAutomaticAffinity(): AutomaticAffinityState {
        val result = readRootCommandResult("""
            snapshot=${'$'}(cat '$CONFIG_DIR/auto_affinity.state') || exit 1
            printf '%s\n' "${'$'}snapshot"
            printf '\n__PACKAGES__\n'
            if [ -f '$AUTO_AFFINITY_FILE' ]; then cat '$AUTO_AFFINITY_FILE' || exit 1; fi
            printf '\n__LIVE__\n'
            pid=${'$'}(printf '%s\n' "${'$'}snapshot" | sed -n 's/^pid=//p')
            boot=${'$'}(printf '%s\n' "${'$'}snapshot" | sed -n 's/^boot_id=//p')
            start=${'$'}(printf '%s\n' "${'$'}snapshot" | sed -n 's/^start_ticks=//p')
            case "${'$'}pid:${'$'}start" in *[!0-9:]*|:*|*:) exit 0;; esac
            [ "${'$'}pid" -gt 0 ] && [ "${'$'}start" -gt 0 ] || exit 0
            [ "${'$'}boot" = "${'$'}(cat /proc/sys/kernel/random/boot_id)" ] || exit 0
            [ "${'$'}(readlink /proc/${'$'}pid/exe)" = '$BIN_RS_FILE' ] || exit 0
            stat=${'$'}(cat /proc/${'$'}pid/stat) || exit 0
            stat=${'$'}{stat##*) }
            set -- ${'$'}stat
            [ "${'$'}#" -ge 20 ] || exit 0
            shift 19
            [ "${'$'}1" = "${'$'}start" ] && printf 1
            true
        """.trimIndent())
        if (!result.success) return AutomaticAffinityState()
        return parseAutomaticAffinity(result.output)
    }

    internal fun parseAutomaticAffinity(raw: String): AutomaticAffinityState {
        if ("__PACKAGES__" !in raw || "__LIVE__" !in raw) return AutomaticAffinityState()
        val lines = raw.substringBefore("__PACKAGES__").lineSequence().filter(String::isNotBlank).toList()
        val fields = lines.mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.take(i) to it.drop(i + 1) else null
        }.toMap()
        if (fields["version"] != "1" || fields.size != lines.size) return AutomaticAffinityState()
        val selected = parseAutomaticAffinityPackages(raw.substringAfter("__PACKAGES__").substringBefore("__LIVE__"))
        if (raw.substringAfter("__LIVE__").trim() != "1") {
            return AutomaticAffinityState(true, selected, state = "stopped", detail = "自动分配守护尚未运行")
        }
        return AutomaticAffinityState(true, selected, fields["package"].orEmpty(),
            fields["state"] ?: "idle", fields["detail"] ?: "等待所选游戏进入前台")
    }

    /** 与 Rust 一致；每行只读取有效包名，重复包名合并。 */
    internal fun parseAutomaticAffinityPackages(raw: String): Set<String> = buildSet {
        raw.lineSequence().forEach { rawLine ->
            val pkg = rawLine.substringBefore('#').trim()
            if (isValidBasePackage(pkg) && pkg.split('.').all { it.isNotEmpty() }) {
                add(pkg)
            }
        }
    }

    /** 只改变应用模式，手动规则仍保留在磁盘上。 */
    fun setAutomaticAffinity(pkg: String, enabled: Boolean): Boolean = synchronized(configMutationLock) {
        if (!isValidBasePackage(pkg)) return@synchronized false
        val token = UUID.randomUUID().toString()
        val result = runRootCommand(automaticAffinityMutationScript(pkg, enabled, token))
        result.success && result.output.trim() == token
    }

    internal fun automaticAffinityMutationScript(
        pkg: String, enabled: Boolean, token: String,
        configPath: String = AUTO_AFFINITY_FILE, statePath: String = "$CONFIG_DIR/auto_affinity.state"
    ): String {
        require(isValidBasePackage(pkg) && token.matches(Regex("[a-zA-Z0-9-]+")))
        val tmp = "$configPath.$token.tmp"
        return """
            ${if (enabled) automaticAffinitySupportCheck(statePath) else ":"}
            lock=${shellQuote("$configPath.lock")}
            waited=0
            while ! { [ ! -e "${'$'}lock" ] && mkdir "${'$'}lock" 2>/dev/null &&
                (set -C; printf '%s' '$token' > "${'$'}lock/owner") 2>/dev/null; }; do
                now=${'$'}(date +%s)
                mtime=${'$'}(stat -c %Y "${'$'}lock" 2>/dev/null || printf 0)
                if [ "${'$'}mtime" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((mtime + 30))" ]; then
                    rm -f "${'$'}lock/owner"
                    if rmdir "${'$'}lock" 2>/dev/null; then continue; fi
                fi
                waited=${'$'}((waited + 1))
                [ "${'$'}waited" -ge 3 ] && exit 1
                sleep 1
            done
            tmp=${shellQuote(tmp)}
            trap 'rm -f "${'$'}tmp"; if [ "${'$'}(cat "${'$'}lock/owner" 2>/dev/null)" = "$token" ]; then rm -f "${'$'}lock/owner"; rmdir "${'$'}lock" 2>/dev/null; fi' EXIT
            if [ -f ${shellQuote(configPath)} ]; then
                awk -v pkg='$pkg' '
                    { line=${'$'}0; sub(/#.*/, "", line)
                      gsub(/^[ \t]+|[ \t\r]+${'$'}/, "", line)
                      if (line != pkg) print ${'$'}0 }
                ' ${shellQuote(configPath)} > "${'$'}tmp" || exit 1
            else
                : > "${'$'}tmp" || exit 1
            fi
            ${if (enabled) "printf '%s\\n' '$pkg' >> \"${'$'}tmp\" || exit 1" else ":"}
            chmod 0644 "${'$'}tmp" || exit 1
            mv -f "${'$'}tmp" ${shellQuote(configPath)} || exit 1
            printf '%s' '$token'
        """.trimIndent()
    }

    /** 在同一次 Root 操作中检查能力；保存模式无需依赖实时运行状态。 */
    internal fun automaticAffinitySupportCheck(path: String): String = """
        awk '
            /^[[:space:]]*${'$'}/ { next }
            {
                sub(/\r${'$'}/, "")
                equal=index(${'$'}0, "=")
                if (equal <= 1) { invalid=1; next }
                key=substr(${'$'}0, 1, equal-1)
                if (seen[key]++) invalid=1
                if (key == "version") version=substr(${'$'}0, equal+1)
            }
            END { exit (invalid || version != "1") }
        ' ${shellQuote(path)} || exit 1
    """.trimIndent()
    private const val CONFIG_LOCK_DIR = "$CONFIG_DIR/applist.conf.lock"
    private const val POLICY_FILE = "$CONFIG_DIR/calib_policy.conf"
    private const val POLICY_LOCK_DIR = "$CONFIG_DIR/calib_policy.conf.lock"
    private const val POLICY_UPDATE_FILE = "$UPDATE_CONFIG_DIR/calib_policy.conf"
    private const val RS_RESTART_FILE = "$CONFIG_DIR/.qixia_restart_rs_daemon"
    private val HISTORY_DIR: String get() = PrivateCaptureStorage.history.absolutePath
    private const val LOG_FILE = "$LOG_DIR/QixiaThreads.log"
    private const val FOREGROUND_HELPER_LOG_FILE = "$LOG_DIR/ForegroundHelper.log"
    private const val FPS_CMD_FILE = "$CONFIG_DIR/fps.cmd"
    private const val FOREGROUND_TASK_STATE_FILE = "$CONFIG_DIR/foreground_task.state"
    private const val FOREGROUND_HELPER_SCRIPT = "$CONFIG_DIR/tools/qixia_foreground_helper.sh"
    private const val FOREGROUND_TASK_MAX_AGE_MS = 25_000L
    private const val DAEMON_SOCKET_CALLBACK_PREFIX = "qixia.callback top.qixia.threads v1 "
    private const val ROOT_TIMEOUT_SECONDS = 15L
    const val REQUIRED_MODULE_VERSION_CODE = 202
    const val REQUIRED_MODULE_VERSION_NAME = "2.0.2"
    private val configMutationLock = Any()

    /** 检测设备是否有可用 root；首次调用可能触发 Magisk 授权弹窗。 */
    fun hasRoot(): Boolean {
        val granted = runAsRoot("id -u").trim() == "0"
        if (granted) PrivateCaptureStorage.register()
        return granted
    }

    fun hasPendingModuleUpdate(): Boolean {
        val command = """
            prop='$MODULE_UPDATE_DIR/module.prop'
            id=
            code=
            [ -f "${'$'}prop" ] && id=${'$'}(sed -n 's/^id=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            [ -f "${'$'}prop" ] && code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            if [ "${'$'}id" = 'QixiaThreads' ] &&
                [ -n "${'$'}code" ] && [ "${'$'}{code#*[!0-9]}" = "${'$'}code" ] && [ "${'$'}code" -gt 0 ] 2>/dev/null &&
                [ -f '$MODULE_UPDATE_DIR/service.sh' ] &&
                [ -f '$MODULE_UPDATE_DIR/config/bin/QiXiaRs' ]; then
                printf 1
            else
                printf 0
            fi
        """.trimIndent()
        return runAsRoot(command)
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
        val success: Boolean,
        val timedOut: Boolean = false
    )

    enum class CalibrationStartStatus {
        STARTED,
        MODULE_UPDATE_REQUIRED,
        STORAGE_UNAVAILABLE,
        BUSY,
        INVALID_PACKAGE,
        ROOT_TIMEOUT,
        ROOT_COMMAND_FAILED,
        TARGET_PROCESS_NOT_READY,
        TARGET_PROCESS_EXITED,
        DAEMON_NO_RESPONSE
    }

    data class CalibrationStartResult(
        val status: CalibrationStartStatus,
        val state: String = ""
    ) {
        val started: Boolean
            get() = status == CalibrationStartStatus.STARTED
    }

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
        val versionName: String? = null,
        val pid: Int? = null,
        val raw: String = ""
    )

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

    /** 请求守护进程把指定应用的异常规则恢复为待检测状态。 */
    fun requestRuleHealthReset(pkg: String): Boolean = synchronized(configMutationLock) {
        val basePkg = normalizeRuleHealthResetPackage(pkg) ?: return@synchronized false
        if (basePkg in readAutomaticAffinity().packages) return@synchronized false
        val content = "$basePkg\n"
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val token = UUID.randomUUID().toString()
        readRootCommandResult(buildRuleHealthResetWriteCommand(b64, token)).success
    }

    /** App 只原子追加请求文件；rule_health.tsv 始终由守护进程独占写入。 */
    internal fun buildRuleHealthResetWriteCommand(encodedContent: String, token: String): String {
        val tmp = "$RULE_HEALTH_RESET_FILE.app-$token.tmp"
        return """
            state='$RUNTIME_STATE_DIR'
            target='$RULE_HEALTH_RESET_FILE'
            tmp='$tmp'
            mkdir -p "${'$'}state" || exit 1
            trap 'rm -f "${'$'}tmp"' EXIT
            if [ -f "${'$'}target" ]; then
                if ! cat "${'$'}target" > "${'$'}tmp"; then
                    [ ! -e "${'$'}target" ] || exit 1
                    : > "${'$'}tmp" || exit 1
                fi
                [ ! -s "${'$'}tmp" ] || [ "${'$'}(tail -c 1 "${'$'}tmp" 2>/dev/null)" = '' ] ||
                    printf '\n' >> "${'$'}tmp" || exit 1
            elif [ -e "${'$'}target" ]; then
                exit 1
            else
                : > "${'$'}tmp" || exit 1
            fi
            if ! base64 -d >> "${'$'}tmp" << 'EOF_BASE64'
            $encodedContent
            EOF_BASE64
            then
                exit 1
            fi
            chmod 0644 "${'$'}tmp" 2>/dev/null || true
            chown 0:0 "${'$'}tmp" 2>/dev/null || true
            mv -f "${'$'}tmp" "${'$'}target" || exit 1
        """.trimIndent()
    }

    internal fun normalizeRuleHealthResetPackage(value: String): String? {
        val basePkg = value.trim().removePrefix("\uFEFF").substringBefore(':').trim()
        if (basePkg.isEmpty() ||
            basePkg.toByteArray(Charsets.UTF_8).size > RuleConfigLogic.MAX_OWNER_BYTES ||
            basePkg.startsWith('.') || basePkg.endsWith('.') ||
            basePkg.split('.').any(String::isEmpty) ||
            basePkg.any {
                it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' && it != '.'
            }) {
            return null
        }
        return basePkg
    }

    /** 守护进程认领请求前先乐观更新界面，稍后的状态复读会校正最终结果。 */
    internal fun markRuleHealthResetPending(
        health: Map<String, RuleHealth>,
        pkg: String
    ): Map<String, RuleHealth> {
        val basePkg = normalizeRuleHealthResetPackage(pkg) ?: return health
        var changed = false
        val updated = LinkedHashMap<String, RuleHealth>(health.size)
        health.forEach { (key, entry) ->
            val resetEntry = entry.status != RuleHealthStatus.VALID &&
                !(entry.status == RuleHealthStatus.PENDING && entry.missCount == 0) &&
                normalizeRuleHealthResetPackage(entry.owner) == basePkg
            val next = if (resetEntry) {
                changed = true
                entry.copy(
                    status = RuleHealthStatus.PENDING,
                    missCount = 0,
                    firstObservedAt = 0L,
                    lastMatchedAt = 0L,
                    lastCheckedAt = 0L
                )
            } else {
                entry
            }
            updated[key] = next
        }
        return if (changed) updated else health
    }

    internal fun isValidBasePackage(value: String): Boolean {
        if (value.isBlank() || value.length >= 128 || '.' !in value) return false
        return value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.'
        }
    }

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
            success = out.isNotErrored(),
            timedOut = out.contains(ROOT_TIMEOUT_MARK)
        )
    }

    fun runRootCommandStreaming(
        cmd: String,
        timeoutSeconds: Long = 15L,
        onOutput: (String) -> Unit
    ): RootCommandResult {
        return runAsRootStreaming(cmd, timeoutSeconds.coerceAtLeast(1L), onOutput)
    }

    fun updateFloatingBallLease(active: Boolean, targetPackage: String?, calibrating: Boolean) {
        val lease = "$RUNTIME_STATE_DIR/floating_ball.lease"
        if (!active) {
            runRootCommand("rm -f ${shellQuote(lease)}")
            return
        }
        val pid = android.os.Process.myPid()
        val target = targetPackage.orEmpty().replace(Regex("[^A-Za-z0-9._:-]"), "")
        val token = UUID.randomUUID().toString().replace("-", "")
        val tmp = "$lease.$token.tmp"
        val command = """
            mkdir -p ${shellQuote(RUNTIME_STATE_DIR)}
            starttime=${'$'}(sed 's/^.*) //' /proc/$pid/stat 2>/dev/null | awk '{print ${'$'}20}')
            [ -n "${'$'}starttime" ] || exit 1
            {
                printf 'version=1\n'
                printf 'boot_id=%s\n' "${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
                printf 'pid=$pid\n'
                printf 'process_starttime=%s\n' "${'$'}starttime"
                printf 'started_at=%s\n' "${'$'}(date +%s 2>/dev/null)"
                printf 'target_package=%s\n' '$target'
                printf 'calibrating=%s\n' '${if (calibrating) 1 else 0}'
            } > ${shellQuote(tmp)} || exit 1
            chmod 0644 ${shellQuote(tmp)} 2>/dev/null || true
            mv -f ${shellQuote(tmp)} ${shellQuote(lease)}
        """.trimIndent()
        runRootCommand(command)
    }

    /** 只读 module.prop 获取已刷入模块版本，不触发守护进程的策略同步或其他写操作。 */
    fun readModuleVersion(): ModuleVersion? {
        val cmd = """
            prop="$MODULE_DIR/module.prop"
            prop_code=
            prop_version=
            [ -f "${'$'}prop" ] && prop_code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            [ -f "${'$'}prop" ] && prop_version=${'$'}(sed -n 's/^version=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            printf 'propCode=%s\npropVersion=%s\n' "${'$'}prop_code" "${'$'}prop_version"
        """.trimIndent()
        val out = runAsRoot(cmd)
        if (!out.isNotErrored()) return null
        val values = out.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
            }
            .toMap()
        val propVersion = values["propVersion"].orEmpty().removePrefix("v").trim()
        val code = values["propCode"]?.toIntOrNull() ?: return null
        val name = propVersion.takeIf { it.isNotBlank() }
            ?: code.toString()
        return ModuleVersion(
            versionName = name,
            versionCode = code,
            binaryVersionName = null,
            raw = out
        )
    }

    /**
     * 检测当前运行的守护进程是否确实是本 App 可交互的 QixiaThreads。
     *
     * 这里不使用 pgrep 判断进程名，因为开源后二改版本也可能叫 QixiaThreads。
     * 只有守护进程能按随机 token 反连 App 的一次性 socket，才认为验证通过。
     */
    fun isDaemonRunning(): Boolean = readDaemonRuntime().running

    fun readDaemonRuntime(): DaemonRuntime = verifyDaemonSocketReverse()

    private fun verifyDaemonSocketReverse(): DaemonRuntime {
        val socketName = "qixia_verify_${android.os.Process.myPid()}_${System.nanoTime()}"
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
            }, "QixiaThreadsDaemonVerify").apply {
                isDaemon = true
                start()
            }

            val rootThread = Thread({
                val daemonBin = shellQuote(BIN_RS_FILE)
                runAsRoot(
                    "daemon_bin=$daemonBin; \"\$daemon_bin\" --ping-daemon '$socketName' '$token' 2>/dev/null",
                    timeoutSeconds = 4L
                )
            }, "QixiaThreadsDaemonPing").apply {
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
            if (rootThread.isAlive) {
                rootThread.interrupt()
                rootThread.join(1200)
            }
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
        return DaemonRuntime(
            running = true,
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

    /**
     * 批量判断非 APK 配置项是否对应正在运行的系统进程。
     * 用于 UI 区分“系统组件”和“未安装/配置残留”。优先查询 QixiaThreads 文件索引；
     * 旧模块或索引不可用时才回退一次 /proc 遍历。
     */
    fun findRunningProcessNames(names: Collection<String>): Set<String> {
        val targets = names.map { it.trim().replace("'", "") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return emptySet()
        val targetSet = targets.toHashSet()

        val targetArgs = targets.joinToString(" ") { "'$it'" }
        val daemonBin = shellQuote(BIN_RS_FILE)
        val cmd = """
            daemon_bin=$daemonBin
            if [ -x "${'$'}daemon_bin" ] &&
                "${'$'}daemon_bin" --find-processes $targetArgs 2>/dev/null; then
                exit 0
            fi

            for proc in /proc/[0-9]*; do
                [ -r "${'$'}proc/comm" ] || continue
                comm=''
                IFS= read -r comm < "${'$'}proc/comm" 2>/dev/null || true
                [ -n "${'$'}comm" ] && printf '%s\n' "${'$'}comm"

                [ -r "${'$'}proc/cmdline" ] || continue
                first=''
                IFS= read -r -d '' first < "${'$'}proc/cmdline" 2>/dev/null || true
                [ -n "${'$'}first" ] && printf '%s\n' "${'$'}first"
                base=${'$'}{first##*/}
                [ "${'$'}base" = "${'$'}first" ] || printf '%s\n' "${'$'}base"
            done
            true
        """.trimIndent()
        val out = runAsRoot(cmd)
        val clean = out.substringBefore(ERR_MARK)
        return clean.lineSequence()
            .map { it.trim() }
            .filter { it in targetSet }
            .toSet()
    }

    fun readTopAppState(pkg: String): TopAppState {
        val safePkg = cleanCommandArg(pkg.substringBefore(':'), allowColon = false)
        if (safePkg.isBlank()) {
            return TopAppState(false, null, 0, emptyList(), backend = "invalid")
        }
        val daemonBin = shellQuote(BIN_RS_FILE)
        val cmd = buildString {
            append("daemon_bin=")
            append(daemonBin)
            append("; \"\$daemon_bin\"")
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

    /** 下发开始线程负载采样命令，并区分 Root、目标进程和守护确认失败。 */
    fun startCalibration(pkg: String): CalibrationStartResult {
        if (pkg.isBlank()) return CalibrationStartResult(CalibrationStartStatus.INVALID_PACKAGE)
        val safe = cleanCommandArg(pkg, allowColon = true)
        if (safe.isBlank()) return CalibrationStartResult(CalibrationStartStatus.INVALID_PACKAGE)
        if (!PrivateCaptureStorage.register()) return CalibrationStartResult(CalibrationStartStatus.STORAGE_UNAVAILABLE)

        val runtime = readDaemonRuntime()
        if (!runtime.running || runtime.pid == null) return CalibrationStartResult(CalibrationStartStatus.DAEMON_NO_RESPONSE)
        if (!readAutomaticAffinity().supported ||
            readRootFile("$CONFIG_DIR/calibration_draft.version")?.trim() != "2 ${runtime.pid}") {
            return CalibrationStartResult(CalibrationStartStatus.MODULE_UPDATE_REQUIRED)
        }

        val initialState = readState()
        val writeResult = runRootCommand(
            "mkdir -p '$CONFIG_DIR'; printf '%s' 'start $safe' > $CMD_FILE",
            timeoutSeconds = 5L
        )
        if (!writeResult.success) {
            return CalibrationStartResult(
                if (writeResult.timedOut) {
                    CalibrationStartStatus.ROOT_TIMEOUT
                } else {
                    CalibrationStartStatus.ROOT_COMMAND_FAILED
                }
            )
        }

        val deadline = System.currentTimeMillis() + 2500L
        while (System.currentTimeMillis() < deadline) {
            val state = readState()
            val status = calibrationStartStatusFromState(state, safe)
            if (status == CalibrationStartStatus.STARTED ||
                (state != initialState && status != null)
            ) {
                return CalibrationStartResult(status ?: CalibrationStartStatus.DAEMON_NO_RESPONSE, state)
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return CalibrationStartResult(CalibrationStartStatus.DAEMON_NO_RESPONSE, state)
            }
        }

        val targetRunning = findRunningProcessNames(listOf(safe)).contains(safe)
        return CalibrationStartResult(
            if (targetRunning) {
                CalibrationStartStatus.DAEMON_NO_RESPONSE
            } else {
                CalibrationStartStatus.TARGET_PROCESS_NOT_READY
            },
            readState()
        )
    }

    /** 下发停止采样命令，守护随后落盘待确认建议，不修改生效配置。 */
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

    /** 读取前台助手最近的输出，限制行数以避免异常大日志拖慢界面。 */
    fun readForegroundHelperLog(maxLines: Int = 500): String {
        val out = runAsRoot("tail -n $maxLines $FOREGROUND_HELPER_LOG_FILE 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /** 按需读取有界事件窗口，缺失文件视为空，权限不足或读取失败视为错误。 */
    fun readEventLogWindow(foreground: Boolean): String {
        val legacy = if (foreground) FOREGROUND_HELPER_LOG_FILE else LOG_FILE
        val events = "$LOG_DIR/${if (foreground) "ForegroundEvents" else "Runtime"}.log"
        val files = listOf(legacy, "$events.2", "$events.1", events).joinToString(" ", transform = ::shellQuote)
        val command = """
            for f in $files; do
                [ -e "${'$'}f" ] || continue
                [ -r "${'$'}f" ] || exit 1
                size=${'$'}(wc -c < "${'$'}f") || exit 1
                if [ "${'$'}size" -gt 131072 ]; then
                    tail -c 131072 "${'$'}f" | sed '1d' | tail -n 400
                else
                    tail -n 400 "${'$'}f" || exit 1
                fi
                printf '\n'
            done
        """.trimIndent()
        val result = readRootCommandResult(command)
        check(result.success) { "日志读取失败，请检查 Root 权限和模块目录" }
        return result.output
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

    internal fun calibrationStartStatusFromState(
        state: String,
        pkg: String
    ): CalibrationStartStatus? {
        if (statePackage(state, "sampling") == pkg) return CalibrationStartStatus.STARTED
        if (stateReason(state) == "busy" && stateField(state, "requested") == pkg) {
            return CalibrationStartStatus.BUSY
        }
        if (statePackage(state, "rejected") == pkg) {
            return when (stateReason(state)) {
                "no_process" -> CalibrationStartStatus.TARGET_PROCESS_NOT_READY
                "storage_unavailable" -> CalibrationStartStatus.STORAGE_UNAVAILABLE
                else -> CalibrationStartStatus.DAEMON_NO_RESPONSE
            }
        }
        if (statePackage(state, "done") == pkg && stateReason(state) == "short") {
            return CalibrationStartStatus.TARGET_PROCESS_EXITED
        }
        return null
    }

    private fun stateReason(state: String): String? {
        return stateField(state, "reason")
    }

    private fun stateField(state: String, key: String): String? {
        return state.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=', missingDelimiterValue = "") == key }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?.trim()
            ?.ifBlank { null }
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

    data class SettingsPolicySnapshot(
        val hasRoot: Boolean,
        val moduleVersion: ModuleVersion?,
        val policyFile: PolicyFile,
        val cpusetSupported: Boolean,
        val presentCpus: Set<Int>
    )

    enum class RustDaemonRestartStatus {
        REQUESTED,
        NOT_RUNNING,
        FAILED
    }

    fun supportsCustomCpuset(): Boolean {
        val binary = shellQuote(BIN_RS_FILE)
        val result = readRootCommandResult(
            "if [ -x $binary ] && $binary -h 2>/dev/null | grep -q -- '--cpuset-name'; " +
                "then printf 1; else printf 0; fi"
        )
        return result.success && result.output.trim() == "1"
    }

    /**
     * 设置页一次性读取 Root、模块版本、策略、cpuset 能力和 CPU 范围。
     * 这些字段原先会分别启动多个 su，部分 Root 管理器会为每次调用显示提示，
     * 也会让首次进入设置页明显卡顿。单次快照保持原有只读语义。
     */
    fun readSettingsPolicySnapshot(): SettingsPolicySnapshot {
        val token = UUID.randomUUID().toString().replace("-", "")
        val begin = "__QIXIA_POLICY_BEGIN_${token}__"
        val end = "__QIXIA_POLICY_END_${token}__"
        val binary = shellQuote(BIN_RS_FILE)
        val command = """
            uid=${'$'}(id -u 2>/dev/null) || exit 1
            [ "${'$'}uid" = 0 ] || exit 1
            prop='$MODULE_DIR/module.prop'
            prop_code=
            prop_version=
            [ -f "${'$'}prop" ] && prop_code=${'$'}(sed -n 's/^versionCode=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            [ -f "${'$'}prop" ] && prop_version=${'$'}(sed -n 's/^version=//p' "${'$'}prop" 2>/dev/null | head -n 1)
            if [ -f '$POLICY_UPDATE_FILE' ]; then
                pending=1
                policy='$POLICY_UPDATE_FILE'
            elif [ ! -e '$POLICY_UPDATE_FILE' ]; then
                pending=0
                policy='$POLICY_FILE'
            else
                exit 1
            fi
            if [ -f "${'$'}policy" ]; then
                exists=1
            elif [ ! -e "${'$'}policy" ]; then
                exists=0
            else
                exit 1
            fi
            if [ -x $binary ] && $binary -h 2>/dev/null | grep -q -- '--cpuset-name'; then
                cpuset=1
            else
                cpuset=0
            fi
            present=${'$'}(cat /sys/devices/system/cpu/present 2>/dev/null || true)
            printf 'uid=%s\npropCode=%s\npropVersion=%s\npending=%s\npolicy=%s\nexists=%s\ncpuset=%s\npresent=%s\n' \
                "${'$'}uid" "${'$'}prop_code" "${'$'}prop_version" "${'$'}pending" \
                "${'$'}policy" "${'$'}exists" "${'$'}cpuset" "${'$'}present"
            printf '%s\n' '$begin'
            [ "${'$'}exists" = 0 ] || cat "${'$'}policy" || exit 1
            printf '\n%s\n' '$end'
        """.trimIndent()
        val result = readRootCommandResult(command)
        val failedPolicy = PolicyFile(
            content = "",
            lockedByPendingUpdate = false,
            path = POLICY_FILE,
            exists = false,
            readSuccess = false
        )
        if (!result.success) {
            return SettingsPolicySnapshot(false, null, failedPolicy, false, emptySet())
        }
        val beginLine = "$begin\n"
        val beginIndex = result.output.indexOf(beginLine)
        val contentStart = beginIndex.takeIf { it >= 0 }?.plus(beginLine.length) ?: -1
        val endIndex = if (contentStart >= 0) result.output.indexOf("\n$end", contentStart) else -1
        if (beginIndex < 0 || contentStart < 0 || endIndex < contentStart) {
            return SettingsPolicySnapshot(true, null, failedPolicy, false, emptySet())
        }
        val values = result.output.substring(0, beginIndex)
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to
                    line.substring(separator + 1).trimEnd('\r')
            }
            .toMap()
        val path = values["policy"].orEmpty().takeIf(String::isNotBlank) ?: POLICY_FILE
        val pending = values["pending"] == "1"
        val exists = values["exists"] == "1"
        val versionCode = values["propCode"]?.toIntOrNull()
        val versionName = values["propVersion"].orEmpty().removePrefix("v").trim()
        val version = versionCode?.let { code ->
            ModuleVersion(
                versionName = versionName.ifBlank { code.toString() },
                versionCode = code,
                binaryVersionName = null,
                raw = "propCode=$code\npropVersion=$versionName\n"
            )
        }
        return SettingsPolicySnapshot(
            hasRoot = values["uid"] == "0",
            moduleVersion = version,
            policyFile = PolicyFile(
                content = if (exists) result.output.substring(contentStart, endIndex) else "",
                lockedByPendingUpdate = pending,
                path = path,
                exists = exists,
                readSuccess = true
            ),
            cpusetSupported = values["cpuset"] == "1",
            presentCpus = parseCpuRangeList(values["present"].orEmpty())
        )
    }

    fun restartRustDaemon(): RustDaemonRestartStatus {
        val binary = shellQuote(BIN_RS_FILE)
        val restartFlag = shellQuote(RS_RESTART_FILE)
        val command = """
            bin=$binary
            flag=$restartFlag
            [ -x "${'$'}bin" ] || { printf failed; exit 0; }
            pids="${'$'}("${'$'}bin" --find-pid QiXiaRs 2>/dev/null)"
            [ -n "${'$'}pids" ] || pids="${'$'}(pidof QiXiaRs 2>/dev/null) ${'$'}(pgrep -x QiXiaRs 2>/dev/null)"
            targets=""
            for pid in ${'$'}pids; do
                [ -n "${'$'}pid" ] || continue
                [ "${'$'}(readlink "/proc/${'$'}pid/exe" 2>/dev/null)" = "${'$'}bin" ] || continue
                case " ${'$'}targets " in *" ${'$'}pid "*) ;; *) targets="${'$'}targets ${'$'}pid" ;; esac
            done
            if [ -z "${'$'}targets" ]; then
                rm -f "${'$'}flag" 2>/dev/null || true
                printf not_running
                exit 0
            fi
            : > "${'$'}flag" || { printf failed; exit 0; }
            for pid in ${'$'}targets; do
                if ! kill "${'$'}pid" 2>/dev/null; then
                    rm -f "${'$'}flag"
                    printf failed
                    exit 0
                fi
            done
            printf requested
        """.trimIndent()
        val result = readRootCommandResult(command)
        if (!result.success) return RustDaemonRestartStatus.FAILED
        return when (result.output.trim()) {
            "requested" -> RustDaemonRestartStatus.REQUESTED
            "not_running" -> RustDaemonRestartStatus.NOT_RUNNING
            else -> RustDaemonRestartStatus.FAILED
        }
    }

    /**
     * 读取自动校准策略文件。
     * 如果 /data/adb/modules_update/QixiaThreads/config/calib_policy.conf 存在，说明模块更新已刷入但未重启，
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
        val existsMarker = "__QIXIA_POLICY_EXISTS_${UUID.randomUUID()}__"
        val missingMarker = "__QIXIA_POLICY_MISSING_${UUID.randomUUID()}__"
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

    /** 仅更新界面编辑的策略字段，保留磁盘上最新的记录开关和运行设置。 */
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
        val migratedDeprecatedFormat: Boolean = false,
        val detail: String? = null
    ) {
        val success: Boolean
            get() = status == RuleOutputFormatApplyStatus.SUCCESS
    }

    /**
     * 在配置锁内转换现有规则并保存策略。策略写入失败时恢复原始 applist.conf，
     * 避免现有规则格式与后续 Rust 校准生成格式不一致。
     */
    fun applyRuleOutputFormat(
        format: CalibPolicy.RuleOutputFormat,
        policyContent: String
    ): RuleOutputFormatApplyResult {
        val outputFormat = format.generationTarget()
        val normalizedPolicyContent = updatePolicyValuePreservingText(
            policyContent,
            "rule_output_format",
            outputFormat.wire
        ).let { removePolicyValuePreservingText(it, "rule_output_format_migration") }
        val lockFailure = RuleOutputFormatApplyResult(
            RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
            detail = "无法获取规则配置锁"
        )
        return withConfigMutation(lockFailure) { token ->
            ruleFormatPolicyFailure(readCalibPolicyRaw())?.let { return@withConfigMutation it }
            val raw = readConfigRawForMutation() ?: return@withConfigMutation lockFailure.copy(
                detail = "读取 applist.conf 失败"
            )
            val result = RuleFormatConverter.convert(raw, outputFormat, preserveDuplicates = true)
            val conversion = result.conversion ?: return@withConfigMutation RuleOutputFormatApplyResult(
                RuleOutputFormatApplyStatus.INVALID_CONFIG,
                detail = result.error
            )

            if (conversion.changed && !writeConfigFileLocked(conversion.content, token, expectedContent = raw)) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
                    detail = "规则文件已变化、模块待重启或写入失败，原文件已保留，请刷新后重试"
                )
            }

            if (writeCalibPolicyRaw(normalizedPolicyContent)) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    status = RuleOutputFormatApplyStatus.SUCCESS,
                    format = outputFormat,
                    ruleCount = conversion.ruleCount,
                    groupCount = conversion.groupCount,
                    changed = conversion.changed
                )
            }

            val rollbackOk = !conversion.changed ||
                writeConfigFileLocked(raw, token, expectedContent = conversion.content, allowPendingUpdate = true)
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
                    "策略保存失败；规则文件已被其他操作修改或恢复失败，未覆盖当前内容"
                }
            )
        }
    }

    /** 以用户选择的策略为准，外部复制的规则文件也遵循该设置。
     * 常见的未变化路径只读取和转换；需要写入时在锁内重读两个文件。 */
    fun synchronizeRuleOutputFormat(): RuleOutputFormatApplyResult {
        val lockFailure = RuleOutputFormatApplyResult(
            RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
            detail = "无法获取规则配置锁"
        )
        val initialPolicy = readCalibPolicyRaw()
        ruleFormatPolicyFailure(initialPolicy)?.let { return it }
        val initialRaw = readConfigRawForMutation() ?: return lockFailure.copy(detail = "读取 applist.conf 失败")
        val initialFormat = selectedRuleOutputFormat(initialPolicy)
        val initialResult = RuleFormatConverter.convert(initialRaw, initialFormat, preserveDuplicates = true)
        val initialConversion = initialResult.conversion ?: return RuleOutputFormatApplyResult(
            RuleOutputFormatApplyStatus.INVALID_CONFIG, format = initialFormat,
            detail = initialResult.error ?: "现有规则无法安全转换"
        )
        if (!initialConversion.changed) return ruleFormatSuccess(initialFormat, initialConversion)

        return withConfigMutation(lockFailure) { token ->
            val policyFile = readCalibPolicyRaw()
            ruleFormatPolicyFailure(policyFile)?.let { return@withConfigMutation it }
            val raw = readConfigRawForMutation() ?: return@withConfigMutation lockFailure.copy(
                detail = "读取 applist.conf 失败"
            )
            val targetFormat = selectedRuleOutputFormat(policyFile)
            val result = RuleFormatConverter.convert(raw, targetFormat, preserveDuplicates = true)
            val conversion = result.conversion
                ?: return@withConfigMutation RuleOutputFormatApplyResult(
                    RuleOutputFormatApplyStatus.INVALID_CONFIG,
                    format = targetFormat,
                    detail = result.error ?: "现有规则无法安全转换"
                )
            if (conversion.changed && !writeConfigFileLocked(conversion.content, token, expectedContent = raw)) {
                return@withConfigMutation RuleOutputFormatApplyResult(
                    RuleOutputFormatApplyStatus.CONFIG_WRITE_FAILED,
                    format = targetFormat,
                    ruleCount = conversion.ruleCount,
                    detail = "规则文件已变化、模块待重启或写入失败，原文件已保留，请刷新后重试"
                )
            }
            ruleFormatSuccess(targetFormat, conversion)
        }
    }

    /** 旧入口也必须遵循与 Compose 相同的用户格式设置。 */
    fun detectAndApplyRuleOutputFormat(): RuleOutputFormatApplyResult = synchronizeRuleOutputFormat()

    private fun selectedRuleOutputFormat(policy: PolicyFile): CalibPolicy.RuleOutputFormat =
        (if (policy.exists) CalibPolicy.parse(policy.content) else CalibPolicy.DEFAULT)
            .ruleOutputFormat.generationTarget()

    private fun ruleFormatPolicyFailure(policy: PolicyFile): RuleOutputFormatApplyResult? = when {
        !policy.readSuccess -> RuleOutputFormatApplyResult(RuleOutputFormatApplyStatus.POLICY_WRITE_FAILED,
            detail = "规则格式设置读取失败，已保留原规则")
        policy.lockedByPendingUpdate || hasPendingModuleUpdate() ->
            RuleOutputFormatApplyResult(RuleOutputFormatApplyStatus.POLICY_WRITE_FAILED,
                detail = "模块更新待重启，未修改规则格式")
        else -> null
    }

    private fun ruleFormatSuccess(format: CalibPolicy.RuleOutputFormat,
        conversion: RuleFormatConverter.Conversion): RuleOutputFormatApplyResult = RuleOutputFormatApplyResult(
        status = RuleOutputFormatApplyStatus.SUCCESS, format = format,
        ruleCount = conversion.ruleCount, groupCount = conversion.groupCount, changed = conversion.changed
    )

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

    /** 串行执行本应用的静态配置提交和自动模式交接；
     * 交接失败时保留草稿供重试，不报告成功。 */
    fun saveCalibrationRules(pkg: String, original: List<String>, rules: List<String>, cpus: Set<Int>): String? =
        synchronized(configMutationLock) {
            val current = readPkgRulesOrNull(listOf(pkg)) ?: return@synchronized "配置读取失败"
            if (current.toSet() != rules.toSet()) {
                when (replaceConfigRulesPreservingLayout(listOf(pkg), original, emptyMap(), rules, cpus, allowEmpty = true)) {
                    ConfigReplaceResult.SUCCESS -> Unit
                    ConfigReplaceResult.SOURCE_CHANGED -> return@synchronized "现有规则已被其他操作修改。请重新载入配置后再保存。"
                    else -> return@synchronized "配置保存失败，待确认结果已保留，请重试"
                }
            }
            if (!setAutomaticAffinity(pkg, false)) return@synchronized "规则已保存，但自动分配模式尚未关闭。请重试完成切换。"
            null
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
        allowedCpus: Set<Int>? = null,
        allowEmpty: Boolean = false
    ): ConfigReplaceResult {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty() || replacements.keys.any { it !in expectedOriginalLines.indices }) {
            return ConfigReplaceResult.INVALID
        }

        val finalLines = expectedOriginalLines.indices.mapNotNull(replacements::get) + addedLines
        if (finalLines.isEmpty() && !allowEmpty) return ConfigReplaceResult.INVALID
        val presentCpus = allowedCpus ?: readConfigAllowedCpus().takeIf { it.isNotEmpty() }
        val check = validateConfigRulesForPackages(
            targets,
            finalLines.joinToString("\n"),
            presentCpus
        )
        if (finalLines.isNotEmpty() && !check.ok) return ConfigReplaceResult.INVALID

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

            // 用户明确将所有行交回系统调度后，
            // 仍保留应用，以便日后校准。
            if (finalLines.isEmpty()) targets.forEach { pkg ->
                if (document.rules.none { it.owner == pkg && it.thread == null && it.cpus == "auto" }) {
                    output.add("$pkg=auto")
                }
            }

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

    fun readSystemCpuStatLine(): String {
        return runAsRoot("head -n 1 /proc/stat 2>/dev/null")
            .substringBefore(ERR_MARK)
            .lineSequence()
            .firstOrNull { it.startsWith("cpu ") }
            .orEmpty()
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

    internal fun removePolicyValuePreservingText(raw: String, key: String): String {
        val pattern = Regex(
            "(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[^\\r\\n]*(?:\\r?\\n|$)"
        )
        return pattern.replace(raw, "")
    }

    internal fun deprecatedRuleOutputMigration(raw: String): CalibPolicy.RuleOutputFormat? {
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            val index = line.indexOf('=')
            if (index <= 0 || line.substring(0, index).trim() != "rule_output_format_migration") {
                continue
            }
            return CalibPolicy.RuleOutputFormat.fromWire(line.substring(index + 1))
                .takeIf(CalibPolicy.RuleOutputFormat::requiresAuthorMigration)
        }
        return null
    }

    private const val HISTORY_IMPORT_SUFFIX = ".qixia-importing"

    /** 与 Rust 完成记录写入及保留策略共用同一个短期目录锁。 */
    private fun historyMutation(command: String, missingResult: String = "1"): String = """
        if [ ! -d '${PrivateCaptureStorage.root.absolutePath}' ]; then
            printf '%s' '$missingResult'
            exit 0
        fi
        history_lock='${PrivateCaptureStorage.root.absolutePath}/.history.lock'
        history_process_start() {
            history_stat=${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null) || return 1
            history_stat=${'$'}{history_stat##*) }
            set -- ${'$'}history_stat
            [ "${'$'}#" -ge 20 ] || return 1
            shift 19
            printf '%s' "${'$'}1"
        }
        history_start=${'$'}(history_process_start "${'$'}${'$'}") || exit 1
        history_owner="${'$'}${'$'}:${'$'}history_start"
        waited=0
        while ! mkdir "${'$'}history_lock" 2>/dev/null; do
            history_previous=${'$'}(cat "${'$'}history_lock/owner" 2>/dev/null)
            history_abandoned=0
            case "${'$'}history_previous" in
                ''|*[!0-9:]*|:*|*:)
                    now=${'$'}(date +%s)
                    modified=${'$'}(stat -c %Y "${'$'}history_lock" 2>/dev/null || printf 0)
                    if [ "${'$'}modified" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((modified + 30))" ]; then history_abandoned=1; fi
                    ;;
                *)
                    history_pid=${'$'}{history_previous%:*}
                    if current_start=${'$'}(history_process_start "${'$'}history_pid"); then
                        [ "${'$'}current_start" != "${'$'}{history_previous#*:}" ] && history_abandoned=1
                    elif [ ! -d "/proc/${'$'}history_pid" ]; then history_abandoned=1; fi
                    ;;
            esac
            if [ "${'$'}history_abandoned" -eq 1 ] && [ "${'$'}(cat "${'$'}history_lock/owner" 2>/dev/null)" = "${'$'}history_previous" ]; then
                rm -f "${'$'}history_lock/owner"
                rmdir "${'$'}history_lock" 2>/dev/null || true
            fi
            waited=${'$'}((waited + 1))
            [ "${'$'}waited" -ge 5 ] && exit 1
            sleep 1
        done
        printf '%s' "${'$'}history_owner" > "${'$'}history_lock/owner" || { rmdir "${'$'}history_lock" 2>/dev/null; exit 1; }
        trap 'if [ "${'$'}(cat "${'$'}history_lock/owner" 2>/dev/null)" = "${'$'}history_owner" ]; then rm -f "${'$'}history_lock/owner"; rmdir "${'$'}history_lock" 2>/dev/null || true; fi' EXIT HUP INT TERM
        $command
    """.trimIndent()

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
        val out = runAsRoot(historyMutation(
            "if [ -f $claim ]; then cat $claim; " +
                "elif [ -f $source ] && mv $source $claim; then cat $claim; fi; true",
            missingResult = ""
        ))
        return if (out.isNotErrored()) out.substringBefore(ERR_MARK) else ""
    }

    /** 删除已经成功入库的认领文件；history 为空时一并清理目录。 */
    fun completeHistoryImport(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        val out = runAsRoot(historyMutation(
            "if rm -f $claim; then " +
                "rmdir '$HISTORY_DIR' 2>/dev/null || true; printf 1; " +
                "else printf 0; fi"
        ))
        return out.isNotErrored() && out.substringBefore(ERR_MARK).trim() == "1"
    }

    /** 将解析失败的认领文件隔离为 .invalid，避免它阻塞后续新 .log，同时保留现场供诊断。 */
    fun quarantineInvalidHistoryImport(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        val out = runAsRoot(historyMutation(
            "if [ ! -f $claim ]; then printf 1; " +
                "else stamp=\$(date +%s 2>/dev/null || printf 0); " +
                "target=\"$HISTORY_DIR/$safe.log.invalid.\${stamp}\"; " +
                "n=0; while [ -e \"\${target}\" ]; do n=\$((n + 1)); target=\"$HISTORY_DIR/$safe.log.invalid.\${stamp}.\${n}\"; done; " +
                "if mv $claim \"\${target}\" 2>/dev/null; then printf 1; else printf 0; fi; fi"
        ))
        return out.isNotErrored() && out.substringBefore(ERR_MARK).trim() == "1"
    }

    /** 删除某包名的整份历史 .log 文件。 */
    fun deleteHistory(pkg: String): Boolean {
        val safe = safeHistoryPackage(pkg)
        if (safe.isBlank()) return false
        val source = shellQuote("$HISTORY_DIR/$safe.log")
        val claim = shellQuote("$HISTORY_DIR/$safe.log$HISTORY_IMPORT_SUFFIX")
        val invalidPrefix = shellQuote("$HISTORY_DIR/$safe.log.invalid.")
        val out = runAsRoot(historyMutation(
            "if ! rm -f $source $claim ${invalidPrefix}*; then printf 0; exit 1; fi; " +
                "remaining=0; [ -e $source ] && remaining=1; [ -e $claim ] && remaining=1; " +
                "for f in ${invalidPrefix}*; do [ -e \"\$f\" ] && remaining=1; done; " +
                "if [ \"\$remaining\" -eq 0 ]; then rmdir '$HISTORY_DIR' 2>/dev/null || true; printf 1; else printf 0; fi"
        ))
        return out.isNotErrored() && out.substringBefore(ERR_MARK).trim() == "1"
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
            android.util.Log.w("QixiaThreads", "history list: root 枚举失败")
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
        android.util.Log.d("QixiaThreads", "history list: 枚举到 ${entries.size} 个应用历史文件")
        return entries
    }

    private const val ERR_MARK = "__QIXIA_ERR__"
    private const val ROOT_TIMEOUT_MARK = "__QIXIA_TIMEOUT__"

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
            while ! { [ ! -e "${'$'}lock" ] && mkdir "${'$'}lock" 2>/dev/null &&
                (set -C; printf '%s' "${'$'}token" > "${'$'}lock/owner") 2>/dev/null; }; do
                now=${'$'}(date +%s)
                mtime=${'$'}(stat -c %Y "${'$'}lock" 2>/dev/null || printf 0)
                if [ "${'$'}mtime" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((mtime + 30))" ]; then
                    rm -f "${'$'}lock/owner"
                    if rmdir "${'$'}lock" 2>/dev/null; then continue; fi
                fi
                waited=${'$'}((waited + 1))
                [ "${'$'}waited" -ge 5 ] && exit 1
                sleep 1
            done
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
        val missingMarker = "__QIXIA_CONFIG_MISSING_${UUID.randomUUID()}__"
        // 流式读取器会规范化换行并补上末尾换行符。
        // 比较后替换必须保留原字节，包括 CRLF 及末尾无换行符的情况。
        val result = runRootCommand(
            "if [ -f '$CONFIG_FILE' ]; then cat '$CONFIG_FILE' || exit 1; " +
                "elif [ -e '$CONFIG_FILE' ] || [ -L '$CONFIG_FILE' ]; then exit 1; " +
                "else printf '$missingMarker'; fi"
        )
        if (!result.success) return null
        val content = result.output
        return if (content.trimEnd('\r', '\n') == missingMarker) "" else content
    }

    /** 锁内原子写入 applist.conf，避免 App 与守护进程相互覆盖或留下半文件。 */
    private fun writeConfigFileLocked(content: String, token: String,
        expectedContent: String? = null, allowPendingUpdate: Boolean = false): Boolean {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val expected = expectedContent?.let {
            android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
        val result = runRootCommand(configFileMutationScript(b64, token, expected, allowPendingUpdate))
        return result.success && result.output.trim() == token
    }

    /** 使用编码参数，让生成器不依赖 Android，并保留精确文件字节。 */
    internal fun configFileMutationScript(b64: String, token: String,
        expected: String? = null, allowPendingUpdate: Boolean = false): String {
        val pendingGuard = if (expected != null && !allowPendingUpdate) """
            [ ! -e '$POLICY_UPDATE_FILE' ] && [ ! -e '$MODULE_UPDATE_DIR/module.prop' ] &&
                [ ! -e '$MODULE_DIR/update' ] || exit 1
        """.trimIndent() else ""
        val sourceGuard = expected?.let { encoded -> """
            if [ -f "${'$'}target" ]; then
                printf '%s' '$encoded' | base64 -d | cmp -s - "${'$'}target" || exit 1
            elif [ -e "${'$'}target" ] || [ -L "${'$'}target" ] || [ -n '$encoded' ]; then
                exit 1
            fi
        """.trimIndent() }.orEmpty()
        val tmp = "$CONFIG_FILE.app-$token.tmp"
        return """
            lock='$CONFIG_LOCK_DIR'
            target='$CONFIG_FILE'
            tmp='$tmp'
            [ "${'$'}(cat "${'$'}lock/owner" 2>/dev/null)" = '$token' ] || exit 1
            $pendingGuard
            trap 'rm -f "${'$'}tmp"' EXIT
            if ! printf '%s' '$b64' | base64 -d > "${'$'}tmp"; then
                exit 1
            fi
            chmod 0644 "${'$'}tmp" 2>/dev/null || true
            chown 0:0 "${'$'}tmp" 2>/dev/null || true
            [ "${'$'}(cat "${'$'}lock/owner" 2>/dev/null)" = '$token' ] || exit 1
            $pendingGuard
            $sourceGuard
            mv -f "${'$'}tmp" "${'$'}target" || exit 1
            printf '%s' '$token'
        """.trimIndent()
    }

    /** 应用内所有策略写入与 Rust 拓扑更新共用目录锁。 */
    private fun policyMutationScript(action: String): String = """
            lock='$POLICY_LOCK_DIR'
            target='$POLICY_FILE'
            tmp='$POLICY_FILE.${UUID.randomUUID()}.tmp'
            mkdir -p '$CONFIG_DIR'
            waited=0
            while ! mkdir "${'$'}lock" 2>/dev/null; do
                now=${'$'}(date +%s)
                mtime=${'$'}(stat -c %Y "${'$'}lock" 2>/dev/null || printf 0)
                if [ "${'$'}mtime" -gt 0 ] && [ "${'$'}now" -gt "${'$'}((mtime + 30))" ]; then
                    if rmdir "${'$'}lock" 2>/dev/null; then continue; fi
                fi
                waited=${'$'}((waited + 1))
                [ "${'$'}waited" -ge 5 ] && exit 1
                sleep 1
            done
            trap 'rm -f "${'$'}tmp"; rmdir "${'$'}lock" 2>/dev/null' EXIT
        """.trimIndent() + "\n" + action

    /** 在策略锁内读取并修改，避免过期设置快照覆盖其他写入方的内容。 */
    private fun writePolicyFileAsRoot(content: String): Boolean {
        return runRootCommand(editablePolicyMutationScript(content)).success
    }

    internal fun editablePolicyMutationScript(content: String): String {
        val policy = CalibPolicy.parse(content)
        return policyMutationScript("""
            [ ! -e '$POLICY_UPDATE_FILE' ] || exit 1
            input=/dev/null
            if [ -e "${'$'}target" ]; then
                [ -f "${'$'}target" ] && [ -r "${'$'}target" ] || exit 1
                input="${'$'}target"
            fi
            awk -v format='${policy.ruleOutputFormat.generationTarget().wire}' -v cpuset='${policy.cpusetName}' '
            BEGIN { values["version"]="2"; values["rule_output_format"]=format; values["cpuset_name"]=cpuset }
            {
                clean=${'$'}0; sub(/#.*/, "", clean); split_at=index(clean, "=")
                key=substr(clean, 1, split_at-1)
                gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "", key)
                if (split_at && key == "rule_output_format_migration") next
                if (split_at && key in values) {
                    if (!written[key]++) {
                        hash=index(${'$'}0, "#")
                        print key "=" values[key] (hash ? " " substr(${'$'}0, hash) : "")
                    }
                    next
                }
                print
            }
            END {
                if (!written["version"]) print "version=2"
                if (!written["rule_output_format"]) print "rule_output_format=" format
                if (!written["cpuset_name"]) print "cpuset_name=" cpuset
            }' "${'$'}input" > "${'$'}tmp" || exit 1
            chmod 0644 "${'$'}tmp" || exit 1
            mv -f "${'$'}tmp" "${'$'}target" || exit 1
        """.trimIndent())
    }

    private val DEV_NULL = java.io.File("/dev/null")

    private fun newRootSessionFile(): String =
        "/data/local/tmp/.qixia_root_${android.os.Process.myPid()}_${UUID.randomUUID().toString().replace("-", "")}.state"

    /**
     * 把实际命令放入独立会话。超时时另一个 su 可以按 PID、starttime 和进程组精确终止整棵命令树，
     * 避免只杀外层 su 后刷模块等孙进程仍在后台继续执行。
     */
    private fun wrapRootSessionCommand(cmd: String, sessionFile: String, marker: String? = null): String {
        val markerCommand = marker?.let { "printf '%s\\n' ${shellQuote(it)}" }.orEmpty()
        val quotedCommand = shellQuote(cmd)
        return """
            session_file=${shellQuote(sessionFile)}
            rm -f "${'$'}session_file"
            $markerCommand
            if command -v setsid >/dev/null 2>&1; then
                setsid /system/bin/sh -c $quotedCommand &
                grouped=1
            else
                /system/bin/sh -c $quotedCommand &
                grouped=0
            fi
            child=${'$'}!
            start=${'$'}(sed 's/^.*) //' "/proc/${'$'}child/stat" 2>/dev/null | awk '{print ${'$'}20}')
            printf '%s %s %s\n' "${'$'}child" "${'$'}start" "${'$'}grouped" > "${'$'}session_file"
            wait "${'$'}child"
            rc=${'$'}?
            rm -f "${'$'}session_file"
            exit "${'$'}rc"
        """.trimIndent()
    }

    private fun terminateRootSession(sessionFile: String) {
        val command = """
            state=${shellQuote(sessionFile)}
            [ -f "${'$'}state" ] || exit 0
            read pid start grouped < "${'$'}state"
            case "${'$'}pid" in ''|*[!0-9]*) rm -f "${'$'}state"; exit 0;; esac
            current=${'$'}(sed 's/^.*) //' "/proc/${'$'}pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
            if [ -z "${'$'}start" ] || [ "${'$'}current" != "${'$'}start" ]; then
                rm -f "${'$'}state"
                exit 0
            fi
            collect_tree() {
                local parent="${'$'}1" child children children_file status key value ppid used_children=0
                printf '%s\n' "${'$'}parent"
                for children_file in "/proc/${'$'}parent/task/"*/children; do
                    [ -r "${'$'}children_file" ] || continue
                    used_children=1
                    children=
                    IFS= read -r children < "${'$'}children_file" || true
                    for child in ${'$'}children; do
                        case "${'$'}child" in ''|*[!0-9]*) continue;; esac
                        collect_tree "${'$'}child"
                    done
                done
                [ "${'$'}used_children" -eq 1 ] && return

                # 极少数内核未提供 task/*/children；回退时只用 shell 内建读取 PPid，
                # 避免为 /proc 中的每个进程派生 sed/head，导致清理自身再次超时。
                for status in /proc/[0-9]*/status; do
                    [ -r "${'$'}status" ] || continue
                    child=${'$'}{status#/proc/}; child=${'$'}{child%/status}
                    ppid=
                    while IFS=: read -r key value; do
                        [ "${'$'}key" = PPid ] || continue
                        set -- ${'$'}value
                        ppid=${'$'}1
                        break
                    done < "${'$'}status"
                    [ "${'$'}ppid" = "${'$'}parent" ] || continue
                    collect_tree "${'$'}child"
                done
            }
            if [ "${'$'}grouped" = 1 ]; then
                kill -TERM -"${'$'}pid" 2>/dev/null || kill -TERM "${'$'}pid" 2>/dev/null || true
            else
                targets=${'$'}(collect_tree "${'$'}pid")
                for target in ${'$'}targets; do kill -TERM "${'$'}target" 2>/dev/null || true; done
            fi
            sleep 0.2
            if [ "${'$'}grouped" = 1 ]; then
                kill -KILL -"${'$'}pid" 2>/dev/null || kill -KILL "${'$'}pid" 2>/dev/null || true
            else
                for target in ${'$'}targets; do kill -KILL "${'$'}target" 2>/dev/null || true; done
            fi
            rm -f "${'$'}state"
        """.trimIndent()
        try {
            val cleanup = ProcessBuilder("su", "-c", command)
                .redirectOutput(ProcessBuilder.Redirect.to(DEV_NULL))
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            if (!cleanup.waitFor(4, TimeUnit.SECONDS)) cleanup.destroyForcibly()
        } catch (_: Exception) {
        }
    }

    /** 等待 root 子进程结束，并读取 stdout；超时或非零退出会附加错误标记。 */
    private fun waitAndRead(
        process: Process,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
        sessionFile: String? = null
    ): String {
        val out = StringBuilder()
        val readFailed = AtomicReference(false)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(out) {
                            out.append(buffer, 0, count)
                        }
                    }
                }
            } catch (_: Exception) {
                readFailed.set(true)
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            stopRootProcess(process, reader, sessionFile)
            return synchronized(out) { out.toString() } + ERR_MARK + ROOT_TIMEOUT_MARK
        }

        joinRootReader(reader)
        val output = synchronized(out) { out.toString() }
        return if (process.exitValue() != 0 || readFailed.get()) "$output$ERR_MARK" else output
    }

    /** Root 进程超时后主动关闭读取端，并等待读取线程退出，避免关闭流异常逃逸到主进程。 */
    private fun stopRootProcess(process: Process, reader: Thread, sessionFile: String? = null) {
        sessionFile?.let(::terminateRootSession)
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            process.inputStream.close()
        } catch (_: Exception) {
        }
        reader.interrupt()
        joinRootReader(reader)
    }

    private fun joinRootReader(reader: Thread) {
        try {
            reader.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitAndStream(
        process: Process,
        timeoutSeconds: Long = ROOT_TIMEOUT_SECONDS,
        sessionFile: String? = null,
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

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            stopRootProcess(process, reader, sessionFile)
            return RootCommandResult(
                output = synchronized(out) { out.toString() },
                success = false,
                timedOut = true
            )
        }

        joinRootReader(reader)
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
            val marker = "__QIXIA_SU_C_${UUID.randomUUID()}__"
            val sessionFile = newRootSessionFile()
            val wrapped = wrapRootSessionCommand(cmd, sessionFile, marker)
            val process = ProcessBuilder("su", "-c", wrapped)
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                val output = waitAndRead(process, timeoutSeconds, sessionFile)
                val clean = stripSuShellMarker(output, marker)
                if (clean != null) {
                    clean
                } else if (output.contains(ROOT_TIMEOUT_MARK)) {
                    output
                } else {
                    return runViaStdin(cmd, timeoutSeconds)
                }
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
            val sessionFile = newRootSessionFile()
            val process = ProcessBuilder("su")
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(wrapRootSessionCommand(cmd, sessionFile))
                    writer.write("\nexit\n")
                    writer.flush()
                }
                waitAndRead(process, timeoutSeconds, sessionFile)
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
            val marker = "__QIXIA_SU_C_${UUID.randomUUID()}__"
            val sessionFile = newRootSessionFile()
            val wrapped = wrapRootSessionCommand(cmd, sessionFile, marker)
            val process = ProcessBuilder("su", "-c", wrapped)
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                var markerSeen = false
                val result = waitAndStream(process, timeoutSeconds, sessionFile) { chunk ->
                    if (chunk.trimEnd('\r', '\n') == marker) {
                        markerSeen = true
                    } else if (markerSeen) {
                        onOutput(chunk)
                    }
                }
                val output = stripSuShellMarker(result.output, marker)
                if (output == null) {
                    if (result.timedOut) return result
                    return runViaStdinStreaming(cmd, timeoutSeconds, onOutput)
                }
                result.copy(output = output)
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
            val sessionFile = newRootSessionFile()
            val process = ProcessBuilder("su")
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(wrapRootSessionCommand(cmd, sessionFile))
                    writer.write("\nexit\n")
                    writer.flush()
                }
                waitAndStream(process, timeoutSeconds, sessionFile, onOutput)
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            RootCommandResult(output = "", success = false)
        }
    }

    private fun stripSuShellMarker(output: String, marker: String): String? {
        var offset = 0
        for (rawLine in output.splitToSequence('\n')) {
            val lineEnd = offset + rawLine.length
            if (rawLine.trimEnd('\r') == marker) {
                val contentStart = if (lineEnd < output.length && output[lineEnd] == '\n') {
                    lineEnd + 1
                } else {
                    lineEnd
                }
                return output.substring(contentStart)
            }
            offset = lineEnd + 1
        }
        return null
    }
}
