package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyPerformanceTest {
    @Test
    fun summaryCountsWinsAndExcludesOpenTradesFromWinRate() {
        val outcomes = listOf(
            PaperTradeOutcome(ObservationStrategy.PRE_BREAKOUT, "A", "2026-10-01", "2026-10-03", 100.0, 2.0),
            PaperTradeOutcome(ObservationStrategy.PRE_BREAKOUT, "B", "2026-10-02", "2026-10-04", -50.0, -1.0),
            PaperTradeOutcome(ObservationStrategy.LEADER, "C", "2026-10-02", "2026-10-05", 80.0, 4.0),
            PaperTradeOutcome(ObservationStrategy.LEADER, "D", "2026-10-03", null, 0.0, 0.0),
        )

        val summary = StrategyPerformance.summarize(outcomes, initialCapital = 10_000.0)

        assertEquals(2, summary[ObservationStrategy.PRE_BREAKOUT]!!.closedTrades)
        assertEquals(50.0, summary[ObservationStrategy.PRE_BREAKOUT]!!.winRatePct, 0.0001)
        assertEquals(50.0, summary[ObservationStrategy.PRE_BREAKOUT]!!.totalPnl, 0.0001)
        assertEquals(1, summary[ObservationStrategy.LEADER]!!.closedTrades)
        assertEquals(100.0, summary[ObservationStrategy.LEADER]!!.winRatePct, 0.0001)
        assertEquals(0.0, summary[ObservationStrategy.LEADER]!!.maxDrawdownPct, 0.0001)
    }
}
