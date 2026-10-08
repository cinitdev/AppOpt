package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.*

class CoreTimelinePresentationTest {
    @Test fun cpuRangesDoNotInventUnobservedCores() {
        assertEquals("CPU 0–3, 5, 7", CoreTimelinePresentation.cpus(listOf(7, 0, 2, 3, 5, 1, 3)))
        assertEquals("范围未记录", CoreTimelinePresentation.cpus(null))
        assertEquals("CPU 12", CoreTimelinePresentation.cpus(listOf(12)))
    }

    @Test fun namesAndReusedTidsDoNotMergeIndependentTimelines() {
        val id = ThreadIdentity(1, 2, 3)
        val sample = CoreEvent(1000, id, "RenderThread", CoreEventKind.OBSERVE, CoreEventSource.QIXIA,
            null, listOf(5), 5, 12f, "initial")
        val report = CoreTimelineReport(-1, 1000, 2000, listOf(sample,
            sample.copy(timestampMs = 1500, kind = CoreEventKind.RELEASE, source = CoreEventSource.SYSTEM, reason = "low_average"),
            sample.copy(identity = id.copy(startTicks = 4))), coreEventsVersion = 1)
        val threads = CoreTimelinePresentation.threads(report)
        assertEquals(2, threads.size)
        assertEquals(1, threads[0].operations)
        assertEquals(1, threads[0].observations)
        assertFalse(CoreTimelinePresentation.isOperation(sample))
        assertTrue(CoreTimelinePresentation.reason(threads[0].events.last()).contains("5%"))
        assertEquals("采样观察", CoreTimelinePresentation.title(sample))
    }

    @Test fun delayedEventsKeepTheirTimeBeforeSegmentStart() {
        assertEquals("−0:01", CoreTimelinePresentation.relativeTime(999, 1000))
        assertEquals("+1:05", CoreTimelinePresentation.relativeTime(66000, 1000))
    }

    @Test fun samplingDoesNotClaimTheSystemOwnsAnQixiaThreadsRange() {
        val managed = reasonEvent("cpu_changed").copy(kind = CoreEventKind.OBSERVE)
        val system = managed.copy(source = CoreEventSource.SYSTEM)
        val unknown = managed.copy(source = CoreEventSource.UNKNOWN)
        listOf(managed, system, unknown).forEach { event ->
            assertEquals("采样观察", CoreTimelinePresentation.title(event))
            assertFalse(CoreTimelinePresentation.isOperation(event))
        }
        assertEquals("自动分配", CoreTimelinePresentation.source(managed.source))
        assertEquals("系统调度", CoreTimelinePresentation.source(system.source))
        assertEquals("归属未知", CoreTimelinePresentation.source(unknown.source))
    }

    @Test fun unchangedRangeRequiresKnownEqualMasksButNotEqualRunningCpus() {
        val sample = reasonEvent("cpu_changed").copy(kind = CoreEventKind.OBSERVE,
            beforeCpus = listOf(1, 0, 1), afterCpus = listOf(0, 1), runningCpu = 1)
        assertTrue(CoreTimelinePresentation.rangeUnchanged(sample))
        assertTrue(CoreTimelinePresentation.rangeUnchanged(sample.copy(runningCpu = 0)))
        assertFalse(CoreTimelinePresentation.rangeUnchanged(sample.copy(afterCpus = listOf(1))))
        assertFalse(CoreTimelinePresentation.rangeUnchanged(sample.copy(beforeCpus = null, afterCpus = null)))
        assertFalse(CoreTimelinePresentation.rangeUnchanged(sample.copy(beforeCpus = emptyList(), afterCpus = emptyList())))
    }

