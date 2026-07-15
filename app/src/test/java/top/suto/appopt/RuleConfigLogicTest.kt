package top.suto.appopt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleConfigLogicTest {

    @Test
    fun cpuParsingRejectsOversizedOrMalformedRanges() {
        assertNull(RuleConfigLogic.parseSingleCpuRange("0-2147483647"))
        assertNull(RuleConfigLogic.parseSingleCpuRange("0-1024"))
        assertNull(RuleConfigLogic.parseSingleCpuRange("7-3"))
        assertNull(RuleConfigLogic.parseSingleCpuRange("01-3"))
        assertNull(RuleConfigLogic.parseSingleCpuRange("١-٣"))
        assertNull(RuleConfigLogic.parseCpuRangeList("0-3,1024"))
        assertNull(RuleConfigLogic.parseCpuRangeList(List(1025) { "0" }.joinToString(",")))
    }

    @Test
    fun cpuParsingAcceptsTheNativeCpuSetBoundary() {
        val cpus = RuleConfigLogic.parseCpuRangeList("0-1023")
        assertEquals(1024, cpus?.size)
        assertTrue(0 in cpus.orEmpty())
        assertTrue(1023 in cpus.orEmpty())
        assertEquals(
            RuleConfigLogic.CpuBounds(5, 7),
            RuleConfigLogic.cpuBoundsFromRuleLine("com.example{RenderThread}=5-7")
        )
        assertEquals(
            linkedSetOf(0, 1, 2, 3, 7),
            RuleConfigLogic.parseCpuRangeList("0-3,7")
        )
        assertEquals(
            RuleConfigLogic.CpuBounds(0, 7),
            RuleConfigLogic.cpuBoundsFromRuleLine("com.example{RenderThread}=0-3,7")
        )
    }

    @Test
    fun ruleSortingUsesPreparsedRangeStartAndKeepsMainProcessLast() {
        val sorted = DaemonBridge.sortConfigRuleLines(
            listOf(
                "com.example=0-3",
                "com.example{three}=3-6",
                "com.example{five}=5-6",
                "com.example{seven}=7",
                "com.example{four}=4-5"
            )
        )
        assertEquals(
            listOf(
                "com.example{seven}=7",
                "com.example{five}=5-6",
                "com.example{four}=4-5",
                "com.example{three}=3-6",
                "com.example=0-3"
            ),
            sorted
        )
    }

    @Test
    fun nativeNameLimitsUseUtf8Bytes() {
        assertTrue(RuleConfigLogic.ownerFitsNativeBuffer("a".repeat(127)))
        assertFalse(RuleConfigLogic.ownerFitsNativeBuffer("a".repeat(128)))
        assertTrue(RuleConfigLogic.threadFitsNativeBuffer("线".repeat(10)))
        assertFalse(RuleConfigLogic.threadFitsNativeBuffer("线".repeat(11)))
        assertTrue(RuleConfigLogic.threadFitsNativeBuffer("a".repeat(31)))
        assertFalse(RuleConfigLogic.threadFitsNativeBuffer("a".repeat(32)))
    }

    @Test
    fun configValidationRejectsValuesNativeCWouldDrop() {
        val valid = DaemonBridge.validateConfigRulesForPackages(
            listOf("com.example"),
            "com.example{${"线".repeat(10)}}=0-3"
        )
        assertTrue(valid.ok)

        val combinedRange = DaemonBridge.validateConfigRulesForPackages(
            listOf("com.example"),
            "com.example{RenderThread}=0-3,7"
        )
        assertTrue(combinedRange.ok)

        val longThread = DaemonBridge.validateConfigRulesForPackages(
            listOf("com.example"),
            "com.example{${"线".repeat(11)}}=0-3"
        )
        assertTrue(longThread.invalidLines.isNotEmpty())

        val oversizedCpu = DaemonBridge.validateConfigRulesForPackages(
            listOf("com.example"),
            "com.example{RenderThread}=0-1024"
        )
        assertTrue(oversizedCpu.invalidCoreLines.isNotEmpty())

        val longOwner = "a".repeat(128)
        val oversizedOwner = DaemonBridge.validateConfigRulesForPackages(
            listOf(longOwner),
            "$longOwner=0-3"
        )
        assertTrue(oversizedOwner.invalidLines.isNotEmpty())
    }

    @Test
    fun requestGenerationRejectsOlderResults() {
        val requests = RequestGeneration()
        val first = requests.next()
        val second = requests.next()
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
        assertEquals(second, requests.current())
    }

    @Test
    fun independentRequestDomainsDoNotCancelEachOther() {
        val environmentRequests = RequestGeneration()
        val appListRequests = RequestGeneration()
        val environment = environmentRequests.next()

        appListRequests.next()
        appListRequests.next()

        assertTrue(environmentRequests.isCurrent(environment))
    }
}
