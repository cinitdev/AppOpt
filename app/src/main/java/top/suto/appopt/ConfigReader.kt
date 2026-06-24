package top.suto.appopt

/**
 * 读取模块配置文件 applist.conf, 提取待配置和已配置应用。
 *
 * 配置文件位于 /data/adb/modules/AppOpt/config/applist.conf, 普通应用无权限读取,
 * 故经由 root (su) 读取。改版守护进程支持 "<包名>=auto" 语法:
 * 仅给出包名、不指定大小核, 由本 App 启动悬浮球做实时校准。
 */
object ConfigReader {

    private const val CONFIG_FILE = "/data/adb/modules/AppOpt/config/applist.conf"

    data class ConfigPackages(
        val autoPackages: List<String>,
        val configuredPackages: List<String>
    )

    /**
     * 返回配置中所有 "=auto" 的包名 (去重, 保序)。
     * 解析规则: 去掉 '#' 注释行, 行内允许 "pkg=auto" 或 "pkg{Thread}=auto",
     * 只要等号右侧 (大小写不敏感) 为 auto 即收录其包名部分。
     */
    fun readAutoPackages(): List<String> = readPackages().autoPackages

    /**
     * 返回配置中的待配置包名(auto)和已配置包名(非 auto)。
     * 多条线程规则会合并为同一个包名, 保留配置文件中的首次出现顺序。
     */
    fun readPackages(): ConfigPackages {
        val text = DaemonBridge.readConfigRaw()
        if (text.isBlank()) return ConfigPackages(emptyList(), emptyList())
        val auto = LinkedHashSet<String>()
        val configured = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val value = line.substring(eq + 1).trim()
            // 等号左侧形如 pkg 或 pkg{Thread}; 取 '{' 之前作为包名
            var key = line.substring(0, eq).trim()
            val brace = key.indexOf('{')
            if (brace >= 0) key = key.substring(0, brace).trim()
            if (key.isEmpty()) continue
            if (value.equals("auto", ignoreCase = true)) {
                auto.add(key)
            } else {
                configured.add(key)
            }
        }
        return ConfigPackages(auto.toList(), configured.toList())
    }
}
