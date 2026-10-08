package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartCalibrationDelayTest {
    @Test
    fun millisecondsAreShownAsReadableSeconds() {
        assertEquals("1.5 秒", AutoStartCalibrationDelay.label(1_500L))
        assertEquals(
            "检测到目标应用后，将在 1.5 秒后自动开始校准",
            AutoStartCalibrationDelay.summary(1_500L)
        )
    }

    @Test
    fun delayInputIsBounded() {
        assertEquals(800L, AutoStartCalibrationDelay.parse("800"))
        assertNull(AutoStartCalibrationDelay.parse(""))
        assertNull(AutoStartCalibrationDelay.parse("60001"))
    }
}
