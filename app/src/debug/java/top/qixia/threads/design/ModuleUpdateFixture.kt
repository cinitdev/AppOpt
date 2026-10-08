package top.qixia.threads.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.qixia.threads.ModuleUpdateDownloadViewModel.State
import top.qixia.threads.ModuleUpdateDownloadViewModel.Stage
import top.qixia.threads.ModuleUpdater
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.ModuleUpdateSheet

/** 仅用于界面验证，所有操作留在内存中，不下载或安装模块。 */
@Composable
internal fun ModuleUpdateFixture(screen: String) {
    var visible by remember { mutableStateOf(true) }
    val update = remember(screen) { ModuleUpdater.UpdateInfo(
        localVersion = "v1.8.6", localVersionCode = 186,
        remoteVersion = "v1.8.7", remoteVersionCode = 187,
        zipUrl = "https://example.invalid/preview.zip", changelogUrl = null,
        changelogText = if (screen.endsWith("empty")) "" else UPDATE_SAMPLE,
        changelogLoadFailed = screen.endsWith("unavailable")
    ) }
    var state by remember(screen) { mutableStateOf(when {
        screen.endsWith("download") -> State(update, Stage.DOWNLOADING, "正在下载模块", percent = 42)
        screen.endsWith("failed") -> State(update, Stage.FAILED, "网络连接中断，可重新下载")
        else -> State()
    }) }
    QixiaThreadsTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).statusBarsPadding()) {
                Text("更新弹窗 · 样式预览")
                Text("示例版本与内容，不会下载或刷入")
                Button({ visible = true }) { Text("打开弹窗") }
            }
            if (visible) ModuleUpdateSheet(update, state, { visible = false },
                onBegin = { state = State(update, Stage.DOWNLOADING, "正在下载模块 · 样式预览", percent = 42) },
                onRetry = { state = State(update, Stage.DOWNLOADING, "正在重试 · 样式预览", percent = 42) },
                onCancel = { state = State() }, onInstall = {})
        }
    }
}

private val UPDATE_SAMPLE = """
    ## 更清晰的更新体验

    这是用于检查排版的 **Markdown 示例**，并非正式版本公告。

    ### 优化与修复
    - **更新详情**：版本、说明和操作入口清晰分区。
    - **阅读体验**：长文档完整展示，支持滚动与链接。
      - 标题、列表层级与段落间距。
      - 下载期间仍能继续阅读说明。
    - 保留 `calib_policy.conf` 等行内代码样式。

    > 提示：模块刷入后，需要重启设备才会生效。

    ### 支持情况
    | 内容 | 展示效果 |
    | --- | --- |
    | 标题与列表 | 保留层级 |
    | 引用与代码 | 独立样式 |
    | 表格与链接 | 正常渲染 |

    ### 阅读检查
    - [x] 恢复 Markdown 格式
    - [x] ~~纯文本显示~~ 改为文档排版
    - [ ] 完成后确认刷入

    ```ini
    # 示例配置，不会写入设备
    rule_output_format=function
    ```

    **加粗**、*斜体* 和 [文档链接](https://example.com) 都应保留效果。

    ---

    ### 文档末尾
    滚动到这里时，底部操作按钮仍然可见。
""".trimIndent()
