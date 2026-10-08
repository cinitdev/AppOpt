package top.qixia.threads.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.qixia.threads.R

class UiConceptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val concept = intent.getIntExtra("concept", 1).coerceIn(1, 3)
        val screen = intent.getStringExtra("screen") ?: "apps"
        setContent {
            if (screen == "logs-events") LogsVisualFixture()
            else if (screen.startsWith("module-update")) ModuleUpdateFixture(screen)
            else if (screen == "calibration-review") CalibrationReviewFixture()
            else if (screen == "rule-editor") RuleEditorFixture()
            else if (screen.startsWith("mint-")) HomeVisualFixture(screen)
            else if (screen.startsWith("history-")) HistoryVisualFixture(screen)
            else ConceptApp(concept = concept, screen = screen)
        }
    }
}

@Immutable
private data class UiPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val secondary: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val primarySoft: Color,
)

private fun paletteFor(concept: Int): UiPalette = when (concept) {
    2 -> UiPalette(
        background = Color(0xFFF7F8FC), surface = Color.White, surfaceAlt = Color(0xFFF0F1FF),
        primary = Color(0xFF5268F2), secondary = Color(0xFF2BAE83), text = Color(0xFF1B1C20),
        muted = Color(0xFF61646F), border = Color(0xFFDEE1EA), success = Color(0xFF21885F),
        warning = Color(0xFFD97722), danger = Color(0xFFC94747), primarySoft = Color(0xFFE8EAFF),
    )
    3 -> UiPalette(
        background = Color(0xFFF5F1E8), surface = Color(0xFFFFFDF8), surfaceAlt = Color(0xFFF0EADD),
        primary = Color(0xFF1768C7), secondary = Color(0xFF3A9D84), text = Color(0xFF172536),
        muted = Color(0xFF6E6B64), border = Color(0xFFD8D0C3), success = Color(0xFF2E8C72),
        warning = Color(0xFFB9852F), danger = Color(0xFFBF543E), primarySoft = Color(0xFFE7EFF8),
    )
    else -> UiPalette(
        background = Color(0xFFF4F7FB), surface = Color.White, surfaceAlt = Color(0xFFF7FAFE),
        primary = Color(0xFF246BFD), secondary = Color(0xFF00A7A5), text = Color(0xFF122033),
        muted = Color(0xFF667085), border = Color(0xFFD7E1EE), success = Color(0xFF119C68),
        warning = Color(0xFFD97706), danger = Color(0xFFC9363F), primarySoft = Color(0xFFE8F1FF),
    )
}

private fun radius(concept: Int, large: Boolean = false): Dp = when (concept) {
    2 -> if (large) 28.dp else 20.dp
    3 -> if (large) 12.dp else 8.dp
    else -> if (large) 16.dp else 12.dp
}

@Composable
private fun ConceptApp(concept: Int, screen: String) {
    val p = paletteFor(concept)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = p.primary,
            secondary = p.secondary,
            background = p.background,
            surface = p.surface,
            onPrimary = Color.White,
            onSurface = p.text,
        ),
        typography = Typography(),
    ) {
        Surface(color = p.background, modifier = Modifier.fillMaxSize()) {
            when (screen) {
                "add" -> ApplicationsScreen(concept, p, 1)
                "configured" -> ApplicationsScreen(concept, p, 2)
                "rules" -> RulesScreen(concept, p)
                "editor" -> RuleEditorScreen(concept, p)
                "environment" -> EnvironmentScreen(concept, p)
                "history" -> HistoryListScreen(concept, p)
                "history_detail" -> HistoryDetailScreen(concept, p)
                "logs" -> LogsScreen(concept, p)
                "settings_rules" -> SettingsRulesScreen(concept, p)
                "settings_perf" -> SettingsPerformanceScreen(concept, p)
                "flows" -> FlowStatesScreen(concept, p)
                else -> ApplicationsScreen(concept, p, 0)
            }
        }
    }
}

@Composable
private fun ScreenShell(
    concept: Int,
    p: UiPalette,
    pageTitle: String,
    subtitle: String,
    activeNav: String,
    showBottomNav: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AppHeader(concept, p, pageTitle, subtitle)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        if (showBottomNav) BottomNavigation(concept, p, activeNav)
    }
}

