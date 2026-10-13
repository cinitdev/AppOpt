package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class FpsSampleTest {
    @Test fun missingSamplesAreDistinctFromRealZeroFrames() {
        val missing = parseFpsChannelSample("gap\n")!!
        assertTrue(missing.isNaN())
        assertNull(fpsSampleForHistory(missing, 1000, 1000))
        assertEquals(0f, fpsSampleForHistory(parseFpsChannelSample("0.0")!!, 1000, 1000))
        assertNull(fpsSampleForHistory(60f, 1000, 4501))
        assertNull(fpsSampleForHistory(60f, 0, 1000))
        assertNull(fpsSampleForHistory(60f, 1001, 1000))
    }

    @Test fun recoveryAndHighRefreshSamplesRemainAvailable() {
        for (fps in listOf(60f, 120f, 144f, 165f, 240f, 360f)) {
            val sample = parseFpsChannelSample(fps.toString())!!
            assertEquals(fps, fpsSampleForHistory(sample, 5000, 6000))
        }
        for (invalid in listOf("NaN", "Infinity", "-1", "frame 10", "")) {
            assertNull(parseFpsChannelSample(invalid))
        }
    }
}
