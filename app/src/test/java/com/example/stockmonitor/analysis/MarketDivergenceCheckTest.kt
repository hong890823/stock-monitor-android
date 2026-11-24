package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大盘分歧日体检与次日证伪状态机。
 * 对应 docs/2026-11-24-divergence-execution-strategy.md 模块 A/B。
 */
class MarketDivergenceCheckTest {

    /** 以昨收 10 元为基准构造指数日线；prev5CloseAvg 默认 9.5，收盘价在前 5 日均线上方。 */
    private fun day(
        date: String,
        openPct: Double = 0.0,
        highPct: Double = 0.5,
        closePct: Double = 0.0,
        turnoverRatioPct: Double = 1.0,
        prev5CloseAvg: Double = 9.5,
    ): IndexDayStats {
        val prevClose = 10.0
        return IndexDayStats(
            date = date,
            openPct = openPct,
            highPct = highPct,
            closePct = closePct,
            turnoverRatioPct = turnoverRatioPct,
            open = prevClose * (1 + openPct / 100),
            close = prevClose * (1 + closePct / 100),
            prev5CloseAvg = prev5CloseAvg,
        )
    }

    /** 标准分歧日：高开 +1%、冲高 +3%、收 +0.2%、放量 +8%、结构未破坏。 */
    private fun divergence(date: String = "2026-11-24") =
        day(date, openPct = 1.0, highPct = 3.0, closePct = 0.2, turnoverRatioPct = 8.0)

    // ---------- 分歧日判定表 ----------

    @Test
    fun allFourConditionsMetDetectsDivergenceDay() {
        val report = MarketDivergenceCheck.detect(divergence())
        assertTrue(report.isDivergenceDay)
        assertEquals(FundLeg.ABSORB_STRONG, report.fundLeg)
        assertTrue(report.policyLegUnknown)
        assertTrue(report.evidence.any { it.contains("高开兑现") })
    }

    @Test
    fun anySingleConditionMissedIsNotDivergenceDay() {
        listOf(
            divergence().copy(openPct = 0.3, open = 10.03),          // 不算高开
            divergence().copy(highPct = 1.0),                        // 没冲高
            divergence().copy(closePct = 0.8, close = 10.08),        // 收盘未回落到高开幅度 60% 内
            divergence().copy(turnoverRatioPct = 4.0),               // 没放量
            divergence().copy(prev5CloseAvg = 10.5),                 // 结构已破坏
        ).forEach { input ->
            val report = MarketDivergenceCheck.detect(input)
            assertFalse(input.toString(), report.isDivergenceDay)
            assertNull(report.fundLeg)
        }
    }

    @Test
    fun fundLegGradesByClosePct() {
        assertEquals(FundLeg.ABSORB_STRONG, MarketDivergenceCheck.detect(divergence().copy(closePct = -0.1)).fundLeg)
        assertEquals(FundLeg.ABSORB_NORMAL, MarketDivergenceCheck.detect(divergence().copy(closePct = -0.5)).fundLeg)
        assertEquals(FundLeg.WEAK, MarketDivergenceCheck.detect(divergence().copy(closePct = -1.5)).fundLeg)
    }

    // ---------- 中阳与弱势口径 ----------

    @Test
    fun alphaMidCandleNeedsBothGainAndCloseAboveDivergenceMidpoint() {
        val d = divergence() // 实体中点 (10.10+10.02)/2 = 10.06
        assertTrue(MarketDivergenceCheck.isMidCandle(day("d1", closePct = 0.7), d, MidCandleRule.ALPHA))
        // 涨幅够但收在分歧日实体中点下方（10.055 < 10.06）：不算中阳。
        assertFalse(MarketDivergenceCheck.isMidCandle(day("d1", closePct = 0.55), d, MidCandleRule.ALPHA))
    }

    @Test
    fun betaMidCandleIsStrictAndNeedsReclaimOfDivergenceOpen() {
        val d = divergence() // 开盘 10.10、实体中点 10.06
        // α 认中阳（收 10.07 > 中点 10.06）、β 不认：0.7% < 1%，且 10.07 未收复分歧开盘 10.10。
        val marginal = day("d1", closePct = 0.7)
        assertTrue(MarketDivergenceCheck.isMidCandle(marginal, d, MidCandleRule.ALPHA))
        assertFalse(MarketDivergenceCheck.isMidCandle(marginal, d, MidCandleRule.BETA))
        val strong = day("d1", closePct = 1.2)
        assertTrue(MarketDivergenceCheck.isMidCandle(strong, d, MidCandleRule.BETA))
    }

    @Test
    fun weakDayIsCloseAtOrBelowMinusHalfPercent() {
        assertTrue(MarketDivergenceCheck.isWeakDay(day("d1", closePct = -0.5)))
        assertFalse(MarketDivergenceCheck.isWeakDay(day("d1", closePct = -0.4)))
    }

