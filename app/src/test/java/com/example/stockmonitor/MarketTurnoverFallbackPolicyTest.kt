package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketTurnoverFallbackPolicy
import com.example.stockmonitor.analysis.MarketTurnoverSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketTurnoverFallbackPolicyTest {
    @Test
    fun fallbackNeedsAtLeastThreeRecentFullDaySamples() {
        assertNull(MarketTurnoverFallbackPolicy.evaluate(24_000.0, listOf(18_000.0, 17_000.0)))
    }

    @Test
    fun fallbackDiscardsAbnormalSamplesInsteadOfUsingThem() {
        val result = MarketTurnoverFallbackPolicy.evaluate(
            predictedTurnoverYi = 24_000.0,
            recentTurnoversYi = listOf(18_000.0, Double.NaN, 0.0, 16_000.0),
        )

        assertEquals(1.4117647, result!!.ratio, 0.0001)
        assertEquals(MarketTurnoverSource.FULL_DAY_FALLBACK, result.source)
    }

    @Test
    fun abnormalForecastCannotCreateFallbackRatio() {
        assertNull(
            MarketTurnoverFallbackPolicy.evaluate(
                predictedTurnoverYi = Double.NaN,
                recentTurnoversYi = listOf(18_000.0, 17_000.0, 16_000.0),
            ),
        )
        assertNull(
            MarketTurnoverFallbackPolicy.evaluate(
                predictedTurnoverYi = 0.0,
                recentTurnoversYi = listOf(18_000.0, 17_000.0, 16_000.0),
            ),
        )
    }
}
