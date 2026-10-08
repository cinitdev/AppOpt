package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class CalibrationDraftTest {
    private fun hex(s: String) = s.toByteArray().joinToString("") { "%02x".format(it) }
    private fun wire(name: String = "RenderThread", cpus: String = "7", pkg: String = "com.test") =
        "QIXIA_CALIBRATION_DRAFT\t1\nmeta\t1700000000000-123\t$pkg\t1700000000000\t60000\n" +
            (0..7).joinToString("") { "cpu\t$it\t${if (it == 7) 1024 else 300}\n" } +
            "thread\t${hex(pkg)}\t${hex(name)}\t35.0\t90.0\t85.0\t$cpus\t${hex("根据负载推荐")}\n"

    @Test fun recommendationsRemainDraftsAndCanReturnToSystemScheduling() {
        val draft = requireNotNull(CalibrationDraft.parse(wire()))
        val review = CalibrationReview(draft)
        assertNull(review.original)
        assertEquals(listOf("com.test{RenderThread}=7"), review.rules())
        assertTrue(review.copy(selections = listOf(emptySet())).rules().isEmpty())
        assertEquals(listOf("com.test{RenderThread}=4-7"), review.copy(selections = listOf(setOf(4,5,6,7))).rules())
    }
    @Test fun displaySortsCoreNumbersDescendingWithoutChangingThreadEditOrRuleIdentity() {
        val template = requireNotNull(CalibrationDraft.parse(wire()))
        val threads = listOf(
            "Main" to setOf(4), "Render" to setOf(5), "Worker" to setOf(0, 1),
            "Decoder" to setOf(2), "Sleeping" to emptySet(), "OtherRender" to setOf(5)
        ).map { (name, cpus) -> template.threads.single().copy(name = name, suggested = cpus) }
        val review = CalibrationReview(template.copy(threads = threads, capacities = (0..10).associateWith { 1024L }))
        val originalRules = review.rules()
        assertEquals(listOf(1, 5, 0, 3, 2, 4), review.threadDisplayOrder)
        assertEquals(originalRules, review.rules())

        // 最上方可见卡片是 Render（源索引 1），而非 Main（源索引 0）。
        val editedIndex = review.threadDisplayOrder.first()
        val edited = review.copy(selections = review.selections.mapIndexed { index, cpus ->
            if (index == editedIndex) setOf(10) else cpus
        })
        assertEquals(listOf(1, 5, 0, 3, 2, 4), edited.threadDisplayOrder)
        assertEquals("com.test{Render}=10", edited.rules()[1])
        assertEquals("com.test{Main}=4", edited.rules()[0])
        val released = edited.copy(selections = edited.selections.mapIndexed { index, cpus ->
            if (index == editedIndex) emptySet() else cpus
        })
        assertEquals(listOf(5, 0, 3, 2, 1, 4), released.threadDisplayOrder)
        assertFalse(released.rules().any { "{Render}" in it })
    }
    @Test fun corruptedOrCrossApplicationDraftsAreRejected() {
        assertNull(CalibrationDraft.parse(wire().replace("QIXIA_CALIBRATION_DRAFT", "OTHER_CALIBRATION_DRAFT")))
        assertNull(CalibrationDraft.parse(wire(cpus = "8")))
        assertNull(CalibrationDraft.parse(wire(pkg = "com.test;reboot")))
        assertNull(CalibrationDraft.parse(wire().replace(hex("com.test"), hex("com.other"))))
        assertNull(CalibrationDraft.parse(wire().replace("35.0", "NaN")))
        assertNull(CalibrationDraft.parse(wire().replace("\t1\nmeta", "\t9\nmeta")))
        assertNull(CalibrationDraft.parse(wire().repeat(100)))
        assertNull(CalibrationDraft.parse(wire() + wire().lineSequence().first { it.startsWith("thread") }))
    }
    @Test fun ambiguousNamesCanBeDisplayedButCannotAccidentallyCreatePatterns() {
        assertNull(CalibrationDraft.parse(wire(name = "Worker*")))
        val draft = requireNotNull(CalibrationDraft.parse(wire(name = "Worker*", cpus = "-")))
        assertFalse(draft.threads.single().editable)
        assertTrue(CalibrationReview(draft).rules().isEmpty())
    }
    @Test fun noncontiguousCoresAndUnicodeThreadNamesArePreserved() {
        val draft = requireNotNull(CalibrationDraft.parse(wire(name = "渲染线程", cpus = "1,7")))
        assertEquals("渲染线程", draft.threads.single().name)
        assertEquals(setOf(1,7), draft.threads.single().suggested)
    }
    private fun groupedWire(pattern: String = "Binder:*", members: String = "Binder:10534_7\nBinder:10534_9") =
        wire().lineSequence().filterNot { it.startsWith("thread") }.joinToString("\n")
            .replace("DRAFT\t1", "DRAFT\t2").replace("\t60000\n", "\t60000\t0\n") +
            "thread\t${hex("com.test")}\t${hex(pattern)}\t21.1\t80.3\t99.0\t4-6\t${hex("联合分配")}\t2\t${hex(members)}\n"

    @Test fun generatedWildcardsSurviveReviewEditingAndRuleSerialization() {
        val draft = requireNotNull(CalibrationDraft.parse(groupedWire()))
        assertEquals(2, draft.version)
        assertEquals(2, draft.threads.single().memberCount)
        assertTrue(draft.threads.single().editable)
        assertEquals(listOf("com.test{Binder:*}=4-6"), CalibrationReview(draft).rules())
        assertEquals(listOf("com.test{Binder:*}=5-7"), CalibrationReview(draft, listOf(setOf(5,6,7))).rules())
        assertEquals(listOf("Binder:10534_7", "Binder:10534_9"), draft.threads.single().members)
        assertNotNull(CalibrationDraft.parse(groupedWire("thread-shared-*", "thread-shared-1\nthread-shared-2")))
    }
    @Test fun generatedPatternValidationStillRejectsInjectionAndUnboundedPatterns() {
        assertNull(CalibrationDraft.parse(groupedWire("*")))
        assertNull(CalibrationDraft.parse(groupedWire("Binder:*}=7")))
        assertNull(CalibrationDraft.parse(groupedWire("Binder:[oops")))
        assertNull(CalibrationDraft.parse(groupedWire(members = "one\none")))
        assertNull(CalibrationDraft.parse(groupedWire().replace("\t2\t${hex("Binder:10534_7")}", "\t0\t${hex("Binder:10534_7")}")))
    }
    @Test fun processFallbacksAreOptInAndCannotEscapeTheDraftApplication() {
        val draft = requireNotNull(CalibrationDraft.parse(groupedWire()))
        val child = draft.threads.single().copy(owner = "com.test:render", name = "RenderThread")
        val review = CalibrationReview(draft.copy(threads = draft.threads + child))
        assertEquals(2, review.ruleCount)
        assertTrue(review.rules().all { '{' in it })
        val chosen = review.copy(processSelections = mapOf("com.test" to setOf(0, 1), "com.test:render" to setOf(4, 6)))
        assertEquals(4, chosen.ruleCount)
        assertEquals(listOf("com.test=0-1", "com.test:render=4,6"), chosen.rules().takeLast(2))
        assertEquals(review.rules(), chosen.copy(processSelections = chosen.processSelections.mapValues { emptySet() }).rules())
        assertTrue(runCatching { review.copy(processSelections = mapOf("com.other" to setOf(7))).rules() }.isFailure)
        assertTrue(runCatching { review.copy(processSelections = mapOf("com.test" to setOf(8))).rules() }.isFailure)
    }
}