@Composable
private fun AppHeader(concept: Int, p: UiPalette, title: String, subtitle: String) {
    when (concept) {
        2 -> Surface(
            color = p.primarySoft,
            shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = p.muted, fontSize = 12.sp)
                }
                StatusPill(p, "守护在线", p.success)
            }
        }
        3 -> Column(modifier = Modifier.fillMaxWidth().background(p.surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("APP/OPT", color = p.primary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusPill(p, "ONLINE", p.success)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BrandMark(36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = p.text, fontSize = 27.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = p.muted, fontSize = 12.sp)
                }
                Text("•••", color = p.muted, fontSize = 18.sp)
            }
            Spacer(Modifier.height(13.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.primary))
        }
        else -> Surface(color = p.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("QixiaThreads", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(9.dp))
                        StatusPill(p, "守护在线", p.success)
                    }
                    Text(title + "  ·  " + subtitle, color = p.muted, fontSize = 11.sp)
                }
                Icon(Icons.Outlined.MoreVert, null, tint = p.muted)
            }
        }
    }
}

@Composable
private fun BrandMark(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_qixia_mark),
        contentDescription = "QixiaThreads",
        modifier = Modifier.size(size),
    )
}

@Composable
private fun BottomNavigation(concept: Int, p: UiPalette, active: String) {
    val items = listOf(
        Triple("应用", Icons.Outlined.Apps, "应用"),
        Triple("环境", Icons.Outlined.Shield, "环境"),
        Triple("历史", Icons.Outlined.History, "历史"),
        Triple("日志", Icons.Outlined.Description, "日志"),
        Triple("设置", Icons.Outlined.Settings, "设置"),
    )
    val outer = when (concept) {
        2 -> Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(p.surface)
        else -> Modifier.background(p.surface).border(width = 1.dp, color = p.border)
    }
    Row(
        modifier = outer.fillMaxWidth().height(if (concept == 2) 72.dp else 68.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, icon, key) ->
            val selected = key == active
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().clickable { }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (concept == 3 && selected) Box(Modifier.width(28.dp).height(2.dp).background(p.primary))
                Icon(icon, null, tint = if (selected) p.primary else p.muted, modifier = Modifier.size(22.dp))
                Text(label, color = if (selected) p.primary else p.muted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PageList(
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Section(
    concept: Int,
    p: UiPalette,
    title: String,
    kicker: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius(concept, true))
    val modifier = when (concept) {
        3 -> Modifier.fillMaxWidth().border(1.dp, p.border, shape).padding(14.dp)
        2 -> Modifier.fillMaxWidth().background(p.surface, shape).padding(16.dp)
        else -> Modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.border, shape).padding(14.dp)
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (kicker != null) Text(kicker.uppercase(), color = p.primary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(title, color = p.text, fontSize = if (concept == 3) 17.sp else 15.sp, fontFamily = if (concept == 3) FontFamily.Serif else FontFamily.Default, fontWeight = FontWeight.Bold)
            }
            if (trailing != null) Text(trailing, color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun StatusPill(p: UiPalette, label: String, color: Color) {
    Row(
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.10f)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tabs(concept: Int, p: UiPalette, labels: List<String>, selected: Int) {
    when (concept) {
        2 -> Row(
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(p.primarySoft).padding(4.dp),
        ) {
            labels.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier.weight(1f).clip(CircleShape).background(if (index == selected) p.surface else Color.Transparent).clickable { }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (index == selected) p.primary else p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        else -> Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier.weight(1f).clickable { }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (concept == 3) "0${index + 1}  $label" else label, color = if (index == selected) p.primary else p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.fillMaxWidth(if (index == selected) 0.78f else 1f).height(2.dp).background(if (index == selected) p.primary else p.border))
                }
            }
        }
    }
}

@Composable
private fun SearchField(concept: Int, p: UiPalette, hint: String = "搜索应用或包名") {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(radius(concept))).background(if (concept == 3) p.surface else p.surfaceAlt).border(1.dp, p.border, RoundedCornerShape(radius(concept))).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = p.muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(hint, color = p.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.FilterList, null, tint = p.primary, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun StatusStrip(concept: Int, p: UiPalette) {
    Section(concept, p, "运行状态", kicker = if (concept == 3) "SYSTEM 01" else null, trailing = "全部正常") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val statuses = listOf(
                "悬浮窗" to Icons.Outlined.Layers,
                "使用情况" to Icons.Outlined.Assessment,
                "Root" to Icons.Outlined.Security,
                "守护进程" to Icons.Outlined.Shield,
                "前台监听" to Icons.Outlined.Visibility,
            )
            statuses.forEach { (label, icon) ->
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(radius(concept))).background(p.success.copy(alpha = 0.07f)).padding(vertical = 9.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, null, tint = p.success, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = p.text, fontSize = 8.sp, maxLines = 1)
                    Text("正常", color = p.success, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(p: UiPalette, icon: ImageVector, tint: Color = p.primary) {
    Box(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun AppRow(
    concept: Int,
    p: UiPalette,
    name: String,
    pkg: String,
    meta: String,
    icon: ImageVector,
    action: String,
    actionColor: Color = p.primary,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(p, icon, actionColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pkg, color = p.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = if (meta.contains("平均")) p.primary else p.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(p, action, actionColor)
    }
}

@Composable
private fun ApplicationsScreen(concept: Int, p: UiPalette, mode: Int) {
    val title = when (mode) { 1 -> "添加应用"; 2 -> "已配置应用"; else -> "应用与校准" }
    ScreenShell(concept, p, title, "线程策略与会话采样", "应用") {
        if (mode == 2) {
            Box(Modifier.fillMaxSize()) {
                PageList(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    item { StatusStrip(concept, p) }
                    item { Tabs(concept, p, listOf("待校准", "添加应用", "已配置"), 2) }
                    item { SearchField(concept, p) }
                    item {
                        Section(concept, p, "已配置 · 3", kicker = if (concept == 3) "CATALOG 02" else null) {
                            AppRow(concept, p, "系统界面", "com.android.systemui", "2 条规则 · 上次平均 59.1 FPS", Icons.Outlined.Android, "管理")
                            DividerLine(p)
                            AppRow(concept, p, "surfaceflinger", "/system/bin/surfaceflinger", "8 条规则 · 上次平均 60.0 FPS", Icons.Outlined.Timeline, "管理", p.secondary)
                            DividerLine(p)
                            AppRow(concept, p, "原神", "com.miHoYo.Yuanshen", "5 条规则 · 上次平均 58.6 FPS", Icons.Outlined.Science, "管理")
                        }
                    }
                    item { Spacer(Modifier.height(210.dp)) }
                }
                ManagementSheet(concept, p, Modifier.align(Alignment.BottomCenter))
            }
        } else {
            PageList {
                item { StatusStrip(concept, p) }
                item { Tabs(concept, p, listOf("待校准", "添加应用", "已配置"), mode) }
                if (mode == 0) {
                    item {
                        Section(concept, p, "自动开始", trailing = "关闭") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("启动目标应用后自动记录负载", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("建议进入稳定场景后手动开始，结果更准确", color = p.muted, fontSize = 10.sp)
                                }
                                Switch(checked = false, onCheckedChange = {})
                            }
                        }
                    }
                    item { SearchField(concept, p) }
                    item {
                        Section(concept, p, "待校准 · 4", kicker = if (concept == 3) "QUEUE 02" else null) {
                            AppRow(concept, p, "KernelSU", "me.weishu.kernelsu", "尚无完成会话", Icons.Outlined.Security, "启动")
                            DividerLine(p)
                            AppRow(concept, p, "原神", "com.miHoYo.Yuanshen", "等待校准 · 不显示实时 FPS", Icons.Outlined.Science, "启动")
                            DividerLine(p)
                            AppRow(concept, p, "崩坏：星穹铁道", "com.miHoYo.hkrpg", "等待校准", Icons.Outlined.Bolt, "启动", p.secondary)
                        }
                    }
                } else {
                    item { SearchField(concept, p, "搜索应用名称或包名") }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(p, "已安装 86", p.secondary)
                            Spacer(Modifier.weight(1f))
                            Text("隐藏未安装", color = p.muted, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = true, onCheckedChange = {})
                        }
                    }
                    item {
                        Section(concept, p, "可添加应用", kicker = if (concept == 3) "LIBRARY 02" else null) {
                            AppRow(concept, p, "MT 管理器", "bin.mt.plus.canary", "未配置", Icons.Outlined.Description, "添加")
                            DividerLine(p)
                            AppRow(concept, p, "YouTube", "com.google.android.youtube", "未配置", Icons.Outlined.PlayArrow, "添加", p.danger)
                            DividerLine(p)
                            AppRow(concept, p, "QQ 飞车", "com.tencent.tmgp.speedmobile", "未配置", Icons.Outlined.Speed, "添加", p.secondary)
                            DividerLine(p)
                            AppRow(concept, p, "设置", "com.android.settings", "未配置", Icons.Outlined.Settings, "添加")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementSheet(concept: Int, p: UiPalette, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = p.surface,
        shape = RoundedCornerShape(topStart = radius(concept, true) + 8.dp, topEnd = radius(concept, true) + 8.dp),
        shadowElevation = if (concept == 2) 10.dp else 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(p.border))
            Spacer(Modifier.height(12.dp))
            Text("应用管理", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("系统界面 · com.android.systemui", color = p.muted, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            ActionRow(p, Icons.Outlined.EditNote, "查看与编辑规则", "当前共 2 条规则", p.primary)
            ActionRow(p, Icons.Outlined.Bolt, "掉帧动态调度", "仅在检测到掉帧时临时增强", p.secondary, switchOn = false)
            ActionRow(p, Icons.Outlined.DeleteOutline, "删除应用配置", "移除该应用的全部绑定规则", p.danger)
        }
    }
}

@Composable
private fun ActionRow(p: UiPalette, icon: ImageVector, title: String, body: String, color: Color, switchOn: Boolean? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (color == p.danger) p.danger else p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = p.muted, fontSize = 10.sp)
        }
        if (switchOn != null) Switch(checked = switchOn, onCheckedChange = {})
        else Icon(Icons.Outlined.ExpandMore, null, tint = p.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DividerLine(p: UiPalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.border.copy(alpha = 0.75f)))
}

@Composable
private fun RulesScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "绑定规则", "系统界面 · 2 条规则", "应用", showBottomNav = false) {
        PageList(contentPadding = PaddingValues(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactButton(concept, p, Icons.Outlined.ArrowBack, "返回", outlined = true)
                    Spacer(Modifier.weight(1f))
                    CompactButton(concept, p, Icons.Outlined.Refresh, "复检")
                    Spacer(Modifier.width(8.dp))
                    CompactButton(concept, p, Icons.Outlined.Add, "新增")
                }
            }
            item {
                Section(concept, p, "规则健康", kicker = if (concept == 3) "RULESET 01" else null, trailing = "无异常") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(concept, p, "主进程", "1", p.primary, Modifier.weight(1f))
                        MetricTile(concept, p, "子进程", "0", p.secondary, Modifier.weight(1f))
                        MetricTile(concept, p, "线程", "1", p.warning, Modifier.weight(1f))
                    }
                }
            }
            item {
                Section(concept, p, "绑定规则", kicker = if (concept == 3) "RULESET 02" else null, trailing = "左滑可删除") {
                    RuleCard(concept, p, "主进程", "com.android.systemui", "CPU 0–3", "全局")
                    Spacer(Modifier.height(8.dp))
                    RuleCard(concept, p, "线程", "RenderThread", "CPU 4–7", "历史匹配 6 次")
                }
            }
            item {
                Section(concept, p, "保存状态", trailing = "有 1 处修改") {
                    Text("离开前保存；未保存修改会触发二次确认。", color = p.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    FullButton(concept, p, Icons.Outlined.Save, "保存全部规则")
                }
            }
        }
    }
}

@Composable
private fun RuleCard(concept: Int, p: UiPalette, type: String, name: String, cores: String, meta: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(radius(concept))).background(p.surfaceAlt).border(1.dp, p.border, RoundedCornerShape(radius(concept))).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(p.primarySoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Rule, null, tint = p.primary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row { StatusPill(p, type, p.primary); Spacer(Modifier.width(6.dp)); Text(meta, color = p.muted, fontSize = 9.sp) }
            Text(name, color = p.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Text(cores, color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.EditNote, null, tint = p.muted, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun RuleEditorScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "编辑规则", "RenderThread", "应用", showBottomNav = false) {
        PageList {
            item { Tabs(concept, p, listOf("主进程", "子进程", "线程"), 2) }
            item {
                Section(concept, p, "匹配目标", kicker = if (concept == 3) "MATCH 01" else null) {
                    LabelValueField(concept, p, "线程名称", "RenderThread", Icons.Outlined.EditNote)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(concept, p, Icons.Outlined.History, "历史多选", outlined = true, modifier = Modifier.weight(1f))
                        CompactButton(concept, p, Icons.Outlined.Tune, "建议通配符", outlined = true, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Section(concept, p, "历史命中", trailing = "6 次") {
                    listOf("RenderThread", "RenderThread#1", "RenderThread-GL").forEachIndexed { index, name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (index < 2) p.primary else p.surface).border(1.dp, p.primary, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                if (index < 2) Text("✓", color = Color.White, fontSize = 10.sp)
                            }
                            Spacer(Modifier.width(9.dp))
                            Text(name, color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(if (index == 0) "AVG 21.4%" else "AVG 14.8%", color = p.muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                Section(concept, p, "分配核心", kicker = if (concept == 3) "CPUSET 02" else null, trailing = "4 / 8") {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (0..3).forEach { CoreChip(concept, p, it, false, Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (4..7).forEach { CoreChip(concept, p, it, true, Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("性能核心 CPU 4–7 · 适合渲染线程", color = p.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactButton(concept, p, Icons.Outlined.ArrowBack, "取消", outlined = true, modifier = Modifier.weight(1f))
                    CompactButton(concept, p, Icons.Outlined.CheckCircle, "完成", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CoreChip(concept: Int, p: UiPalette, index: Int, selected: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(radius(concept))).background(if (selected) p.primary else p.surfaceAlt).border(1.dp, if (selected) p.primary else p.border, RoundedCornerShape(radius(concept))).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CPU", color = if (selected) Color.White.copy(alpha = 0.8f) else p.muted, fontSize = 8.sp)
        Text(index.toString(), color = if (selected) Color.White else p.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EnvironmentScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "运行环境", "权限、模块与更新", "环境") {
        PageList {
            item {
                Section(concept, p, "当前运行", kicker = if (concept == 3) "HEALTH 01" else null, trailing = "全部正常") {
                    EnvironmentRow(p, Icons.Outlined.Security, "Root 权限", "KernelSU · 已授权", p.success)
                    EnvironmentRow(p, Icons.Outlined.Shield, "守护进程", "Rust v1.8.6 · PID 1842", p.success)
                    EnvironmentRow(p, Icons.Outlined.Visibility, "前台监听", "运行中 · eBPF 可用", p.success)
                    EnvironmentRow(p, Icons.Outlined.Layers, "QixiaThreads 模块", "v1.8.6 · 已生效", p.success)
                }
            }
            item {
                Section(concept, p, "诊断", kicker = if (concept == 3) "TOOLS 02" else null) {
                    ActionRow(p, Icons.Outlined.FilePresent, "导出诊断包", "日志、规则和系统状态将保存到 Download/QixiaThreads", p.primary)
                }
            }
            item {
                Section(concept, p, "更新中心", kicker = if (concept == 3) "UPDATE 03" else null, trailing = "刚刚检查") {
                    UpdateRow(concept, p, Icons.Outlined.CloudDownload, "Root 模块", "本地 v1.8.6", "云端 v1.9.0", "查看更新")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = p.warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("模块更新完成后需要重启设备生效", color = p.warning, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentRow(p: UiPalette, icon: ImageVector, title: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = p.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StatusPill(p, value, color)
    }
}

@Composable
private fun UpdateRow(concept: Int, p: UiPalette, icon: ImageVector, title: String, local: String, remote: String, action: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(radius(concept))).background(p.surfaceAlt).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = p.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$local  ·  $remote", color = p.muted, fontSize = 10.sp)
        }
        StatusPill(p, action, p.primary)
    }
}

@Composable
private fun HistoryListScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "历史记录", "已完成会话与平均帧率", "历史") {
        PageList {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(concept, p, "应用", "3", p.primary, Modifier.weight(1f))
                    MetricTile(concept, p, "会话", "12", p.secondary, Modifier.weight(1f))
                    MetricTile(concept, p, "总采样", "7h 18m", p.warning, Modifier.weight(1f))
                }
            }
            item { SearchField(concept, p, "搜索历史应用") }
            item {
                Section(concept, p, "最近记录", kicker = if (concept == 3) "ARCHIVE 01" else null, trailing = "按时间") {
                    HistoryAppRow(concept, p, "原神", "com.miHoYo.Yuanshen", "5 次会话", "平均 58.6 FPS", Icons.Outlined.Science, p.primary)
                    DividerLine(p)
                    HistoryAppRow(concept, p, "崩坏：星穹铁道", "com.miHoYo.hkrpg", "4 次会话", "平均 57.9 FPS", Icons.Outlined.Bolt, p.secondary)
                    DividerLine(p)
                    HistoryAppRow(concept, p, "KernelSU", "me.weishu.kernelsu", "3 次会话", "仅负载记录", Icons.Outlined.Security, p.warning)
                }
            }
            item {
                Section(concept, p, "数据说明") {
                    Text("FPS 仅在应用运行期间本地采样；应用结束后计算并保留该次会话平均值。", color = p.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryAppRow(concept: Int, p: UiPalette, name: String, pkg: String, sessions: String, fps: String, icon: ImageVector, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(p, icon, color)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(pkg, color = p.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("$sessions · 最近 09-08 18:40", color = p.muted, fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fps, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ExpandMore, null, tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HistoryDetailScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "原神 · 校准记录", "5 次历史会话", "历史", showBottomNav = false) {
        PageList {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactButton(concept, p, Icons.Outlined.ArrowBack, "返回", outlined = true)
                    Spacer(Modifier.weight(1f))
                    CompactButton(concept, p, Icons.Outlined.Download, "导出")
                    Spacer(Modifier.width(8.dp))
                    CompactButton(concept, p, Icons.Outlined.MoreVert, "管理", outlined = true)
                }
            }
            item {
                Section(concept, p, "2026-09-08 · 18:40", kicker = if (concept == 3) "SESSION 05" else null, trailing = "42:18") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(concept, p, "平均 FPS", "58.6", p.primary, Modifier.weight(1f))
                        MetricTile(concept, p, "1% Low", "46.2", p.warning, Modifier.weight(1f))
                        MetricTile(concept, p, "采样点", "2,538", p.secondary, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Sparkline(p, listOf(0.42f, 0.55f, 0.49f, 0.68f, 0.61f, 0.77f, 0.72f, 0.81f, 0.69f, 0.74f), p.primary, 62.dp)
                }
            }
            item {
                Section(concept, p, "线程负载", kicker = if (concept == 3) "THREADS 06" else null, trailing = "AVG / MAX") {
                    ThreadLoadRow(p, "com.miHoYo.Yuanshen", "32.6%", "58.2%", p.primary, listOf(.32f,.48f,.42f,.61f,.55f,.72f,.66f,.58f))
                    DividerLine(p)
                    ThreadLoadRow(p, "RenderThread", "21.4%", "46.1%", p.secondary, listOf(.21f,.31f,.26f,.41f,.33f,.46f,.36f,.29f))
                    DividerLine(p)
                    ThreadLoadRow(p, "DefaultDispatcher-worker-1", "14.8%", "35.3%", p.warning, listOf(.12f,.18f,.16f,.28f,.23f,.35f,.25f,.19f))
                    DividerLine(p)
                    ThreadLoadRow(p, "binder:1234_1", "9.6%", "24.7%", p.danger, listOf(.08f,.11f,.09f,.17f,.14f,.24f,.16f,.12f))
                }
            }
        }
    }
}

@Composable
private fun ThreadLoadRow(p: UiPalette, name: String, avg: String, max: String, color: Color, data: List<Float>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = p.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("AVG $avg", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("MAX $max", color = p.muted, fontSize = 9.sp)
        }
        Spacer(Modifier.height(5.dp))
        Sparkline(p, data, color, 34.dp)
    }
}

@Composable
private fun Sparkline(p: UiPalette, data: List<Float>, color: Color, height: Dp) {
    Canvas(Modifier.fillMaxWidth().height(height).background(p.surfaceAlt, RoundedCornerShape(7.dp))) {
        val step = size.width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        data.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height - (value.coerceIn(0f, 1f) * size.height * .82f) - size.height * .08f)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawLine(p.border, Offset(0f, size.height * .5f), Offset(size.width, size.height * .5f), 1f)
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun LogsScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "运行日志", "守护进程与前台助手", "日志") {
        PageList {
            item { Tabs(concept, p, listOf("守护进程", "前台助手"), 0) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(p, "全部 115", p.primary)
                    StatusPill(p, "提醒 3", p.warning)
                    StatusPill(p, "错误 0", p.success)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Refresh, null, tint = p.primary)
                }
            }
            item {
                Section(concept, p, "实时输出", kicker = if (concept == 3) "LOGBOOK 01" else null, trailing = "已暂停自动滚动") {
                    LogRow(concept, p, "18:40:12.084", "INFO", "Rust", "检测到前台应用 com.miHoYo.Yuanshen", p.primary)
                    DividerLine(p)
                    LogRow(concept, p, "18:40:12.126", "INFO", "eBPF", "开始写入本地帧时间采样，不在界面显示实时 FPS", p.secondary)
                    DividerLine(p)
                    LogRow(concept, p, "19:22:30.447", "DONE", "FPS", "会话结束：42:18 · 平均 58.6 FPS · 已保存", p.success)
                    DividerLine(p)
                    LogRow(concept, p, "19:22:31.004", "WARN", "Rule", "RenderThread 存在 2 个相似名称，建议检查通配符", p.warning)
                    DividerLine(p)
                    LogRow(concept, p, "19:22:31.026", "INFO", "Daemon", "规则已应用到 CPU 4–7", p.primary)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(concept, p, Icons.Outlined.ContentCopy, "复制所选", outlined = true, modifier = Modifier.weight(1f))
                    CompactButton(concept, p, Icons.Outlined.Download, "导出日志", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LogRow(concept: Int, p: UiPalette, time: String, level: String, source: String, message: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Column(Modifier.width(76.dp)) {
            Text(time, color = p.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            StatusPill(p, level, color)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(source, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(message, color = p.text, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun SettingsRulesScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "设置", "自动校准规则生成", "设置") {
        PageList {
            item { Tabs(concept, p, listOf("规则生成", "性能档位"), 0) }
            item {
                Section(concept, p, "核心拓扑", kicker = if (concept == 3) "TOPOLOGY 01" else null, trailing = "已自动识别") {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MetricTile(concept, p, "效率核", "CPU 0–3", p.secondary, Modifier.weight(1f))
                        MetricTile(concept, p, "性能核", "CPU 4–7", p.primary, Modifier.weight(1f))
                    }
                }
            }
            item {
                Section(concept, p, "规则写入", kicker = if (concept == 3) "FORMAT 02" else null) {
                    SettingRow(p, Icons.Outlined.DataObject, "校准规则生成格式", "原作者格式", p.primary)
                    SettingRow(p, Icons.Outlined.Rule, "最大线程规则数量", "12 条", p.primary)
                    SettingRow(p, Icons.Outlined.Tune, "相似线程聚合", "平均取最高", p.secondary)
                    SettingRow(p, Icons.Outlined.FilePresent, "cpuset 根目录", "/dev/cpuset/QixiaThreads", p.warning)
                }
            }
            item {
                Section(concept, p, "更改状态", trailing = "已保存") {
                    Text("修改后会安全重启 Rust 守护；正在校准时延迟应用。", color = p.muted, fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactButton(concept, p, Icons.Outlined.RestartAlt, "恢复默认", outlined = true, modifier = Modifier.weight(1f))
                        CompactButton(concept, p, Icons.Outlined.Save, "保存设置", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPerformanceScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "设置", "线程负载档位", "设置") {
        PageList {
            item { Tabs(concept, p, listOf("规则生成", "性能档位"), 1) }
            item {
                Section(concept, p, "档位总览", kicker = if (concept == 3) "THRESHOLDS 01" else null) {
                    ThresholdBar(p, listOf(p.secondary, p.primary, p.warning, p.danger))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("0%", color = p.muted, fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        Text("13%", color = p.muted, fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        Text("22%", color = p.muted, fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        Text("30%+", color = p.muted, fontSize = 9.sp)
                    }
                }
            }
            item { PerformanceTier(concept, p, "01", "最高负载线程", "18", "30", "CPU 4–7", p.danger) }
            item { PerformanceTier(concept, p, "02", "较重线程", "13", "22", "CPU 4–7", p.warning) }
            item { PerformanceTier(concept, p, "03", "中等线程", "7", "13", "CPU 2–5", p.primary) }
            item {
                Section(concept, p, "兜底核心", kicker = if (concept == 3) "FALLBACK 04" else null, trailing = "CPU 0–3") {
                    Text("未达到阈值的线程与未识别子进程使用效率核。", color = p.muted, fontSize = 10.sp)
                    Spacer(Modifier.height(9.dp))
                    FullButton(concept, p, Icons.Outlined.Save, "保存性能档位")
                }
            }
        }
    }
}

@Composable
private fun PerformanceTier(concept: Int, p: UiPalette, number: String, title: String, avg: String, peak: String, cores: String, color: Color) {
    Section(concept, p, title, kicker = if (concept == 3) "TIER $number" else number, trailing = cores) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelValueMini(concept, p, "平均负载 ≥", "$avg%", Modifier.weight(1f))
            LabelValueMini(concept, p, "峰值负载 ≥", "$peak%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(p.border)) {
            Box(Modifier.fillMaxWidth(avg.toFloat() / 32f).fillMaxHeight().background(color))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (0..7).forEach { cpu ->
                val selected = cores.contains(cpu.toString())
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (selected) color.copy(alpha = .15f) else p.surfaceAlt).padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(cpu.toString(), color = if (selected) color else p.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun FlowStatesScreen(concept: Int, p: UiPalette) {
    ScreenShell(concept, p, "交互状态", "校准、确认、更新与指南", "应用", showBottomNav = false) {
        PageList(contentPadding = PaddingValues(14.dp)) {
            item {
                Section(concept, p, "校准进行中", kicker = if (concept == 3) "FLOW 01" else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFF172536), shape = CircleShape) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(p.danger))
                                Spacer(Modifier.width(7.dp))
                                Text("记录中  00:23", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("仅本地采样", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("不展示实时 FPS；结束后计算平均值", color = p.muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                TwoPanelRow {
                    MiniDialog(concept, p, "自动开始校准", "启动阶段负载可能偏高，建议等待场景稳定后手动开始。", "暂不开启", "继续开启", p.warning)
                    MiniDialog(concept, p, "会话已保存", "42:18 · 平均 FPS 58.6\n共记录 2,538 个采样点。", "查看历史", "生成规则", p.success)
                }
            }
            item {
                TwoPanelRow {
                    MiniDialog(concept, p, "建议使用通配符", "RenderThread 存在 2 个相似线程名称。", "保留当前", "使用通配符", p.primary)
                    MiniDialog(concept, p, "放弃未保存修改？", "本次新增、编辑或删除的内容将丢失。", "继续编辑", "放弃修改", p.danger)
                }
            }
            item {
                Section(concept, p, "使用指南", kicker = if (concept == 3) "GUIDE 05" else null, trailing = "3 / 13") {
                    GuideStep(p, "01", "完成权限与 Root 授权", true)
                    GuideStep(p, "02", "从待校准列表启动目标应用", true)
                    GuideStep(p, "03", "进入稳定场景后点击“开始记录”", false)
                    GuideStep(p, "04", "结束会话，检查平均 FPS 与线程负载", false)
                }
            }
        }
    }
}

@Composable
private fun TwoPanelRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun RowScope.MiniDialog(concept: Int, p: UiPalette, title: String, body: String, secondary: String, primary: String, color: Color) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(radius(concept, true))).background(p.surface).border(1.dp, p.border, RoundedCornerShape(radius(concept, true))).padding(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(if (color == p.danger || color == p.warning) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(body, color = p.muted, fontSize = 9.sp, lineHeight = 13.sp, minLines = 3)
        Spacer(Modifier.height(8.dp))
        Text(secondary, color = p.muted, fontSize = 9.sp)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(color).padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(primary, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GuideStep(p: UiPalette, number: String, title: String, complete: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (complete) p.success else p.primarySoft), contentAlignment = Alignment.Center) {
            Text(if (complete) "✓" else number, color = if (complete) Color.White else p.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = if (complete) p.muted else p.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingRow(p: UiPalette, icon: ImageVector, title: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(value, color = p.muted, fontSize = 10.sp)
        }
        Icon(Icons.Outlined.ExpandMore, null, tint = p.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LabelValueField(concept: Int, p: UiPalette, label: String, value: String, icon: ImageVector) {
    Column {
        Text(label, color = p.muted, fontSize = 9.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(radius(concept))).background(p.surfaceAlt).border(1.dp, p.border, RoundedCornerShape(radius(concept))).padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = p.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LabelValueMini(concept: Int, p: UiPalette, label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(radius(concept))).background(p.surfaceAlt).border(1.dp, p.border, RoundedCornerShape(radius(concept))).padding(9.dp)) {
        Text(label, color = p.muted, fontSize = 8.sp)
        Text(value, color = p.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricTile(concept: Int, p: UiPalette, label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(radius(concept))).background(color.copy(alpha = .09f)).padding(10.dp)) {
        Text(label, color = p.muted, fontSize = 8.sp, maxLines = 1)
        Text(value, color = color, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ThresholdBar(p: UiPalette, colors: List<Color>) {
    Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
        colors.forEachIndexed { index, color ->
            Box(Modifier.weight(if (index == 0) 1.3f else 1f).fillMaxHeight().background(color.copy(alpha = if (index == 2) .8f else 1f)))
        }
    }
}

@Composable
private fun CompactButton(
    concept: Int,
    p: UiPalette,
    icon: ImageVector,
    label: String,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(radius(concept))
    Row(
        modifier = modifier.clip(shape).background(if (outlined) p.surface else p.primary).border(1.dp, if (outlined) p.border else p.primary, shape).clickable { }.padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (outlined) p.primary else Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (outlined) p.text else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FullButton(concept: Int, p: UiPalette, icon: ImageVector, label: String) {
    CompactButton(concept, p, icon, label, modifier = Modifier.fillMaxWidth())
}
