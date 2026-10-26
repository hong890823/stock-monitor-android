package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalQualityPolicyTest {

    @Test
    fun completeDailyWeeklyAndMonthlyEvidenceScores100() {
        val result = TechnicalQualityPolicy.score(
            dailyEvidenceRatio = 1.0,
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertEquals(100, result.score)
        assertEquals(40, result.dailyPoints)
        assertEquals(30, result.weeklyPoints)
        assertEquals(30, result.monthlyPoints)
    }

    @Test
    fun weeklyAndMonthlyTrendsChangeTechnicalScore() {
        val strong = TechnicalQualityPolicy.score(
            dailyEvidenceRatio = 1.0,
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )
        val weak = TechnicalQualityPolicy.score(
            dailyEvidenceRatio = 1.0,
            weeklyTrend = SwingTrend.WEAK,
            monthlyTrend = SwingTrend.WEAK,
        )

        assertTrue(strong.score > weak.score)
        assertEquals(40, weak.score)
    }

    @Test
    fun missingLongTrendDoesNotReceiveInventedPoints() {
        val result = TechnicalQualityPolicy.score(
            dailyEvidenceRatio = 1.0,
            weeklyTrend = SwingTrend.MISSING,
            monthlyTrend = SwingTrend.MISSING,
        )

        assertEquals(40, result.score)
    }

    @Test
    fun evaluatesCompleteWeeklyAndMonthlyBarsFromDailyHistory() {
        val bars = bullishBars(700)
        val result = TechnicalQualityPolicy.evaluateBars(
            history = bars,
            asOf = bars.last().date,
            dailyEvidenceRatio = 1.0,
        )

        assertEquals(SwingTrend.UP, result.weeklyTrend)
        assertEquals(SwingTrend.UP, result.monthlyTrend)
        assertTrue(result.score >= 70)
    }

    @Test
    fun trendWeakCapsTechnicalScore() {
        val result = TechnicalQualityPolicy.score(
            dailyEvidenceRatio = 1.0,
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
            trendWeak = true,
        )

        assertTrue(result.score <= 45)
    }

    private fun bullishBars(count: Int): List<DayBar> {
        val start = java.time.LocalDate.of(2023, 1, 2)
        var date = start
        return (0 until count).map { index ->
            val close = 10.0 + index * 0.02
            val bar = DayBar(
                date = date.toString(),
                open = close - 0.05,
                high = close + 0.10,
                low = close - 0.10,
                close = close,
                volume = 1_000.0 + index,
            )
            do {
                date = date.plusDays(1)
            } while (date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                date.dayOfWeek == java.time.DayOfWeek.SUNDAY
            )
            bar
        }
    }
}
