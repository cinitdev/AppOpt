package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.theme.*

/** 扩展入口只负责导航，进入具体工具后才读取配置和设备状态。 */
@Composable
internal fun SettingsExtensionsContent(onRefreshRate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 24.dp)) {
        Surface(onClick = onRefreshRate, color = OceanSurface, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(.7.dp, OceanOutline)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = PorcelainHeader, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DisplaySettings, null, Modifier.size(28.dp), tint = PorcelainOnTonal)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("屏幕显示", color = OceanTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(22.dp))
                Text("刷新率管理", color = OceanText, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("为应用和游戏设置活动、闲置刷新率，按设备支持的档位调整。",
                    color = OceanTextSecondary, fontSize = 13.sp, lineHeight = 22.sp)
                HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 16.dp), color = OceanDivider)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("全局策略 · 应用规则", Modifier.weight(1f), color = PorcelainOnTonal,
                        fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = PorcelainOnTonal)
                }
            }
        }
    }
}
