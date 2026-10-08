package top.qixia.threads.compose

import top.qixia.threads.CalibPolicy
import top.qixia.threads.RuleConfigLogic

/** 先校验再规范化，避免静默替换无效输入。 */
object PolicyEditorLogic {
    fun validate(policy: CalibPolicy, presentCpus: Set<Int>): String? {
        if (CalibPolicy.normalizeCpusetNameOrNull(policy.cpusetName) == null) return "cpuset 名称无效"
        return null
    }
}
