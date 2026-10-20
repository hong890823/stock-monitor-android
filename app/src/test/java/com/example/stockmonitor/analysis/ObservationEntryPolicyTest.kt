package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Quote
import org.junit.Assert.*
import org.junit.Test

class ObservationEntryPolicyTest {
    private val pullback = EntrySetup(
        EntrySetupType.M20_PULLBACK, true, true, false, 12.0, 1.0, true,
        MarketFilter.NEUTRAL, 9.5, "回踩企稳",
    )

    @Test
    fun strengthAloneOrBreakoutCannotBecomeLeaderBuyPoint() {
        assertFalse(ObservationEntryPolicy.leaderCanBuy(2, 6.0, 1.6, 10.0, null))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(2, 6.0, 0.6, 10.0,
            pullback.copy(type = EntrySetupType.BREAKOUT)))
    }

    @Test
    fun leaderRequiresQualifiedStrengthAndConfirmedLowVolumePullback() {
        assertTrue(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 0.6, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(0, 4.0, 0.6, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, null, 0.6, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 2.0, 0.6, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, null, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 1.6, 10.0, pullback))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 0.6, 10.0, pullback.copy(confirmed = false)))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 0.6, 10.0, pullback.copy(trendWeak = true)))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 0.6, 10.0,
            pullback.copy(marketFilter = MarketFilter.HEADWIND)))
        assertFalse(ObservationEntryPolicy.leaderCanBuy(1, 4.0, 0.6, 10.0, pullback.copy(stopPrice = 10.5)))
    }

    @Test
    fun liveBarUsesRawVolumeAndCurrentDayRangeOnly() {
        val yesterday = DayBar("2026-10-14", 10.0, 15.0, 8.0, 10.0, 1000.0)
        val quote = Quote("sh600000", "测试", 10.2, 10.0, 10.1, 10.3, 10.0, 100.0, 1020.0, "2026-10-15")
        val staleToday = yesterday.copy(date = "2026-10-15")
        val bars = ObservationEntryPolicy.withLiveBar(listOf(yesterday, staleToday), quote)
        assertEquals(2, bars.size)
        assertEquals(yesterday, bars.first())
        assertEquals(DayBar("2026-10-15", 10.1, 10.3, 10.0, 10.2, 100.0), bars.last())
    }
}
