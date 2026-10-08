package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.UsageGuide
import top.qixia.threads.compose.theme.*

internal val LocalGuideAnchors = staticCompositionLocalOf<MutableMap<String, Rect>?> { null }

@Composable
internal fun Modifier.guideAnchor(key: String): Modifier {
    val anchors = LocalGuideAnchors.current ?: return this
    DisposableEffect(anchors, key) { onDispose { anchors.remove(key) } }
    return onGloballyPositioned { anchors[key] = it.boundsInRoot() }
}

private data class GuidePage(val ids: Set<String>, val anchor: String, val title: String, val text: String)
private val pages = listOf(
    GuidePage(setOf("core_workflow_v1", "add_app_v1", "calibration_review_v2", "rule_management_v1"), "APPLICATIONS",
        "应用管理现在有独立入口", "底栏“应用”中，待校准与可添加应用共用一个列表。点击添加后即可启动，点击已添加的应用可开启自动分配。悬浮球默认显示 FPS；点击开始、再次点击结束采集。返回 App 调整核心，点击保存才生效。“已配置”中可管理规则。"),
    GuidePage(setOf("environment_tools_v1"), "HOME", "从首页检查运行状态",
        "点击首页的权限与服务卡片，检查权限、Root、模块与守护进程。模块更新待重启时，首页会显示醒目的提醒。"),
    GuidePage(setOf("history_logs_diagnostics_v1"), "HISTORY", "采集历史会保留",
        "这里查看每次采集的活跃线程与负载；旁边的日志页用于排查守护进程问题。放弃分配建议不会删除采集历史。"),
    GuidePage(setOf("rule_generation_settings_v1", "cpuset_runtime_v1"), "SETTINGS", "设置与帮助在这里",
        "设置中可选择规则保存格式和 cpuset 根目录。核心建议根据实际处理器与线程负载计算。“帮助与维护”中可导出诊断包。")
)

/** 只高亮真实测量得到的控件，不轮询、不执行后台 IO 或操作。 */
@Composable
internal fun FirstUseGuide(anchors: Map<String, Rect>, onDone: () -> Unit) {
    val context = LocalContext.current
    val pending = remember(context) { UsageGuide.pendingSteps(context) }
    val remaining = remember(pending) { pages.filter { page -> pending.any { it.id in page.ids } } }
    var index by remember { mutableIntStateOf(0) }
    if (index >= remaining.size) { LaunchedEffect(Unit) { onDone() }; return }
    val page = remaining[index]
    val target = anchors[page.anchor]
    fun completePage() {
        UsageGuide.markCompleted(context, pending.filter { it.id in page.ids })
        if (index + 1 == remaining.size) onDone() else index++
    }
    fun skip() { UsageGuide.markCompleted(context, pending); onDone() }
    BackHandler { skip() }
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                target?.let { addRoundRect(androidx.compose.ui.geometry.RoundRect(it.inflate(4.dp.toPx()), CornerRadius(16.dp.toPx()))) }
            }
            drawPath(path, Color(0xB3222B40))
            target?.let { drawRoundRect(Color.White, it.topLeft, it.size, CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx())) }
        }
        Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 460.dp),
            shape = RoundedCornerShape(24.dp), color = OceanSurface) {
            Column(Modifier.padding(22.dp)) {
                Text("使用指引 ${index + 1} / ${remaining.size}", color = OceanPrimary, fontSize = 12.sp)
                Text(page.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                Text(page.text, color = OceanTextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ skip() }) { Text("跳过引导") }
                    Button({ completePage() }) { Text(if (index + 1 == remaining.size) "开始使用" else "下一步") }
                }
            }
        }
    }
}
