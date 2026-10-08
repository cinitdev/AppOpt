package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ModuleDownloadHandleTest {
    @Test fun interruptedDirectDownloadKeepsItsPartialAcrossNewAttempts() {
        val dir = Files.createTempDirectory("download-resume-test").toFile()
        try {
            val target = File(dir, "first-attempt.zip")
            assertFalse(ModuleUpdater.resumesDirectDownload(target, null))
            val partial = File(dir, "${target.name}.part").apply { writeText("partial body") }
            assertTrue(ModuleUpdater.resumesDirectDownload(target, null))
            assertFalse(ModuleUpdater.resumesDirectDownload(target, 42L))
            assertFalse(ModuleUpdater.resumesDirectDownload(null, null))
            assertEquals("partial body", partial.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun repeatedConcurrentCancellationCleansOnlyOnce() {
        val calls = AtomicInteger()
        val handle = ModuleUpdater.DownloadHandle { calls.incrementAndGet() }
        val start = CountDownLatch(1)
        val workers = List(8) { Thread { start.await(); handle.cancel() }.apply { start() } }
        start.countDown()
        workers.forEach(Thread::join)
        assertTrue(handle.isCancelled)
        assertEquals(1, calls.get())
    }

    @Test fun artifactPublishedAfterCancellationIsRemovedWithoutTouchingRetry() {
        val dir = Files.createTempDirectory("download-attempt-test").toFile()
        try {
            val old = ModuleUpdater.DownloadHandle()
            val retry = ModuleUpdater.DownloadHandle()
            assertNotEquals(old.attempt, retry.attempt)
            val oldFile = File(dir, "${old.attempt}.zip").apply { writeText("old") }
            val newFile = File(dir, "${retry.attempt}.zip").apply { writeText("retry") }
            old.cancel()
            retry.track(newFile)
            old.track(oldFile)
            old.cancel()
            assertFalse(oldFile.exists())
            assertEquals("retry", newFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
