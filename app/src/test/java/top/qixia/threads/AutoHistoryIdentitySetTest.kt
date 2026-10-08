package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AutoHistoryIdentitySetTest {
    @Test fun growthRetainsFullIdentityAndActiveStateWithoutDoubleCounting() {
        val identities = AutoHistoryIdentitySet(30000)
        repeat(10000) { index -> assertTrue(identities.add(1, index + 1, 100, false)) }
        repeat(10000) { index -> assertFalse(identities.add(1, index + 1, 100, true)) }
        repeat(10000) { index -> assertFalse(identities.add(1, index + 1, 100, true)) }
        assertEquals(10000, identities.size)
        assertEquals(10000, identities.activeSize)
        assertTrue(identities.add(2, 1, 100, true))
        assertTrue(identities.add(1, 1, 101, true))
        assertEquals(10002, identities.activeSize)
    }

    @Test fun boundRejectsOnlyNewIdentityAndStillAcceptsExistingUpdates() {
        val identities = AutoHistoryIdentitySet(2)
        identities.add(1, 1, 1, false); identities.add(1, 2, 1, false)
        assertTrue(runCatching { identities.add(1, 3, 1, true) }.isFailure)
        assertFalse(identities.add(1, 1, 1, true))
        assertEquals(1, identities.activeSize)
    }
}
