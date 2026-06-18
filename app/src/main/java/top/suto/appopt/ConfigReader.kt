package top.suto.appopt

/**
 * 读取模块配置文件 applist.conf, 提取所有写成 "<包名>=auto" 的应用。
 *
 * 配置文件位于 /data/adb/modules/AppOpt/applist.conf, 普通应用无权限读取,
 * 故经由 root (su) 读取。改版守护进程支持 "<包名>=auto" 语法:
 * 仅给出包名、不指定大小核, 由本 App 启动悬浮球做实时校准。
 */
object ConfigReader {

    private const val CONFIG_FILE = "/data/adb/modules/AppOpt/applist.conf"

    /**
     * 返回配置中所有 "=auto" 的包名 (去重, 保序)。
     * 解析规则: 去掉 '#' 注释行, 行内允许 "pkg=auto" 或 "pkg{Thread}=auto",
     * 只要等号右侧 (大小写不敏感) 为 auto 即收录其包名部分。
     */
    fun readAutoPackages(): List<String> {
        val text = DaemonBridge.readConfigRaw()
        if (text.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val value = line.substring(eq + 1).trim()
            if (!value.equals("auto", ignoreCase = true)) continue
            // 等号左侧形如 pkg 或 pkg{Thread}; 取 '{' 之前作为包名
            var key = line.substring(0, eq).trim()
            val brace = key.indexOf('{')
            if (brace >= 0) key = key.substring(0, brace).trim()
            if (key.isNotEmpty()) result.add(key)
        }
        return result.toList()
    }
}
