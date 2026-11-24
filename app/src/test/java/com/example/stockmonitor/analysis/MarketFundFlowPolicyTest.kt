package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.SectorFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketFundFlowPolicyTest {

    @Test
    fun marketInflowRequiresRatioAndBoardBreadth() {
        val flows = (1..20).map {
            SectorFlow(mainNetInflow = 300_000_000.0, mainNetRatio = 3.0, amountYuan = 10_000_000_000.0)
        }

        val snapshot = MarketFundFlowPolicy.assess(flows, asOfMs = 1L)

        assertEquals(MarketFundFlowTone.INFLOW, snapshot.tone)
        assertEquals(6_000_000_000.0, snapshot.netInflowYuan!!, 0.0)
        assertEquals(200_000_000_000.0, snapshot.totalAmountYuan!!, 0.0)
        assertEquals(3.0, snapshot.netRatioPct!!, 0.0001)
        assertEquals(20, snapshot.inflowBoardCount)
        assertEquals(1.0, snapshot.inflowBoardRatio!!, 0.0)
    }

    @Test
    fun marketOutflowRequiresRatioAndBoardBreadth() {
        val flows = (1..20).map {
            SectorFlow(mainNetInflow = -300_000_000.0, mainNetRatio = -3.0, amountYuan = 10_000_000_000.0)
        }

        val snapshot = MarketFundFlowPolicy.assess(flows)

        assertEquals(MarketFundFlowTone.OUTFLOW, snapshot.tone)
        assertEquals(0, snapshot.inflowBoardCount)
    }

    @Test
    fun weakRatioStaysNeutral() {
        val flows = (1..20).map {
            SectorFlow(mainNetInflow = 100_000_000.0, mainNetRatio = 1.0, amountYuan = 10_000_000_000.0)
        }

        assertEquals(MarketFundFlowTone.NEUTRAL, MarketFundFlowPolicy.assess(flows).tone)
    }

    @Test
    fun narrowInflowIsNotEnoughWhenMostBoardsAreOut() {
        // 净额被少数大板块拉正，但只有 4/20 个板块流入：扩散不足，按中性处理。
        val flows = (1..20).map { index ->
            if (index <= 4) {
                SectorFlow(2_000_000_000.0, 8.0, 25_000_000_000.0)
            } else {
                SectorFlow(-200_000_000.0, -2.0, 10_000_000_000.0)
            }
        }

        assertEquals(MarketFundFlowTone.NEUTRAL, MarketFundFlowPolicy.assess(flows).tone)
    }

    @Test
    fun tooFewBoardsIsUnavailable() {
        val flows = (1..5).map {
            SectorFlow(300_000_000.0, 3.0, 10_000_000_000.0)
        }

        assertEquals(MarketFundFlowTone.UNAVAILABLE, MarketFundFlowPolicy.assess(flows).tone)
    }

    @Test
    fun tooLittleTurnoverIsUnavailable() {
        val flows = (1..20).map {
            SectorFlow(300_000_000.0, 3.0, 500_000_000.0)
        }

        assertEquals(MarketFundFlowTone.UNAVAILABLE, MarketFundFlowPolicy.assess(flows).tone)
    }

    @Test
    fun missingAmountYuanIsIgnored() {
        val flows = (1..20).map {
            SectorFlow(mainNetInflow = 300_000_000.0, mainNetRatio = 3.0, amountYuan = null)
        }

        assertEquals(MarketFundFlowTone.UNAVAILABLE, MarketFundFlowPolicy.assess(flows).tone)
    }

    @Test
    fun trackerNeedsTwoConfirmationsToFlip() {
        val tracker = MarketFundFlowTracker(confirmations = 2)

        val first = tracker.update("2026-11-23", snapshot(MarketFundFlowTone.INFLOW))
        assertEquals(MarketFundFlowTone.INFLOW, first.tone)
        assertFalse(first.changed)

        val pending = tracker.update("2026-11-23", snapshot(MarketFundFlowTone.OUTFLOW))
        assertEquals(MarketFundFlowTone.INFLOW, pending.tone)
        assertFalse(pending.changed)

        val flipped = tracker.update("2026-11-23", snapshot(MarketFundFlowTone.OUTFLOW))
        assertEquals(MarketFundFlowTone.OUTFLOW, flipped.tone)
        assertEquals(MarketFundFlowTone.INFLOW, flipped.previousTone)
        assertTrue(flipped.changed)
    }

    @Test
    fun trackerKeepsConfirmedToneWhenDataUnavailable() {
        val tracker = MarketFundFlowTracker(confirmations = 2)
        tracker.update("2026-11-23", snapshot(MarketFundFlowTone.OUTFLOW))

        val stale = tracker.update("2026-11-23", snapshot(MarketFundFlowTone.UNAVAILABLE))

        assertEquals(MarketFundFlowTone.OUTFLOW, stale.tone)
        assertTrue(stale.stale)
        assertFalse(stale.changed)
    }

    @Test
    fun trackerResetsAcrossTradingDays() {
        val tracker = MarketFundFlowTracker(confirmations = 2)
        tracker.update("2026-11-23", snapshot(MarketFundFlowTone.INFLOW))

        val nextDay = tracker.update("2026-11-24", snapshot(MarketFundFlowTone.OUTFLOW))

        assertEquals(MarketFundFlowTone.OUTFLOW, nextDay.tone)
        assertFalse(nextDay.changed)
    }

    @Test
    fun holdingAdviceCombinesMarketAndStockDirection() {
        val marketIn = state(MarketFundFlowTone.INFLOW)
        val marketOut = state(MarketFundFlowTone.OUTFLOW)

        assertEquals(
            HoldingFundFlowSignal.ALIGNED_INFLOW,
            MarketFundFlowPolicy.holdingAdvice(marketIn, 5.0)?.signal,
        )
        assertEquals(
            HoldingFundFlowSignal.ALIGNED_OUTFLOW,
            MarketFundFlowPolicy.holdingAdvice(marketOut, -5.0)?.signal,
        )
        assertEquals(
            HoldingFundFlowSignal.MARKET_OUT_STOCK_IN,
            MarketFundFlowPolicy.holdingAdvice(marketOut, 5.0)?.signal,
        )
        assertEquals(
            HoldingFundFlowSignal.MARKET_IN_STOCK_OUT,
            MarketFundFlowPolicy.holdingAdvice(marketIn, -5.0)?.signal,
        )
        assertEquals(
            HoldingFundFlowSignal.MARKET_ONLY,
            MarketFundFlowPolicy.holdingAdvice(marketOut, null)?.signal,
        )
        assertNull(MarketFundFlowPolicy.holdingAdvice(null, 5.0))
    }

    private fun snapshot(tone: MarketFundFlowTone) = MarketFundFlowSnapshot(
        tone = tone,
        netInflowYuan = 1_000_000_000.0,
        totalAmountYuan = 100_000_000_000.0,
        netRatioPct = 1.0,
        boardCount = 20,
        inflowBoardCount = 10,
        asOfMs = 1L,
    )

    private fun state(tone: MarketFundFlowTone) = MarketFundFlowState(
        tone = tone,
        snapshot = snapshot(tone),
        previousTone = MarketFundFlowTone.NEUTRAL,
        changed = false,
    )
}