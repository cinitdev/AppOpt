package top.qixia.threads

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式指定的模拟器集成测试；暂存并原样恢复配置，不接触历史数据库。 */
class AutomaticFallbackStorageTest {
    @Test fun createsReusesAndChangesMainFallbackWithoutEnablingStaticThreadRules() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fallback_fixture") == "1")
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("emulator"))
        val folder = "/data/adb/modules/QixiaThreads/config"
        val backup = "${InstrumentationRegistry.getInstrumentation().targetContext.cacheDir}/fallback-storage-test"
        val pkg = "com.qixia.fallback.fixture"
        check(DaemonBridge.readAutomaticAffinity().fallbackSupported)
        check(DaemonBridge.runRootCommand("""
            [ ! -e '$backup' ] || exit 1
            mkdir '$backup' || exit 1
            cp -p '$folder/applist.conf' '$backup/applist.conf' || exit 1
            if [ -f '$folder/auto_affinity.conf' ]; then cp -p '$folder/auto_affinity.conf' '$backup/auto_affinity.conf' || exit 1; fi
        """.trimIndent()).success)
        try {
            val cores = requireNotNull(RuleConfigLogic.readPresentCpuSet()).sorted()
            check(cores.size >= 2)
            val first = cores[0].toString()
            val second = cores[1].toString()
            assertTrue(DaemonBridge.addAutoPackage(pkg))
            assertEquals(DaemonBridge.ConfigReplaceResult.SUCCESS, DaemonBridge.replaceConfigRulesPreservingLayout(
                listOf(pkg), emptyList(), emptyMap(), listOf("$pkg{Renderer}=$second", "$pkg:worker=$second"), cores.toSet()))
            assertTrue(DaemonBridge.setAutomaticAffinity(pkg, true))
            assertEquals(first, DaemonBridge.setAutomaticFallback(pkg, true, first).cpus)
            fun assertRules(expected: String) {
                val rules = requireNotNull(DaemonBridge.readPkgRulesOrNull(listOf(pkg)))
                assertEquals(setOf("$pkg{Renderer}=$second", "$pkg:worker=$second", "$pkg=$expected"), rules.toSet())
                assertEquals(3, rules.size)
                assertTrue(pkg in DaemonBridge.readAutomaticAffinity().packages)
            }
            assertRules(first)
            assertTrue(pkg in DaemonBridge.readAutomaticAffinity().fallbackPackages)
            assertEquals(first, DaemonBridge.setAutomaticFallback(pkg, true, null).cpus)
            assertEquals(second, DaemonBridge.setAutomaticFallback(pkg, true, second).cpus)
            assertRules(second)
            DaemonBridge.setAutomaticFallback(pkg, false, null)
            assertFalse(pkg in DaemonBridge.readAutomaticAffinity().fallbackPackages)
            assertRules(second)
        } finally {
            check(DaemonBridge.runRootCommand("""
                cp -p '$backup/applist.conf' '$folder/applist.conf' || exit 1
                if [ -f '$backup/auto_affinity.conf' ]; then
                    cp -p '$backup/auto_affinity.conf' '$folder/auto_affinity.conf' || exit 1
                else
                    rm -f '$folder/auto_affinity.conf' || exit 1
                fi
                rm -f '$backup/applist.conf' '$backup/auto_affinity.conf'
                rmdir '$backup'
            """.trimIndent()).success)
        }
    }
}
