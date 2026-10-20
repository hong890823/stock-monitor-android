package com.example.stockmonitor

import com.example.stockmonitor.analysis.IntradayTactics
import com.example.stockmonitor.analysis.IntradaySignalType
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTObservation
import com.example.stockmonitor.analysis.HoldingTTrend
import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.HealthLevel
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HoldingTPolicyTest {
    @Test
    fun invalidVolumeCannotSuppressConfirmedPriceBreakRisk() {
        val signal = IntradayTactics.evaluate(input(18.0, -5.0).copy(volumeRatio = Double.NaN))
        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal?.type)
    }

    @Test
    fun rallyWithoutRecentHistoryDoesNotSuggestSellingMobilePosition() {
        assertNull(IntradayTactics.evaluate(input(20.7, 3.5)))
    }

    @Test
    fun supportTouchWithoutObservedReboundDoesNotSuggestBuyingBack() {
        assertNull(IntradayTactics.evaluate(input(19.1, -3.6)))
    }

    private fun context(trend: HoldingTTrend = HoldingTTrend.RANGE) = HoldingTContext(
        "2026-10-15", 1000L, trend, 2.0, 2.0, SwingTrend.UP, SwingTrend.UP,
    )

    private val rebound = HoldingTObservation(19.0, 19.2, 1.0, 0.0, true, false)
    private val retreat = HoldingTObservation(20.7, 21.0, 0.0, 1.4, false, true)

    @Test
    fun strongUptrendNearHighDefersSellingWithoutClaimingMarketWideStrength() {
        val signal = IntradayTactics.evaluate(input(20.7, 3.5).copy(
            holdingTContext = context(HoldingTTrend.UP),
            holdingTObservation = rebound, dailyHighPrice = 20.72,
        ))!!
        assertEquals(IntradaySignalType.T_TREND_HOLD, signal.type)
        assertTrue(signal.body.contains("周K"))
        assertFalse(signal.body.contains("两市"))
    }

    @Test
    fun strongTrendStillAllowsMobileSellOnceRolloverIsObserved() {
        val signal = IntradayTactics.evaluate(input(20.7, 3.5).copy(
            holdingTContext = context(HoldingTTrend.UP), holdingTObservation = retreat,
        ))!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("机动仓"))
    }

    @Test
    fun extendedMultiDayRallyCanTriggerBelowDailyThreePercentAtResistance() {
        val signal = IntradayTactics.evaluate(input(20.7, 1.0).copy(
            resistance = 20.8, holdingTContext = context(HoldingTTrend.EXTENDED), holdingTObservation = retreat,
        ))!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    @Test
    fun flatOrFallingSupportTouchIsNotARebound() {
        val base = input(19.1, -3.6).copy(holdingTContext = context())
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = rebound.copy(rising = false))))
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = rebound.copy(reboundPct = 0.05))))
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = rebound, volumeRatio = 2.0)))
    }

    @Test
    fun weakLongTermDirectionRequiresStrongerReboundAndCannotOverrideWeakMarket() {
        val base = input(19.1, -3.6).copy(volumeRatio = 0.8,
            holdingTContext = context().copy(weekTrend = SwingTrend.WEAK),
            holdingTObservation = rebound.copy(reboundPct = 0.35))
        assertNull(IntradayTactics.evaluate(base))
        assertEquals(IntradaySignalType.T_BUY,
            IntradayTactics.evaluate(base.copy(holdingTObservation = rebound))!!.type)
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = rebound, marketRegime = MarketRegime.WEAK)))
    }

    @Test
    fun reclaimDoesNotBypassReboundConfirmationOrPromiseRiskCleared() {
        val base = input(19.1, 0.0).copy(brokenSupport = true)
        val signal = IntradayTactics.evaluate(base)!!
        assertTrue(signal.body.contains("尚未"))
        assertFalse(signal.body.contains("风险暂解除"))
        val confirmed = IntradayTactics.evaluate(base.copy(volumeRatio = 0.8,
            holdingTContext = context(), holdingTObservation = rebound))!!
        assertEquals(IntradaySignalType.RECLAIM_SUPPORT, confirmed.type)
        assertTrue(signal.type != confirmed.type)
    }

    @Test
    fun riskBreakStillWarnsWithoutTContextAndSealedLimitDoesNotSuggestTSell() {
        assertEquals(IntradaySignalType.BREAK_SUPPORT, IntradayTactics.evaluate(input(18.0, -5.0))!!.type)
        assertNull(IntradayTactics.evaluate(input(20.7, 10.0).copy(
            holdingTContext = context(), holdingTObservation = retreat)))
    }

    @Test
    fun sectorWeaknessBlocksBuyButDoesNotBlockRolloverSell() {
        val base = input(19.1, -3.6).copy(volumeRatio = 0.8, holdingTContext = context(),
            holdingTObservation = rebound, sectorRegime = MarketRegime.WEAK)
        assertNull(IntradayTactics.evaluate(base))
        assertEquals(IntradaySignalType.T_SELL, IntradayTactics.evaluate(base.copy(
            price = 20.7, changePct = 3.5, volumeRatio = 1.6, holdingTObservation = retreat))!!.type)
    }

    @Test
    fun redFundamentalsCannotBeOverriddenByTechnicalRebound() {
        val health = FundamentalHealth(HealthLevel.ALERT, 40, "risk", listOf("risk"), "2026-10-15 10:00")
        assertNull(IntradayTactics.evaluate(input(19.1, -3.6).copy(
            volumeRatio = 0.8, holdingTContext = context(), holdingTObservation = rebound,
            fundamentalHealth = health)))
    }

    @Test
    fun cachedBreakoutDoesNotHideObservedRolloverSell() {
        val setup = EntrySetup(EntrySetupType.BREAKOUT, true, false, false,
            20.0, 1.0, true, MarketFilter.UP, 19.0, "breakout")
        val signal = IntradayTactics.evaluate(input(20.7, 3.5).copy(
            holdingTContext = context(), holdingTObservation = retreat, entrySetup = setup))!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    private fun input(price: Double, change: Double) = IntradayTactics.Input(
        code = "sh600000", name = "test", price = price, changePct = change,
        volumeRatio = 1.6, limitPct = 10.0, support = 19.0, resistance = 22.0,
        score = 3, quantity = 2000.0, cost = 30.0, totalCapital = 100000.0,
        availableCapital = 10000.0, initialEntryPct = 5.0, maxPositionPct = 20.0,
    )
}
