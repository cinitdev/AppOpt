package top.qixia.threads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleUpdateMarkdownTest {
    @Test
    fun rendersHeadingsEmphasisQuotesCodeAndLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = ModuleUpdateMarkdown.create(context).toMarkdown(
            "## 更新内容\n\n**修复** *斜体* `配置` [说明](https://example.com)\n\n> 重启生效"
        )
        val spans = text.getSpans(0, text.length, Any::class.java).map { it.javaClass.simpleName }.toSet()
        assertTrue(spans.toString(), spans.containsAll(setOf("HeadingSpan", "StrongEmphasisSpan",
            "EmphasisSpan", "CodeSpan", "LinkSpan", "BlockQuoteSpan")))
        assertFalse(text.toString().contains("##"))
        assertFalse(text.toString().contains("**"))
        assertTrue(text.toString().contains("更新内容"))
    }

    @Test
    fun preservesOriginalMarkdownExtensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = ModuleUpdateMarkdown.create(context).toMarkdown(
            "- [x] 已完成\n\n~~旧行为~~\n\n| 项目 | 状态 |\n| --- | --- |\n| 模块 | 正常 |\n\n<b>加粗</b>"
        )
        val spans = text.getSpans(0, text.length, Any::class.java).map { it.javaClass.simpleName }.toSet()
        assertTrue(spans.toString(), spans.containsAll(setOf("TaskListSpan", "StrikethroughSpan", "TableRowSpan")))
        assertFalse(text.toString().contains("<b>"))
        assertFalse(text.toString().contains("~~"))
    }
}
