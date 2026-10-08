package top.qixia.threads

import android.content.Context
import android.graphics.Color
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.math.roundToInt

/** 两个更新入口均保留原有 Markdown 扩展能力。 */
internal object ModuleUpdateMarkdown {
    fun create(context: Context): Markwon {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        return Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(TableTheme.Builder()
                .tableCellPadding(dp(9))
                .tableBorderColor(Color.rgb(220, 229, 243))
                .tableBorderWidth(dp(1))
                .tableHeaderRowBackgroundColor(Color.rgb(228, 237, 255))
                .tableOddRowBackgroundColor(Color.WHITE)
                .tableEvenRowBackgroundColor(Color.rgb(245, 248, 255))
                .build()))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(Color.rgb(47, 99, 210))
                        .isLinkUnderlined(true)
                        .headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f))
                        .headingBreakHeight(0)
                        .blockMargin(dp(18))
                        .blockQuoteWidth(dp(3))
                        .blockQuoteColor(Color.rgb(149, 180, 240))
                        .listItemColor(Color.rgb(47, 99, 210))
                        .bulletWidth(dp(4))
                        .codeTextColor(Color.rgb(47, 95, 189))
                        .codeBackgroundColor(Color.rgb(240, 245, 255))
                        .codeBlockTextColor(Color.rgb(24, 34, 53))
                        .codeBlockBackgroundColor(Color.rgb(240, 245, 255))
                        .codeBlockMargin(dp(12))
                        .thematicBreakColor(Color.rgb(220, 229, 243))
                        .thematicBreakHeight(dp(1))
                }
            })
            .build()
    }
}
