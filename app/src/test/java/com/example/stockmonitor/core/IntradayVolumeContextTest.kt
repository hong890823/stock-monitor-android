package com.example.stockmonitor.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradayVolumeContextTest {
    private fun bar(
        minute: Int,
        open: Double = 10.0,
        high: Double = 10.2,
        low: Double = 9.9,
        close: Double = 10.1,
        volume: Double = 100.0,
    ) = IntradayBar("2026-10-23 09:%02d:00".format(minute), open, high, low, close, volume)

    @Test
    fun marketRatioUsesPredictedFullDayOverLastFiveCompleteDaysMedian() {
        val context = IntradayVolumeContext.build(
            marketPredictedFullDayTurnover = 12.0,
            recentCompleteMarketTurnovers = listOf(20.0, 20.0, 10.0, 10.0, 10.0, 1.0),
            now = LocalDateTime.parse("2026-10-23T10:00:00"),
            quoteDate = "2026-10-23",
        )

        assertEquals(1.2, context.marketTurnoverRatio!!, 0.001)
        assertTrue(context.marketExpanding!!)
    }

    @Test
    fun absorptionRequiresLatestCompletedBarVolumeAndPriceShape() {
        val previous = (0 until 20).map {
            bar(it, open = 9.9, high = 10.1, low = 9.8, close = 9.85)
        }
        val current = bar(
            minute = 30,
            open = 10.0,
            high = 10.4,
            low = 8.0,
            close = 9.8,
            volume = 260.0,
        )
        val context = IntradayVolumeContext.build(
            stockVolumeRatio = 1.4,
            bars15 = previous + current,
            price = 9.7,
            support = 10.0,
            now = LocalDateTime.parse("2026-10-23T10:00:00"),
            quoteDate = "2026-10-23",
        )

        assertEquals(2.6, context.barVolumeRatio!!, 0.001)
        assertTrue(context.barVolumeConfirming!!)
        assertTrue(context.absorptionConfirmed)
    }

    @Test
    fun missingOrWeakCurrentBarVolumeRejectsAbsorption() {
        val previous = (0 until 20).map { bar(it) }
        val shape = bar(30, open = 10.0, high = 10.4, low = 8.0, close = 9.8, volume = 100.0)
        val weak = IntradayVolumeContext.build(
            stockVolumeRatio = 1.4,
            bars15 = previous + shape,
            price = 9.7,
            support = 10.0,
            now = LocalDateTime.parse("2026-10-23T10:00:00"),
            quoteDate = "2026-10-23",
        )
        val missing = IntradayVolumeContext.build(
            stockVolumeRatio = 1.4,
            bars15 = previous,
            price = 9.7,
            support = 10.0,
            now = LocalDateTime.parse("2026-10-23T10:00:00"),
            quoteDate = "2026-10-23",
        )

        assertFalse(weak.absorptionConfirmed)
        assertFalse(missing.barVolumeConfirming == true)
        assertFalse(missing.absorptionConfirmed)
    }

    @Test
    fun unfinishedBarCannotConfirmAbsorptionOrBlowoff() {
        val previous = (0 until 20).map {
            bar(it, open = 9.9, high = 10.1, low = 9.8, close = 9.85)
        }
        val current = bar(
            minute = 59,
            open = 10.0,
            high = 10.4,
            low = 8.0,
            close = 9.8,
            volume = 260.0,
        )
        val context = IntradayVolumeContext.build(
            stockVolumeRatio = 1.4,
            bars15 = previous + current,
            price = 9.7,
            support = 10.0,
            now = LocalDateTime.parse("2026-10-23T09:50:00"),
            quoteDate = "2026-10-23",
        )

        assertFalse(context.absorptionConfirmed)
        assertFalse(context.barVolumeConfirming == true)
    }

    @Test
    fun upperShadowRejectionIsBlowoffRiskNotBuyConfirmation() {
        val previous = (0 until 20).map { bar(it) }
        val current = bar(30, open = 10.0, high = 11.0, low = 10.0, close = 10.1, volume = 260.0)
        val context = IntradayVolumeContext.build(
            stockVolumeRatio = 1.6,
            priceChangePct = 2.0,
            bars15 = previous + current,
            now = LocalDateTime.parse("2026-10-23T10:00:00"),
            quoteDate = "2026-10-23",
        )

        assertTrue(context.blowoffRisk)
    }
}
