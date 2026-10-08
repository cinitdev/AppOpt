package top.qixia.threads

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryMetricsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fragment(id: String?, vararg times: Long, value: Float = 2f) {
        HistoryMetricsStore.Writer(temp.root, "app.one", times.first(), captureId = id).use { writer ->
            times.forEach { writer.append(HistoryMetrics.Sample(it, false, mapOf("power_w" to value))) }
        }
    }

    @Test fun sameCalibrationFragmentsMergeWithBoundaryDeduplicationAndRealGaps() {
        fragment("one-calibration", 2000, 4000, value = 2f)
        fragment("one-calibration", 4000, 8000, 10000, value = 2f)
        val report = HistoryMetricsStore.reports(temp.root, "app.one", listOf(10L to 10000L)).getValue(10)
        assertEquals(4, report.samples)
        assertEquals(2f, report.series.getValue("power_w").average, .001f)
        assertEquals(listOf(.2f, .4f, .8f, 1f), report.series.getValue("power_w").points.map { it.progress })
        assertTrue(report.series.getValue("power_w").points[2].breakBefore)
    }

    @Test fun overlappingDifferentCapturesAreNeverCombinedAndEachWindowIsClipped() {
        fragment("first-launch", 2000, 4000, value = 2f)
        fragment("first-launch", 6000, value = 2f)
        fragment("second-launch", 4000, 6000, value = 20f)
        val reports = HistoryMetricsStore.reports(temp.root, "app.one", listOf(6L to 6000L, 3L to 2000L))
        assertEquals(3, reports.getValue(6).samples)
        assertEquals(2f, reports.getValue(6).series.getValue("power_w").average, .001f)
        assertEquals(1, reports.getValue(3).samples)
    }

    @Test fun legacyFilesRemainIsolatedWhenCaptureOwnershipCannotBeProved() {
        fragment(null, 2000, 4000, value = 2f)
        fragment(null, 6000, value = 20f)
        val report = HistoryMetricsStore.reports(temp.root, "app.one", listOf(6L to 6000L)).getValue(6)
        assertEquals(2, report.samples)
        assertEquals(2, temp.root.resolve("history_metrics").listFiles()!!.size)
    }

    @Test fun longMergedCalibrationKeepsExactStatisticsWithBoundedPointsAndFrequencyBins() {
        val accumulator = HistoryMetricsAccumulator(0, 40000000)
        repeat(20000) { index -> accumulator.add(HistoryMetrics.Sample((index + 1) * 2000L, false,
            mapOf("power_w" to if (index == 19000) 19f else 3f, "cpu_mhz.0" to (1000f + index / 20f)))) }
        val report = accumulator.report(emptyMap())
        assertEquals(20000, report.samples)
        assertEquals(3.0008f, report.series.getValue("power_w").average, .0001f)
        assertEquals(19f, report.series.getValue("power_w").maximum, .001f)
        assertTrue(report.series.values.all { it.points.size <= 240 })
        assertTrue(report.distributions.getValue("cpu_mhz.0").size <= 32)
        assertEquals(100f, report.distributions.getValue("cpu_mhz.0").sumOf { it.percent.toDouble() }.toFloat(), .01f)
    }

    @Test fun metricsRequireTheCurrentProtocolAndKeepUnsupportedFilesUntouched() {
        HistoryMetricsStore.Writer(temp.root, "app.one", 1000).use { writer ->
            writer.append(HistoryMetrics.Sample(2000, false, mapOf("power_w" to 2f)))
        }
        val file = File(temp.root, "history_metrics").listFiles()!!.single()
        assertEquals(1, HistoryMetricsStore.read(file, "app.one").size)
        val foreign = file.readText().replace("# QixiaThreads metrics v1", "# Other metrics v1")
        file.writeText(foreign)
        assertTrue(HistoryMetricsStore.read(file, "app.one").isEmpty())
        assertEquals(foreign, file.readText())
    }

    @Test fun deletingHistoryPreservesActiveAndSharedMetricFragments() {
        val writer = HistoryMetricsStore.Writer(temp.root, "app.one", 1000)
        writer.append(HistoryMetrics.Sample(2000, false, mapOf("power_w" to 2f)))
        writer.append(HistoryMetrics.Sample(4000, false, mapOf("power_w" to 3f)))
        val folder = File(temp.root, "history_metrics")
        HistoryMetricsStore.removeSessions(temp.root, "app.one", listOf(4L to 4000L), emptyList())
        assertEquals(1, folder.listFiles()!!.size)
        assertTrue(writer.append(HistoryMetrics.Sample(6000, false, mapOf("power_w" to 4f))))
        writer.close()
        HistoryMetricsStore.removeSessions(temp.root, "app.one", listOf(4L to 4000L), listOf(6L to 2000L))
        assertEquals(1, folder.listFiles()!!.size)
        HistoryMetricsStore.removeSessions(temp.root, "app.one", listOf(6L to 2000L), emptyList())
        assertEquals(0, folder.listFiles()!!.size)
    }

    @Test fun oneCalibrationCanKeepMoreThanTenForegroundFragments() {
        repeat(12) { index ->
            HistoryMetricsStore.Writer(temp.root, "app.one", 1000L + index * 2000L).use { writer ->
                writer.append(HistoryMetrics.Sample(2000L + index * 2000L, false, mapOf("power_w" to 2f)))
            }
        }
        HistoryMetricsStore.pruneHistory(temp.root)
        assertEquals(12, File(temp.root, "history_metrics").listFiles()!!.size)
    }

    @Test fun acceptsIndependentRustValuesWithoutRecalculatingOrBorrowingTotals() {
        val sample = HistoryMetrics.decode(mapOf("charging" to "0", "cpu_usage" to "40", "cpu_core.0" to "0",
            "cpu_core.7" to "100", "cpu_mhz.6_7" to "3187.2", "gpu_usage" to "42", "power_w" to "4"), 1000)
        assertEquals(0f, sample.values.getValue("cpu_core.0"), .001f)
        assertEquals(100f, sample.values.getValue("cpu_core.7"), .001f)
        assertEquals(3187.2f, sample.values.getValue("cpu_mhz.6_7"), .001f)
        assertEquals(4f, sample.values.getValue("power_w"), .001f)
        assertEquals(1000L, sample.timestampMs)
    }

    @Test fun chargingOrUnknownStatusCannotPresentBatteryCurrentAsDevicePower() {
        for (status in listOf("1", "?", "", "invalid")) {
            val sample = HistoryMetrics.decode(mapOf("charging" to status, "power_w" to "4", "battery_ma" to "1000", "battery_pct" to "70"), 1)
            assertNull(sample.values["power_w"])
            assertNull(sample.values["battery_ma"])
            assertEquals(70f, sample.values.getValue("battery_pct"), .001f)
        }
    }

    @Test fun invalidUnknownAndLegacyRawValuesAreRejected() {
        val sample = HistoryMetrics.decode(mapOf("cpu_core.0" to "101", "gpu_mhz" to "NaN",
            "battery_c" to "Infinity", "cpu_c" to "-274", "cpu.policy0" to "1000000", "cycles.0" to "200"), 1)
        assertTrue(sample.values.isEmpty())
    }

    @Test fun modeIsNotStoredOrImportedFromLegacyMetadata() {
        val writer = HistoryMetricsStore.Writer(temp.root, "app.one", 1000, mapOf("profile" to "old mode", "model" to "Device"))
        writer.append(HistoryMetrics.Sample(2000, null, mapOf("cpu_usage" to 10f)))
        val file = File(temp.root, "history_metrics").listFiles()!!.single()
        assertFalse(file.readText().contains("profile"))
        file.writeText(file.readText().replace("# device.model=Device", "# device.model=Device\n# device.profile=old mode"))
        val report = HistoryMetricsStore.reports(temp.root, "app.one", listOf(3L to 2000L)).getValue(3)
        assertEquals("Device", report.device["model"])
        assertNull(report.device["profile"])
    }

    @Test fun historySlicesOnlyMatchingPackageAndTimeWindow() {
        val root = temp.root
        val writer = HistoryMetricsStore.Writer(root, "app.one", 1000)
        listOf(2000L, 4000L, 6000L, 8000L).forEach { writer.append(HistoryMetrics.Sample(it, false, mapOf("power_w" to it / 1000f))) }
        val report = HistoryMetricsStore.reports(root, "app.one", listOf(6L to 3000L)).getValue(6)
        assertEquals(5f, report.series.getValue("power_w").average, .01f)
        assertEquals(2, report.samples)
        assertTrue(HistoryMetricsStore.reports(root, "app.two", listOf(6L to 3000L)).isEmpty())
        assertTrue(HistoryMetricsStore.reports(root, "app.one", listOf(20L to 3000L)).isEmpty())
    }

    @Test fun missingAndChargingSamplesLeaveGapsAndDoNotLowerAverage() {
        val samples = listOf(HistoryMetrics.Sample(2000, false, mapOf("power_w" to 4f)),
            HistoryMetrics.Sample(4000, true, emptyMap()), HistoryMetrics.Sample(6000, false, mapOf("power_w" to 6f)))
        val report = HistoryMetricsStore.summarize(samples, 0, 6000)
        val series = report.series.getValue("power_w")
        assertEquals(5f, series.average, .01f)
        assertTrue(series.points.last().breakBefore)
        assertEquals(1, report.chargingSamples)
    }

    @Test fun longSeriesPreservesLatePeaksAndMissingSpans() {
        val samples = (1..10000).filter { it !in 8100..8110 }.map { i ->
            HistoryMetrics.Sample(i * 2000L, false, mapOf("power_w" to if (i == 9500) 19f else 3f)) }
        val series = HistoryMetricsStore.summarize(samples, 0, 20000000).series.getValue("power_w")
        assertTrue(series.points.size <= 240)
        assertEquals(19f, series.points.maxOf { it.value }, .01f)
        assertTrue(series.points.any { it.breakBefore })
        assertEquals(1f, series.points.last().progress, .001f)
    }

    @Test fun rejectsDuplicatesAndCorruptRowsButRecoversInterruptedFile() {
        val writer = HistoryMetricsStore.Writer(temp.root, "app.one", 1000)
        assertTrue(writer.append(HistoryMetrics.Sample(2000, false, mapOf("power_w" to 2f))))
        assertFalse(writer.append(HistoryMetrics.Sample(2000, false, mapOf("power_w" to 9f))))
        val file = File(temp.root, "history_metrics").listFiles()!!.single()
        file.appendText("3000\t0\tpower_w=NaN\n4000\t1\tpower_w=7\nnot-a-timestamp")
        val samples = HistoryMetricsStore.read(file, "app.one")
        assertEquals(3, samples.size)
        assertNull(samples.last().values["power_w"])
        assertTrue(HistoryMetricsStore.read(file, "different.package").isEmpty())
    }

    @Test fun retainsAtMostThirtyRunsPerPackage() {
        repeat(35) { i -> HistoryMetricsStore.Writer(temp.root, "app.one", i.toLong()).use { writer ->
            writer.append(HistoryMetrics.Sample(i + 1L, false, mapOf("power_w" to 2f)))
        } }
        assertEquals(30, File(temp.root, "history_metrics").listFiles()!!.size)
    }
}
