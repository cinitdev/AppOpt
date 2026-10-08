package top.qixia.threads

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
        assertEquals(
            linkedSetOf(0),
            RuleConfigLogic.parseCpuRangeList(List(1025) { "0" }.joinToString(","))
        )
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
            linkedSetOf(0, 1, 2, 3, 4),
            RuleConfigLogic.parseCpuRangeList("00-03,3-3,02-4")
        )
        assertEquals(
            "0-4",
            RuleConfigLogic.formatCpuRangeList(
                RuleConfigLogic.parseCpuRangeList("00-03,3-3,02-4").orEmpty()
            )
        )
        assertEquals(
            RuleConfigLogic.CpuBounds(0, 7),
            RuleConfigLogic.cpuBoundsFromRuleLine("com.example{RenderThread}=0-3,7")
        )
        assertEquals(
            "0-3,5,7",
            RuleConfigLogic.formatCpuRangeList(linkedSetOf(0, 1, 2, 3, 5, 7))
        )
        assertEquals(
            "0-3,5-7",
            RuleConfigLogic.formatCpuRangeList(linkedSetOf(0, 1, 2, 3, 5, 6, 7))
        )
        assertEquals("4-5,7", CalibPolicy.normalizeCoresOrNull("7,4-5"))
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

    @Test
    fun calibrationPolicyPersistsCpusetNameInTheSharedConfig() {
        val parsed = CalibPolicy.parse(
            """
            version=1
            cpuset_name=GameThreads
            """.trimIndent()
        )

        assertEquals("GameThreads", parsed.cpusetName)
        assertTrue(parsed.toConfigText().lineSequence().any { it == "cpuset_name=GameThreads" })
        assertEquals("GameThreads", CalibPolicy.parse(parsed.toConfigText()).cpusetName)
    }

    @Test
    fun legacyCalibrationPolicyCanBeReadButRetiredTiersAreNotWrittenBack() {
        val parsed = CalibPolicy.parse(
            """
            version=1
            best_thread=avg:18,max:30,cores:4-5,7
            group_high=avg:13,max:22,cores:0-3,5-7
            fallback=cores:0-3,5,7
            """.trimIndent()
        )

        assertEquals("4-5,7", parsed.bestCores)
        assertEquals("0-3,5-7", parsed.highCores)
        assertEquals("0-3,5,7", parsed.fallbackCores)

        val serialized = parsed.toConfigText()
        assertFalse(serialized.contains("best_thread="))
        assertFalse(serialized.contains("group_high="))
        assertFalse(serialized.contains("fallback="))
    }

    @Test
    fun foregroundStateRemainsAvailableWithoutInstallationMetadata() {
        val state = DaemonBridge.parseTaskForegroundState(
            """
            version=2
            status=ok
            mode=listener
            focused_package=com.example
            focused_activity=com.example/.MainActivity
            visible_packages=com.example,com.example.other
            updated_elapsed_ms=9000
            generation=12
            """.trimIndent(),
            elapsedNowMs = 10_000L
        )
        assertTrue(state.available)
        assertEquals("com.example", state.packageName)
        assertEquals("listener", state.mode)
        assertEquals(listOf("com.example", "com.example.other"), state.visiblePackages)
        assertEquals(1_000L, state.ageMs)
        assertEquals(12L, state.generation)
    }

    @Test
    fun legacyForegroundMetadataDoesNotChangeFreshnessChecks() {
        val raw = "status=ok\nfocused_package=com.example\nupdated_elapsed_ms=9000"
        // 只更新 APK 时，旧辅助 JAR 仍可能继续写入额外字段。
        for (legacyValue in listOf("0", "1", "unknown")) {
            val legacyRaw = "$raw\nscene_installed=$legacyValue"
            val fresh = DaemonBridge.parseTaskForegroundState(legacyRaw, elapsedNowMs = 10_000L)
            assertTrue(fresh.available)
            assertEquals("com.example", fresh.packageName)
            val expired = DaemonBridge.parseTaskForegroundState(legacyRaw, elapsedNowMs = 40_000L)
            assertFalse(expired.available)
        }
    }

    @Test
    fun calibrationPolicyRejectsInvalidCpusetNames() {
        assertEquals(
            CalibPolicy.DEFAULT_CPUSET_NAME,
            CalibPolicy.parse("cpuset_name=../invalid").cpusetName
        )
        assertNull(CalibPolicy.normalizeCpusetNameOrNull(".hidden"))
        assertNull(CalibPolicy.normalizeCpusetNameOrNull("name/child"))
        assertNull(CalibPolicy.normalizeCpusetNameOrNull("a".repeat(49)))
        assertEquals("game-threads.v2", CalibPolicy.normalizeCpusetNameOrNull("game-threads.v2"))
    }

    @Test
    fun functionBlocksKeepCommaSeparatedCpuMasks() {
        val rules = RuleSyntax.parse(
            """
            app(com.example, 0-3,6-7) {
                thread(RenderThread, 0-3,6-7)
                process(worker, 4-7)
            }
            """.trimIndent()
        ).rules.map { it.canonicalLine }

        assertEquals(
            listOf(
                "com.example{RenderThread}=0-3,6-7",
                "com.example:worker=4-7",
                "com.example=0-3,6-7"
            ),
            rules
        )
    }

    @Test
    fun invalidCpuMemberDoesNotHideValidRulesInTheSameBlock() {
        val parsed = ConfigReader.parsePackages(
            """
            com.example=0-3 {
                RenderThread=6-7
                Worker=abc
            }
            """.trimIndent(),
            (0..7).toSet()
        )

        assertEquals(listOf("com.example"), parsed.configuredPackages)
        assertEquals(2, parsed.configuredRuleCounts["com.example"])
    }

    @Test
    fun calibrationStartStateDistinguishesProcessFailuresFromRootFailures() {
        val pkg = "com.example.game"
        assertEquals(
            DaemonBridge.CalibrationStartStatus.STARTED,
            DaemonBridge.calibrationStartStatusFromState("sampling $pkg", pkg)
        )
        assertEquals(
            DaemonBridge.CalibrationStartStatus.TARGET_PROCESS_NOT_READY,
            DaemonBridge.calibrationStartStatusFromState(
                "rejected $pkg;reason=no_process",
                pkg
            )
        )
        assertEquals(
            DaemonBridge.CalibrationStartStatus.TARGET_PROCESS_EXITED,
            DaemonBridge.calibrationStartStatusFromState("done $pkg;reason=short", pkg)
        )
        assertEquals(
            DaemonBridge.CalibrationStartStatus.BUSY,
            DaemonBridge.calibrationStartStatusFromState(
                "sampling com.other;reason=busy;requested=$pkg",
                pkg
            )
        )
        assertNull(
            DaemonBridge.calibrationStartStatusFromState(
                "sampling com.other;reason=busy;requested=com.example.other",
                pkg
            )
        )
        assertNull(DaemonBridge.calibrationStartStatusFromState("idle", pkg))
        assertNull(
            DaemonBridge.calibrationStartStatusFromState(
                "rejected com.example.other;reason=no_process",
                pkg
            )
        )
    }

    @Test
    fun childProcessThreadRulesRoundTripThroughEveryOutputFormat() {
        val line = "com.tencent.mobileqq:MSF{Thread_*}=0-7"
        val chromeLine =
            "com.android.chrome:sandboxed_process0:org.chromium.content.app." +
                "SandboxedProcessService0:3{CrRendererMain}=7"
        val parsed = RuleSyntax.parseLegacyRule(line)
        assertEquals("com.tencent.mobileqq:MSF", parsed?.owner)
        assertEquals("Thread_*", parsed?.thread)
        assertEquals(line, parsed?.canonicalLine)
        val chrome = RuleSyntax.parseLegacyRule(chromeLine)
        assertEquals(
            "com.android.chrome:sandboxed_process0:org.chromium.content.app." +
                "SandboxedProcessService0:3",
            chrome?.owner
        )
        assertEquals("CrRendererMain", chrome?.thread)
        assertTrue(RuleConfigLogic.ownerFitsNativeBuffer(chrome?.owner.orEmpty()))

        val config = ConfigReader.parsePackages(line, (0..7).toSet())
        assertEquals(listOf("com.tencent.mobileqq:MSF"), config.configuredPackages)
        assertEquals(1, config.configuredRuleCounts["com.tencent.mobileqq:MSF"])
        assertTrue(
            DaemonBridge.ruleHealthKey("T", "com.tencent.mobileqq:MSF", "Thread_*") in
                config.ruleHealthKeys
        )

        val validation = DaemonBridge.validateConfigRulesForPackages(
            listOf("com.tencent.mobileqq"),
            line,
            (0..7).toSet()
        )
        assertTrue(validation.ok)
        assertTrue(
            DaemonBridge.validateConfigRulesForPackages(
                listOf("com.android.chrome"),
                chromeLine,
                (0..7).toSet()
            ).ok
        )

        for (format in CalibPolicy.RuleOutputFormat.values()) {
            val result = RuleFormatConverter.convert(
                "com.tencent.mobileqq=0-7\n$line\n",
                format
            )
            assertTrue("$format conversion failed: ${result.error}", result.success)
            val output = result.conversion?.content.orEmpty()
            assertTrue(
                "$format no longer parses the child thread rule",
                RuleSyntax.parse(output).rules.any { it.canonicalLine == line }
            )
            if (format.generationTarget() == CalibPolicy.RuleOutputFormat.LEGACY) {
                assertTrue(
                    "$format lost the legacy child thread rule",
                    output.lineSequence().any { it == line }
                )
            } else {
                assertFalse(
                    "$format left the child thread in legacy syntax",
                    output.lineSequence().any { it == line }
                )
            }
        }
    }

    @Test
    fun completeChildProcessBlockRoundTripsThroughEveryOutputFormat() {
        val input = """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
                process(MSF, 0-3) {
                    thread(Thread_*, 0-7)
                    thread(MSF-Worker, 4-7)
                    thread(Binder:*, 4-6)
                }
            }
        """.trimIndent() + "\n"
        val expected = setOf(
            "com.tencent.mobileqq=0-6",
            "com.tencent.mobileqq{RenderThread}=7",
            "com.tencent.mobileqq:MSF=0-3",
            "com.tencent.mobileqq:MSF{Thread_*}=0-7",
            "com.tencent.mobileqq:MSF{MSF-Worker}=4-7",
            "com.tencent.mobileqq:MSF{Binder:*}=4-6"
        )

        assertEquals(expected, RuleSyntax.parse(input).rules.map { it.canonicalLine }.toSet())
        val sourceFormats = CalibPolicy.RuleOutputFormat.values().associateWith { format ->
            val result = RuleFormatConverter.convert(input, format)
            assertTrue("$format conversion failed: ${result.error}", result.success)
            result.conversion?.content.orEmpty()
        }
        for ((sourceFormat, source) in sourceFormats) {
            assertEquals(
                "$sourceFormat did not preserve the complete child-process block:\n$source",
                expected,
                RuleSyntax.parse(source).rules.map { it.canonicalLine }.toSet()
            )
            for (targetFormat in CalibPolicy.RuleOutputFormat.values()) {
                val result = RuleFormatConverter.convert(source, targetFormat)
                assertTrue(
                    "$sourceFormat -> $targetFormat conversion failed: ${result.error}",
                    result.success
                )
                assertEquals(
                    "$sourceFormat -> $targetFormat did not preserve the complete child-process block:\n" +
                        result.conversion?.content.orEmpty(),
                    expected,
                    RuleSyntax.parse(result.conversion?.content.orEmpty()).rules
                        .map { it.canonicalLine }
                        .toSet()
                )
            }
        }
    }

    @Test
    fun completeChildProcessRulesUseTheExpectedEightSelectableTemplates() {
        val input = """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
                process(MSF, 0-3)
            }
            app(com.tencent.mobileqq:MSF) {
                thread(Thread_*, 0-7)
                thread(MSF-Worker, 4-7)
                thread(Binder:*, 4-6)
            }
        """.trimIndent() + "\n"
        val expected = linkedMapOf(
            CalibPolicy.RuleOutputFormat.LEGACY to """
                com.tencent.mobileqq{RenderThread}=7
                com.tencent.mobileqq:MSF=0-3
                com.tencent.mobileqq=0-6
                com.tencent.mobileqq:MSF{Thread_*}=0-7
                com.tencent.mobileqq:MSF{MSF-Worker}=4-7
                com.tencent.mobileqq:MSF{Binder:*}=4-6
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK to """
                com.tencent.mobileqq=0-6 {
                    RenderThread=7
                }
                com.tencent.mobileqq:MSF=0-3
                com.tencent.mobileqq:MSF {
                    Thread_*=0-7
                    MSF-Worker=4-7
                    Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK to """
                com.tencent.mobileqq{
                    RenderThread=7
                    :MSF=0-3
                }=0-6
                com.tencent.mobileqq:MSF{
                    Thread_*=0-7
                    MSF-Worker=4-7
                    Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK to """
                com.tencent.mobileqq={
                    thread:RenderThread=7
                    process:MSF=0-3
                    fallback=0-6
                }
                com.tencent.mobileqq:MSF={
                    thread:Thread_*=0-7
                    thread:MSF-Worker=4-7
                    thread:Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK to """
                app com.tencent.mobileqq fallback 0-6 {
                    thread RenderThread=7
                    process MSF=0-3
                }
                app com.tencent.mobileqq:MSF {
                    thread Thread_*=0-7
                    thread MSF-Worker=4-7
                    thread Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK to """
                com.tencent.mobileqq={
                    threads {
                        RenderThread=7
                    }
                    processes {
                        MSF=0-3
                    }
                    fallback=0-6
                }
                com.tencent.mobileqq:MSF={
                    threads {
                        Thread_*=0-7
                        MSF-Worker=4-7
                        Binder:*=4-6
                    }
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK to """
                app(com.tencent.mobileqq, 0-6) {
                    thread(RenderThread, 7)
                    process(MSF, 0-3)
                }
                app(com.tencent.mobileqq:MSF) {
                    thread(Thread_*, 0-7)
                    thread(MSF-Worker, 4-7)
                    thread(Binder:*, 4-6)
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.YAML to """
                com.tencent.mobileqq:
                    threads:
                        RenderThread: 7
                    processes:
                        MSF: 0-3
                    fallback: 0-6
                com.tencent.mobileqq:MSF:
                    threads:
                        Thread_*: 0-7
                        MSF-Worker: 4-7
                        Binder:*: 4-6
            """.trimIndent() + "\n"
        )

        assertEquals(8, expected.size)
        for ((format, template) in expected) {
            val result = RuleFormatConverter.convert(input, format)
            assertTrue("$format conversion failed: ${result.error}", result.success)
            assertEquals("Unexpected $format template", template, result.conversion?.content)
        }
    }

    @Test
    fun childThreadsWithoutProcessFallbackRoundTripThroughEveryOutputFormat() {
        val input = """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
            }
            app(com.tencent.mobileqq:MSF) {
                thread(Thread_*, 0-7)
                thread(MSF-Worker, 4-7)
                thread(Binder:*, 4-6)
            }
        """.trimIndent() + "\n"
        val expected = setOf(
            "com.tencent.mobileqq=0-6",
            "com.tencent.mobileqq{RenderThread}=7",
            "com.tencent.mobileqq:MSF{Thread_*}=0-7",
            "com.tencent.mobileqq:MSF{MSF-Worker}=4-7",
            "com.tencent.mobileqq:MSF{Binder:*}=4-6"
        )

        val sourceFormats = CalibPolicy.RuleOutputFormat.values().associateWith { format ->
            val result = RuleFormatConverter.convert(input, format)
            assertTrue("$format conversion failed: ${result.error}", result.success)
            result.conversion?.content.orEmpty()
        }
        for ((sourceFormat, source) in sourceFormats) {
            assertEquals(
                "$sourceFormat lost child threads without a process fallback:\n$source",
                expected,
                RuleSyntax.parse(source).rules.map { it.canonicalLine }.toSet()
            )
            for (targetFormat in CalibPolicy.RuleOutputFormat.values()) {
                val result = RuleFormatConverter.convert(source, targetFormat)
                assertTrue(
                    "$sourceFormat -> $targetFormat conversion failed: ${result.error}",
                    result.success
                )
                assertEquals(
                    "$sourceFormat -> $targetFormat lost child threads without a process fallback:\n" +
                        result.conversion?.content.orEmpty(),
                    expected,
                    RuleSyntax.parse(result.conversion?.content.orEmpty()).rules
                        .map { it.canonicalLine }
                        .toSet()
                )
            }
        }
    }

    @Test
    fun childThreadsWithoutProcessFallbackUseTheExpectedEightSelectableTemplates() {
        val input = """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
            }
            app(com.tencent.mobileqq:MSF) {
                thread(Thread_*, 0-7)
                thread(MSF-Worker, 4-7)
                thread(Binder:*, 4-6)
            }
        """.trimIndent() + "\n"
        val expected = linkedMapOf(
            CalibPolicy.RuleOutputFormat.LEGACY to """
                com.tencent.mobileqq{RenderThread}=7
                com.tencent.mobileqq=0-6
                com.tencent.mobileqq:MSF{Thread_*}=0-7
                com.tencent.mobileqq:MSF{MSF-Worker}=4-7
                com.tencent.mobileqq:MSF{Binder:*}=4-6
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK to """
                com.tencent.mobileqq=0-6 {
                    RenderThread=7
                }
                com.tencent.mobileqq:MSF {
                    Thread_*=0-7
                    MSF-Worker=4-7
                    Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK to """
                com.tencent.mobileqq{
                    RenderThread=7
                }=0-6
                com.tencent.mobileqq:MSF{
                    Thread_*=0-7
                    MSF-Worker=4-7
                    Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.TAGGED_BLOCK to """
                com.tencent.mobileqq={
                    thread:RenderThread=7
                    fallback=0-6
                }
                com.tencent.mobileqq:MSF={
                    thread:Thread_*=0-7
                    thread:MSF-Worker=4-7
                    thread:Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.NATURAL_BLOCK to """
                app com.tencent.mobileqq fallback 0-6 {
                    thread RenderThread=7
                }
                app com.tencent.mobileqq:MSF {
                    thread Thread_*=0-7
                    thread MSF-Worker=4-7
                    thread Binder:*=4-6
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.NESTED_BLOCK to """
                com.tencent.mobileqq={
                    threads {
                        RenderThread=7
                    }
                    fallback=0-6
                }
                com.tencent.mobileqq:MSF={
                    threads {
                        Thread_*=0-7
                        MSF-Worker=4-7
                        Binder:*=4-6
                    }
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK to """
                app(com.tencent.mobileqq, 0-6) {
                    thread(RenderThread, 7)
                }
                app(com.tencent.mobileqq:MSF) {
                    thread(Thread_*, 0-7)
                    thread(MSF-Worker, 4-7)
                    thread(Binder:*, 4-6)
                }
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.YAML to """
                com.tencent.mobileqq:
                    threads:
                        RenderThread: 7
                    fallback: 0-6
                com.tencent.mobileqq:MSF:
                    threads:
                        Thread_*: 0-7
                        MSF-Worker: 4-7
                        Binder:*: 4-6
            """.trimIndent() + "\n"
        )

        assertEquals(8, expected.size)
        for ((format, template) in expected) {
            val result = RuleFormatConverter.convert(input, format)
            assertTrue("$format conversion failed: ${result.error}", result.success)
            assertEquals("Unexpected $format template without process fallback", template, result.conversion?.content)
        }
    }

    @Test
    fun functionFormatKeepsChildThreadsInTheirOwnOwnerBlock() {
        val result = RuleFormatConverter.convert(
            """
            com.tencent.mobileqq=0-6
            com.tencent.mobileqq{RenderThread}=7
            com.tencent.mobileqq:MSF=0-3
            com.tencent.mobileqq:MSF{Thread_*}=0-7
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK
        )

        assertEquals(
            """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
                process(MSF, 0-3)
            }
            app(com.tencent.mobileqq:MSF) {
                thread(Thread_*, 0-7)
            }

            """.trimIndent(),
            result.conversion?.content
        )
    }

    @Test
    fun nestedFunctionProcessCanContainThreadsWithoutAProcessFallback() {
        val rules = RuleSyntax.parse(
            """
            app(com.tencent.mobileqq, 0-6) {
                process(MSF) {
                    thread(Thread_*, 0-7)
                    thread(MSF-Worker, 4-7)
                }
            }
            """.trimIndent()
        ).rules.map { it.canonicalLine }

        assertEquals(
            listOf(
                "com.tencent.mobileqq:MSF{Thread_*}=0-7",
                "com.tencent.mobileqq:MSF{MSF-Worker}=4-7",
                "com.tencent.mobileqq=0-6"
            ),
            rules
        )
    }

    @Test
    fun functionConversionKeepsChildThreadsWithoutFallbackInTheirOwnOwnerBlock() {
        val result = RuleFormatConverter.convert(
            """
            com.tencent.mobileqq=0-6
            com.tencent.mobileqq{RenderThread}=7
            com.tencent.mobileqq:MSF{Thread_*}=0-7
            com.tencent.mobileqq:MSF{MSF-Worker}=4-7
            """.trimIndent() + "\n",
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK
        )

        assertEquals(
            """
            app(com.tencent.mobileqq, 0-6) {
                thread(RenderThread, 7)
            }
            app(com.tencent.mobileqq:MSF) {
                thread(Thread_*, 0-7)
                thread(MSF-Worker, 4-7)
            }

            """.trimIndent(),
            result.conversion?.content
        )
    }

    @Test
    fun commaNamesRoundTripThroughEveryOutputFormat() {
        val input = """
            com.example=0-6
            com.example{worker,0}=4-7
            com.example:push,0=0-3
            com.example:push,0{Binder,1}=5-7
        """.trimIndent() + "\n"
        val expected = RuleSyntax.parse(input).rules.map { it.canonicalLine }.toSet()

        val sourceFormats = CalibPolicy.RuleOutputFormat.values().associateWith { format ->
            val result = RuleFormatConverter.convert(input, format)
            assertTrue("$format conversion failed: ${result.error}", result.success)
            result.conversion?.content.orEmpty()
        }
        val function = sourceFormats.getValue(CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK)
        assertTrue(function.contains("thread(\"worker,0\", 4-7)"))
        assertTrue(function.contains("process(\"push,0\", 0-3)"))
        assertTrue(function.contains("app(\"com.example:push,0\")"))
        assertTrue(function.contains("thread(\"Binder,1\", 5-7)"))

        for ((sourceFormat, source) in sourceFormats) {
            assertEquals(
                "$sourceFormat did not preserve comma names:\n$source",
                expected,
                RuleSyntax.parse(source).rules.map { it.canonicalLine }.toSet()
            )
            for (targetFormat in CalibPolicy.RuleOutputFormat.values()) {
                val result = RuleFormatConverter.convert(source, targetFormat)
                assertTrue(
                    "$sourceFormat -> $targetFormat conversion failed: ${result.error}",
                    result.success
                )
                assertEquals(
                    "$sourceFormat -> $targetFormat lost comma names",
                    expected,
                    RuleSyntax.parse(result.conversion?.content.orEmpty()).rules
                        .map { it.canonicalLine }
                        .toSet()
                )
            }
        }
    }

    @Test
    fun functionFormatEscapesQuotesAndBackslashesInNames() {
        val config = """
            app(com.example, 0-7) {
                thread("worker\"quoted\\path,0", 4-7)
            }
        """.trimIndent()

        val parsed = RuleSyntax.parse(config)
        assertEquals(listOf("com.example{worker\"quoted\\path,0}=4-7", "com.example=0-7"), parsed.rules.map { it.canonicalLine })
        val converted = RuleFormatConverter.convert(
            config,
            CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK
        ).conversion?.content.orEmpty()
        assertTrue(converted.contains("thread(\"worker\\\"quoted\\\\path,0\", 4-7)"))
    }
}
