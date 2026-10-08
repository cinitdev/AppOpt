package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceBoundsTest {
    @Test
    fun installLogKeepsNewestTextWithinBound() {
        val buffer = StringBuilder()
        repeat(100) { index ->
            BoundedInstallLog.append(buffer, "line-$index:" + "x".repeat(24) + "\n", 256)
        }

        assertTrue(buffer.length <= 256)
        assertTrue(buffer.startsWith("[较早的安装日志已省略]"))
        assertTrue(buffer.endsWith("line-99:" + "x".repeat(24) + "\n"))
    }

    @Test
    fun logIdDoesNotDependOnTailLineNumber() {
        val first = StableLogEntryId.from(0, "[FPS] 已捕获到帧率: 60.0")
        val afterWindowMoved = StableLogEntryId.from(0, "[FPS] 已捕获到帧率: 60.0")

        assertEquals(first, afterWindowMoved)
        assertNotEquals(first, StableLogEntryId.from(1, "[FPS] 已捕获到帧率: 60.0"))
        assertNotEquals(first, StableLogEntryId.from(0, "[FPS] 已捕获到帧率: 60.0", 1))
    }

    @Test
    fun sparklineUsesFixedZeroToHundredScale() {
        assertEquals(0f, SparklineView.normalizedCpuLoad(-1f), 0f)
        assertEquals(0.02f, SparklineView.normalizedCpuLoad(2f), 0.0001f)
        assertEquals(0.5f, SparklineView.normalizedCpuLoad(50f), 0.0001f)
        assertEquals(1f, SparklineView.normalizedCpuLoad(180f), 0f)
    }
}
