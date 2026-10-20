package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketEnvironmentQuality
import com.example.stockmonitor.analysis.MarketEnvironmentStatus
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
}
