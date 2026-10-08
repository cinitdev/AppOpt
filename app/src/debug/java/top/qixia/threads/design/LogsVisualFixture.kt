package top.qixia.threads.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.LogsScreen

/** 仅在内存中运行，不向设备日志添加模拟事件。 */
@Composable
internal fun LogsVisualFixture() {
    val now = remember { System.currentTimeMillis() }
    val raw = remember {
        listOf(
            "INFO\tauto\t1\t核心分配已更新: pkg=com.example.game 本轮调整=3\\ntid=381 name=RenderThread avg=24.6% CPU=[4,5]",
            "WARN\tFPS\t12\t后端切换: eBPF → SurfaceFlinger\\npkg=com.example.game 原因=帧源暂不可用 下次重试=30秒",
            "ERROR\tCALIB\t1\t历史保存失败: pkg=com.example.game\\n错误=存储空间不足\\n建议=清理空间后重新保存",
            "INFO\tCALIB\t1\t校准采集完成: pkg=com.example.game 活跃线程=23 时长=443秒\\n规则建议已生成，等待在 App 中确认。",
        ).mapIndexed { index, text -> "@QIXIA/1\t${now - (4 - index) * 60000}\t123:$index\t$text" }.joinToString("\n")
    }
    var state by remember { mutableStateOf(LogsUiState(loaded = true, entries = LogEventParser.parse(LogSource.DAEMON, raw), updatedAtMs = now)) }
    QixiaThreadsTheme {
        Surface(Modifier.statusBarsPadding()) {
            LogsScreen(state, PaddingValues(0.dp), { state = state.copy(updatedAtMs = System.currentTimeMillis()) },
                { state = state.copy(source = it, entries = if (it == LogSource.DAEMON) LogEventParser.parse(it, raw) else emptyList()) },
                { state = state.copy(filter = it) }, {}, { state = state.copy(category = it) }, { state = state.copy(query = it) })
        }
    }
}
