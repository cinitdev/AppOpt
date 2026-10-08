package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qixia.threads.db.RuleHistoryRecord

class RuleHistoryCandidatesTest {
    @Test
    fun largeHistoryIsBoundedToEditorCandidateLimit() {
        val records = (0 until 5_000).map { index ->
            RuleHistoryRecord(
                epoch = 5_000L - index,
                name = "Thread-$index",
                avg = 20f - (index % 20) / 10f,
                max = 80f,
                details = ""
            )
        }

        val candidates = RuleHistoryCandidates.build("com.example", records)

        assertEquals(RuleHistoryCandidates.MAX_EDITOR_CANDIDATES, candidates.size)
        assertEquals("Thread-0", candidates.first().thread)
        assertTrue(candidates.none { it.thread == "Thread-4999" })
    }

    @Test
    fun childThreadDetailsAreBoundedPerProcess() {
        val details = (0 until 200)
            .joinToString(";") { "Worker-$it,10.0,80.0" }
            .let { "v2:$it" }
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                RuleHistoryRecord(
                    epoch = 1L,
                    name = "com.example:worker",
                    avg = 30f,
                    max = 90f,
                    details = details
                )
            )
        )

        assertEquals(65, candidates.size)
        assertEquals(64, candidates.count { it.kind == RuleHistoryKind.THREAD })
    }

    @Test
    fun multiSelectCandidatesStillProvideWildcardSuggestions() {
        val records = listOf("thread-shared-1", "thread-shared-2", "thread-shared-6")
            .mapIndexed { index, name ->
                RuleHistoryRecord(
                    epoch = 10L - index,
                    name = name,
                    avg = 20f,
                    max = 60f,
                    details = ""
                )
            }
        val candidates = RuleHistoryCandidates.build("com.example", records)
        val selected = candidates.first { it.thread == "thread-shared-1" }

        val suggestion = RuleHistoryCandidates.suggestThreadWildcard(selected, candidates)

        assertNotNull(suggestion)
        assertEquals("thread-shared-*", suggestion?.pattern)
        assertEquals(3, suggestion?.matchedNames?.size)
    }

    @Test
    fun multiSelectWildcardSuggestionsAreGroupedAndResolvedTogether() {
        val records = listOf(
            "thread-shared-1",
            "thread-shared-2",
            "RenderThread 1",
            "RenderThread 9"
        ).mapIndexed { index, name ->
            RuleHistoryRecord(
                epoch = 20L - index,
                name = name,
                avg = 20f,
                max = 60f,
                details = ""
            )
        }
        val candidates = RuleHistoryCandidates.build("com.example", records)
        val selected = candidates.filter {
            it.thread == "thread-shared-1" || it.thread == "RenderThread 1"
        }

        val suggestions = RuleHistoryCandidates.collectThreadWildcardSuggestions(
            selected,
            candidates
        )
        val resolved = RuleHistoryCandidates.resolveThreadTargets(selected, suggestions)

        assertEquals(2, suggestions.size)
        assertEquals(
            setOf("thread-shared-*", "RenderThread*"),
            resolved.map { it.second }.toSet()
        )
    }

    @Test
    fun packageNamedThreadIsKeptButProcessSummaryIsIgnored() {
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                RuleHistoryRecord(3L, "com.example", 20f, 70f, ""),
                RuleHistoryRecord(2L, "com.example", 30f, 80f, "v2:Worker,10,20"),
                RuleHistoryRecord(1L, "RenderThread", 10f, 60f, "")
            )
        )

        assertEquals(1, candidates.count { it.thread == "com.example" })
        assertEquals(1, candidates.count { it.thread == "RenderThread" })
    }

    @Test
    fun encodedChildThreadNamesRemainSelectable() {
        val original = "Worker|组,1;main"
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                RuleHistoryRecord(
                    epoch = 1L,
                    name = "com.example:worker",
                    avg = 20f,
                    max = 70f,
                    details = "v3:${HistoryFieldCodec.encodeName(original)},12.0,40.0"
                )
            )
        )

        assertTrue(candidates.any { it.thread == original })
    }

    @Test
    fun v3pDistinguishesEmptyProcessSummaryFromSameNamedThread() {
        val processName = "com.example:worker"
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                RuleHistoryRecord(2L, processName, 30f, 80f, "v3p:"),
                RuleHistoryRecord(1L, processName, 20f, 70f, "")
            )
        )

        assertTrue(
            candidates.any {
                it.kind == RuleHistoryKind.CHILD_PROCESS && it.owner == processName
            }
        )
        assertTrue(
            candidates.any {
                it.kind == RuleHistoryKind.THREAD &&
                    it.owner == "com.example" &&
                    it.thread == processName
            }
        )
    }

    @Test
    fun v3pChildThreadBodyUsesV3NameDecoding() {
        val original = "Binder:1,io;worker"
        val encoded = HistoryFieldCodec.encodeName(original)
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                RuleHistoryRecord(
                    epoch = 1L,
                    name = "com.example:worker",
                    avg = 20f,
                    max = 70f,
                    details = "v3p:$encoded,10.0,60.0"
                )
            )
        )

        assertTrue(
            candidates.any {
                it.kind == RuleHistoryKind.THREAD &&
                    it.owner == "com.example:worker" &&
                    it.thread == original
            }
        )
    }

    @Test
    fun candidateLimitKeepsEveryRecentSessionRepresented() {
        val records = (1L..3L).flatMap { epoch ->
            (0 until 200).map { index ->
                RuleHistoryRecord(
                    epoch = epoch,
                    name = "Session-$epoch-Thread-$index",
                    avg = (200 - index).toFloat(),
                    max = 100f,
                    details = ""
                )
            }
        }.sortedByDescending(RuleHistoryRecord::epoch)

        val candidates = RuleHistoryCandidates.build("com.example", records, maxCandidates = 300)

        assertEquals(300, candidates.size)
        assertEquals(setOf(1L, 2L, 3L), candidates.map { it.epoch }.toSet())
        assertEquals(100, candidates.count { it.epoch == 1L })
        assertEquals(100, candidates.count { it.epoch == 2L })
        assertEquals(100, candidates.count { it.epoch == 3L })
    }
}
