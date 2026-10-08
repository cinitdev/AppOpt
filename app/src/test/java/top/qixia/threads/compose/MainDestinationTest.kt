package top.qixia.threads.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class MainDestinationTest {
    @Test
    fun removedEnvironmentPageRestoresToHome() {
        assertEquals(MainDestination.HOME, MainDestination.fromSavedName("ENVIRONMENT"))
    }

    @Test
    fun existingSavedPagesArePreserved() {
        MainDestination.entries.forEach { page ->
            assertEquals(page, MainDestination.fromSavedName(page.name))
        }
    }

    @Test
    fun missingOrUnknownSavedPageRestoresToHome() {
        assertEquals(MainDestination.HOME, MainDestination.fromSavedName(null))
        assertEquals(MainDestination.HOME, MainDestination.fromSavedName("UNKNOWN"))
    }
}
