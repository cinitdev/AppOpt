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
    private const val CMD_FILE = "$MODULE_DIR/calibrate.cmd"
    private const val STATE_FILE = "$MODULE_DIR/calibrate.state"
    private const val CONFIG_FILE = "$MODULE_DIR/applist.conf"
    private const val HISTORY_DIR = "$MODULE_DIR/history"
    private const val LOG_FILE = "$MODULE_DIR/AppOpt.log"
    private const val FPS_CMD_FILE = "$MODULE_DIR/fps.cmd"
    private const val DAEMON_SOCKET_CALLBACK_PREFIX = "appopt.callback top.suto.appopt v1 "
    private const val ROOT_TIMEOUT_SECONDS = 15L

    /** 检测设备是否有可用的 root (su); 首次调用会触发 Magisk 授权弹窗 */
    fun hasRoot(): Boolean = runAsRoot("id -u").trim() == "0"

    /**
     * 检测 C 守护进程是否在运行。
     *
     * 不再用 `pgrep -x AppOpt`: 项目开源后, 社区改版或旧二进制也可能叫 AppOpt。
     * 这里必须让守护进程按随机 token 反连 App 的一次性 socket, 才视为本 App
     * 可交互的 AppOpt 守护进程。
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
                runAsRoot("'$MODULE_DIR/AppOpt' --ping-daemon '$socketName' '$token' 2>/dev/null")
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
            line.contains("pid=")
    }

    /**
     * 在同一次 su 会话中精确检查一组非 APK 配置项是否为正在运行的进程。
     * 用于 UI 区分 "系统组件" 与 "未安装/配置残留", 不参与守护进程规则应用。
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

    /** 下发开始采样命令 */
    fun startCalibration(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val safe = cleanCommandArg(pkg, allowColon = true)
        if (safe.isBlank()) return false
        val wrote = runAsRoot("printf '%s' 'start $safe' > $CMD_FILE").isNotErrored()
        return wrote && waitForStatePackage("sampling", safe, timeoutMs = 2500)
    }

    /** 下发停止采样命令, 守护进程随后生成规则并回写配置 */
    fun stopCalibration(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        return runAsRoot("printf '%s' 'stop $safe' > $CMD_FILE").isNotErrored()
    }

    /** 通知守护进程开始真实帧率监测, 优先让守护进程把 FPS 推到 App 的本地 socket。 */
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
        return runAsRoot("printf '%s' '$cmd' > $FPS_CMD_FILE").isNotErrored()
    }

    /** 通知守护进程停止帧率监测 */
    fun stopFpsMonitor(): Boolean {
        return runAsRoot("printf '%s' 'stop' > $FPS_CMD_FILE").isNotErrored()
    }

    /** 读取守护进程当前状态; 读不到返回空串 */
    fun readState(): String {
        return runAsRoot("cat $STATE_FILE 2>/dev/null").trim()
    }

    /**
     * 读取守护进程运行日志 (service.sh 把 stdout/stderr 重定向到 AppOpt.log,
     * 每次开机覆盖写, 故只含本次开机以来的日志)。只取最近 maxLines 行避免过大。
     */
    fun readDaemonLog(maxLines: Int = 500): String {
        val out = runAsRoot("tail -n $maxLines $LOG_FILE 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /**
     * 轮询等待守护进程进入 "done ..." 状态 (规则已生成回写)。
     * 返回状态详情: null=超时, "ok"=成功, "short"=采样时长不足, "no_load"=负载不足, "write_fail"=写回失败
     */
    fun waitDone(pkg: String, timeoutMs: Long = 4000): String? {
        val expected = cleanCommandArg(pkg, allowColon = true)
        if (expected.isBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val st = readState()
            val donePkg = statePackage(st, "done")
            if (donePkg == expected) {
                // 解析状态: "done pkg" 或 "done pkg;reason=xxx"
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
     * 读取配置文件中某包名当前生效的规则行 (排除 "=auto" 占位)。
     * 用于校准结束后向用户展示生成结果。
     */
    fun readPkgRules(pkg: String): List<String> {
        val text = readConfigRaw()
        if (text.isBlank()) return emptyList()
        val result = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            var key = line.substring(0, eq).trim()
            val brace = key.indexOf('{')
            if (brace >= 0) key = key.substring(0, brace).trim()
            val value = line.substring(eq + 1).trim()
            if (key == pkg && !value.equals("auto", ignoreCase = true)) {
                result.add(line)
            }
        }
        return result
    }

    /** 读取配置文件中某包名的全部规则行, 包括 "=auto" 占位 */
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

    /** 读取配置文件原始内容; 读不到返回空串 */
    fun readConfigRaw(): String {
        val out = runAsRoot("cat $CONFIG_FILE 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /** 把未配置应用追加为 "pkg=auto", 后续可从待配置应用里启动校准 */
    fun addAutoPackage(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        if (safe.isBlank()) return false
        return runAsRoot("printf '\\n%s=auto\\n' '$safe' >> $CONFIG_FILE").isNotErrored()
    }

    /** 删除 applist.conf 中某包名的所有配置行, 包括 pkg=... 和 pkg{thread}=... */
    fun deleteConfigPackage(pkg: String): Boolean {
        return deleteConfigPackages(listOf(pkg))
    }

    /** 删除 applist.conf 中多个包名/进程名的所有配置行。 */
    fun deleteConfigPackages(pkgs: Collection<String>): Boolean {
        val targets = pkgs.map { it.replace("'", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (targets.isEmpty()) return false
        val raw = readConfigRaw()
        if (raw.isBlank()) return true
        val kept = raw.lineSequence()
            .filterNot { line -> targets.any { pkg -> lineBelongsToPackage(line, pkg) } }
            .joinToString("\n")
        return writeFileAsRoot(CONFIG_FILE, if (kept.isBlank()) "" else kept + "\n")
    }

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

    /** 读取某包名的历史线程负载记录原始内容; 读不到返回空串 */
    fun readHistory(pkg: String): String {
        val safe = pkg.replace("'", "")
        val out = runAsRoot("cat '$HISTORY_DIR/$safe.log' 2>/dev/null")
        return if (out.isNotErrored()) out else ""
    }

    /** 删除某包名的整个历史记录文件(应用列表里"删除"该应用的全部历史) */
    fun deleteHistory(pkg: String): Boolean {
        val safe = pkg.replace("'", "")
        if (safe.isBlank()) return false
        return runAsRoot("rm -f '$HISTORY_DIR/$safe.log'").isNotErrored()
    }

    /**
     * 删除某包名历史中的单次会话(按 epoch 定位)。
     * 日志是单文件多段格式, 故读出全文 -> 过滤掉以 "# <epoch> ..." 开头的那一段 -> 写回。
     * 删完若文件已空, 则连同文件一起删除。
     * 返回是否成功(包括"该会话本就不存在"也视为成功)。
     */
    fun deleteSession(pkg: String, epoch: Long): Boolean {
        val safe = pkg.replace("'", "")
        if (safe.isBlank()) return false
        val raw = readHistory(pkg)
        if (raw.isBlank()) return true

        // 调试日志: 打印要删除的 epoch 和文件中的所有段头
        android.util.Log.d("AppOpt", "deleteSession: 要删除 epoch=$epoch")
        raw.lineSequence().filter { it.trim().startsWith("#") }.forEach {
            android.util.Log.d("AppOpt", "deleteSession: 文件中的段头: $it")
        }

        val out = StringBuilder()
        var skipping = false
        var removedAny = false
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("#")) {
                // 段头: 解析 epoch 决定是否进入"跳过本段"状态
                val first = t.removePrefix("#").trim().split(Regex("\\s+")).getOrNull(0)?.toLongOrNull()
                skipping = (first == epoch)   // 每次遇到新段头都重新判断
                android.util.Log.d("AppOpt", "deleteSession: 段头 epoch=$first, 匹配=${first==epoch}, skipping=$skipping")
                if (skipping) {
                    removedAny = true
                    continue   // 跳过这个段头
                }
            }
            if (!skipping) out.append(line).append('\n')
        }
        android.util.Log.d("AppOpt", "deleteSession: removedAny=$removedAny, 剩余${out.length}字符")
        if (!removedAny) return true   // 没找到该会话, 不必写回
        val remaining = out.toString().trim()

        val result = if (remaining.isEmpty()) {
            android.util.Log.d("AppOpt", "deleteSession: 删空了,删除整个文件")
            deleteHistory(pkg)         // 删空了, 整文件移除
        } else {
            android.util.Log.d("AppOpt", "deleteSession: 写回文件,剩余内容行数=${remaining.lines().size}")
            val writeOk = writeFileAsRoot("$HISTORY_DIR/$safe.log", remaining + "\n")
            android.util.Log.d("AppOpt", "deleteSession: 写入结果=$writeOk")

            // 验证写入: 读回文件检查
            val verify = readHistory(pkg)
            val verifyLines = verify.lines().filter { it.trim().startsWith("#") }
            android.util.Log.d("AppOpt", "deleteSession: 验证读回,段头数=${verifyLines.size}")
            verifyLines.forEach { android.util.Log.d("AppOpt", "deleteSession: 验证段头: $it") }

            writeOk
        }
        android.util.Log.d("AppOpt", "deleteSession: 最终返回=$result")
        return result
    }

    /** 一条历史记录的概要: 包名 + 最近一次生成时间(epoch 秒, 取文件 mtime) */
    data class HistoryEntry(val pkg: String, val mtime: Long)

    /**
     * 枚举 history/ 目录下所有 *.log, 返回 (包名, 最近修改时间) 列表, 按时间倒序。
     * 不依赖配置中的 auto —— 即使配置已被生成的规则替换, 历史依旧可见。
     * 用 `ls` 一次性拿到时间戳与文件名, 避免多次 su 调用。
     */
    fun listHistoryEntries(): List<HistoryEntry> {
        // %Y=mtime(epoch), %n=文件名; 仅取 .log
        val out = runAsRoot(
            "for f in $HISTORY_DIR/*.log; do [ -e \"\$f\" ] && stat -c '%Y %n' \"\$f\"; done 2>/dev/null"
        )
        if (!out.isNotErrored()) return emptyList()
        val list = ArrayList<HistoryEntry>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val mtime = line.substring(0, sp).toLongOrNull() ?: continue
            val full = line.substring(sp + 1).trim()
            val name = full.substringAfterLast('/')
            if (!name.endsWith(".log")) continue
            val pkg = name.removeSuffix(".log")
            if (pkg.isNotEmpty()) list.add(HistoryEntry(pkg, mtime))
        }
        return list.sortedByDescending { it.mtime }
    }

    private const val ERR_MARK = "__APPOPT_ERR__"

    private fun String.isNotErrored(): Boolean = !this.contains(ERR_MARK)

    private fun cleanCommandArg(value: String, allowColon: Boolean): String {
        val allowed = if (allowColon) Regex("[^A-Za-z0-9._:-]") else Regex("[^A-Za-z0-9._-]")
        return value.trim().replace("'", "").replace(allowed, "")
    }

    /**
     * 以 root 把内容写到指定路径。内容经 base64 传递再由 `base64 -d` 还原,
     * 彻底规避 shell 转义/注入(内容里的引号、$、换行都不会被解释)。
     * 路径由调用方用固定常量拼出(仅含包名 safe 化), 不含用户可控的危险字符。
     *
     * 对于大内容(>100KB),使用 heredoc 避免命令行参数长度限制。
     */
    private fun writeFileAsRoot(path: String, content: String): Boolean {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        // 使用 heredoc 避免参数长度限制
        val cmd = "base64 -d > '$path' << 'EOF_BASE64'\n$b64\nEOF_BASE64"
        return runAsRoot(cmd).isNotErrored()
    }

    /**
     * 通过 su 执行一条 shell 命令, 返回 stdout。出错时返回值包含 ERR_MARK。
     * 每条命令起一个短命的 su 进程, 简单可靠, 频率为秒级不构成性能问题。
     */
    private val DEV_NULL = java.io.File("/dev/null")

    private fun waitAndRead(process: Process): String {
        val outRef = AtomicReference("")
        val reader = Thread {
            outRef.set(process.inputStream.bufferedReader().readText())
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return ERR_MARK
        }

        reader.join(1000)
        val out = outRef.get()
        return if (process.exitValue() != 0) "$out$ERR_MARK" else out
    }

    private fun runAsRoot(cmd: String): String {
        return try {
            // 把 stderr 重定向到 /dev/null: 没有无人读的 stderr 管道, 既避免
            // "stderr 写满管道缓冲(~64KB)->子进程阻塞写、父进程阻塞读 stdout"的死锁,
            // 又不像合并 stderr 那样污染 stdout(hasRoot 等按内容精确解析)。
            // (不用 Redirect.DISCARD: 那是 Java9+ API, Android 上不可用。)
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))
                .start()
            try {
                waitAndRead(process)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            // 某些 su 实现不支持 "su -c", 回退到管道写入方式
            runViaStdin(cmd)
        }
    }

    private fun runViaStdin(cmd: String): String {
        return try {
            val process = ProcessBuilder("su")
                .redirectError(ProcessBuilder.Redirect.to(DEV_NULL))   // 同理: 弃 stderr 防死锁
                .start()
            try {
                DataOutputStream(process.outputStream).use { os ->
                    os.writeBytes("$cmd\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                waitAndRead(process)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            "$ERR_MARK"
        }
    }
}
