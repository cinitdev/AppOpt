package top.qixia.threads.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import top.qixia.threads.RuleHistoryCandidate
import top.qixia.threads.RuleHistoryKind
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.RuleEditorUiState
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.theme.OceanBackground
import top.qixia.threads.compose.ui.RuleEditorScreen

/** 内存中的界面测试数据，编辑与保存均不写入 applist.conf。 */
@Composable
internal fun RuleEditorFixture() {
    val manager = LocalContext.current.packageManager
    val pkg = "com.tencent.tmgp.pubgmhd"
    val lines = remember { listOf(
        "$pkg{Thread-*}=7", "$pkg{RenderThread*}=5-6", "$pkg{RHIThread}=5-6",
        "$pkg{TaskGraphNP*}=4-6", "$pkg=0-6", "$pkg:service=0-3",
        "$pkg:service{Binder:*}=0-3", "$pkg:service{Worker-1}=4-5"
    ) }
    val icon = remember { runCatching { manager.getApplicationIcon(pkg) }.getOrNull() }
    var state by remember { mutableStateOf(RuleEditorUiState(
        AppItemModel(pkg, "和平精英", true, icon), lines, lines.joinToString("\n"), (0..7).toSet(),
        historyCandidates = listOf("worker-1", "worker-2", "AudioTrack").mapIndexed { i, name ->
            RuleHistoryCandidate(RuleHistoryKind.THREAD, pkg, name, 18f - i * 4, 60f, 1790176864)
        } + RuleHistoryCandidate(RuleHistoryKind.CHILD_PROCESS, "$pkg:push", null, 4f, 15f, 1790176864)
    )) }
    QixiaThreadsTheme {
        Surface(Modifier.fillMaxSize(), color = OceanBackground) {
            RuleEditorScreen(state, { state = state.copy(draft = it) }, {},
                { state = state.copy(originalLines = state.draft.lines()) }, {})
        }
    }
}
