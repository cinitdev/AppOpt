package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class AffinityDiagnosticsTest {
    private fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it) }
    private fun report(group: String, life: String = "alive", actual: String = "80") =
        "QIXIA_DIAG\t1\tcom.game\t1000\t2000\t${hex("active")}\t\t1\n" +
            "T\t1\t2\t3\t${hex("线程\tA")}\t5.0000\t80\t$actual\t${hex(group)}\t${hex("/QiXiaRs/auto/1-2-3/80")}\t1200\t${hex("assign")}\t${hex("cpuset_takeover")}\t$life\t\nEND\n"
    @Test fun equalMasksAreNotOwnershipWhenCpusetDiffers() {
        val row = AffinityDiagnostics.parse(report("/top-app"), "com.game").rows.single()
        assertEquals("线程\tA", row.name)
        assertFalse(row.matched)
        assertTrue(row.status("active").contains("不一致"))
        assertTrue(AffinityDiagnostics.parse(report("/QiXiaRs/auto/1-2-3/80"), "com.game").rows.single().matched)
    }
    @Test fun exitAndIdleAreNotFalselyReportedAsSystemInterference() {
        assertEquals("线程已退出", AffinityDiagnostics.parse(report("", "exited", "-"), "com.game").rows.single().status("active"))
        assertEquals("自动分配当前未运行", AffinityDiagnostics.parse(report("/top-app"), "com.game").rows.single().status("idle"))
    }
    @Test fun incompleteWrongPackageAndDuplicateIdentityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AffinityDiagnostics.parse(report("/top-app").substringBefore("END"), "com.game") }
        assertThrows(IllegalArgumentException::class.java) { AffinityDiagnostics.parse(report("/top-app"), "com.other") }
        val raw = report("/top-app")
        val row = raw.lines()[1]
        assertThrows(IllegalArgumentException::class.java) { AffinityDiagnostics.parse(raw.replace("END", "$row\nEND"), "com.game") }
    }
}
