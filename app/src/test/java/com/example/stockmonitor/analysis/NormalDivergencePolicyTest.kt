package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalDivergencePolicyTest {
    @Test
    fun highPositionWithVolumeAndMa5HoldIsNormalDivergence() {
        val result = NormalDivergencePolicy.evaluateDaily(
            NormalDivergenceDailyInput(
                price = 110.0,
                ma5 = 100.0,
                ma20 = 90.0,
                priceLocation = PriceLocation.HIGH,
                atrPct = 2.0,
                volumeRatio = 1.5,
                priceChangePct = 2.0,
            ),
        )

        assertEquals(NormalDivergenceLevel.NORMAL, result.level)
        assertEquals(NormalDivergenceLine.MA5, result.line)
        assertTrue(result.reason.contains("MA5"))
    }

    @Test
    fun highPositionWithoutVolumeConfirmationStaysPending() {
        val result = NormalDivergencePolicy.evaluateDaily(
            NormalDivergenceDailyInput(
                price = 110.0,
                ma5 = 100.0,
                ma20 = 90.0,
                priceLocation = PriceLocation.HIGH,
                atrPct = 2.0,
                volumeRatio = 1.0,
                priceChangePct = 2.0,
            ),
        )

        assertEquals(NormalDivergenceLevel.PENDING_BREAK, result.level)
    }

    @Test
    fun midLowPositionUsesMa20AndAllowsQuietPullback() {
        val result = NormalDivergencePolicy.evaluateDaily(
            NormalDivergenceDailyInput(
                price = 20.0,
                ma5 = 20.5,
                ma20 = 19.0,
                priceLocation = PriceLocation.MID,
                atrPct = 2.0,
                volumeRatio = 0.7,
                priceChangePct = -0.3,
            ),
        )

        assertEquals(NormalDivergenceLevel.NORMAL, result.level)
        assertEquals(NormalDivergenceLine.MA20, result.line)
    }

    @Test
    fun intradayRequiresConfirmedBreakBeforeChangingLevel() {
        val pending = NormalDivergencePolicy.evaluateIntraday(
            NormalDivergenceIntradayInput(
                price = 9.6,
                ma5 = 10.0,
                ma20 = 9.0,
                priceLocation = PriceLocation.MID,
                volumeRatio = 1.1,
                priceChangePct = -0.5,
                riskLineThresholdPct = 0.005,
                riskLineConfirmations = 1,
                hasCompleted15m = true,
                hasCompleted30m = true,
            ),
        )
        val confirmed = NormalDivergencePolicy.evaluateIntraday(
            NormalDivergenceIntradayInput(
                price = 9.6,
                ma5 = 10.0,
                ma20 = 9.0,
                priceLocation = PriceLocation.MID,
                volumeRatio = 1.1,
                priceChangePct = -0.5,
                riskLineThresholdPct = 0.005,
                riskLineConfirmations = 3,
                riskLineM30Below = true,
                hasCompleted15m = true,
                hasCompleted30m = true,
            ),
        )

        assertEquals(NormalDivergenceLevel.PENDING_BREAK, pending.level)
        assertEquals(NormalDivergenceLevel.CONFIRMED_BREAK, confirmed.level)
    }

    @Test
    fun missingCompletedTimeframesDoNotGuessBreak() {
        val result = NormalDivergencePolicy.evaluateIntraday(
            NormalDivergenceIntradayInput(
                price = 9.6,
                ma5 = 10.0,
                ma20 = 9.0,
                hasCompleted15m = true,
                hasCompleted30m = false,
            ),
        )

        assertEquals(NormalDivergenceLevel.MISSING, result.level)
    }
}
