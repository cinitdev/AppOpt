package top.suto.appopt

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

    fun export(context: Context): Result<String> = runCatching {
        val appContext = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "AppOpt-diagnostic-$stamp.zip"
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/AppOpt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("创建诊断包失败")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    val root = DaemonBridge.hasRoot()
                    val moduleVersion = if (root) DaemonBridge.readModuleVersion() else null
                    val rootManager = if (root) ModuleUpdater.detectRootManagerLabel() else null
                    val daemonRunning = if (root) DaemonBridge.isDaemonRunning() else false

                    zip.addText("summary.txt", buildSummary(appContext, root, moduleVersion, rootManager, daemonRunning))
                    zip.addText("app/logcat_appopt.txt", readAppLogcat())

                    if (root) {
                        zip.addRootFile("module/AppOpt.log", "/data/adb/modules/AppOpt/logs/AppOpt.log")
                        zip.addRootFile("module/ForegroundHelper.log", "/data/adb/modules/AppOpt/logs/ForegroundHelper.log")
                        zip.addRootFile("module/module.prop", "/data/adb/modules/AppOpt/module.prop")
                        zip.addRootFile("module/pending_module.prop", "/data/adb/modules_update/AppOpt/module.prop")
                        zip.addRootFile("config/applist.conf", "/data/adb/modules/AppOpt/config/applist.conf")
                        zip.addRootFile("config/calib_policy.conf", "/data/adb/modules/AppOpt/config/calib_policy.conf")
                        zip.addRootFile("config/foreground_task.state", "/data/adb/modules/AppOpt/config/foreground_task.state")
                        zip.addRootFile("config/foreground_helper.pid", "/data/adb/modules/AppOpt/config/foreground_helper.pid")
                        zip.addText("system/cpu_topology.txt", runRoot(CPU_TOPOLOGY_CMD, timeoutSeconds = 10L))
                        zip.addText("system/cpuset.txt", runRoot(CPUSET_CMD, timeoutSeconds = 10L))
                        zip.addText("system/processes.txt", runRoot(PROCESS_CMD, timeoutSeconds = 10L))
                        zip.addText("fps/fps_status.txt", runRoot(FPS_CMD, timeoutSeconds = 10L))
                        zip.addText("app/logcat_root_appopt.txt", runRoot(ROOT_LOGCAT_CMD, timeoutSeconds = 15L))
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
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        "$relativeDir/$fileName"
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
            appendLine("AppOpt 诊断包")
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
            listOf("logcat", "-d", "-v", "threadtime", "-t", "3000", "AppOpt:D", "AndroidRuntime:E", "System.err:W", "*:S"),
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
                            out.appendLine(line)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(1000)
            out.toString()
        } catch (e: Exception) {
            "执行失败: ${e.message}\n"
        }
    }

    private fun runRoot(command: String, timeoutSeconds: Long): String {
        val result = DaemonBridge.runRootCommand(command, timeoutSeconds)
        return buildString {
            append(result.output.ifBlank { "(无输出)\n" })
            if (!result.success) {
                appendLine()
                appendLine("[命令执行失败或超时]")
            }
        }
    }

    private fun ZipOutputStream.addRootFile(entryName: String, path: String) {
        val content = DaemonBridge.readRootFile(path)
        addText(entryName, content ?: "读取失败或文件不存在: $path\n")
    }

    private fun ZipOutputStream.addText(entryName: String, text: String) {
        putNextEntry(ZipEntry(entryName))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

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
        echo '# appopt processes'
        ps -A 2>/dev/null | grep -i -E 'AppOpt|top.suto.appopt|appopt_foreground_helper' || true
        echo
        echo '# module daemon pid'
        pidof AppOpt 2>/dev/null || true
        echo
        echo '# app pid'
        pidof top.suto.appopt 2>/dev/null || true
    """

    private val FPS_CMD = """
        echo '# fps command'
        cat /data/adb/modules/AppOpt/config/fps.cmd 2>/dev/null || true
        echo
        echo '# app fps file'
        cat /data/user/0/top.suto.appopt/files/fps 2>/dev/null || true
        echo
        echo '# recent fps log'
        grep -i -E 'FPS|eBPF|Fallback|SurfaceFlinger|queueBuffer|RingBuf|PerfEvent' /data/adb/modules/AppOpt/logs/AppOpt.log 2>/dev/null | tail -n 240 || true
    """

    private val ROOT_LOGCAT_CMD = """
        logcat -d -v threadtime -t 6000 2>/dev/null | grep -i -E 'AppOpt|top.suto.appopt|AndroidRuntime|FATAL EXCEPTION' || true
    """
}
