package top.qixia.threads

/** App 只展示 service.sh 回传的状态，组件操作和策略写入均由 Shell 完成。 */
data class JoyoseSmartOpState(
    val recognized: Boolean = false,
    val successful: Boolean = false,
    val available: Boolean = false,
    val disabled: Boolean = false,
    val requestedDisabled: Boolean? = null,
    val reason: String = "state_unreadable"
) {
    val failureMessage: String
        get() = when (reason) {
            "policy_busy" -> "设置正在被其他操作使用，请稍后重试"
            "pending_update" -> "模块更新或卸载尚未完成，暂时无法修改 Joyose"
            "package_unavailable", "component_missing", "unsupported_system" -> "当前设备没有可用的 Joyose SmartOpService"
            "policy_write_failed" -> "Joyose 配置保存失败，组件未作修改"
            "apply_failed" -> "配置已保存，但 Joyose 状态未按要求生效，请重试"
            else -> "无法确认 Joyose 实际状态，请刷新设置后重试"
        }

    companion object {
        fun parse(raw: String, commandSucceeded: Boolean): JoyoseSmartOpState {
            val keys = setOf("version", "ok", "available", "disabled", "requested", "reason")
            val entries = raw.lineSequence().mapNotNull { line ->
                val key = line.substringBefore('=', "").trim()
                if (key in keys) key to line.substringAfter('=').trim() else null
            }.toList()
            if (entries.groupingBy { it.first }.eachCount().any { it.value != 1 }) return JoyoseSmartOpState()
            val values = entries.toMap()
            if (values["version"] != "1" || values["ok"] !in listOf("0", "1") ||
                values["available"] !in listOf("0", "1") || values["requested"] !in listOf("0", "1")
            ) return JoyoseSmartOpState()
            val available = values["available"] == "1"
            if (available && values["disabled"] !in listOf("0", "1")) return JoyoseSmartOpState()
            return JoyoseSmartOpState(
                recognized = true,
                successful = commandSucceeded && values["ok"] == "1",
                available = available,
                disabled = values["disabled"] == "1",
                requestedDisabled = when (values["requested"]) { "0" -> false; "1" -> true; else -> null },
                reason = values["reason"] ?: "state_unreadable"
            )
        }
    }
}
