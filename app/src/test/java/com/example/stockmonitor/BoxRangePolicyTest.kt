package com.example.stockmonitor

import com.example.stockmonitor.analysis.BoxRangePolicy
import com.example.stockmonitor.analysis.BoxRangeState
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BoxRangePolicyTest {
    @Test fun identifiesStableBoxAndVolumeConfirmedBreakouts() {
        val bars = boxBars()

        val range = BoxRangePolicy.evaluate(bars, "2026-02-10", currentPrice = 10.5)
        val breakout = BoxRangePolicy.evaluate(bars, "2026-02-10", currentPrice = 11.1, currentVolumeRatio = 1.4)
        val candidate = BoxRangePolicy.evaluate(bars, "2026-02-10", currentPrice = 11.1, currentVolumeRatio = 1.0)

        assertEquals(BoxRangeState.BOX_RANGE, range.state)
        assertEquals(BoxRangeState.BREAKOUT_CONFIRMED, breakout.state)
        assertEquals(BoxRangeState.BREAKOUT_CANDIDATE, candidate.state)
        assertEquals(11.0, range.upper!!, 0.001)
        assertEquals(10.0, range.lower!!, 0.001)
    }

    @Test fun identifiesBreakdownAndRejectsWideRanges() {
        val bars = boxBars()
        val breakdown = BoxRangePolicy.evaluate(bars, "2026-02-10", currentPrice = 9.9, currentVolumeRatio = 1.3)
        val wide = BoxRangePolicy.evaluate(bars.map { it.copy(high = it.high + 2.0) }, "2026-02-10")

        assertEquals(BoxRangeState.BREAKDOWN_CONFIRMED, breakdown.state)
        assertEquals(BoxRangeState.NOT_BOX, wide.state)
        assertTrue(wide.reason.contains("宽度过大"))
    }

    @Test fun reportsMissingDataWithoutGuessing() {
        val result = BoxRangePolicy.evaluate(boxBars().take(10), "2026-02-10")

        assertEquals(BoxRangeState.MISSING, result.state)
        assertTrue(result.reason.contains("不足30根"))
    }

    private fun boxBars(): List<DayBar> {
        val start = LocalDate.parse("2026-01-01")
        return List(30) { index ->
            val date = start.plusDays(index.toLong()).toString()
            DayBar(date, 10.4, 11.0, 10.0, 10.5, 1_000_000.0)
        }
    }
}
