package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstBoardReclaimTrackerTest {
    private val tracker = FirstBoardReclaimTracker()

    @Test
    fun emitsWatchAfterOpeningPriceReclaimedAndLowHoldsForFiveMinutes() {
        assertNull(tracker.update(sample(timestamp = 1_000L, low = 9.70, price = 9.80)))
        assertNull(tracker.update(sample(timestamp = 2_000L, low = 9.70, price = 10.00)))

        val alert = tracker.update(sample(timestamp = 302_000L, low = 9.70, price = 10.02))

        assertEquals("首板分歧转强观察", alert?.title)
        assertEquals("sh600000", alert?.code)
    }

    @Test
    fun restartsStabilityWindowWhenPriceMakesANewLow() {
        tracker.update(sample(timestamp = 1_000L, low = 9.70, price = 9.80))
        tracker.update(sample(timestamp = 2_000L, low = 9.70, price = 10.00))
        tracker.update(sample(timestamp = 250_000L, low = 9.65, price = 9.90))

        assertNull(tracker.update(sample(timestamp = 400_000L, low = 9.65, price = 10.01)))
        assertEquals(
            "首板分歧转强观察",
            tracker.update(sample(timestamp = 551_000L, low = 9.65, price = 10.01))?.title,
        )
    }

    @Test
    fun ignoresStaleCandidatesAndSealedLimitUpQuotes() {
        assertNull(tracker.update(sample(timestamp = 1_000L, low = 9.70, price = 9.80, recentFirstBoard = false)))
        assertNull(tracker.update(sample(timestamp = 2_000L, low = 9.70, price = 10.00, limitUp = true)))
    }

    @Test
    fun emitsAtMostOneAlertPerCandidatePerDay() {
        tracker.update(sample(timestamp = 1_000L, low = 9.70, price = 9.80))
        tracker.update(sample(timestamp = 2_000L, low = 9.70, price = 10.00))
        tracker.update(sample(timestamp = 302_000L, low = 9.70, price = 10.02))
        tracker.update(sample(timestamp = 400_000L, low = 9.60, price = 9.90))

        assertNull(tracker.update(sample(timestamp = 701_000L, low = 9.60, price = 10.01)))
    }

    private fun sample(
        timestamp: Long,
        low: Double,
        price: Double,
        tradingDay: String = "2026-11-19",
        recentFirstBoard: Boolean = true,
        limitUp: Boolean = false,
    ) = FirstBoardReclaimTracker.Sample(
        code = "sh600000",
        name = "测试股",
        tradingDay = tradingDay,
        previousClose = 10.0,
        open = 9.80,
        low = low,
        price = price,
        timestamp = timestamp,
        recentFirstBoard = recentFirstBoard,
        limitUp = limitUp,
    )
}
