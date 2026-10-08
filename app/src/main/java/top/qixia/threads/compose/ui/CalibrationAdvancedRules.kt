package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.CalibrationReview
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.theme.*

@Composable
internal fun CalibrationAdvancedRulesShortcut(configured: Int, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = PorcelainHeader, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(21.dp), tint = PorcelainOnTonal)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                Text("高级规则", color = PorcelainOnTonal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (configured == 0) "进程兜底 · 可选" else "已设置 $configured 项进程兜底",
                    color = OceanTextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
            TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (configured == 0) "去设置" else "查看设置", fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(15.dp))
            }
        }
    }
}

@Composable
internal fun CalibrationAdvancedRulesCard(review: CalibrationReview, enabled: Boolean, onEdit: (String) -> Unit) {
    Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, PorcelainAction.copy(alpha = .25f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = PorcelainHeader, shape = RoundedCornerShape(11.dp)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(21.dp), tint = PorcelainOnTonal)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("高级规则", color = OceanText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("为未匹配线程设置进程兜底", color = PorcelainOnTonal, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
                Surface(color = OceanSurface, shape = RoundedCornerShape(8.dp)) {
                    Text("可选", color = OceanTextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Text("默认交由系统调度；选择核心后才添加兜底规则，单独设置的线程规则优先生效。",
                color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            review.processOwners.sorted().forEach { owner ->
                val cpus = review.processSelections[owner].orEmpty()
                Surface(color = OceanSurface, shape = RoundedCornerShape(15.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (owner == review.draft.packageName) "主进程兜底" else ":${owner.substringAfter(':')}",
                                color = OceanText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (cpus.isEmpty()) "未设置 · 系统调度" else "已设置进程兜底", color = OceanTextSecondary,
                                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton({ onEdit(owner) }, enabled = enabled,
                            modifier = Modifier.widthIn(max = 150.dp).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(if (cpus.isEmpty()) "选择核心" else "CPU ${RuleConfigLogic.formatCpuRangeList(cpus)}",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Outlined.Edit, null, Modifier.size(15.dp))
                        }
                    }
                }
            }
            Text("与上方线程规则一起保存后生效", color = PorcelainOnTonal, fontSize = 11.sp)
        }
    }
}
