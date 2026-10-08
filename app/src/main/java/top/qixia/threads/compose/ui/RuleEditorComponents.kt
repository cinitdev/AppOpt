package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.theme.*

@Composable
internal fun RulePanelHandle() {
    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(28.dp, 4.dp), color = OceanOutline, shape = RoundedCornerShape(3.dp)) {}
    }
}

@Composable
internal fun RuleEditorFooter(error: String?, status: String,
    secondary: @Composable RowScope.() -> Unit, primary: @Composable RowScope.() -> Unit) {
    Surface(color = OceanSurface) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = OceanDivider)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(error ?: status, color = if (error == null) OceanTextSecondary else OceanError,
                    fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { secondary(); primary() }
            }
        }
    }
}

@Composable
internal fun RuleCoreChooser(allowed: Set<Int>, cpuText: String, onChange: (String) -> Unit) {
    val selected = remember(cpuText) { RuleConfigLogic.parseCpuRangeList(cpuText).orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许使用的核心", color = OceanText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (selected.isEmpty()) "未选择" else "已选 ${selected.size} 核", color = PorcelainOnTonal, fontSize = 12.sp)
        }
        if (allowed.isEmpty()) Text("核心信息读取失败，请关闭后重新打开。", color = OceanError, fontSize = 12.sp)
        allowed.sorted().chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cpu ->
                    val checked = cpu in selected
                    Surface(onClick = {
                        onChange(RuleConfigLogic.formatCpuRangeList(if (checked) selected - cpu else selected + cpu))
                    }, selected = checked, modifier = Modifier.weight(1f).height(46.dp),
                        color = if (checked) PorcelainAction else OceanSurface,
                        contentColor = if (checked) Color.White else OceanTextSecondary,
                        border = if (checked) null else BorderStroke(1.dp, OceanOutline), shape = RoundedCornerShape(12.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (checked) { Icon(Icons.Outlined.Check, null, Modifier.size(12.dp)); Spacer(Modifier.width(3.dp)) }
                            Text("CPU $cpu", fontSize = 12.sp)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        OutlinedTextField(cpuText, onChange, Modifier.fillMaxWidth().testTag("rule-core-range"), singleLine = true,
            label = { Text("核心范围", fontSize = 12.sp) }, placeholder = { Text("例如 0-3,5", fontSize = 13.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp), shape = RoundedCornerShape(14.dp),
            isError = cpuText.isNotBlank() && (selected.isEmpty() || !allowed.containsAll(selected)))
    }
}
