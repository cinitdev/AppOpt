package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class SceneBlacklistSyncTest {
    @Test fun onlyPlainAutomaticPackagesAreSyncedToScene() {
        assertEquals(setOf("com.game", "com.light"), SceneBlacklistSync.collectPackages(
            "com.game:worker=0-3", "com.light\ncom.game\ncom.bad,unknown"))
    }
    @Test fun combinesRulesAndAutomaticAppsWithoutProcessesOrDuplicates() {
        val rules = """
            # com.ignore=0-3
            com.game=4-7
            com.game{RenderThread}=7
            com.game:worker=0-3
            com.pending=auto
            surfaceflinger=0-7
            app(com.block, 0-3) {
                thread(RenderThread, 7)
                process(worker, 4-6)
            }
        """.trimIndent()
        assertEquals(setOf("com.game", "com.pending", "com.block", "com.automatic"),
            SceneBlacklistSync.collectPackages(rules,
                "# selected apps\ncom.automatic\ncom.game\ncom.automatic\ncom.no:worker\ninvalid\n"))
    }

    @Test fun rejectsInvalidOwnersAndShellSyntax() {
        assertEquals(emptySet<String>(), SceneBlacklistSync.collectPackages(
            "# comments only", "bad..name\ncom.game;reboot\ncom.*\ncom.example=1\n"))
        assertThrows(IllegalArgumentException::class.java) {
            SceneBlacklistSync.updateScript(setOf("com.game;reboot"))
        }
    }

    @Test fun foregroundEntryRunsOncePerVisitAcrossActivitiesAndRotation() {
        var calls = 0
        val gate = ForegroundEntryGate { calls++ }
        gate.started()
        assertEquals(1, calls)
        gate.started() // 切换到应用内部 Activity。
        gate.stopped(false)
        assertEquals(1, calls)
        gate.stopped(true) // 旋转时重建当前 Activity。
        gate.started()
        assertEquals(1, calls)
        gate.stopped(false) // 先返回桌面，再重新打开。
        gate.started()
        assertEquals(2, calls)
        gate.stopped(false)
        gate.started()
        assertEquals(3, calls)
    }
}
