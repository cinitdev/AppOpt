package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import top.qixia.threads.ConfigReader
import top.qixia.threads.DaemonBridge
import top.qixia.threads.RuleSyntax

class SystemComponentRecognitionTest {
    private val surfaceFlingerConfig = """
        app(surfaceflinger, 0-7) {
            thread(RenderEngine, 7)
        }
    """.trimIndent()

    @Test fun functionConfigurationPreservesNativeOwnerAndBothRules() {
        val document = RuleSyntax.parse(surfaceFlingerConfig)
        assertEquals(RuleSyntax.Format.FUNCTION, document.segments.single().format)
        assertTrue(document.segments.single().valid)
        assertEquals(listOf(
            RuleSyntax.Rule("surfaceflinger", "RenderEngine", "7"),
            RuleSyntax.Rule("surfaceflinger", null, "0-7")
        ), document.rules)

        val config = ConfigReader.parsePackages(surfaceFlingerConfig, (0..7).toSet())
        assertEquals(listOf("surfaceflinger"), config.configuredPackages)
        assertEquals(mapOf("surfaceflinger" to 2), config.configuredRuleCounts)
        assertTrue(config.autoPackages.isEmpty())
        // 主进程兜底不需要线程名健康检查，不能被误判为待检查的线程规则。
        assertEquals(setOf(DaemonBridge.ruleHealthKey("T", "surfaceflinger", "RenderEngine")),
            config.ruleHealthKeys)
    }

    @Test fun oneBatchDistinguishesInstalledApkRunningComponentAndMissingApp() {
        val queries = mutableListOf<List<String>>()
        val result = resolveConfiguredComponents(
            listOf("surfaceflinger", "com.example.game", "com.example.removed"),
            isInstalled = { it == "com.example.game" },
            findProcesses = { names ->
                queries += names.toList()
                setOf("surfaceflinger")
            }
        )
        assertEquals(listOf(listOf("surfaceflinger", "com.example.removed")), queries)
        assertEquals(mapOf(
            "surfaceflinger" to AppComponentKind.SYSTEM_COMPONENT,
            "com.example.game" to AppComponentKind.APP,
            "com.example.removed" to AppComponentKind.MISSING_APP
        ), result)
    }

    @Test fun emptyOrFullyInstalledConfigurationDoesNotQueryRoot() {
        for (names in listOf(emptyList(), listOf("com.example.game", "com.example.game"))) {
            val result = resolveConfiguredComponents(names, isInstalled = { true }, findProcesses = {
                throw AssertionError("没有未安装候选时不应读取进程索引")
            })
            assertEquals(names.toSet(), result.keys)
            assertTrue(result.values.all { it == AppComponentKind.APP })
        }
    }

    @Test fun duplicateRuleOwnersAreCheckedOnceInOneProcessQuery() {
        val installedCalls = mutableMapOf<String, Int>()
        var queryCount = 0
        val result = resolveConfiguredComponents(
            listOf("surfaceflinger", "surfaceflinger", "system_server", "surfaceflinger"),
            isInstalled = { name ->
                installedCalls[name] = (installedCalls[name] ?: 0) + 1
                false
            },
            findProcesses = { names ->
                queryCount++
                assertEquals(setOf("surfaceflinger", "system_server"), names.toSet())
                assertEquals(2, names.size)
                names.toSet()
            }
        )
        assertEquals(1, queryCount)
        assertEquals(mapOf("surfaceflinger" to 1, "system_server" to 1), installedCalls)
        assertTrue(result.values.all { it == AppComponentKind.SYSTEM_COMPONENT })
    }

    @Test fun vendorNativeProcessIsRecognizedWithoutHardcodedNames() {
        val vendorProcess = "vendor.example.hardware.display-service"
        val missingProcess = "vendor.example.hardware.absent-service"
        val result = resolveConfiguredComponents(listOf(vendorProcess, missingProcess),
            isInstalled = { false }, findProcesses = { setOf(vendorProcess, "unrelated_process") })
        assertEquals(AppComponentKind.SYSTEM_COMPONENT, result[vendorProcess])
        assertEquals(AppComponentKind.MISSING_APP, result[missingProcess])
        assertEquals(setOf(vendorProcess, missingProcess), result.keys)
    }

    @Test fun nativeChildProcessHitRecognizesItsDisplayOwnerWithoutBaseProcess() {
        val installedQueries = mutableListOf<String>()
        val processQueries = mutableListOf<Set<String>>()
        val result = resolveConfiguredComponents(
            listOf("vendor.example:service", "vendor.example:worker", "vendor.example:service"),
            isInstalled = { name -> installedQueries += name; false },
            findProcesses = { names ->
                processQueries += names.toSet()
                setOf("vendor.example:service")
            },
            ownerOf = { it.substringBefore(':') }
        )
        assertEquals(listOf("vendor.example"), installedQueries)
        assertEquals(listOf(setOf("vendor.example:service", "vendor.example:worker", "vendor.example")),
            processQueries)
        assertEquals(mapOf("vendor.example" to AppComponentKind.SYSTEM_COMPONENT), result)
    }

