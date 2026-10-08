package top.qixia.threads

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticExporter {

    private const val ROOT_RECORD_PREFIX = "__QIXIA_DIAG_V1__"

    fun export(context: Context): Result<String> = runCatching {
        val appContext = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "QixiaThreads-diagnostic-$stamp.zip"
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/QixiaThreads"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("创建诊断包失败")
        var finalFileName = fileName

        try {
            resolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    val root = DaemonBridge.hasRoot()
                    val moduleVersion = if (root) DaemonBridge.readModuleVersion() else null
                    val rootManager = if (root) ModuleUpdater.detectRootManagerLabel() else null
                    val daemonRunning = if (root) DaemonBridge.isDaemonRunning() else false

                    zip.addText("summary.txt", buildSummary(appContext, root, moduleVersion, rootManager, daemonRunning))
                    zip.addText("app/logcat_qixia.txt", readAppLogcat())

                    if (root) {
                        val rootBundle = collectRootDiagnostics()
                        rootBundle.entries.forEach { entry ->
                            zip.addBytes(entry.name, entry.content)
                        }
                        if (rootBundle.error != null) {
                            zip.addText("root_bundle_error.txt", rootBundle.error)
                        }
                    } else {
                        zip.addText("root_unavailable.txt", "Root 权限不可用，未导出模块目录、系统 cpuset 和 root logcat。\n")
                    }
                }
            } ?: error("打开诊断包失败")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            finalFileName = resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { fileName }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        "$relativeDir/$finalFileName"
    }

    private fun buildSummary(
        context: Context,
        root: Boolean,
        moduleVersion: DaemonBridge.ModuleVersion?,
        rootManager: String?,
        daemonRunning: Boolean
    ): String {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
        return buildString {
            appendLine("QixiaThreads 诊断包")
            appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine()
            appendLine("App")
            appendLine("package: ${context.packageName}")
            appendLine("versionName: ${pkgInfo.versionName}")
            appendLine("versionCode: $appVersionCode")
            appendLine("pid: ${android.os.Process.myPid()}")
            appendLine()
            appendLine("模块")
            appendLine("root: ${if (root) "可用" else "不可用"}")
            appendLine("rootManager: ${rootManager ?: "未知"}")
            appendLine("moduleVersion: ${moduleVersion?.versionName ?: "未知"} (${moduleVersion?.versionCode ?: "未知"})")
            appendLine("daemonRunning: ${if (daemonRunning) "是" else "否"}")
            appendLine()
            appendLine("设备")
            appendLine("brand: ${Build.BRAND}")
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("device: ${Build.DEVICE}")
            appendLine("sdk: ${Build.VERSION.SDK_INT}")
            appendLine("release: ${Build.VERSION.RELEASE}")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
        }
    }

    private fun readAppLogcat(): String {
        val commands = listOf(
            listOf("logcat", "-d", "-v", "threadtime", "-t", "3000", "QixiaThreads:D", "AndroidRuntime:E", "System.err:W", "*:S"),
            listOf("logcat", "-d", "-v", "threadtime", "-t", "3000")
        )
        for (cmd in commands) {
            val text = runProcess(cmd, timeoutMs = 8_000L)
            if (text.isNotBlank()) return text
        }
        return "logcat 读取失败或没有可见日志。\n"
    }

    private fun runProcess(command: List<String>, timeoutMs: Long): String {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            synchronized(out) {
                                out.appendLine(line)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                reader.interrupt()
            }
            reader.join(1000)
            synchronized(out) {
                if (!finished) out.appendLine("[logcat 读取超时，以上为已收集内容]")
                out.toString()
            }
        } catch (e: Exception) {
            "执行失败: ${e.message}\n"
        }
    }

    private data class DiagnosticEntry(val name: String, val content: ByteArray)

    private data class RootDiagnosticBundle(
        val entries: List<DiagnosticEntry>,
        val error: String? = null
    )

    private fun collectRootDiagnostics(): RootDiagnosticBundle {
        val result = DaemonBridge.runRootCommand(buildRootBundleCommand(), timeoutSeconds = 45L)
        val entries = linkedMapOf<String, DiagnosticEntry>()
        result.output.lineSequence().forEach { line ->
            if (!line.startsWith(ROOT_RECORD_PREFIX)) return@forEach
            val parts = line.split('\t', limit = 4)
            if (parts.size < 3) return@forEach
            val name = parts[1].takeIf(::isSafeEntryName) ?: return@forEach
            val content = when (parts[2]) {
                "ok" -> runCatching {
                    android.util.Base64.decode(parts.getOrElse(3) { "" }, android.util.Base64.DEFAULT)
                }.getOrElse {
                    "诊断数据解码失败。\n".toByteArray(Charsets.UTF_8)
                }
                else -> "读取失败或文件不存在。\n".toByteArray(Charsets.UTF_8)
            }
            entries[name] = DiagnosticEntry(name, content)
        }
        val error = if (result.success && entries.isNotEmpty()) {
            null
        } else {
            buildString {
                appendLine(
                    if (result.timedOut) "Root 诊断收集整体超时。"
                    else "Root 诊断收集未完整结束。"
                )
                if (result.output.isBlank()) appendLine("没有可用输出。")
            }
        }
        return RootDiagnosticBundle(entries.values.toList(), error)
    }

    private fun isSafeEntryName(name: String): Boolean {
        return name.isNotBlank() && !name.startsWith('/') &&
            name.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun ZipOutputStream.addBytes(entryName: String, content: ByteArray) {
        putNextEntry(ZipEntry(entryName))
        write(content)
        closeEntry()
    }

    private fun ZipOutputStream.addText(entryName: String, text: String) {
        addBytes(entryName, text.toByteArray(Charsets.UTF_8))
    }

    private fun buildRootBundleCommand(): String = """
        prefix='$ROOT_RECORD_PREFIX'
        work="/data/local/tmp/.qixia_diag.${'$'}${'$'}"
        rm -rf "${'$'}work"
        mkdir -p "${'$'}work" || exit 2
        command -v base64 >/dev/null 2>&1 || exit 3
        trap 'rm -rf "${'$'}work"' EXIT HUP INT TERM
        emit_file() {
            local entry path max_bytes size source capped
            entry=${'$'}1
            path=${'$'}2
            max_bytes=${'$'}{3:-0}
            if [ -f "${'$'}path" ]; then
                source=${'$'}path
                case "${'$'}max_bytes" in
                    ''|*[!0-9]*) max_bytes=0 ;;
                esac
                if [ "${'$'}max_bytes" -gt 0 ] 2>/dev/null; then
                    size=${'$'}(wc -c < "${'$'}path" 2>/dev/null) || size=0
                    case "${'$'}size" in
                        ''|*[!0-9]*) size=0 ;;
                    esac
                    if [ "${'$'}size" -gt "${'$'}max_bytes" ] 2>/dev/null; then
                        capped="${'$'}work/capped.${'$'}${'$'}.diag"
                        printf '# QixiaThreads 诊断导出：原文件 %s 字节，仅保留末尾 %s 字节。\n' \
                            "${'$'}size" "${'$'}max_bytes" > "${'$'}capped" || return
                        tail -c "${'$'}max_bytes" "${'$'}path" >> "${'$'}capped" 2>/dev/null || return
                        source=${'$'}capped
                    fi
                fi
                printf '%s\t%s\tok\t' "${'$'}prefix" "${'$'}entry"
                base64 < "${'$'}source" 2>/dev/null | tr -d '\r\n'
                printf '\n'
                [ "${'$'}source" = "${'$'}path" ] || rm -f "${'$'}source"
            else
                printf '%s\t%s\tmissing\t\n' "${'$'}prefix" "${'$'}entry"
            fi
        }

        emit_file 'module/QixiaThreads.log' '/data/adb/modules/QixiaThreads/logs/QixiaThreads.log' 1572864
        emit_file 'module/Runtime.log' '/data/adb/modules/QixiaThreads/logs/Runtime.log' 524288
        emit_file 'module/Runtime.log.1' '/data/adb/modules/QixiaThreads/logs/Runtime.log.1' 524288
        emit_file 'module/Runtime.log.2' '/data/adb/modules/QixiaThreads/logs/Runtime.log.2' 524288
        emit_file 'module/ForegroundEvents.log' '/data/adb/modules/QixiaThreads/logs/ForegroundEvents.log' 524288
        emit_file 'module/ForegroundEvents.log.1' '/data/adb/modules/QixiaThreads/logs/ForegroundEvents.log.1' 524288
        emit_file 'module/ForegroundEvents.log.2' '/data/adb/modules/QixiaThreads/logs/ForegroundEvents.log.2' 524288
        emit_file 'module/ForegroundHelper.log' '/data/adb/modules/QixiaThreads/logs/ForegroundHelper.log' 524288
        emit_file 'module/module.prop' '/data/adb/modules/QixiaThreads/module.prop'
        emit_file 'module/pending_module.prop' '/data/adb/modules_update/QixiaThreads/module.prop'
        emit_file 'config/applist.conf' '/data/adb/modules/QixiaThreads/config/applist.conf'
        emit_file 'config/state/package_uid.map' '/data/adb/modules/QixiaThreads/config/state/package_uid.map'
        emit_file 'config/state/rule_health.tsv' '/data/adb/modules/QixiaThreads/config/state/rule_health.tsv'
        emit_file 'config/state/pid_cache.tsv' '/data/adb/modules/QixiaThreads/config/state/pid_cache.tsv'
        emit_file 'config/calib_policy.conf' '/data/adb/modules/QixiaThreads/config/calib_policy.conf'
        emit_file 'config/auto_affinity.conf' '/data/adb/modules/QixiaThreads/config/auto_affinity.conf'
        emit_file 'config/auto_affinity.state' '/data/adb/modules/QixiaThreads/config/auto_affinity.state'
        emit_file 'config/state/auto_affinity.restore' '/data/adb/modules/QixiaThreads/config/state/auto_affinity.restore'
        emit_file 'config/foreground_task.state' '/data/adb/modules/QixiaThreads/config/foreground_task.state'
        emit_file 'config/state/app_storage.conf' '${PrivateCaptureStorage.REGISTRATION}'

        history_count=0
        history_skipped=0
        for history in '${PrivateCaptureStorage.history.absolutePath}'/*.log \
            '${PrivateCaptureStorage.history.absolutePath}'/*.qixia-importing \
            '${PrivateCaptureStorage.history.absolutePath}'/*.invalid.*; do
            [ -f "${'$'}history" ] || continue
            if [ "${'$'}history_count" -ge 8 ]; then
                history_skipped=${'$'}((history_skipped + 1))
                continue
            fi
            base=${'$'}{history##*/}
            emit_file "history/${'$'}base" "${'$'}history" 262144
            history_count=${'$'}((history_count + 1))
        done
        if [ "${'$'}history_skipped" -gt 0 ]; then
            printf '为控制诊断包内存占用，另有 %s 个历史残留文件未导出。\n' \
                "${'$'}history_skipped" > "${'$'}work/history_truncated.txt"
            emit_file 'history/_truncated.txt' "${'$'}work/history_truncated.txt"
        fi

        (
        $CPU_TOPOLOGY_CMD
        ) > "${'$'}work/cpu_topology.txt" 2>&1
        emit_file 'system/cpu_topology.txt' "${'$'}work/cpu_topology.txt"
        (
        $CPUSET_CMD
        ) > "${'$'}work/cpuset.txt" 2>&1
        emit_file 'system/cpuset.txt' "${'$'}work/cpuset.txt"
        (
        $PROCESS_CMD
        ) > "${'$'}work/processes.txt" 2>&1
        emit_file 'system/processes.txt' "${'$'}work/processes.txt"
        (
        $FPS_CMD
        ) > "${'$'}work/fps_status.txt" 2>&1
        emit_file 'fps/fps_status.txt' "${'$'}work/fps_status.txt"
        (
        $ROOT_LOGCAT_CMD
        ) > "${'$'}work/logcat_root_qixia.txt" 2>&1
        emit_file 'app/logcat_root_qixia.txt' "${'$'}work/logcat_root_qixia.txt" 1048576
    """.trimIndent()

    private val CPU_TOPOLOGY_CMD = """
        echo '# uname'
        uname -a
        echo
        echo '# getprop'
        getprop ro.build.version.release
        getprop ro.build.version.sdk
        getprop ro.product.manufacturer
        getprop ro.product.model
        getprop ro.product.device
        getprop ro.vendor.product.device
        echo
        echo '# cpu online/possible/present'
        for f in /sys/devices/system/cpu/online /sys/devices/system/cpu/possible /sys/devices/system/cpu/present; do
            [ -f "${'$'}f" ] && printf '%s=' "${'$'}f" && cat "${'$'}f"
        done
        echo
        echo '# per-cpu'
        for c in /sys/devices/system/cpu/cpu[0-9]*; do
            [ -d "${'$'}c" ] || continue
            echo "[${'$'}c]"
            for f in "${'$'}c"/topology/physical_package_id "${'$'}c"/topology/core_id "${'$'}c"/cpu_capacity "${'$'}c"/cpufreq/cpuinfo_max_freq "${'$'}c"/cpufreq/scaling_max_freq "${'$'}c"/cpufreq/scaling_cur_freq; do
                [ -f "${'$'}f" ] && printf '%s=' "${'$'}f" && cat "${'$'}f"
            done
        done
    """

    private val CPUSET_CMD = """
        echo '# cpuset groups'
        for f in /dev/cpuset/cpus /dev/cpuset/*/cpus /dev/cpuset/*/*/cpus; do
            [ -f "${'$'}f" ] && printf '%s=' "${'$'}f" && cat "${'$'}f"
        done
        echo
        echo '# stune/uclamp if present'
        for f in /dev/stune/*/schedtune.boost /dev/cpuctl/*/cpu.uclamp.* /dev/cpuctl/*/*/cpu.uclamp.*; do
            [ -f "${'$'}f" ] && printf '%s=' "${'$'}f" && cat "${'$'}f"
        done
    """

    private val PROCESS_CMD = """
        echo '# qixia processes'
        ps -A 2>/dev/null | grep -i -E 'QixiaThreads|top.qixia.threads|qixia_foreground_helper' || true
        echo
        echo '# module daemon pid'
        daemon_bin=/data/adb/modules/QixiaThreads/config/bin/QiXiaRs
        find_pid() {
            name="${'$'}1"
            found=$("${'$'}daemon_bin" --find-pid "${'$'}name" 2>/dev/null) || found=''
            if [ -z "${'$'}found" ]; then
                for proc_dir in /proc/[0-9]*; do
                    [ -r "${'$'}proc_dir/cmdline" ] || continue
                    process_name=${'$'}(tr '\000' '\n' < "${'$'}proc_dir/cmdline" 2>/dev/null | head -n 1)
                    [ "${'$'}process_name" = "${'$'}name" ] || continue
                    found="${'$'}found ${'$'}{proc_dir##*/}"
                done
            fi
            printf '%s\n' "${'$'}found"
        }
        find_pid QiXiaRs
        echo
        echo '# app pid'
        find_pid top.qixia.threads
    """

    private val FPS_CMD = """
        echo '# fps command'
        cat /data/adb/modules/QixiaThreads/config/fps.cmd 2>/dev/null || true
        echo
        echo '# app fps file'
        cat /data/user/0/top.qixia.threads/files/fps 2>/dev/null || true
        echo
        echo '# recent fps log'
        grep -i -E 'FPS|eBPF|Fallback|SurfaceFlinger|queueBuffer|RingBuf|PerfEvent' /data/adb/modules/QixiaThreads/logs/QixiaThreads.log /data/adb/modules/QixiaThreads/logs/Runtime.log.2 /data/adb/modules/QixiaThreads/logs/Runtime.log.1 /data/adb/modules/QixiaThreads/logs/Runtime.log 2>/dev/null | tail -n 240 || true
    """

    private val ROOT_LOGCAT_CMD = """
        logcat -d -v threadtime -t 6000 2>/dev/null | grep -i -E 'QixiaThreads|top.qixia.threads|AndroidRuntime|FATAL EXCEPTION' || true
    """
}
