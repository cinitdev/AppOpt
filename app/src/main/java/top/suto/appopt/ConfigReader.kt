package top.suto.appopt

/**
 * 读取模块配置文件 applist.conf, 提取待配置和已配置应用。
 *
 * 配置文件位于 /data/adb/modules/AppOpt/config/applist.conf, 普通应用无权限读取,
 * 故经由 root (su) 读取。改版守护进程支持 "<包名>=auto" 语法:
 * 仅给出包名、不指定大小核, 由本 App 启动悬浮球做实时校准。
 */
object ConfigReader {

    data class ConfigPackages(
        val autoPackages: List<String>,
        val configuredPackages: List<String>,
        val configuredRuleCounts: Map<String, Int> = emptyMap(),
        val ruleHealthKeys: Set<String> = emptySet()
    )

    /**
     * 返回配置中的待配置包名(auto)和已配置包名(非 auto)。
     * 多条线程规则会合并为同一个包名, 同时保留每个进程的实际规则行数。
     */
    fun readPackages(): ConfigPackages {
        return readPackagesOrNull() ?: ConfigPackages(emptyList(), emptyList())
    }

    fun readPackagesOrNull(): ConfigPackages? =
        DaemonBridge.readConfigRawOrNull()?.let(::parsePackages)

    internal fun parsePackages(text: String): ConfigPackages {
        if (text.isBlank()) return ConfigPackages(emptyList(), emptyList())
        val auto = LinkedHashSet<String>()
        val configured = LinkedHashSet<String>()
        val configuredRuleCounts = LinkedHashMap<String, Int>()
        val ruleHealthKeys = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val value = line.substring(eq + 1).trim()
            // 等号左侧形如 pkg 或 pkg{Thread}; 取 '{' 之前作为包名
            val left = line.substring(0, eq).trim()
            val brace = left.indexOf('{')
            val key = if (brace >= 0) left.substring(0, brace).trim() else left
            if (key.isEmpty()) continue
            if (value.equals("auto", ignoreCase = true)) {
                auto.add(key)
            } else {
                configured.add(key)
                configuredRuleCounts[key] = (configuredRuleCounts[key] ?: 0) + 1
                val healthKey = if (brace >= 0) {
                    val close = left.lastIndexOf('}')
                    val target = if (close > brace) left.substring(brace + 1, close).trim() else ""
                    target.takeIf { it.isNotEmpty() }
                        ?.let { DaemonBridge.ruleHealthKey("T", key, it) }
                } else if (key.contains(':')) {
                    DaemonBridge.ruleHealthKey("P", key, null)
                } else {
                    null
                }
                if (healthKey != null) ruleHealthKeys.add(healthKey)
            }
        }
        return ConfigPackages(
            auto.toList(),
            configured.toList(),
            configuredRuleCounts,
            ruleHealthKeys
        )
    }
}
