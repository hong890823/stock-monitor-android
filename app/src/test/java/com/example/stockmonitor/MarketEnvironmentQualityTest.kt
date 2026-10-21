package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketEnvironmentQuality
import com.example.stockmonitor.analysis.MarketEnvironmentStatus
import com.example.stockmonitor.analysis.MarketTurnoverSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketEnvironmentQualityTest {
    private fun evaluate(
        industry: Double? = 0.72,
        high: Double? = 3210.0,
        history: List<Double> = listOf(17000.0, 18000.0, 19000.0),
    ) = MarketEnvironmentQuality.evaluate(22000.0, 1.2, 3200.0, high, industry, 0.68, history)

    @Test
    fun missingEvidenceIsUnknownNotObservedDivergence() {
        assertEquals(MarketEnvironmentStatus.UNKNOWN, evaluate(industry = null).status)
        assertEquals(MarketEnvironmentStatus.UNKNOWN, evaluate(high = null).status)
    }

    @Test
    fun invalidBreadthOrPriceCannotConfirmStrength() {
        for (invalid in listOf(-0.1, 1.2, 60.0, Double.NaN)) {
            assertEquals(MarketEnvironmentStatus.UNKNOWN, evaluate(industry = invalid).status)
        }
        assertEquals(MarketEnvironmentStatus.UNKNOWN, evaluate(high = 3100.0).status)
    }

    @Test
    fun historyGapsBreakPersistence() {
        val result = evaluate(history = listOf(16000.0, Double.NaN, 17000.0))
        assertEquals(1, result.persistenceDays)
        assertFalse(result.strong)
    }

    @Test
    fun evidenceLabelsTodayAsAnEstimate() {
        assertTrue(evaluate().summary.contains("此前完整收盘日连续3日"))
        assertTrue(evaluate().summary.contains("最近3日均值18000亿"))
        assertTrue(evaluate().summary.contains("今日预估单独观察"))

        val lowAverage = evaluate(history = listOf(17000.0, 17000.0, 17000.0))
        assertEquals(3, lowAverage.persistenceDays)
        assertEquals(17000.0, lowAverage.persistenceAverageYi!!, 0.001)
        assertEquals(MarketEnvironmentStatus.STRONG_SINGLE_DAY, lowAverage.status)
    }

    @Test
    fun adaptiveEnvironmentUsesSameTimeTurnoverRatioInsteadOfFixedYiAmounts() {
        val strong = MarketEnvironmentQuality.evaluateAdaptive(
            marketTurnoverRatio = 1.4,
            marketTurnoverSource = MarketTurnoverSource.SAME_TIME,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
        )
        val neutral = MarketEnvironmentQuality.evaluateAdaptive(
            marketTurnoverRatio = 0.9,
            marketTurnoverSource = MarketTurnoverSource.SAME_TIME,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
        )

        assertTrue(strong.strong)
        assertFalse(neutral.strong)
        assertFalse(neutral.cautious)
        assertTrue(strong.summary.contains("同期相对成交额"))
    }

    @Test
    fun fullDayFallbackStaysNeutralEvenAtLargeRatio() {
        val result = MarketEnvironmentQuality.evaluateAdaptive(
            marketTurnoverRatio = 4.0,
            marketTurnoverSource = MarketTurnoverSource.FULL_DAY_FALLBACK,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
        )

        assertEquals(MarketEnvironmentStatus.NORMAL, result.status)
        assertFalse(result.strong)
        assertFalse(result.cautious)
    }
}
