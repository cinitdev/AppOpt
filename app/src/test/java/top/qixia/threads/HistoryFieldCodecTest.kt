package top.qixia.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFieldCodecTest {
    @Test
    fun encodedNameRoundTripsUtf8AndHistorySeparators() {
        val original = "Render|线程,组;100%\nnext"
        val encoded = HistoryFieldCodec.encodeName(original)

        assertTrue(encoded.startsWith("e1:"))
        assertEquals("e1:Render%7C%E7%BA%BF%E7%A8%8B%2C%E7%BB%84%3B100%25%0Anext", encoded)
        assertEquals(original, HistoryFieldCodec.decodeName(encoded))
    }

    @Test
    fun legacyNamesRemainUnchanged() {
        assertEquals("RenderThread 2", HistoryFieldCodec.decodeName("RenderThread 2"))
    }

    @Test
    fun historyParserDecodesEncodedTopLevelName() {
        val encoded = HistoryFieldCodec.encodeName("worker|io,1;2")
        val parsed = DatabaseMigrator.parseThreadLine("12.0 80.0 $encoded|1.0,2.0")

        assertEquals("worker|io,1;2", parsed?.name)
    }

    @Test
    fun v3ChildThreadNamesAreDecoded() {
        val child = HistoryFieldCodec.encodeName("Binder:1,io;worker")
        val candidates = RuleHistoryCandidates.build(
            "com.example",
            listOf(
                top.qixia.threads.db.RuleHistoryRecord(
                    epoch = 1L,
                    name = "com.example:worker",
                    avg = 20f,
                    max = 70f,
                    details = "v3:$child,10.0,60.0"
                )
            )
        )

        assertTrue(candidates.any { it.thread == "Binder:1,io;worker" })
    }

    @Test
    fun v3pMarksAProcessEvenWhenItsChildBodyIsEmpty() {
        val payload = checkNotNull(HistoryFieldCodec.parseChildDetails("v3p:"))

        assertTrue(payload.processAggregate)
        assertTrue(payload.encodedNames)
        assertEquals("", payload.body)
        assertTrue(
            HistoryFieldCodec.isProcessAggregateRecord(
                "com.example",
                "com.example:worker",
                "v3p:"
            )
        )
    }

    @Test
    fun unmarkedSameNamedRowRemainsARealThread() {
        assertFalse(
            HistoryFieldCodec.isProcessAggregateRecord(
                "com.example",
                "com.example:worker",
                ""
            )
        )
    }

    @Test
    fun databaseImportPreservesV3pTypeMarker() {
        val parsed = DatabaseMigrator.parseThreadLine(
            "12.0 80.0 com.example:worker|1.0,2.0|v3p:"
        )

        assertEquals("v3p:", parsed?.details)
    }
}
