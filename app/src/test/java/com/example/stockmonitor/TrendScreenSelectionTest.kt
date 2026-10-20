package com.example.stockmonitor

import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.TrendScreenSelection
import com.example.stockmonitor.core.TrendScreenHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendScreenSelectionTest {
    private fun hit(code: String = "600001", canBuy: Boolean = false) = TrendScreenHit(
        code = code,
        name = code,
        price = 10.0,
        changePct = 1.0,
        volumeRatio = 1.4,
        entrySetup = EntrySetup(
            type = if (canBuy) EntrySetupType.M20_PULLBACK else EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = canBuy,
            priorHigh = 9.5,
            ma20SlopePct = 1.0,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 9.0,
            reason = "confirmed",
        ),
    )

    @Test
    fun `only confirmed healthy structures qualify`() {
        val valid = hit()
        val rejected = listOf(
            valid.entrySetup.copy(confirmed = false),
            valid.entrySetup.copy(type = EntrySetupType.BREAKOUT_WATCH),
            valid.entrySetup.copy(type = EntrySetupType.NONE),
            valid.entrySetup.copy(trendWeak = true),
            valid.entrySetup.copy(volumeConfirmed = false),
            valid.entrySetup.copy(marketFilter = MarketFilter.HEADWIND),
        ).mapIndexed { index, setup -> valid.copy(code = "reject$index", entrySetup = setup) }

        assertEquals(listOf(valid), TrendScreenSelection.select(rejected + valid))
        assertTrue(TrendScreenSelection.select(rejected).isEmpty())
    }

    @Test
    fun `cap at twenty after filtering and rank buy eligible first without promoting breakouts`() {
        val breakouts = (1..30).map { hit("breakout$it") }
        val pullback = hit("pullback", canBuy = true)
        val selected = TrendScreenSelection.select(breakouts + pullback)

        assertEquals(20, selected.size)
        assertEquals(pullback, selected.first())
        assertTrue(selected.drop(1).all { !it.entrySetup.canBuy })
        assertFalse(TrendScreenSelection.select(listOf(hit())).single().entrySetup.canBuy)
    }

    @Test
    fun `successful deep scan with no matches clears old trends while quick scan retains filtered cache`() {
        val old = (1..30).map { hit("old$it") }
        assertTrue(TrendScreenSelection.merge(old, emptyList(), includePatterns = true).isEmpty())
        assertEquals(20, TrendScreenSelection.merge(old, emptyList(), includePatterns = false).size)
        val unconfirmed = hit().let { it.copy(entrySetup = it.entrySetup.copy(confirmed = false)) }
        assertTrue(TrendScreenSelection.merge(listOf(unconfirmed), emptyList(), false).isEmpty())
    }

    @Test
    fun `new deep scan replaces cache and selection is idempotent`() {
        val fresh = hit("fresh")
        assertEquals(listOf(fresh), TrendScreenSelection.merge(listOf(hit()), listOf(fresh), true))
        val selected = TrendScreenSelection.select((1..30).map { hit("stock$it") })
        assertEquals(selected, TrendScreenSelection.select(selected))
    }
}
