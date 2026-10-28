package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.UpdateInstallViewModel
import top.qixia.threads.compose.theme.OceanBackground
import top.qixia.threads.compose.theme.OceanError
import top.qixia.threads.compose.theme.OceanPrimary
import top.qixia.threads.compose.theme.OceanSuccess
import top.qixia.threads.compose.theme.OceanWarning
import top.qixia.threads.compose.theme.OceanSurfaceHigh
import top.qixia.threads.compose.theme.OceanText
import top.qixia.threads.compose.theme.OceanTextSecondary

@Composable
fun UpdateInstallScreen(
    state: UpdateInstallViewModel.State,
    subtitle: String,
    rebooting: Boolean,
    rebootStatus: Pair<String, String>?,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    onUpdateApp: () -> Unit = {}
) {
    var confirmAppUpdate by rememberSaveable { mutableStateOf(false) }
    val logScroll = rememberScrollState()
    LaunchedEffect(state.log, logScroll.maxValue) {
        logScroll.scrollTo(logScroll.maxValue)
    }
    val title = rebootStatus?.first ?: state.statusTitle
    val detail = rebootStatus?.second ?: state.statusDetail
    val accent = when {
        state.success == false -> OceanError
        state.rebootRequired -> OceanWarning
        state.success == true -> OceanSuccess
        else -> OceanPrimary
    }
    val formattedLog = remember(state.log) { formatInstallLog(state.log) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OceanBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = OceanText)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("模块刷入", color = OceanText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = OceanTextSecondary, fontSize = 12.sp)
            }
        }

        GroupedSurface {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when {
                        state.success == false -> Icons.Outlined.ErrorOutline
                        state.rebootRequired -> Icons.Outlined.RestartAlt
                        state.success == true -> Icons.Outlined.CheckCircle
                        else -> Icons.Outlined.SystemUpdateAlt
                    }
                    Icon(icon, null, tint = accent, modifier = Modifier.size(29.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(detail, color = OceanText, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
                if (state.running || rebooting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = accent)
                }
            }
        }

        if (state.running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = OceanPrimary,
                trackColor = OceanSurfaceHigh
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("刷入日志", color = OceanText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text(
                formattedLog,
                color = OceanText,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White, RoundedCornerShape(15.dp))
                    .border(1.dp, Color(0xFFE0E7F3), RoundedCornerShape(15.dp))
                    .verticalScroll(logScroll)
                    .padding(14.dp)
            )
        }

        if (!state.running && state.appUpdatePending) {
            Spacer(Modifier.height(14.dp))
            PendingAppUpdateCard(enabled = !rebooting, onUpdate = { confirmAppUpdate = true })
        }
        if (!state.running) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onPrimaryAction,
                enabled = !rebooting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (state.rebootRequired) "重启系统" else if (state.success == true) "完成" else "返回")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmAppUpdate) AlertDialog(
        onDismissRequest = { confirmAppUpdate = false },
        title = { Text("更新 App？") },
        text = { Text("确认后将先返回桌面，再安装新版 App。看到安装完成提示后，即可重新打开。模块已经生效，规则和历史记录会保留。") },
        confirmButton = { TextButton({ confirmAppUpdate = false; onUpdateApp() }) { Text("返回桌面并更新") } },
        dismissButton = { TextButton({ confirmAppUpdate = false }) { Text("暂不更新") } }
    )
}

@Composable
internal fun PendingAppUpdateCard(enabled: Boolean = true, onUpdate: () -> Unit) {
    GroupedSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("App 待更新", color = OceanText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("安装前会先返回桌面。也可稍后在设置中继续更新。", color = OceanTextSecondary,
                fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onUpdate, enabled = enabled, modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, OceanPrimary.copy(alpha = .35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OceanPrimary)) {
                Text("更新 App", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 只增强日志显示，不删改原文；颜色与加粗同时区分关键结果。 */
private fun formatInstallLog(log: String) = buildAnnotatedString {
    log.trimEnd().ifBlank { "正在等待安装输出…" }.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val text = line.trim().removePrefix("-").trimStart()
        val color = when {
            text.contains("失败") || text.startsWith("error=", true) ||
                text.startsWith("Failed", true) || text.startsWith("Failure", true) -> OceanError
            text.startsWith("!") || listOf("需要重启", "请重启", "重启后", "等待重启", "尚未更新", "无法", "未能")
                .any(text::contains) -> OceanWarning
            text.startsWith("【") || listOf("热更新成功", "无需重启", "已生效", "通过检查", "安装完成", "安装成功")
                .any(text::contains) -> Color(0xFF2459BF)
            else -> null
        }
        if (color == null) append(line)
        else withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(line) }
    }
}
