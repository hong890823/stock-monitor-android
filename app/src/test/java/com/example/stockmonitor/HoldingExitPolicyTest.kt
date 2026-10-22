package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import com.example.stockmonitor.core.Quote
import org.junit.Assert.*
import org.junit.Test

class HoldingExitPolicyTest {
    private val input = HoldingExitPolicy.Input(price = 19.8, support = 20.0, quantity = 1000.0,
        cost = 20.0, atrPct = 1.0)

    @Test fun highPositionOnlyChangesConfirmedRiskAndNeverReplacesExitEvidence() {
        val high = input.copy(priceContext = PriceContext(PriceLocation.HIGH, 0.5))
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(high)?.stage)
        assertNull(HoldingExitPolicy.evaluate(high.copy(price = 20.5)))
        assertEquals(HoldingExitStage.HIGH_REDUCE, HoldingExitPolicy.evaluate(high.copy(confirmed = true))?.stage)
        assertEquals(HoldingExitStage.EXIT,
            HoldingExitPolicy.evaluate(high.copy(confirmed = true, trendWeak = true, price = 19.3))?.stage)
        assertEquals(HoldingExitStage.EXIT,
            HoldingExitPolicy.evaluate(high.copy(confirmed = true, fundamentalAlert = true))?.stage)
        assertEquals(HoldingExitStage.REDUCE, HoldingExitPolicy.evaluate(input.copy(confirmed = true,
            priceContext = PriceContext(PriceLocation.LOW, 0.0)))?.stage)
        assertEquals(HoldingExitStage.DEEP_T, HoldingExitPolicy.evaluate(input.copy(confirmed = true,
            cost = 40.0, priceContext = PriceContext(PriceLocation.LOW, 0.0)))?.stage)
    }

    @Test fun highRolloverRequiresRecentRallyAndObservedWeaknessNotJustVolumeOrLocation() {
        val context = HoldingTContext("2026-10-15", 1000L, HoldingTTrend.EXTENDED, 8.0, 2.0,
            SwingTrend.UP, SwingTrend.UP)
        val high = input.copy(price = 21.0, priceContext = PriceContext(PriceLocation.HIGH, 0.5),
            changePct = 0.5, volumeRatio = 1.5, holdingTContext = context,
            holdingTObservation = HoldingTObservation(20.9, 21.2, 0.0, 0.7, false, true))
        val stagnating = HoldingExitPolicy.evaluate(high)!!
        assertEquals(HoldingExitStage.HIGH_REDUCE, stagnating.stage)
        assertTrue(stagnating.action.contains("放量滞涨"))
        assertNull(HoldingExitPolicy.evaluate(high.copy(volumeRatio = 1.0)))
        assertNull(HoldingExitPolicy.evaluate(high.copy(changePct = 3.0)))
        assertNull(HoldingExitPolicy.evaluate(high.copy(holdingTObservation = high.holdingTObservation!!.copy(falling = false))))
        assertNull(HoldingExitPolicy.evaluate(high.copy(holdingTContext = context.copy(trend = HoldingTTrend.UP))))
        assertNull(HoldingExitPolicy.evaluate(high.copy(holdingTContext = context.copy(gain5Pct = Double.NaN))))
        assertNull(HoldingExitPolicy.evaluate(high.copy(volumeRatio = Double.NaN)))
        assertNull(HoldingExitPolicy.evaluate(high.copy(priceContext = null)))
        assertNull(HoldingExitPolicy.evaluate(high.copy(quantity = 0.0)))
        val retreat = high.copy(volumeRatio = 1.0, changePct = 2.0,
            holdingTObservation = high.holdingTObservation!!.copy(high = 21.5, retreatPct = 2.0))
        assertTrue(HoldingExitPolicy.evaluate(retreat)!!.action.contains("冲高回落"))
        assertNull(HoldingExitPolicy.evaluate(retreat.copy(holdingTContext = context.copy(atrPct = 5.0, gain5Pct = 20.0))))
    }

    @Test fun firstBreakIsWatchButConfirmedBreakReducesWithoutWaitingForRebound() {
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(input)?.stage)
        val decision = HoldingExitPolicy.evaluate(input.copy(confirmed = true))!!
        assertEquals(HoldingExitStage.REDUCE, decision.stage)
        assertTrue(decision.action.contains("分批减仓"))
        assertFalse(decision.action.contains("等反抽"))
    }

    @Test fun expandedBreakReducesButDoesNotAloneForceLiquidation() {
        assertEquals(HoldingExitStage.REDUCE, HoldingExitPolicy.evaluate(input.copy(volumeRatio = 1.5))?.stage)
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(input.copy(volumeRatio = Double.NaN))?.stage)
    }

    @Test fun structuralFailureExitsEvenDeepLossButSingleNeedleDoesNot() {
        val failed = input.copy(price = 19.3, trendWeak = true, confirmed = true)
        assertEquals(HoldingExitStage.EXIT, HoldingExitPolicy.evaluate(failed)?.stage)
        val deep = HoldingExitPolicy.evaluate(failed.copy(cost = 40.0))!!
        assertEquals(HoldingExitStage.EXIT, deep.stage)
        assertTrue(deep.action.contains("清仓退出"))
        assertFalse(deep.action.contains("保留底仓"))
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(failed.copy(confirmed = false))?.stage)
        assertEquals(HoldingExitStage.DEEP_T, HoldingExitPolicy.evaluate(input.copy(cost = 40.0, confirmed = true))?.stage)
    }

    @Test fun redFlagAndConfirmedBreakPrioritizesExitButUnknownHealthDoesNot() {
        assertEquals(HoldingExitStage.EXIT,
            HoldingExitPolicy.evaluate(input.copy(confirmed = true, fundamentalAlert = true))?.stage)
        assertEquals(HoldingExitStage.REDUCE, HoldingExitPolicy.evaluate(input.copy(confirmed = true))?.stage)
        assertNull(HoldingExitPolicy.evaluate(input.copy(price = 20.1, fundamentalAlert = true)))
        assertNull(HoldingExitPolicy.evaluate(input.copy(quantity = 0.0)))
        assertNull(HoldingExitPolicy.evaluate(input.copy(support = Double.POSITIVE_INFINITY)))
    }

    @Test fun lightLowPositionWithoutRedFlagIsNoticeNotCommandedExit() {
        // 2026-10-22 招商证券：基本面没红灯、价格处 60 日区间低位、仓位轻，
        // 破位事实照报，但不再给「退出优先/清仓」的命令式动作。
        val mitigated = input.copy(
            confirmed = true, trendWeak = true, price = 19.3,
            priceContext = PriceContext(PriceLocation.LOW, 0.0), positionPct = 2.0,
        )
        val decision = HoldingExitPolicy.evaluate(mitigated)!!
        assertEquals(HoldingExitStage.NOTICE, decision.stage)
        assertTrue(decision.action.contains("去留"))
        assertFalse(decision.action.contains("清仓退出"))
    }

    @Test fun mitigationOnlyForLightLowUnflaggedPositions() {
        val lowLight = input.copy(confirmed = true, trendWeak = true, price = 19.3,
            priceContext = PriceContext(PriceLocation.LOW, 0.0), positionPct = 2.0)
        // 重仓不缓释：同样的破位形态，仓位占总资金 20% 就该按退出处理。
        assertEquals(HoldingExitStage.EXIT, HoldingExitPolicy.evaluate(lowLight.copy(positionPct = 20.0))?.stage)
        // 基本面红灯不缓释。
        assertEquals(HoldingExitStage.EXIT,
            HoldingExitPolicy.evaluate(lowLight.copy(positionPct = 2.0, fundamentalAlert = true))?.stage)
        // 中位/高位不缓释。
        assertEquals(HoldingExitStage.EXIT,
            HoldingExitPolicy.evaluate(lowLight.copy(priceContext = PriceContext(PriceLocation.MID, 0.3)))?.stage)
        // 总资金没填（positionPct=null）按旧口径走，不擅自降级。
        assertEquals(HoldingExitStage.EXIT, HoldingExitPolicy.evaluate(lowLight.copy(positionPct = null))?.stage)
        // 缓释只拦命令式动作：未确认的首次破位本来就只是「待确认」，保持 WATCH。
        assertEquals(HoldingExitStage.WATCH,
            HoldingExitPolicy.evaluate(lowLight.copy(confirmed = false, trendWeak = false, price = 19.8))?.stage)
    }

    private fun quote(price: Double = 19.8, volume: Double = 100.0, date: String = "2026-10-15") =
        Quote("sh600000", "测试", price, 20.1, 20.1, 20.2, 19.0, volume, 10000.0, date)

    @Test fun trackerConfirmsOnlyFreshSpacedSamplesAndFreezesRiskReference() {
        val tracker = HoldingBreakTracker()
        val first = tracker.observe(quote(), 20.0, 1.0, "2026-10-15", 1000)!!
        assertFalse(first.confirmed)
        assertFalse(tracker.observe(quote(), 18.0, 5.0, "2026-10-15", 31000)!!.confirmed)
        val second = tracker.observe(quote(volume = 110.0), 18.0, 5.0, "2026-10-15", 46000)!!
        assertTrue(second.confirmed)
        assertEquals(20.0, second.support, 0.001)
        assertEquals(first.exitPrice, second.exitPrice, 0.001)
        assertNull(tracker.observe(quote(price = 20.1, volume = 120.0), 18.0, 5.0, "2026-10-15", 61000))
    }

    @Test fun gapsStaleDatesAndNonHoldingRemovalCannotCarryConfirmation() {
        val tracker = HoldingBreakTracker()
        tracker.observe(quote(), 20.0, 1.0, "2026-10-15", 1000)
        assertFalse(tracker.observe(quote(volume = 110.0), 20.0, 1.0, "2026-10-15", 200000)!!.confirmed)
        assertNull(tracker.observe(quote(date = "2026-10-14"), 20.0, 1.0, "2026-10-15", 230000))
        assertFalse(tracker.observe(quote(volume = 130.0), 20.0, 1.0, "2026-10-15", 260000)!!.confirmed)
        tracker.retainCodes(emptySet())
        assertFalse(tracker.observe(quote(volume = 140.0), 20.0, 1.0, "2026-10-15", 290000)!!.confirmed)
    }

    @Test fun snapshotKeepsAnchorAcrossDataGapWithoutInventingConfirmation() {
        val tracker = HoldingBreakTracker()
        tracker.observe(quote(), 20.0, 1.0, "2026-10-15", 1000)
        tracker.observe(quote(volume = 110.0), 20.0, 1.0, "2026-10-15", 31000)
        val snapshot = tracker.snapshot("sh600000", quote(volume = 120.0), "2026-10-15", 200000)!!
        assertFalse(snapshot.confirmed)
        assertEquals(20.0, snapshot.support, 0.001)
        assertEquals(19.6, snapshot.exitPrice, 0.001)
    }

    @Test fun invalidInitialVolumeCannotBecomeFirstConfirmationSample() {
        val tracker = HoldingBreakTracker()
        tracker.observe(quote(volume = 0.0), 20.0, 1.0, "2026-10-15", 1000)
        assertFalse(tracker.observe(quote(), 20.0, 1.0, "2026-10-15", 31000)!!.confirmed)
        assertTrue(tracker.observe(quote(volume = 110.0), 20.0, 1.0, "2026-10-15", 61000)!!.confirmed)
    }

    @Test fun volumeResetNearRecoveryAndNewDayRestartConfirmation() {
        val tracker = HoldingBreakTracker()
        tracker.observe(quote(), 20.0, 1.0, "2026-10-15", 1000)
        assertTrue(tracker.observe(quote(volume = 110.0), 20.0, 1.0, "2026-10-15", 31000)!!.confirmed)
        assertFalse(tracker.observe(quote(volume = 90.0), 20.0, 1.0, "2026-10-15", 46000)!!.confirmed)
        assertFalse(tracker.observe(quote(price = 19.95, volume = 100.0), 20.0, 1.0, "2026-10-15", 61000)!!.confirmed)
        assertFalse(tracker.observe(quote(volume = 110.0), 20.0, 1.0, "2026-10-15", 76000)!!.confirmed)
        assertFalse(tracker.observe(quote(date = "2026-10-16"), 20.0, 1.0, "2026-10-16", 106000)!!.confirmed)
    }

    @Test fun weakTrendOrRedHealthWithoutConfirmedBreakDoesNotForceExit() {
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(input.copy(trendWeak = true))?.stage)
        assertEquals(HoldingExitStage.WATCH, HoldingExitPolicy.evaluate(input.copy(fundamentalAlert = true))?.stage)
        val deepRed = HoldingExitPolicy.evaluate(input.copy(cost = 40.0, fundamentalAlert = true))!!
        assertEquals(HoldingExitStage.WATCH, deepRed.stage)
        assertTrue(deepRed.action.contains("暂停做T接回"))
        assertEquals(HoldingExitStage.REDUCE,
            HoldingExitPolicy.evaluate(input.copy(price = 19.3, atrPct = 4.0, trendWeak = true, confirmed = true))?.stage)
    }
}
