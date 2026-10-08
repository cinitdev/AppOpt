package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test

class SettingsTopologyPresentationTest {
    @Test fun twoThreeAndFourPerformanceGroupsStayDynamic() {
        for (groups in listOf(
            listOf("0-5", "6-7"),
            listOf("0-2", "3-6", "7"),
            listOf("0-1", "2-3", "4-5", "6-7")
        )) {
            val result = SettingsTopologyPresentation.parse(block(groups), (0..7).toSet())
            assertTrue(result.pendingReason, result.confirmed)
            assertEquals(groups.size, result.groups.size)
            assertEquals((0..7).toSet(), result.groups.flatMap { it.cpus }.toSet())
        }
    }

    @Test fun tenCoreDeviceDoesNotUseAnEightCoreMaskOrFixedCardCount() {
        val result = SettingsTopologyPresentation.parse(
            block(listOf("0-3", "4-7", "8-9")), (0..9).toSet()
        )
        assertTrue(result.confirmed)
        assertEquals(listOf((0..3).toSet(), (4..7).toSet(), setOf(8, 9)), result.groups.map { it.cpus })
    }

    @Test fun nativePerformanceOrderWinsOverCpuIdAndFileOrder() {
        val result = SettingsTopologyPresentation.parse("""
            detected_complete=1
            detected_clusters=3
            detected_group_2=0
            detected_group_0=4-5
            detected_group_1=1-3
        """.trimIndent(), (0..5).toSet())
        assertTrue(result.confirmed)
        assertEquals(listOf(setOf(4, 5), setOf(1, 2, 3), setOf(0)), result.groups.map { it.cpus })
    }

    @Test fun oneGroupAndSparseCpuIdsAreValidWithoutInferringArchitecture() {
        val result = SettingsTopologyPresentation.parse(
            block(listOf("2,6,10")), setOf(2, 6, 10)
        )
        assertTrue(result.confirmed)
        assertEquals(listOf(setOf(2, 6, 10)), result.groups.map { it.cpus })
    }

    @Test fun legacyTiersNeverBecomeOverlappingPerformanceCards() {
        assertPending("""
            detected_complete=1
            detected_clusters=3
            detected_low=0-3
            detected_main=4-6
            detected_high=5-6
            detected_top=7
        """.trimIndent())
    }

    @Test fun incompleteOrMissingCompleteFlagCannotClaimConfirmedGroups() {
        assertPending(block(listOf("0-3", "4-7")).replace("detected_complete=1", "detected_complete=0"))
        assertPending(block(listOf("0-3", "4-7")).replace("detected_complete=1", ""))
    }

    @Test fun missingNumberAndNonCanonicalGroupNumberAreRejected() {
        assertPending(block(listOf("0-3", "4-7")).replace("group_1", "group_2"))
        assertPending(block(listOf("0-3", "4-7")).replace("group_1", "group_01"))
        assertPending(block(listOf("0-3", "4-7")).replace("group_1", "group_x"))
    }

    @Test fun overlapDuplicateMembershipMissingAndForeignCoresAreRejected() {
        for (groups in listOf(
            listOf("0-4", "4-7"),
            listOf("0-3,3", "4-7"),
            listOf("0-2", "4-7"),
            listOf("0-3", "4-8"),
            listOf("0-3", "")
        )) assertPending(block(groups))
    }

    @Test fun malformedAndHugeRangesCannotExpandIntoFakeGroups() {
        for (invalid in listOf("-1", "3-0", "0-2147483647", "0-999999999999", "0-3,", "0-3,x")) {
            assertPending(block(listOf(invalid, "4-7")))
        }
    }

    @Test fun duplicateFieldsRemainVisibleButCannotSilentlyOverrideEarlierMetadata() {
        val text = block(listOf("0-3", "4-7")) + "\ndetected_group_1=4-6"
        val result = SettingsTopologyPresentation.parse(text, (0..7).toSet())
        assertFalse(result.confirmed)
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf("4-7", "4-6"), result.fields.filter { it.first == "detected_group_1" }.map { it.second })
    }

    @Test fun conflictingCountOrAllCpuMetadataIsPending() {
        val valid = block(listOf("0-3", "4-7"))
        assertPending(valid.replace("detected_clusters=2", "detected_clusters=3"))
        assertPending("$valid\ndetected_all=0-6")
        assertTrue(SettingsTopologyPresentation.parse("$valid\ndetected_all=0-7", (0..7).toSet()).confirmed)
    }

    @Test fun deviceCoreListMustBeKnownBeforeConfirmingGroups() {
        val result = SettingsTopologyPresentation.parse(block(listOf("0-3", "4-7")), emptySet())
        assertFalse(result.confirmed)
        assertTrue(result.groups.isEmpty())
    }

    private fun block(groups: List<String>): String = buildString {
        appendLine("detected_complete=1")
        appendLine("detected_clusters=${groups.size}")
        groups.forEachIndexed { index, cpus -> appendLine("detected_group_$index=$cpus") }
    }

    private fun assertPending(block: String) {
        val result = SettingsTopologyPresentation.parse(block, (0..7).toSet())
        assertFalse(block, result.confirmed)
        assertTrue(block, result.groups.isEmpty())
        assertFalse(result.pendingReason.isNullOrBlank())
    }
}