    // ---------- 证伪状态机 ----------

    @Test
    fun noDivergenceDayMeansNone() {
        assertEquals(DivergencePhase.NONE, MarketDivergenceCheck.phaseOf(listOf(day("d1"), day("d2"), day("d3"))).phase)
        assertEquals(DivergencePhase.NONE, MarketDivergenceCheck.phaseOf(emptyList()).phase)
    }

    @Test
    fun dayAfterDivergenceIsWatch() {
        val state = MarketDivergenceCheck.phaseOf(listOf(day("d0"), divergence("d1")))
        assertEquals(DivergencePhase.WATCH, state.phase)
        assertEquals("d1", state.divergenceDate)
    }

    @Test
    fun midCandleOnNextDayConfirmsWashout() {
        val state = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = 1.0)),
        )
        assertEquals(DivergencePhase.REBOUND_HOLD, state.phase)
        assertTrue(state.detail.contains("洗盘"))
    }

    @Test
    fun weakNextDayDowngradesEarly() {
        val state = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = -1.0)),
        )
        assertEquals(DivergencePhase.DOWNGRADE, state.phase)
        assertTrue(state.detail.contains("降级"))
    }

    @Test
    fun neutralNextDayKeepsWatchingThenDowngradesAfterWindow() {
        val watch = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = 0.0)),
        )
        assertEquals(DivergencePhase.WATCH, watch.phase)

        val downgraded = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = 0.0), day("d3", closePct = 0.1)),
        )
        assertEquals(DivergencePhase.DOWNGRADE, downgraded.phase)
        assertTrue(downgraded.detail.contains("宁降勿拖"))
    }

    @Test
    fun midCandleOnLastObservationDayStillConfirms() {
        val state = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = 0.0), day("d3", closePct = 1.0)),
        )
        assertEquals(DivergencePhase.REBOUND_HOLD, state.phase)
    }

    @Test
    fun midCandleOutsideTwoDayWindowDoesNotRewriteResolvedCycle() {
        // D+3 的中阳在证伪窗口外：周期已按降级解析，不追溯改写。
        val state = MarketDivergenceCheck.phaseOf(
            listOf(divergence("d1"), day("d2", closePct = 0.0), day("d3", closePct = 0.1), day("d4", closePct = 1.5)),
        )
        assertEquals(DivergencePhase.DOWNGRADE, state.phase)
    }

    @Test
    fun resolutionLastsThroughD5ThenReturnsToNone() {
        val days = mutableListOf(divergence("d1"))
        repeat(3) { days.add(day("d${it + 2}", closePct = 0.1)) }
        // 今天 = D+4：解析结果（降级）仍有效。
        assertEquals(DivergencePhase.DOWNGRADE, MarketDivergenceCheck.phaseOf(days).phase)
        days.add(day("d5", closePct = 0.1))
        // 今天 = D+5：仍有效。
        assertEquals(DivergencePhase.DOWNGRADE, MarketDivergenceCheck.phaseOf(days).phase)
        days.add(day("d6", closePct = 0.1))
        // 今天 = D+6：过期回常规。
        assertEquals(DivergencePhase.NONE, MarketDivergenceCheck.phaseOf(days).phase)
    }

    @Test
    fun newDivergenceDayResetsCycleToWatch() {
        val state = MarketDivergenceCheck.phaseOf(
            listOf(
                divergence("d1"), day("d2", closePct = -1.0), day("d3", closePct = -1.0),
                day("d4", closePct = 0.5), divergence("d5"),
            ),
        )
        assertEquals(DivergencePhase.WATCH, state.phase)
        assertEquals("d5", state.divergenceDate)
    }

    @Test
    fun fromBarsComputesStatsAndSkipsShortHistory() {
        val bars = listOf(
            com.example.stockmonitor.core.DayBar("2026-11-18", 9.9, 10.1, 9.8, 10.0, 1000.0),
            com.example.stockmonitor.core.DayBar("2026-11-19", 10.1, 10.3, 10.0, 10.2, 1200.0),
        )
        assertTrue(MarketDivergenceCheck.fromBars(bars).isEmpty()) // 2 根凑不出前 5 日均值
        val long = (1..6).map { i ->
            com.example.stockmonitor.core.DayBar("2026-11-1$i", 9.9, 10.1, 9.8, 10.0, 1000.0)
        }
        val stats = MarketDivergenceCheck.fromBars(long)
        // 前 5 根凑不出「前 5 日收盘均值」，只有最后一根成行。
        assertEquals(1, stats.size)
        assertEquals(0.0, stats.last().turnoverRatioPct, 1e-9)
    }
}
