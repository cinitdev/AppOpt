package top.qixia.threads

import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryMetricSamplesTest {
    @Test fun chartCursorKeepsEveryMetricAcrossAnHourWithoutUsingReducedVertices() {
        val duration = 3_600_000L
        val values = mapOf("cpu_core.7" to 78f, "cpu_cycles_core.7" to 1600f, "cpu_c" to 65f,
            "battery_c" to 39f, "battery_ma" to 1500f, "battery_pct" to 72f, "ddr_mhz" to 1600f)
        val report = HistoryMetricsStore.summarize((1..1800).map {
            HistoryMetrics.Sample(it * 2000L, false, values)
        }, 0, duration)
        val progress = 1_606_000f / duration
        values.forEach { (key, value) ->
            assertFalse(report.series.getValue(key).points.any { abs(it.progress - progress) * duration < 1500 })
            assertEquals(value, report.analysisSamples!!.pointAt(key, progress)!!.value)
        }
        assertEquals(78f, report.analysisSamples!!.pointAt("cpu_core.7", 0f)!!.value)
        assertEquals(78f, report.analysisSamples!!.pointAt("cpu_core.7", 1f)!!.value)
    }

    @Test fun chartCursorDoesNotFillMissingFieldsChargingOrLongSamplingGaps() {
        val builder = HistoryMetricSamples.Builder(0, 20_000)
        builder.add(HistoryMetrics.Sample(2000, false, mapOf("cpu_core.0" to 20f, "power_w" to 4f)))
        builder.add(HistoryMetrics.Sample(4000, true, mapOf("power_w" to 4f)))
        builder.add(HistoryMetrics.Sample(14000, false, mapOf("cpu_core.0" to 40f)))
        val samples = builder.build()!!
        assertNull(samples.pointAt("power_w", .2f))
        assertNull(samples.pointAt("cpu_core.0", .2f))
        assertNull(samples.pointAt("cpu_core.0", .45f))
        // 真实采样就在 14 秒，前面的空洞不能遮掉邻近读数。
        assertEquals(40f, samples.pointAt("cpu_core.0", .699f)!!.value)
    }
    @get:Rule val temp = TemporaryFolder()
    private val start = 1_791_000_000_000L
    private val duration = 2_040_000L
    private val selected = 1_606_000L
    private fun values(index: Int) = mapOf("cpu_usage" to index / 20f, "gpu_usage" to 0f,
        "gpu_mhz" to 300f + index, "cpu_mhz.0_1_2" to 500f + index,
        "cpu_mhz.3_4_5_6" to 1500f + index, "cpu_mhz.7" to 2000f + index, "power_w" to 3f + index / 1000f)

    private fun assertOriginalReadings(report: HistoryMetrics.Report) {
        val progress = selected.toFloat() / duration
        // 26:46 的连续原始样本没有留在绘图数组里，复现原来的误报条件。
        assertTrue(report.series.getValue("cpu_usage").points.all {
            abs(it.progress - progress) * duration > 1500
        })
        assertTrue(report.series.values.all { it.points.size <= 240 })
        assertEquals(values(803), report.analysisSamples!!.at(progress))
    }

    @Test fun longAutomaticCaptureReadsUnreducedMetricsFromBothPlainAndGzipArchives() {
        val text = buildString {
            append("QIXIA_AUTO_HISTORY\t1\nsource\tauto\npackage\tapp.one\nstart_ms\t$start\n")
            for (index in 1..1020) {
                val time = start + index * 2000L
                append("M\t$time\t0")
                values(index).forEach { (key, value) -> append("\t$key=$value") }
                append("\nF\t$time\t120\t16.7\n")
            }
            append("end_ms\t${start + duration}\nduration_ms\t$duration\nsamples\t1020\n")
        }
        val plain = File(temp.root, "auto_${start}_12.log").apply { writeText(text) }
        val gzip = File(temp.root, "${plain.name}.gz").apply {
            GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) }
        }
        val original = AutoHistoryRecord.read(plain)!!.metrics!!
        val compressed = AutoHistoryRecord.read(gzip)!!.metrics!!
        assertOriginalReadings(original)
        assertOriginalReadings(compressed)
        assertEquals(original, compressed)
        assertEquals(text, plain.readText())
    }

    @Test fun longCalibrationUsesTheSameOriginalSamplesAcrossForegroundFragments() {
        for (indices in listOf(1..520, 520..1020)) {
            HistoryMetricsStore.Writer(temp.root, "app.one", start + indices.first * 2000L,
                captureId = "long-calibration").use { writer ->
                indices.forEach { index -> writer.append(HistoryMetrics.Sample(start + index * 2000L, false, values(index))) }
            }
        }
        val report = HistoryMetricsStore.reports(temp.root, "app.one", listOf((start + duration) / 1000 to duration)).values.single()
        assertEquals(1020, report.samples)
        assertOriginalReadings(report)
        assertEquals(values(520), report.analysisSamples!!.at(1_040_000f / duration))
    }

    @Test fun lookupKeepsRealGapsWindowEdgesAndMissingFieldsInsteadOfBorrowingValues() {
        val builder = HistoryMetricSamples.Builder(start, 10_000)
        listOf(-2000L, 2000L, 4000L, 8000L, 12_000L).forEach { offset ->
            builder.add(HistoryMetrics.Sample(start + offset, false, buildMap {
                put("cpu_usage", offset / 1000f)
                if (offset != 4000L) put("gpu_usage", 0f)
            }))
        }
        val samples = builder.build()!!
        assertNull(samples.at(0f))
        assertNull(samples.at(1f))
        assertNull(samples.at(.5f))
        assertNull(samples.at(.7f))
        assertNull(samples.at(Float.NaN))
        assertNull(samples.at(-.1f))
        assertNull(samples.at(1.1f))
        assertEquals(mapOf("cpu_usage" to 4f), samples.at(.4f))
        assertEquals(mapOf("cpu_usage" to 8f, "gpu_usage" to 0f), samples.at(.8f))
        assertEquals(mapOf("cpu_usage" to 2f, "gpu_usage" to 0f), samples.at(.2f))
    }

    @Test fun chargingAndInvalidReadingsCannotBeFilledByNeighboringSamples() {
        val builder = HistoryMetricSamples.Builder(start, 6000)
        listOf(false, true, null, false).forEachIndexed { index, charging ->
            builder.add(HistoryMetrics.Sample(start + index * 2000, charging,
                mapOf("power_w" to 4f, "gpu_usage" to if (index == 2) Float.NaN else 0f)))
        }
        val samples = builder.build()!!
        assertEquals(mapOf("power_w" to 4f, "gpu_usage" to 0f), samples.at(0f))
        assertEquals(mapOf("gpu_usage" to 0f), samples.at(1f / 3))
        assertTrue(samples.at(2f / 3)!!.isEmpty())
        assertEquals(mapOf("power_w" to 4f, "gpu_usage" to 0f), samples.at(1f))
    }

    @Test fun reportWindowsDoNotShareTimestampsOrMutatePublishedSamples() {
        val first = HistoryMetricSamples.Builder(start, 4000)
        val second = HistoryMetricSamples.Builder(start + 4000, 4000)
        for (offset in listOf(2000L, 6000L)) {
            val sample = HistoryMetrics.Sample(start + offset, false, mapOf("cpu_usage" to offset / 1000f))
            first.add(sample); second.add(sample)
        }
        val published = first.build()!!
        first.add(HistoryMetrics.Sample(start + 4000, false, mapOf("cpu_usage" to 4f)))
        assertEquals(mapOf("cpu_usage" to 2f), published.at(.5f))
        assertNull(published.at(1f))
        assertEquals(mapOf("cpu_usage" to 6f), second.build()!!.at(.5f))
    }
}
