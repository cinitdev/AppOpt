package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.theme.*

@Composable
internal fun ReportStatistics(series: List<HistoryPlotSeries>, expanded: Boolean, onToggle: () -> Unit) {
    HorizontalDivider(color = OceanOutline.copy(alpha = .65f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("指标统计", color = OceanText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("${series.size}", color = PorcelainOnTonal, fontSize = 10.sp,
            modifier = Modifier.padding(start = 7.dp).background(OceanSurfaceHigh, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onToggle, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
            Text(if (expanded) "收起" else "展开", fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
    }
    if (expanded) {
        Row(Modifier.fillMaxWidth().background(OceanSurfaceHigh, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text("指标 / 单位", color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1.45f))
            listOf("最高", "最低", "平均").forEach { Text(it, color = OceanTextSecondary, fontSize = 10.sp,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End) }
        }
        series.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.45f), verticalAlignment = Alignment.CenterVertically) {
                    HistoryLegendMark(item.color)
                    Spacer(Modifier.width(4.dp))
                    Text("${if (item.label == "FPS") "帧率" else item.label} · ${item.unit}", color = OceanTextSecondary,
                        fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                listOf(item.data.maximum, item.data.minimum, item.data.average).forEachIndexed { index, value ->
                    Text(historyNumber(value), color = if (index == 2) item.color else OceanText, fontSize = 11.sp,
                        fontWeight = if (index == 2) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
        Text("统计包含全部有效样本，不受曲线显隐影响。", color = OceanTextSecondary, fontSize = 9.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun HistorySeriesDialog(title: String, series: List<HistoryPlotSeries>, hidden: List<String>,
    onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf(hidden) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("$title · 图表选项") }, text = {
        Column {
            Text("选择要显示的曲线", color = OceanTextSecondary, fontSize = 12.sp)
            Row {
                TextButton({ draft = emptyList() }) { Text("全选") }
                TextButton({ draft = series.map { it.data.key } }) { Text("清空") }
            }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                series.forEach { item ->
                    val selected = item.data.key !in draft
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, role = Role.Checkbox) {
                        draft = if (selected) draft + item.data.key else draft - item.data.key
                    }, verticalAlignment = Alignment.CenterVertically) {
                        HistoryLegendMark(item.color, selected)
                        Spacer(Modifier.width(8.dp))
                        Text(item.label, color = OceanText, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Checkbox(selected, onCheckedChange = null)
                    }
                }
            }
        }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } },
        confirmButton = { TextButton({ onConfirm(draft) }) { Text("确定") } })
}
