package top.qixia.threads.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogStoreTest {
    @Test fun restoredSnapshotWithoutElapsedTimestampIsStale() {
        assertFalse(isAppCatalogCacheFresh(120_000L, 0L, 300_000L))
    }

    @Test fun validCacheExpiresAtTheFreshnessBoundary() {
        assertTrue(isAppCatalogCacheFresh(124_999L, 100_000L, 25_000L))
        assertFalse(isAppCatalogCacheFresh(125_000L, 100_000L, 25_000L))
    }

    @Test fun cacheTimestampFromLaterBootIsStale() {
        assertFalse(isAppCatalogCacheFresh(1_000L, 20_000L, 300_000L))
    }
}