    @Test fun takeoverAndReassertionDoNotRewriteOldExternalOverrideHistory() {
        val initial = reasonEvent("cpuset_takeover")
        val reasserted = reasonEvent("control_reasserted")
        val oldOverride = reasonEvent("external_override").copy(
            kind = CoreEventKind.EXTERNAL, source = CoreEventSource.SYSTEM)

        assertTrue(CoreTimelinePresentation.reason(initial).contains("完成接管"))
        assertTrue(CoreTimelinePresentation.reason(reasserted).contains("已重新接管"))
        assertEquals("允许范围被系统或其他调度策略改变，QixiaThreads 让出控制。",
            CoreTimelinePresentation.reason(oldOverride))
    }

    @Test fun unverifiedCpusetOperationsDescribeTheirDistinctFailure() {
        val descriptions = listOf(
            "cpuset_unavailable" to "本次未完成接管",
            "cpuset_failed" to "尚未确认接管",
            "cpuset_readback_failed" to "不能把本次操作当作接管成功",
            "retry_pending" to "不代表已完成接管",
            "recovery_baseline_missing" to "缺少原分组的可靠恢复信息",
            "capacity_pending" to "暂缓接管新线程",
            "restore_failed" to "不能把本次操作当作已交回系统"
        )
        descriptions.forEach { (reason, explanation) ->
            val event = reasonEvent(reason).copy(kind = CoreEventKind.ERROR, source = CoreEventSource.UNKNOWN)
            assertTrue("Missing explanation for $reason", CoreTimelinePresentation.reason(event).contains(explanation))
        }
    }

    @Test fun legacyEventsNeverClaimCpusetOwnershipWasRecorded() {
        val event = reasonEvent("cpuset_takeover").copy(legacy = true)
        assertEquals("旧版记录仅保留操作时间和核心范围，未保存负载与原因。",
            CoreTimelinePresentation.reason(event))
    }

    @Test fun migrationKeepsAllowedRangeMeaningAndIdentityFailureIsNotAnExit() {
        val migration = reasonEvent("migration")
        assertEquals(CoreTimelinePresentation.reason(reasonEvent("affinity_changed")),
            CoreTimelinePresentation.reason(migration))
        assertTrue(CoreTimelinePresentation.reason(migration).contains("允许使用的核心"))

        val identityFailure = reasonEvent("identity_unavailable").copy(
            kind = CoreEventKind.ERROR, source = CoreEventSource.UNKNOWN)
        assertTrue(CoreTimelinePresentation.reason(identityFailure).contains("等待重试"))
        assertTrue(CoreTimelinePresentation.reason(identityFailure).contains("不代表线程已经结束"))
        assertNotEquals(CoreTimelinePresentation.reason(reasonEvent("thread_exit")),
            CoreTimelinePresentation.reason(identityFailure))
    }

    @Test fun inheritedReleaseAndExternalMovementKeepDistinctOwnershipOutcomes() {
        val release = reasonEvent("inherited_release").copy(
            kind = CoreEventKind.RELEASE, source = CoreEventSource.SYSTEM)
        val external = release.copy(kind = CoreEventKind.EXTERNAL, reason = "inherited_external")
        val missingBaseline = release.copy(kind = CoreEventKind.ERROR,
            source = CoreEventSource.UNKNOWN, reason = "inherited_baseline_missing")

        assertTrue(CoreTimelinePresentation.reason(release).contains("已按继承来源的恢复记录解除限制"))
        assertTrue(CoreTimelinePresentation.reason(external).contains("停止后续恢复"))
        assertTrue(CoreTimelinePresentation.reason(external).contains("不再覆盖当前核心设置"))
        assertFalse(CoreTimelinePresentation.reason(external).contains("交回系统"))
        assertTrue(CoreTimelinePresentation.reason(missingBaseline).contains("缺少继承来源的可靠恢复记录"))
        assertTrue(CoreTimelinePresentation.reason(missingBaseline).contains("暂不改写"))
    }

    private fun reasonEvent(reason: String) = CoreEvent(1000, ThreadIdentity(1, 2, 3),
        "RenderThread", CoreEventKind.ASSIGN, CoreEventSource.QIXIA,
        listOf(0, 1), listOf(1), null, 12f, reason)
}
