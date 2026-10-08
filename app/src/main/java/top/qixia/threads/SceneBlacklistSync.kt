package top.qixia.threads

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 只在进入前台时请求执行，不使用定时器或后台轮询。 */
internal object SceneBlacklistSync {
    const val SCENE_CONFIG = "/data/user/0/com.omarea.vtools/files/features/cpuset.conf"
    private const val AUTO_CONFIG = "/data/adb/modules/QixiaThreads/config/auto_affinity.conf"
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun initialize(application: Application) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (request in requests) {
                runCatching { syncNow(application.packageManager) }
                    .onFailure { Log.w("QixiaThreads", "Scene 黑名单同步失败", it) }
            }
        }
        val gate = ForegroundEntryGate { requests.trySend(Unit) }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = gate.started()
            override fun onActivityStopped(activity: Activity) = gate.stopped(activity.isChangingConfigurations)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun syncNow(packageManager: PackageManager) {
        val rules = DaemonBridge.readConfigRawOrNull() ?: return
        val automatic = DaemonBridge.runRootCommand(
            "if [ -f '$AUTO_CONFIG' ]; then cat '$AUTO_CONFIG' || exit 1; " +
                "elif [ -e '$AUTO_CONFIG' ]; then exit 1; fi"
        )
        if (!automatic.success) return
        val configured = collectPackages(rules, automatic.output)
        if (configured.isEmpty()) return
        val packages = installedPackages(configured, packageManager)
        // 只同步本应用已配置的应用，保留无关的 Scene 黑名单条目。
        val uninstalled = configured - packages
        // 准备替换期间若 Scene 修改文件，则重试一次。
        repeat(2) {
            val result = DaemonBridge.runRootCommand(updateScript(packages, uninstalled = uninstalled))
            if (!result.success) {
                Log.w("QixiaThreads", "Scene 黑名单同步失败，原配置未替换")
                return
            }
            when (result.output.trim()) {
                "retry" -> Unit
                "updated" -> {
                    Log.i("QixiaThreads", "Scene 黑名单已同步：${packages.size} 个应用")
                    return
                }
                else -> return // Scene 配置不存在或已同步。
            }
        }
    }

    internal fun collectPackages(rules: String, automatic: String): Set<String> {
        val parsed = ConfigReader.parsePackages(rules)
        val owners = parsed.configuredPackages + parsed.autoPackages
        val autoPackages = DaemonBridge.parseAutomaticAffinityPackages(automatic).asSequence()
        return (owners.asSequence().map { it.substringBefore(':') } + autoPackages)
            .filter { it.length < 128 && packagePattern.matches(it) }.toSortedSet()
    }

    internal fun installedPackages(packages: Set<String>, manager: PackageManager): Set<String> =
        packages.filterTo(sortedSetOf()) { name ->
            try {
                // 包含已停用应用，但排除仅为其他用户或数据而保留的包。
                manager.getApplicationInfo(name, 0).flags and ApplicationInfo.FLAG_INSTALLED != 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            // 遇到其他 PackageManager 错误时中止同步，不移除条目。
        }

    internal fun updateScript(
        packages: Set<String>,
        targetPath: String = SCENE_CONFIG,
        uninstalled: Set<String> = emptySet()
    ): String {
        require(packages.all { it.length < 128 && packagePattern.matches(it) })
        require(uninstalled.all { it.length < 128 && packagePattern.matches(it) })
        require(packages.intersect(uninstalled).isEmpty())
        val additions = packages.sorted().joinToString(",")
        val removals = uninstalled.sorted().joinToString(",")
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return """
            target=${quote(targetPath)}
            # 即使执行 su，Android 仍可能在本应用的挂载命名空间中隐藏其他应用数据。
            # 通过 init 的根目录视图解析相同路径。
            if [ ! -e "${'$'}target" ] && [ -f "/proc/1/root${'$'}target" ]; then
                target="/proc/1/root${'$'}target"
            fi
            [ -f "${'$'}target" ] && [ ! -L "${'$'}target" ] || { printf missing; exit 0; }
            [ -n ${quote(additions + removals)} ] || { printf unchanged; exit 0; }
            size=${'$'}(stat -c %s "${'$'}target") || exit 1
            [ "${'$'}size" -le 65536 ] || exit 1
            original=${'$'}(sha256sum "${'$'}target") || exit 1
            temporary="${'$'}target.qixia.${'$'}${'$'}.tmp"
            trap 'rm -f "${'$'}temporary"' EXIT
            umask 077
            cp -p "${'$'}target" "${'$'}temporary" || exit 1
            awk -v additions=${quote(additions)} -v removals=${quote(removals)} '
            BEGIN {
                count = split(removals, values, ",")
                for (i = 1; i <= count; i++) removed[values[i]] = 1
            }
            function keep(value) {
                gsub(/^[ \t]+|[ \t]+${'$'}/, "", value)
                if (value != "" && !removed[value] && !seen[value]++) {
                    merged = merged (merged == "" ? "" : ",") value
                }
            }
            {
                rows[NR] = ${'$'}0
                line = ${'$'}0
                if (NR == 1 && sub(/\r${'$'}/, "", line)) ending = "\r"
                sub(/\r${'$'}/, "", line)
                equal = index(line, "=")
                key = substr(line, 1, equal - 1)
                gsub(/^[ \t]+|[ \t]+${'$'}/, "", key)
                if (equal && key == "blacklist") {
                    black[NR] = 1
                    if (!first) first = NR
                    count = split(substr(line, equal + 1), values, ",")
                    for (i = 1; i <= count; i++) keep(values[i])
                }
            }
            END {
                count = split(additions, values, ",")
                for (i = 1; i <= count; i++) keep(values[i])
                for (row = 1; row <= NR; row++) {
                    if (row == first) print "blacklist=" merged ending
                    else if (!black[row]) print rows[row]
                }
                if (!first && merged != "") print "blacklist=" merged ending
            }' "${'$'}target" > "${'$'}temporary" || exit 1
            [ "${'$'}original" = "${'$'}(sha256sum "${'$'}target")" ] && [ ! -L "${'$'}target" ] || {
                printf retry; exit 0;
            }
            if cmp -s "${'$'}target" "${'$'}temporary"; then printf unchanged; exit 0; fi
            context=${'$'}(stat -c %C "${'$'}target") || exit 1
            case "${'$'}context" in *:*:*) chcon "${'$'}context" "${'$'}temporary" || exit 1;; esac
            [ "${'$'}original" = "${'$'}(sha256sum "${'$'}target")" ] || { printf retry; exit 0; }
            mv -f "${'$'}temporary" "${'$'}target" || exit 1
            printf updated
        """.trimIndent()
    }
}

/** Activity 切换和旋转仍属于同一次前台使用。 */
internal class ForegroundEntryGate(private val onEnter: () -> Unit) {
    private var startedCount = 0
    private var changingConfiguration = false

    fun started() {
        if (startedCount++ == 0 && !changingConfiguration) onEnter()
        changingConfiguration = false
    }

    fun stopped(changingConfiguration: Boolean) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) this.changingConfiguration = changingConfiguration
    }
}
