package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class HistoryChartPaletteTest {
    @Test fun powerCurrentAndBatteryRemainDistinctWhenShownTogether() {
        val keys = listOf("power_w", "battery_ma", "battery_pct")
        assertEquals(3, keys.map { HistoryChartPalette.metric(it) }.toSet().size)
        keys.reversed().forEachIndexed { index, key ->
            assertEquals(HistoryChartPalette.metric(key), HistoryChartPalette.metric(key, index))
        }
    }

    @Test fun ungroupedFallbackKeepsTenCoreColorsDistinctFromTotal() {
        val keys = listOf("cpu_usage") + (0..9).map { "cpu_core.$it" }
        assertEquals(11, keys.map { HistoryChartPalette.metric(it) }.toSet().size)
    }

    @Test fun colorsRemainUniqueBeyondTheCuratedPalette() {
        val colors = (0..1023).map { HistoryChartPalette.core(it) }
        assertEquals(1024, colors.toSet().size)
        val metrics = listOf("fps", "cpu_usage", "gpu_usage", "cpu_c", "battery_used_pct")
            .map { HistoryChartPalette.metric(it) }
        assertEquals(5, metrics.toSet().size)
        assertTrue(colors.toSet().intersect(metrics.toSet()).isEmpty())
        assertTrue(colors.all { it ushr 24 == 255 })
    }

    @Test fun coreIdentitySurvivesFilteringReorderingAndDifferentCharts() {
        val original = (0..31).associateWith { HistoryChartPalette.metric("cpu_core.$it", it + 1) }
        listOf(31, 17, 9, 4, 0).forEachIndexed { index, cpu ->
            assertEquals(original[cpu], HistoryChartPalette.metric("cpu_core.$cpu", index))
            assertEquals(original[cpu], HistoryChartPalette.metric("cpu_cycles_core.$cpu", index + 3))
        }
        assertEquals(original[8], HistoryChartPalette.metric("cpu_mhz.8_9"))
        assertEquals(original[8], HistoryChartPalette.metric("cpu_mhz.9_8"))
    }

    @Test fun coreLabelsStayReadableOnWhite() {
        for (cpu in 0..15) {
            val color = HistoryChartPalette.core(cpu)
            fun linear(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            val luminance = .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
            assertTrue("CPU $cpu contrast", 1.05 / (luminance + .05) >= 4.5)
        }
    }
}
