package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBlockParserTest {
    @Test
    fun braceRuleIsKeptAsOneEvent() {
        val blocks = LogBlockParser.split(
            """
            [CALIB] 已生成规则
            app(com.example, 0-6) {
                process(worker, 0-3) {
                    thread(Thread_*, 4-7)
                }
            }
            [RS] 运行摘要: 成功
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks[1].text.contains("thread(Thread_*, 4-7)"))
        assertTrue(blocks[1].text.endsWith("}"))
    }

    @Test
    fun yamlMembersAndStackTraceRemainGrouped() {
        val blocks = LogBlockParser.split(
            """
            com.example:
                fallback: 0-6
                threads:
                    RenderThread: 7
            [RS] 失败
                at first
                at second
            """.trimIndent()
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.contains("RenderThread: 7"))
        assertTrue(blocks[1].text.contains("at second"))
    }
}
