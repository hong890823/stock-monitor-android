package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationSnapshot
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperTradeTrackerTest {
    private fun snapshot(
        date: String,
        stage: ObservationStage,
        price: Double,
        stopPrice: Double? = 9.0,
    ) = ObservationSnapshot(
        code = "sh600000",
        name = "测试股",
        date = date,
        stage = stage,
        strategy = ObservationStrategy.PRE_BREAKOUT,
        score = 3,
        price = price,
        changePct = 0.0,
        weakDays = 0,
        observedDays = 1,
        stopPrice = stopPrice,
        reason = "测试",
    )

    @Test
    fun candidateOpensOnePaperTradeAndEliminationClosesIt() {
        val history = listOf(
            snapshot("2026-10-14", ObservationStage.BUY_CANDIDATE, 10.0, stopPrice = null),
            snapshot("2026-10-15", ObservationStage.ELIMINATED, 9.0, stopPrice = null),
        )

        val result = PaperTradeTracker.reconcile(emptyList(), history)

        assertEquals(1, result.size)
        assertEquals("2026-10-14", result.single().entryDate)
        assertEquals("2026-10-15", result.single().exitDate)
        assertEquals("淘汰", result.single().exitReason)
        assertEquals(100, result.single().quantity)
        assertEquals(true, result.single().netPnl!! < 0.0)
    }

    @Test
    fun reconciliationIsIdempotentAndStopLossClosesBeforeElimination() {
        val history = listOf(
            snapshot("2026-10-14", ObservationStage.BUY_CANDIDATE, 10.0, stopPrice = 9.5),
            snapshot("2026-10-15", ObservationStage.NEAR_ENTRY, 9.4, stopPrice = 9.5),
            snapshot("2026-10-16", ObservationStage.ELIMINATED, 8.8, stopPrice = 9.5),
        )

        val first = PaperTradeTracker.reconcile(emptyList(), history)
        val second = PaperTradeTracker.reconcile(first, history)

        assertEquals(1, second.size)
        assertEquals("2026-10-15", second.single().exitDate)
        assertEquals("止损", second.single().exitReason)
    }

    @Test
    fun nonCandidateHistoryDoesNotCreateTrade() {
        val result = PaperTradeTracker.reconcile(
            emptyList(),
            listOf(snapshot("2026-10-14", ObservationStage.NEAR_ENTRY, 10.0)),
        )

        assertEquals(0, result.size)
    }

    @Test
    fun consecutiveBuyCandidateSnapshotsDoNotDuplicateOpenTrade() {
        val history = listOf(
            snapshot("2026-10-14", ObservationStage.BUY_CANDIDATE, 10.0, stopPrice = null),
            snapshot("2026-10-15", ObservationStage.BUY_CANDIDATE, 10.5, stopPrice = null),
        )

        val result = PaperTradeTracker.reconcile(emptyList(), history)

        assertEquals(1, result.size)
        assertEquals("2026-10-14", result.single().entryDate)
        assertEquals("2026-10-15", result.single().lastDate)
    }
}