    @Test fun installedApkWithOnlyChildRulesUsesPackageOwnerWithoutRootQuery() {
        val installedQueries = mutableListOf<String>()
        val result = resolveConfiguredComponents(
            listOf("com.example.game:render", "com.example.game:worker", "com.example.game:render"),
            isInstalled = { name -> installedQueries += name; name == "com.example.game" },
            findProcesses = { throw AssertionError("已安装应用的子进程规则不应触发 Root 查询") },
            ownerOf = { it.substringBefore(':') }
        )
        assertEquals(listOf("com.example.game"), installedQueries)
        assertEquals(mapOf("com.example.game" to AppComponentKind.APP), result)
    }

    @Test fun missingPackageWithoutRunningProcessEvidenceRemainsMissing() {
        val result = resolveConfiguredComponents(listOf("surfaceflinger", "com.example.removed"),
            isInstalled = { false }, findProcesses = { emptySet() })
        assertTrue(result.values.all { it == AppComponentKind.MISSING_APP })
    }

    @Test fun hidingMissingAppsKeepsSystemComponentsWithoutPretendingTheyAreInstalled() {
        val component = systemComponent()
        val installed = AppItemModel("com.example.game", "Game", true, null)
        val missing = AppItemModel("com.example.removed", "Removed", false, null,
            state = AppRuleState.MISSING)
        val state = ApplicationsUiState(loading = false, selectedTab = ApplicationTab.CONFIGURED,
            configured = listOf(component, installed, missing), hideMissing = true)

        assertFalse(component.installed)
        assertTrue(component.isSystemComponent)
        assertTrue(component.available)
        assertFalse(missing.available)
        assertEquals(listOf("surfaceflinger", "com.example.game"), state.visibleItems.map { it.packageName })
        assertEquals(listOf(component), state.copy(query = "surfaceflinger").visibleItems)
        assertEquals(listOf(component, installed, missing), state.copy(hideMissing = false).visibleItems)
    }

    @Test fun disablingAutomaticModePreservesSystemComponentAndManualRuleDetails() {
        val original = systemComponent()
        val updated = original.copy(automaticAffinityEnabled = true,
            cpuSummary = "CPU 自动分配", unhealthyRuleCount = 0).withAutomaticAffinity(false)
        assertEquals(original, updated)
        assertFalse(updated.installed)
        assertTrue(updated.isSystemComponent)
        assertTrue(updated.available)
        assertEquals(AppRuleState.CONFIGURED, updated.state)
    }

    @Test fun recognizedMainFallbackDoesNotClaimThreadOrChildHasMatched() {
        val base = "surfaceflinger"
        val main = RuleSyntax.Rule(base, null, "0-7")
        assertEquals("系统进程已识别", ruleBindingStatus(main, base, false, false, false, null,
            systemComponent = true))
        assertEquals("应用未安装", ruleBindingStatus(main, base, false, false, false, null))
        assertEquals("未保存", ruleBindingStatus(main, base, false, true, false, null,
            systemComponent = true))
        assertEquals("已暂停", ruleBindingStatus(main, base, false, false, true, null,
            systemComponent = true))

        for (rule in listOf(RuleSyntax.Rule(base, "RenderEngine", "7"),
            RuleSyntax.Rule("$base:worker", null, "4-6"))) {
            assertEquals("待检查", ruleBindingStatus(rule, base, false, false, false, null,
                systemComponent = true))
            assertEquals("待检查", ruleBindingStatus(rule, base, false, false, false,
                DaemonBridge.RuleHealthStatus.PENDING, systemComponent = true))
            assertEquals("已匹配", ruleBindingStatus(rule, base, false, false, false,
                DaemonBridge.RuleHealthStatus.VALID, systemComponent = true))
            assertEquals("待复检", ruleBindingStatus(rule, base, false, false, false,
                DaemonBridge.RuleHealthStatus.MISSED, systemComponent = true))
        }
    }

    @Test fun nativeProcessRecognitionDoesNotRelaxAutomaticAffinityPackageValidation() {
        assertFalse(DaemonBridge.isValidBasePackage("surfaceflinger"))
        assertFalse(DaemonBridge.isValidBasePackage("system_server"))
        assertFalse(DaemonBridge.isValidBasePackage("vendor.example.hardware.display-service"))
        assertTrue(DaemonBridge.isValidBasePackage("com.example.game"))
    }

    private fun systemComponent() = AppItemModel(
        packageName = "surfaceflinger",
        label = "surfaceflinger",
        installed = false,
        icon = null,
        ruleCount = 2,
        cpuSummary = "CPU 0-7",
        unhealthyRuleCount = 1,
        componentKind = AppComponentKind.SYSTEM_COMPONENT
    )
}
