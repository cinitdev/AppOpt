package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutoHistoryIdentitySetTest {
    @Test fun growthRetainsFullIdentityAndActiveStateWithoutDoubleCounting() {
        val identities = AutoHistoryIdentitySet(30000)
        repeat(10000) { index -> assertTrue(identities.add(1, index + 1, 100, false, 0)) }
        repeat(10000) { index -> assertFalse(identities.add(1, index + 1, 100, true, 0)) }
        repeat(10000) { index -> assertFalse(identities.add(1, index + 1, 100, true, 0)) }
        assertEquals(10000, identities.size)
        assertEquals(10000, identities.activeSize)
        assertTrue(identities.add(2, 1, 100, true, 0))
        assertTrue(identities.add(1, 1, 101, true, 0))
        assertEquals(10002, identities.activeSize)
    }

    @Test fun boundRejectsOnlyNewIdentityAndStillAcceptsExistingUpdates() {
        val identities = AutoHistoryIdentitySet(2)
        identities.add(1, 1, 1, false, 0); identities.add(1, 2, 1, false, 0)
        assertTrue(runCatching { identities.add(1, 3, 1, true, 0) }.isFailure)
        assertFalse(identities.add(1, 1, 1, true, 0))
        assertEquals(1, identities.activeSize)
    }

    @Test fun sustainedCountsOnlyLongLivedRepeatedlyActiveThreads() {
        val identities = AutoHistoryIdentitySet(64)
        // 持续线程：1200 秒记录里每 10 秒被采样一次，且一直有负载。
        for (second in 0..1200 step 10) identities.add(1, 1, 1, true, second.toLong())
        // 只在 600~605 秒活跃的线程：6 个采样点，但存在期只有 5 秒。
        for (second in 600..605) identities.add(1, 2, 1, true, second.toLong())
        // 长期存在但几乎不动：1200 秒里只有一次有负载。
        for (second in 0..1200 step 100) identities.add(1, 3, 1, second == 300, second.toLong())
        assertEquals(1, identities.sustained())
    }

    @Test fun shortRecordingsDoNotRelaxTheSustainedSpanRequirement() {
        val identities = AutoHistoryIdentitySet(8)
        // 20 秒的短记录不满足固定 30 秒门槛。
        for (second in 0..12) identities.add(1, 1, 1, true, second.toLong())
        assertEquals(0, identities.sustained())
    }

    @Test fun countersContinuePastShortRange() {
        val identities = AutoHistoryIdentitySet(1)
        val count = Short.MAX_VALUE + 1
        repeat(count) { identities.add(1, 1, 1, true, 0L) }
        assertEquals(1, identities.sustained(
            policy = SustainedThreadPolicy(minSamples = count, minPercent = 100, minSpanMs = 0L)
        ))
    }

    @Test fun activitySpanRemainsAccurateAfterNineHours() {
        val identities = AutoHistoryIdentitySet(1)
        for (second in 32_760L..32_795L) identities.add(1, 1, 1, true, second)
        assertEquals(1, identities.sustained())
    }
}
