package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test

class HistoryCpuGroupsTest {
    @Test fun fourThreeOneTopologySharesColorsAcrossUsageAndCycles() {
        val groups = HistoryCpuGroups.fromKeys(listOf("cpu_mhz.0_1_2_3", "cpu_mhz.4_5_6", "cpu_mhz.7"))
        assertEquals("CPU 0–3", groups.forMetric("cpu_core.2")?.label)
        assertEquals("CPU 4–6", groups.forMetric("cpu_core.6")?.label)
        assertEquals("CPU 7", groups.forMetric("cpu_core.7")?.label)
        for (cpu in 0..7) {
            val group = requireNotNull(groups.forMetric("cpu_core.$cpu"))
            assertSame(group, groups.forMetric("cpu_cycles_core.$cpu"))
            assertEquals(HistoryChartPalette.metric("cpu_mhz.${group.cpus.joinToString("_")}"), group.color)
        }
        val colors = (0..7).map { groups.forMetric("cpu_core.$it")!!.color }.toSet()
        assertEquals(3, colors.size)
        assertFalse(HistoryChartPalette.cpuUsage in colors)
        assertFalse(HistoryChartPalette.temperature in colors)
        assertNull(groups.forMetric("cpu_usage"))
        assertNull(groups.forMetric("cpu_c"))
    }

    @Test fun tenCoreFrequencyPoliciesTakePrecedenceOverLegacyClusters() {
        val keys = listOf("cpu_mhz.0_1_2_3", "cpu_mhz.4_5_6_7", "cpu_mhz.8_9",
            "cpu_cluster.0_1_2_3", "cpu_cluster.4_5_6", "cpu_cluster.7")
        val groups = HistoryCpuGroups.fromKeys(keys)
        assertEquals("CPU 4–7", groups.forMetric("cpu_core.7")?.label)
        assertEquals("CPU 8–9", groups.forMetric("cpu_core.9")?.label)
        val reordered = HistoryCpuGroups.fromKeys(keys.reversed())
        for (cpu in 0..9) assertEquals(groups.forMetric("cpu_core.$cpu"), reordered.forMetric("cpu_core.$cpu"))
    }

    @Test fun usageClustersFillInWhenFrequencyIsUnavailable() {
        val groups = HistoryCpuGroups.fromKeys(listOf("cpu_mhz.0_1", "cpu_cluster.2_3_4", "cpu_cluster.5"))
        assertEquals("CPU 2–4", groups.forMetric("cpu_cycles_core.4")?.label)
        assertEquals("CPU 5", groups.forMetric("cpu_core.5")?.label)
    }

    @Test fun sparseUnorderedCpuIdsAreNotShownAsAContinuousRange() {
        val groups = HistoryCpuGroups.fromKeys(listOf("cpu_mhz.4_0_2", "cpu_mhz.2_4_0"))
        assertEquals("CPU 0、2、4", groups.forMetric("cpu_core.2")?.label)
        assertNull(groups.forMetric("cpu_core.1"))
        assertSame(groups.forMetric("cpu_core.0"), groups.forMetric("cpu_core.4"))
    }

    @Test fun missingOrMalformedTopologyDoesNotGuessGroups() {
        val groups = HistoryCpuGroups.fromKeys(listOf("cpu_core.0", "cpu_cycles_core.1", "cpu_mhz.0_x",
            "cpu_mhz.1_", "cpu_mhz.-2", "cpu_mhz.64", "cpu_mhz.999999999999999"))
        (0..7).forEach { assertNull(groups.forMetric("cpu_core.$it")) }
    }

    @Test fun ConflictingPoliciesDoNotSilentlyMergeDifferentClusters() {
        val groups = HistoryCpuGroups.fromKeys(listOf("cpu_mhz.0_1", "cpu_mhz.1_2", "cpu_mhz.3_4"))
        (0..2).forEach { assertNull(groups.forMetric("cpu_core.$it")) }
        assertEquals("CPU 3–4", groups.forMetric("cpu_core.3")?.label)
    }
}
