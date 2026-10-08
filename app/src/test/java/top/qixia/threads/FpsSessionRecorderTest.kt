package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FpsSessionRecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val target = "com.example.game"
    private var nowMs = 1_000L
    private lateinit var recorder: FpsSessionRecorder

    @Before
    fun setUp() {
        recorder = FpsSessionRecorder(temporaryFolder.root) { nowMs }
    }

    @Test
    fun ignoresLaunchSamplesUntilTargetIsConfirmedRunning() {
        recorder.begin(target)

        assertFalse(recorder.record(target, 60f, sampledAtMs = 1_500L))
        assertNull(recorder.finish(target, endedAtMs = 2_000L))
        assertNull(FpsSessionRecorder.readLastAverage(temporaryFolder.root, target))
    }

    @Test
    fun persistsAverageAndRawSamplesForWholeCompletedSession() {
        recorder.begin(target)
        assertTrue(recorder.markRunning(target))
        assertTrue(recorder.record(target, 30f, sampledAtMs = 2_000L))
        assertTrue(recorder.record(target, 60f, sampledAtMs = 3_000L))
        assertTrue(recorder.record(target, 90f, sampledAtMs = 4_000L))

        val completed = recorder.finish(target, endedAtMs = 61_000L)
        val restored = FpsSessionRecorder.readLastAverage(temporaryFolder.root, target)

        assertEquals(60f, completed?.averageFps ?: -1f, 0.001f)
        assertEquals(3L, restored?.sampleCount)
        assertEquals(60_000L, restored?.durationMs)
        assertEquals(60f, restored?.averageFps ?: -1f, 0.001f)
        val sampleFiles = temporaryFolder.root.resolve("fps_sessions")
            .listFiles { file -> file.name.endsWith(".last.csv") }
            .orEmpty()
        assertEquals(1, sampleFiles.size)
        assertTrue(sampleFiles.single().readText().contains("3000,60.000"))
    }

    @Test
    fun keepsPreviousAverageVisibleUntilNextSessionFinishes() {
        recorder.begin(target)
        recorder.markRunning(target)
        recorder.record(target, 50f, sampledAtMs = 2_000L)
        recorder.finish(target, endedAtMs = 3_000L)

        recorder.begin(target)
        recorder.markRunning(target)
        recorder.record(target, 100f, sampledAtMs = 4_000L)

        assertEquals(
            50f,
            FpsSessionRecorder.readLastAverage(temporaryFolder.root, target)?.averageFps ?: -1f,
            0.001f
        )

        recorder.finish(target, endedAtMs = 5_000L)
        assertEquals(
            100f,
            FpsSessionRecorder.readLastAverage(temporaryFolder.root, target)?.averageFps ?: -1f,
            0.001f
        )
    }

    @Test
    fun recoversPersistedSamplesAfterProcessInterruption() {
        recorder.begin(target)
        recorder.markRunning(target)
        recorder.record(target, 40f, sampledAtMs = 2_000L)
        recorder.record(target, 80f, sampledAtMs = 3_000L)

        val restartedRecorder = FpsSessionRecorder(temporaryFolder.root) { 10_000L }
        restartedRecorder.begin(target)

        val restored = FpsSessionRecorder.readLastAverage(temporaryFolder.root, target)
        assertEquals(60f, restored?.averageFps ?: -1f, 0.001f)
        assertEquals(2L, restored?.sampleCount)
    }
}
