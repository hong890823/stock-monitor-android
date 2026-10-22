package com.example.stockmonitor

import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySetupTextTest {
    @Test
    fun compactTextSeparatesStructureFromEvidence() {
        assertEquals(
            "M20回踩 · 已确认",
            setup(EntrySetupType.M20_PULLBACK, confirmed = true, canBuy = true).compactText(),
        )
        assertEquals(
            "突破 · 仅观察",
            setup(EntrySetupType.BREAKOUT, confirmed = true, canBuy = false).compactText(),
        )
        assertEquals(
            "无结构 · 跌破M20",
            setup(EntrySetupType.NONE, trendWeak = true).compactText(),
        )
    }

    private fun setup(
        type: EntrySetupType,
        confirmed: Boolean = false,
        canBuy: Boolean = false,
        trendWeak: Boolean = false,
    ) = EntrySetup(
        type = type,
        confirmed = confirmed,
        canBuy = canBuy,
        trendWeak = trendWeak,
        priorHigh = null,
        ma20SlopePct = null,
        volumeConfirmed = false,
        marketFilter = MarketFilter.NEUTRAL,
        stopPrice = null,
        reason = "测试",
    )
}
