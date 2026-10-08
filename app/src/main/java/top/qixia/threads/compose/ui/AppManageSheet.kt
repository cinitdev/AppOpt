package top.qixia.threads.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.AppRuleState
import top.qixia.threads.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppManageSheet(
    app: AppItemModel, enabled: Boolean, onDismiss: () -> Unit,
    onLaunch: () -> Unit, onEditRules: () -> Unit,
    onAutomaticAffinity: (Boolean) -> Unit, automaticSupported: Boolean, onDelete: () -> Unit,
    onDiagnostics: () -> Unit = {}
) {
    val automatic = app.automaticAffinityEnabled && !app.isSystemComponent
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OceanBackground,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(OceanOutline))
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (app.isSystemComponent) "系统组件管理" else "应用管理", Modifier.weight(1f), color = OceanTextSecondary, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = .5.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, if (app.isSystemComponent) "关闭系统组件管理" else "关闭应用管理",
                        tint = OceanTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = OceanSurface,
                        border = BorderStroke(1.dp, OceanOutline.copy(alpha = .6f))) {
                        AppPackageIcon(app, app.label, Modifier.padding(5.dp).size(52.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(app.label, color = OceanText, fontSize = 24.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = OceanTextSecondary, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (app.isSystemComponent) Text("系统组件", color = PorcelainOnTonal, fontSize = 11.sp)
                        else if (!app.available) Text("应用未安装", color = OceanWarning, fontSize = 11.sp)
                    }
                }

                if (!app.isSystemComponent) Surface(color = if (automatic) PorcelainHeader.copy(alpha = .7f) else OceanSurface,
                    border = BorderStroke(1.dp, if (automatic) OceanPrimary.copy(alpha = .23f) else OceanOutline),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(17.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ManageIcon(Icons.Outlined.Memory)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text("自动分配", color = OceanText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(if (automatic) "已开启 · 平均负载达到 5% 参与分配"
                                    else "按线程负载，自动安排核心",
                                    color = PorcelainOnTonal, fontSize = 11.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                            Switch(checked = automatic, onCheckedChange = onAutomaticAffinity,
                                enabled = enabled && automaticSupported,
                                modifier = Modifier.semantics { contentDescription = "自动分配线程核心" },
                                thumbContent = if (automatic) ({ Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = PorcelainAction) }) else null,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = PorcelainAction, checkedThumbColor = Color.White,
                                    uncheckedTrackColor = OceanOutline, uncheckedThumbColor = Color.White,
                                    uncheckedBorderColor = Color.Transparent))
                        }
                        HorizontalDivider(Modifier.padding(vertical = 13.dp), color = OceanOutline.copy(alpha = .7f))
                        Text(when {
                            !automaticSupported -> "当前模块尚不支持，请先更新模块。"
                            automatic -> app.automaticAffinityDetail
                            else -> "开启后仅在前台生效，已有规则暂停并保留。"
                        }, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                        if (automatic) TextButton(onDiagnostics, Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.FactCheck, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("核心接管诊断")
                        }
                    }
                }

                if (app.isSystemComponent || app.state != AppRuleState.PENDING) {
                    if (!app.isSystemComponent) Spacer(Modifier.height(12.dp))
                    Surface(onClick = onEditRules, enabled = enabled, color = OceanSurface,
                        shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, OceanOutline)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                            ManageIcon(Icons.Outlined.EditNote)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text("线程规则", color = if (enabled) OceanText else OceanTextSecondary,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (automatic) "${app.ruleCount} 条已保留 · 规则与检测已暂停"
                                    else "${app.ruleCount} 条规则 · ${app.cpuSummary}",
                                    color = OceanTextSecondary, fontSize = 11.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "查看与编辑规则",
                                tint = if (enabled) PorcelainOnTonal else OceanTextSecondary,
                                modifier = Modifier.padding(start = 8.dp).size(19.dp))
                        }
                    }
                }

                if (!app.isSystemComponent) {
                    Spacer(Modifier.height(16.dp))
                    if (app.averageFps != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Timeline, null, tint = PorcelainOnTonal, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("上次运行平均", color = OceanTextSecondary, fontSize = 12.sp)
                                Text("${formatSessionDuration(app.fpsSessionDurationMs)} · ${app.fpsSampleCount} 次采样",
                                    color = OceanTextSecondary, fontSize = 10.sp, lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                            Text(formatAverageFps(app.averageFps), color = PorcelainOnTonal,
                                fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Timeline, null, tint = OceanTextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("暂无帧率记录，运行结束后显示平均 FPS", color = OceanTextSecondary,
                                fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (!app.isSystemComponent) Button(onClick = onLaunch, enabled = enabled && app.installed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PorcelainAction),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(if (automatic) "打开应用" else "启动悬浮校准", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = OceanError)) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (app.isSystemComponent) "删除组件配置" else "删除应用配置", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ManageIcon(icon: ImageVector) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(OceanSurfaceHigh),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = PorcelainOnTonal, modifier = Modifier.size(22.dp))
    }
}
