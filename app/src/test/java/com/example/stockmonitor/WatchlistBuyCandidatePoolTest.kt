package com.example.stockmonitor

import com.example.stockmonitor.analysis.WatchlistBuyCandidatePool
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistBuyCandidatePoolTest {
    @Test
    fun createsCandidatesOnlyForActiveStockWatchItems() {
        val candidates = WatchlistBuyCandidatePool.sync(
            watchlist = listOf(
                WatchItem("sh600000", "浦发", false),
                WatchItem("sh000001", "上证", false),
                WatchItem("sz399006", "创业", false),
            ),
            previous = emptyList(),
            heldCodes = emptySet(),
            observationCodes = emptySet(),
            today = "2026-10-23",
        )

        assertEquals(listOf("sh600000"), candidates.map { it.code })
        assertEquals(WatchlistBuyCandidatePool.SOURCE, candidates.single().source)
    }

    @Test
    fun preservesConfirmationStateForStillWatchedStock() {
        val initial = WatchlistBuyCandidatePool.sync(
            watchlist = listOf(WatchItem("sh600000", "浦发", false)),
            previous = emptyList(),
            heldCodes = emptySet(),
            observationCodes = emptySet(),
            today = "2026-10-23",
        ).single().copy(
            stage = ObservationStage.NEAR_ENTRY,
            entryConfirmationRounds = 1,
            entryConfirmedAtMs = 123L,
        )

        val updated = WatchlistBuyCandidatePool.sync(
            watchlist = listOf(WatchItem("sh600000", "浦发银行", false)),
            previous = listOf(initial),
            heldCodes = emptySet(),
            observationCodes = emptySet(),
            today = "2026-10-23",
        ).single()

        assertEquals("浦发银行", updated.name)
        assertEquals(ObservationStage.NEAR_ENTRY, updated.stage)
        assertEquals(1, updated.entryConfirmationRounds)
        assertEquals(123L, updated.entryConfirmedAtMs)
    }

    @Test
    fun removesHeldObservedAndUnwatchedStocks() {
        val previous = listOf(
            WatchlistBuyCandidatePool.candidate("sh600001", "持仓", "2026-10-23"),
            WatchlistBuyCandidatePool.candidate("sh600002", "已观察", "2026-10-23"),
            WatchlistBuyCandidatePool.candidate("sh600003", "已移除", "2026-10-23"),
        )

        val updated = WatchlistBuyCandidatePool.sync(
            watchlist = previous.map { WatchItem(it.code, it.name, false) },
            previous = previous,
            heldCodes = setOf("sh600001"),
            observationCodes = setOf("sh600002"),
            today = "2026-10-23",
        )

        assertEquals(listOf("sh600003"), updated.map { it.code })
    }

    @Test
    fun eliminatedWatchCandidateStartsAFreshObservationCycle() {
        val eliminated = WatchlistBuyCandidatePool
            .candidate("sh600000", "浦发", "2026-10-20")
            .copy(stage = ObservationStage.ELIMINATED, observedDays = 10, weakDays = 3)

        val updated = WatchlistBuyCandidatePool.sync(
            watchlist = listOf(WatchItem("sh600000", "浦发", false)),
            previous = listOf(eliminated),
            heldCodes = emptySet(),
            observationCodes = emptySet(),
            today = "2026-10-23",
        ).single()

        assertEquals(ObservationStage.OBSERVING, updated.stage)
        assertEquals("2026-10-23", updated.addedDate)
        assertEquals(0, updated.observedDays)
        assertEquals(0, updated.weakDays)
        assertFalse(updated.continuousObservation)
    }
}
