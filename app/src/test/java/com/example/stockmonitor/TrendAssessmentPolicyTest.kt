package com.example.stockmonitor

import com.example.stockmonitor.analysis.HoldingTTrend
import com.example.stockmonitor.analysis.MajorTrend
import com.example.stockmonitor.analysis.ShortTermTrend
import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.TrendAssessmentPolicy
import com.example.stockmonitor.analysis.TrendEvidence
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAssessmentPolicyTest {
    private val risingLows = listOf(9.0, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 10.0)

    private fun evidence(
        shortTerm: HoldingTTrend = HoldingTTrend.UP,
        week: SwingTrend = SwingTrend.UP,
        month: SwingTrend = SwingTrend.UP,
        price: Double = 12.0,
        ma20: Double = 11.0,
        previousMa20: Double = 10.9,
        ma250: Double? = 10.0,
        lows: List<Double> = risingLows,
    ) = TrendEvidence(
        shortTerm = shortTerm,
        weekTrend = week,
        monthTrend = month,
        price = price,
        ma20 = ma20,
        previousMa20 = previousMa20,
        ma250 = ma250,
        completedLows = lows,
    )

    @Test
    fun mapsHoldingTrendToSharedShortTermTrend() {
        assertEquals(ShortTermTrend.UP, TrendAssessmentPolicy.evaluate(evidence()).shortTerm)
        assertEquals(
            ShortTermTrend.EXTENDED,
            TrendAssessmentPolicy.evaluate(evidence(shortTerm = HoldingTTrend.EXTENDED)).shortTerm,
        )
        assertEquals(
            ShortTermTrend.DOWN,
            TrendAssessmentPolicy.evaluate(evidence(shortTerm = HoldingTTrend.WEAK)).shortTerm,
        )
    }

    @Test
    fun strongMultiTimeframeTrendAllowsOrdinaryActions() {
        val result = TrendAssessmentPolicy.evaluate(evidence())

        assertEquals(MajorTrend.UP, result.major)
        assertTrue(result.allowsOrdinaryBuy)
        assertTrue(result.allowsAddPosition)
        assertTrue(result.allowsTBuy)
        assertTrue(result.allowsTSell)
        assertFalse(result.allowsBottomReversal)
    }

    @Test
    fun majorDownBlocksOrdinaryBuyAndTActions() {
        val result = TrendAssessmentPolicy.evaluate(
            evidence(
                shortTerm = HoldingTTrend.WEAK,
                week = SwingTrend.WEAK,
                month = SwingTrend.WEAK,
                price = 8.0,
                lows = listOf(12.0, 11.5, 11.0, 10.5, 10.0, 9.5, 9.0, 8.5, 8.0, 7.5, 7.0),
            ),
        )

        assertEquals(MajorTrend.DOWN, result.major)
        assertFalse(result.allowsOrdinaryBuy)
        assertFalse(result.allowsAddPosition)
        assertFalse(result.allowsTBuy)
        assertFalse(result.allowsTSell)
        assertFalse(result.allowsBottomReversal)
        assertTrue(result.riskOnly)
    }

    @Test
    fun majorTrendBottomingRequiresFiveDaysWithoutNewLow() {
        val stableLows = listOf(12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 7.1, 7.2, 7.3, 7.4, 7.5)
        val result = TrendAssessmentPolicy.evaluate(
            evidence(
                shortTerm = HoldingTTrend.REBOUND,
                week = SwingTrend.REPAIR,
                month = SwingTrend.WEAK,
                price = 8.2,
                ma20 = 8.0,
                previousMa20 = 7.95,
                ma250 = 9.0,
                lows = stableLows,
            ),
        )

        assertEquals(MajorTrend.BOTTOMING, result.major)
        assertTrue(result.allowsBottomReversal)
        assertFalse(result.allowsOrdinaryBuy)
        assertFalse(result.allowsAddPosition)
        assertFalse(result.allowsTBuy)
        assertFalse(result.allowsTSell)
    }

    @Test
    fun newLowInsideFiveDayWindowPreventsBottoming() {
        val delayedLow = listOf(12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 7.1, 7.2, 6.8, 7.0, 7.1)
        val result = TrendAssessmentPolicy.evaluate(
            evidence(
                shortTerm = HoldingTTrend.REBOUND,
                week = SwingTrend.REPAIR,
                month = SwingTrend.WEAK,
                price = 8.2,
                ma20 = 8.0,
                previousMa20 = 7.95,
                ma250 = 9.0,
                lows = delayedLow,
            ),
        )

        assertEquals(MajorTrend.REPAIR, result.major)
        assertFalse(result.allowsBottomReversal)
    }

    @Test
    fun missingMajorDataCannotAuthorizeTrades() {
        val result = TrendAssessmentPolicy.evaluate(evidence(ma250 = null))

        assertEquals(MajorTrend.MISSING, result.major)
        assertFalse(result.allowsOrdinaryBuy)
        assertFalse(result.allowsBottomReversal)
        assertFalse(result.riskOnly)
    }

    @Test
    fun evaluateBarsUsesOnlyCompletedDailyBarsForShortTrend() {
        val asOf = "2026-02-01"
        val start = java.time.LocalDate.parse("2026-01-01")
        val completed = (0 until 30).map { index ->
            val close = 10.0 + index * 0.1
            DayBar(
                date = start.plusDays(index.toLong()).toString(),
                open = close - 0.05,
                high = close + 0.1,
                low = close - 0.1,
                close = close,
                volume = 1_000.0 + index,
            )
        }
        val live = completed.last().copy(
            date = asOf,
            open = 14.0,
            high = 14.0,
            low = 1.0,
            close = 1.0,
            volume = 10_000.0,
        )

        val result = TrendAssessmentPolicy.evaluateBars(completed + live, asOf)

        assertEquals(ShortTermTrend.UP, result.shortTerm)
        assertEquals(MajorTrend.MISSING, result.major)
        assertFalse(result.allowsOrdinaryBuy)
    }
}
