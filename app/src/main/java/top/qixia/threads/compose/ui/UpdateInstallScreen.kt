package top.qixia.threads.compose.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.UpdateInstallViewModel
import top.qixia.threads.compose.theme.OceanBackground
import top.qixia.threads.compose.theme.OceanError
import top.qixia.threads.compose.theme.OceanPrimary
import top.qixia.threads.compose.theme.OceanSuccess
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
    onPrimaryAction: () -> Unit
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(state.log) {
        logScroll.scrollTo(logScroll.maxValue)
    }
    val title = rebootStatus?.first ?: state.statusTitle
    val detail = rebootStatus?.second ?: state.statusDetail
    val accent = when (state.success) {
        true -> OceanSuccess
        false -> OceanError
        null -> OceanPrimary
    }

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
                Text(subtitle, color = OceanTextSecondary, fontSize = 11.sp)
            }
        }

        GroupedSurface {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (state.success) {
                        true -> Icons.Outlined.CheckCircle
                        false -> Icons.Outlined.ErrorOutline
                        null -> Icons.Outlined.SystemUpdateAlt
                    }
                    Icon(icon, null, tint = accent, modifier = Modifier.size(29.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = OceanText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(detail, color = OceanTextSecondary, fontSize = 12.sp)
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
                state.log.trimEnd().ifBlank { "正在等待安装输出…" },
                color = OceanTextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .background(OceanSurfaceHigh, RoundedCornerShape(15.dp))
                    .verticalScroll(logScroll)
                    .padding(14.dp)
            )
        }

        if (!state.running) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onPrimaryAction,
                enabled = !rebooting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (state.rebootRequired) "重启系统" else "返回")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
