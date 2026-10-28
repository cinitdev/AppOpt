package top.qixia.threads.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PerformanceRecordingPicker(apps: List<AppItemModel>, loading: Boolean, busy: Set<String>,
    onDismiss: () -> Unit, onToggle: (AppItemModel, Boolean) -> Unit, onLaunch: (AppItemModel) -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = remember(apps, query) { apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false, dragHandle = null, containerColor = OceanBackground) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开始性能记录", color = OceanText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("开启后进入应用，点击悬浮胶囊开始\n开关长期保留，长按胶囊可关闭", color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭性能记录选择") }
            }
            Spacer(Modifier.height(14.dp))
            SearchField(query, { query = it }, "搜索应用或游戏")
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    Surface(color = OceanSurface, shape = RoundedCornerShape(17.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppPackageIcon(app, app.label, Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = OceanText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (app.performanceRecordingEnabled) "已开启 · 点击右侧打开" else "等待开启", color = OceanTextSecondary, fontSize = 11.sp)
                            }
                            Switch(app.performanceRecordingEnabled, { onToggle(app, it) }, enabled = app.packageName !in busy,
                                colors = SwitchDefaults.colors(checkedTrackColor = PorcelainAction))
                            IconButton({ onLaunch(app) }, enabled = app.performanceRecordingEnabled && app.packageName !in busy) {
                                Icon(Icons.Outlined.PlayArrow, "打开 ${app.label}")
                            }
                        }
                    }
                }
            }
        }
    }
}
