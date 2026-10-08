package top.qixia.threads.compose.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import top.qixia.threads.ModuleUpdateMarkdown
import top.qixia.threads.compose.theme.OceanText
import top.qixia.threads.compose.theme.PorcelainAction

@Composable
internal fun UpdateChangelog(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val renderer = remember(context, density) { ModuleUpdateMarkdown.create(context) }
    // 下载进度会触发弹窗重组，只在文档变化时重新解析。
    val document = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    AndroidView(
        modifier = modifier.semantics(mergeDescendants = true) { text = AnnotatedString(document.toString()) },
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextColor(OceanText.toArgb())
                setLinkTextColor(PorcelainAction.toArgb())
                textSize = 14f
                includeFontPadding = false
                setLineSpacing(3 * resources.displayMetrics.density, 1.15f)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setPadding(0, 0, 0, 0)
            }
        },
        update = { view ->
            if (view.tag !== document) {
                renderer.setParsedMarkdown(view, document)
                view.tag = document
            }
        }
    )
}
