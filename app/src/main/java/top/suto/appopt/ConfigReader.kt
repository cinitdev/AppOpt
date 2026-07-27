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
        DaemonBridge.readConfigRawOrNull()?.let {
            parsePackages(it, RuleConfigLogic.readPresentCpuSet())
        }

    internal fun parsePackages(text: String, presentCpus: Set<Int>? = null): ConfigPackages {
        if (text.isBlank()) return ConfigPackages(emptyList(), emptyList())
        val auto = LinkedHashSet<String>()
        val configured = LinkedHashSet<String>()
        val fixedProcessOwners = HashSet<String>()
        val configuredRuleCounts = LinkedHashMap<String, Int>()
        val ruleHealthKeys = LinkedHashSet<String>()
        for (segment in RuleSyntax.parse(text).segments) {
            if (!segment.valid || segment.rules.isEmpty()) continue
            val validRules = segment.rules.filter(::isNativeCompatibleRule)
            if (segment.block && validRules.size != segment.rules.size) continue
            for (rule in validRules) {
                if (!rule.cpus.equals("auto", ignoreCase = true) && presentCpus != null) {
                    val requested = RuleConfigLogic.parseNativeCpuRangeList(rule.cpus).orEmpty()
                    if (requested.none(presentCpus::contains)) continue
                }
                val key = rule.owner
                if (rule.thread == null && rule.cpus.equals("auto", ignoreCase = true)) {
                    if (key !in fixedProcessOwners) auto.add(key)
                } else {
                    configured.add(key)
                    configuredRuleCounts[key] = (configuredRuleCounts[key] ?: 0) + 1
                    if (rule.thread == null) {
                        fixedProcessOwners.add(key)
                        auto.remove(key)
                    }
                    val healthKey = if (rule.thread != null) {
                        DaemonBridge.ruleHealthKey("T", key, rule.thread)
                    } else if (key.contains(':')) {
                        DaemonBridge.ruleHealthKey("P", key, null)
                    } else {
                        null
                    }
                    if (healthKey != null) ruleHealthKeys.add(healthKey)
                }
            }
        }
        return ConfigPackages(
            auto.toList(),
            configured.toList(),
            configuredRuleCounts,
            ruleHealthKeys
        )
    }

    private fun isNativeCompatibleRule(rule: RuleSyntax.Rule): Boolean {
        if (!RuleConfigLogic.ownerFitsNativeBuffer(rule.owner)) return false
        if (rule.thread?.let(RuleConfigLogic::threadFitsNativeBuffer) == false) return false
        if (rule.cpus.equals("auto", ignoreCase = true)) return rule.thread == null
        return RuleConfigLogic.parseNativeCpuRangeList(rule.cpus) != null
    }
}
