package com.example.stockmonitor

import com.example.stockmonitor.analysis.IntradaySignalType
import com.example.stockmonitor.analysis.IntradaySignal
import com.example.stockmonitor.analysis.IntradayTactics
import com.example.stockmonitor.core.DualTimeframeDecision
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.HealthLevel
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.HoldingTContext
import com.example.stockmonitor.analysis.HoldingTObservation
import com.example.stockmonitor.analysis.HoldingTTrend
import com.example.stockmonitor.analysis.SwingTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盘中买卖点 / 做 T 的判定。
 *
 * 这里锁住的是「什么时候该提醒」和「提醒里给不给具体股数」：
 * 减出数量仅为机动仓上限；未核实真实成交时不推算接回数量；
 * 阈值随板块涨跌停幅度缩放，3% 对创业板不该触发做 T。
 */
class IntradayTacticsTest {
    @Test
    fun extendedHighPositionRolloverReducesBeforeSupportBreakButNotOnHighPositionAlone() {
        val base = input(price = 21.5, changePct = 0.5, volumeRatio = 1.5,
            support = 19.0, resistance = 23.0, quantity = 1000.0, cost = 20.0).let {
            it.copy(priceContext = com.example.stockmonitor.analysis.PriceContext(
                com.example.stockmonitor.analysis.PriceLocation.HIGH, 0.5),
                holdingTContext = it.holdingTContext!!.copy(trend = HoldingTTrend.EXTENDED, gain5Pct = 8.0))
        }
        val signal = IntradayTactics.evaluate(base)
        assertEquals(IntradaySignalType.HIGH_REDUCE_POSITION, signal?.type)
        assertTrue(signal!!.body.contains("高位"))
        assertFalse(signal.body.contains("跌破支撑"))
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = null)))
        assertNull(IntradayTactics.evaluate(base.copy(holdingTObservation = base.holdingTObservation!!.copy(falling = false))))
        assertNull(IntradayTactics.evaluate(base.copy(priceContext = null)))
        assertNull(IntradayTactics.evaluate(base.copy(holdingTContext = null)))
        assertNull(IntradayTactics.evaluate(base.copy(quantity = 0.0)))
    }

    @Test
    fun watchReduceAndExitHaveDistinctImmediateRiskTypes() {
        val base = input(price = 18.8, support = 19.0, quantity = 1000.0, cost = 20.0)
        val watched = IntradayTactics.evaluate(base)!!
        val confirmed = base.copy(breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(19.0, 18.62, true))
        val reduced = IntradayTactics.evaluate(confirmed)!!
        val failedSetup = EntrySetup(EntrySetupType.NONE, false, false, true, null, null, false,
            MarketFilter.NEUTRAL, null, "趋势转弱")
        val exited = IntradayTactics.evaluate(confirmed.copy(price = 18.5, entrySetup = failedSetup))!!
        assertEquals(IntradaySignalType.BREAK_SUPPORT, watched.type)
        assertEquals(IntradaySignalType.REDUCE_POSITION, reduced.type)
        assertEquals(IntradaySignalType.EXIT_POSITION, exited.type)
        listOf(watched, reduced, exited).forEach {
            assertTrue(it.type.isRisk)
            assertEquals(1, IntradayTactics.confirmationRounds(it.type))
        }
        assertFalse(IntradaySignalType.T_SELL.isRisk)
    }

    @Test
    fun ordinaryExecutionSignalsRequireDualTimeframeAndUseDedicatedWatchType() {
        assertTrue(IntradaySignalType.BREAKOUT.requiresDualTimeframe)
        assertTrue(IntradaySignalType.RECLAIM_SUPPORT.requiresDualTimeframe)
        assertTrue(IntradaySignalType.T_SELL.requiresDualTimeframe)
        assertTrue(IntradaySignalType.T_BUY.requiresDualTimeframe)
        assertFalse(IntradaySignalType.BREAK_SUPPORT.requiresDualTimeframe)
        assertFalse(IntradaySignalType.TIMEFRAME_WATCH.requiresDualTimeframe)
        assertEquals("周期条件待确认", IntradaySignalType.TIMEFRAME_WATCH.label)
        assertFalse(IntradaySignalType.TIMEFRAME_WATCH.isRisk)
    }

    @Test
    fun timeframeRejectionRemovesActionableShareAdvice() {
        val original = IntradaySignal(
            type = IntradaySignalType.T_SELL,
            title = "华天科技·冲高做T",
            body = "建议减约 300 股，今日新增仓位 T+1。",
        )

        val watchOnly = IntradayTactics.watchOnly(
            signal = original,
            decision = DualTimeframeDecision(false, "30分钟方向向下，仅观察"),
        )

        assertEquals(IntradaySignalType.TIMEFRAME_WATCH, watchOnly.type)
        assertTrue(watchOnly.title.contains("仅观察"))
        assertTrue(watchOnly.body.contains("30分钟方向向下"))
        assertFalse(watchOnly.body.contains("建议减约 300 股"))
    }

    @Test
    fun gapOrSynchronizedWeaknessCanReduceWithoutWaitingButMarketAloneCannot() {
        val base = input(price = 18.8, support = 19.0, quantity = 1000.0, cost = 20.0)
        assertEquals(IntradaySignalType.BREAK_SUPPORT,
            IntradayTactics.evaluate(base.copy(marketRegime = MarketRegime.WEAK))!!.type)
        assertEquals(IntradaySignalType.REDUCE_POSITION,
            IntradayTactics.evaluate(base.copy(marketRegime = MarketRegime.WEAK, sectorRegime = MarketRegime.WEAK))!!.type)
        assertEquals(IntradaySignalType.REDUCE_POSITION,
            IntradayTactics.evaluate(base.copy(openPrice = 18.8, previousClose = 19.1))!!.type)
        assertEquals(IntradaySignalType.BREAK_SUPPORT,
            IntradayTactics.evaluate(base.copy(openPrice = 18.8, previousClose = 18.7))!!.type)
        assertNull(IntradayTactics.evaluate(base.copy(quantity = 0.0, openPrice = 18.8, previousClose = 19.1)))
    }


    @Test
    fun marketEnvironmentSeparatesSustainedFlowPulseAndDivergence() {
        val sustained = com.example.stockmonitor.analysis.MarketEnvironmentQuality.evaluate(
            predictedTurnoverYi = 22_000.0,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
            recentTurnoversYi = listOf(17_000.0, 18_000.0, 19_000.0),
        )
        assertEquals(com.example.stockmonitor.analysis.MarketEnvironmentStatus.STRONG_SUSTAINED, sustained.status)

        val pulse = com.example.stockmonitor.analysis.MarketEnvironmentQuality.evaluate(
            predictedTurnoverYi = 22_000.0,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
            recentTurnoversYi = listOf(12_000.0, 13_000.0),
        )
        assertEquals(com.example.stockmonitor.analysis.MarketEnvironmentStatus.STRONG_SINGLE_DAY, pulse.status)

        val divergence = com.example.stockmonitor.analysis.MarketEnvironmentQuality.evaluate(
            predictedTurnoverYi = 22_000.0,
            marketChangePct = 0.1,
            marketPrice = 3200.0,
            marketHighPrice = 3260.0,
            industryBreadth = 0.38,
            conceptBreadth = 0.42,
            recentTurnoversYi = listOf(16_000.0, 17_200.0, 18_500.0),
        )
        assertEquals(com.example.stockmonitor.analysis.MarketEnvironmentStatus.DIVERGENCE, divergence.status)

        val interrupted = com.example.stockmonitor.analysis.MarketEnvironmentQuality.evaluate(
            predictedTurnoverYi = 22_000.0,
            marketChangePct = 1.2,
            marketPrice = 3200.0,
            marketHighPrice = 3210.0,
            industryBreadth = 0.72,
            conceptBreadth = 0.68,
            recentTurnoversYi = listOf(17_000.0, 12_000.0, 18_000.0),
        )
        assertEquals(1, interrupted.persistenceDays)
        assertEquals(com.example.stockmonitor.analysis.MarketEnvironmentStatus.STRONG_SINGLE_DAY, interrupted.status)
    }

    private fun input(
        price: Double = 20.0,
        changePct: Double = 0.0,
        volumeRatio: Double? = 1.0,
        limitPct: Double = 10.0,
        support: Double = 19.0,
        resistance: Double = 21.0,
        score: Int = 0,
        quantity: Double = 0.0,
        cost: Double = 0.0,
        totalCapital: Double = 0.0,
        availableCapital: Double = 0.0,
        initialEntryPct: Double = 5.0,
        maxPositionPct: Double = 20.0,
        entrySetup: EntrySetup? = null,
        ma20: Double = 0.0,
        fundamentalHealth: FundamentalHealth? = null,
        atrPct: Double? = null,
        brokenSupport: Boolean = false,
        reclaimedSupport: Boolean = false,
        marketRegime: MarketRegime = MarketRegime.NEUTRAL,
        sectorRegime: MarketRegime? = null,
        sectorChangePct: Double? = null,
        sectorTurnoverRatio: Double? = null,
        sectorMainNetInflow: Double? = null,
        sectorMainNetRatio: Double? = null,
        todayTurnoverYi: Double? = 10.0,
        predictedMarketTurnoverYi: Double? = null,
        marketChangePct: Double? = null,
        dailyHighPrice: Double? = null,
        marketIndexPrice: Double? = null,
        marketIndexHighPrice: Double? = null,
        industryBreadth: Double? = null,
        conceptBreadth: Double? = null,
        recentMarketTurnoversYi: List<Double> = emptyList(),
    ) = IntradayTactics.Input(
        code = "sz002185",
        name = "华天科技",
        price = price,
        changePct = changePct,
        volumeRatio = volumeRatio,
        limitPct = limitPct,
        support = support,
        resistance = resistance,
        score = score,
        quantity = quantity,
        cost = cost,
        totalCapital = totalCapital,
        availableCapital = availableCapital,
        initialEntryPct = initialEntryPct,
        maxPositionPct = maxPositionPct,
        entrySetup = entrySetup,
        ma20 = ma20,
        fundamentalHealth = fundamentalHealth,
        atrPct = atrPct,
        brokenSupport = brokenSupport,
        reclaimedSupport = reclaimedSupport,
        marketRegime = marketRegime,
        sectorRegime = sectorRegime,
        sectorChangePct = sectorChangePct,
        sectorTurnoverRatio = sectorTurnoverRatio,
        sectorMainNetInflow = sectorMainNetInflow,
        sectorMainNetRatio = sectorMainNetRatio,
        todayTurnoverYi = todayTurnoverYi,
        predictedMarketTurnoverYi = predictedMarketTurnoverYi,
        marketChangePct = marketChangePct,
        dailyHighPrice = dailyHighPrice,
        marketIndexPrice = marketIndexPrice,
        marketIndexHighPrice = marketIndexHighPrice,
        industryBreadth = industryBreadth,
        conceptBreadth = conceptBreadth,
        recentMarketTurnoversYi = recentMarketTurnoversYi,
        holdingTContext = HoldingTContext("2026-10-15", 1000L, HoldingTTrend.RANGE, 1.0, 2.0,
            SwingTrend.UP, SwingTrend.UP),
        holdingTObservation = HoldingTObservation(
            support, price * 1.02, 1.0, 2.0, changePct <= 0, changePct > 0),
    )

    @Test
    fun breakingSupportWarnsHolderWithQuantity() {
        val signal = IntradayTactics.evaluate(input(price = 18.80, changePct = -3.2, quantity = 2_000.0))!!
        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal.type)
        assertTrue(signal.body.contains("尚缺持续确认"))
        assertTrue(signal.body.contains("确认未收回则分批减仓"))
        assertFalse(signal.body.contains("减约 600 股"))
    }


    @Test
    fun strongMarketDefersOrdinaryTSell() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 21.80,
                changePct = 4.0,
                volumeRatio = 1.5,
                resistance = 23.0,
                quantity = 2_000.0,
                predictedMarketTurnoverYi = 22_000.0,
                marketChangePct = 0.8,
                sectorChangePct = 1.2,
                dailyHighPrice = 21.90,
                marketIndexPrice = 3200.0,
                marketIndexHighPrice = 3210.0,
                industryBreadth = 0.72,
                conceptBreadth = 0.68,
                recentMarketTurnoversYi = listOf(17_000.0, 18_000.0, 19_000.0),
            ).copy(holdingTObservation = HoldingTObservation(21.5, 21.8, 1.3, 0.0, true, false))
        )!!

        assertEquals(IntradaySignalType.T_HOLD, signal.type)
        assertTrue(signal.title.contains("强势环境"))
        assertTrue(signal.body.contains("暂缓做T"))
    }

    @Test
    fun missingStrongMarketContextKeepsOrdinaryTSell() {
        val signal = IntradayTactics.evaluate(
            input(price = 21.80, changePct = 4.0, volumeRatio = 1.5, resistance = 23.0, quantity = 2_000.0)
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    @Test
    fun supportBreakKeepsPriorityInStrongMarket() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 18.80,
                changePct = -3.2,
                volumeRatio = 1.5,
                resistance = 23.0,
                quantity = 2_000.0,
                predictedMarketTurnoverYi = 22_000.0,
                marketChangePct = 0.8,
                sectorChangePct = 1.2,
                dailyHighPrice = 19.00,
            )
        )!!

        assertEquals(IntradaySignalType.REDUCE_POSITION, signal.type)
    }

    @Test
    fun singleDayHighTurnoverOnlyDefersWithCaution() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 21.80,
                changePct = 4.0,
                volumeRatio = 1.5,
                resistance = 23.0,
                quantity = 2_000.0,
                predictedMarketTurnoverYi = 22_000.0,
                marketChangePct = 0.8,
                sectorChangePct = 1.2,
                dailyHighPrice = 21.90,
                marketIndexPrice = 3200.0,
                marketIndexHighPrice = 3210.0,
                industryBreadth = 0.72,
                conceptBreadth = 0.68,
                recentMarketTurnoversYi = listOf(12_000.0, 13_000.0),
            ).copy(holdingTObservation = HoldingTObservation(21.5, 21.8, 1.3, 0.0, true, false))
        )!!

        assertEquals(IntradaySignalType.T_HOLD_CAUTION, signal.type)
        assertTrue(signal.body.contains("持续性尚未确认"))
    }

    @Test
    fun highTurnoverDivergenceKeepsOrdinaryTSell() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 21.80,
                changePct = 4.0,
                volumeRatio = 1.5,
                resistance = 23.0,
                quantity = 2_000.0,
                predictedMarketTurnoverYi = 22_000.0,
                marketChangePct = 0.1,
                sectorChangePct = 0.2,
                dailyHighPrice = 21.90,
                marketIndexPrice = 3200.0,
                marketIndexHighPrice = 3260.0,
                industryBreadth = 0.38,
                conceptBreadth = 0.42,
                recentMarketTurnoversYi = listOf(16_000.0, 17_200.0, 18_500.0),
            )
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    @Test
    fun breakingSupportIsSilentWithoutPosition() {
        assertNull(IntradayTactics.evaluate(input(price = 18.80, changePct = -3.2)))
    }

    @Test
    fun breakoutWithoutTrendContextDoesNotTriggerEmptyEntryButStillGuidesHolders() {
        assertNull(
            IntradayTactics.evaluate(
            input(price = 21.50, volumeRatio = 1.5, totalCapital = 100_000.0)
            )
        )

        val holder = IntradayTactics.evaluate(input(price = 21.50, volumeRatio = 1.5, quantity = 2_000.0))!!
        assertEquals(IntradaySignalType.BREAKOUT, holder.type)
        assertTrue(holder.body.contains("可继续持有"))
        assertTrue(!holder.body.contains("试仓"))
    }

    @Test
    fun breakoutNeedsExpandedVolume() {
        // 突破压力但没放量：不算突破确认。
        assertNull(IntradayTactics.evaluate(input(price = 21.50, volumeRatio = 1.0)))
    }

    @Test
    fun breakoutCallsOutSectorTurnoverConfirmation() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = true,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "站上前高，放量，M20向上",
        )

        val signal = IntradayTactics.evaluate(
            input(
                price = 21.20,
                volumeRatio = 1.5,
                totalCapital = 100_000.0,
                entrySetup = setup,
                sectorChangePct = 1.4,
                sectorTurnoverRatio = 1.5,
            )
        )!!

        assertTrue(signal.body.contains("板块同步走强且放量"))
    }

    @Test
    fun breakoutCallsOutPositiveSectorMainFlowWithoutChangingSignal() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = true,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "站上前高，放量，M20向上",
        )

        val signal = IntradayTactics.evaluate(
            input(
                price = 21.20,
                volumeRatio = 1.5,
                totalCapital = 100_000.0,
                entrySetup = setup,
                sectorChangePct = 1.4,
                sectorTurnoverRatio = 1.5,
                sectorMainNetInflow = 1_250_000_000.0,
                sectorMainNetRatio = 3.25,
            )
        )!!

        assertEquals(IntradaySignalType.BREAKOUT, signal.type)
        assertTrue(signal.body.contains("主力净流入"))
        assertTrue(signal.body.contains("12.50亿"))
        assertTrue(signal.body.contains("3.25%"))
    }

    @Test
    fun negativeSectorMainFlowDiscountsBreakoutContinuation() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = true,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "站上前高，放量，M20向上",
        )

        val signal = IntradayTactics.evaluate(
            input(
                price = 21.20,
                volumeRatio = 1.5,
                totalCapital = 100_000.0,
                entrySetup = setup,
                sectorChangePct = 1.4,
                sectorTurnoverRatio = 1.5,
                sectorMainNetInflow = -800_000_000.0,
                sectorMainNetRatio = -1.40,
            )
        )!!

        assertEquals(IntradaySignalType.BREAKOUT, signal.type)
        assertTrue(signal.body.contains("主力净流出"))
        assertTrue(signal.body.contains("8.00亿"))
        assertTrue(signal.body.contains("持续性需打折"))
    }

    @Test
    fun breakoutWarnsWhenSectorDoesNotConfirm() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = true,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "站上前高，放量，M20向上",
        )

        val signal = IntradayTactics.evaluate(
            input(
                price = 21.20,
                volumeRatio = 1.5,
                totalCapital = 100_000.0,
                entrySetup = setup,
                sectorChangePct = -1.0,
                sectorTurnoverRatio = 0.7,
            )
        )!!

        assertTrue(signal.body.contains("板块未同步"))
    }

    @Test
    fun confirmedBreakoutUsesPriorHighAndRiskSizing() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = true,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "站上前高，放量，M20向上",
        )
        val signal = IntradayTactics.evaluate(
            input(
                price = 21.20,
                volumeRatio = 1.5,
                totalCapital = 100_000.0,
                entrySetup = setup,
            )
        )!!

        assertEquals(IntradaySignalType.BREAKOUT, signal.type)
        assertTrue(signal.body.contains("止损参考 19.00"))
        assertTrue(signal.body.contains("可考虑按风险试仓约"))
    }

    @Test
    fun weakMarketDoesNotTriggerNewEntryReminder() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = false,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.HEADWIND,
            stopPrice = 19.0,
            reason = "大盘偏弱，暂不确认新开仓",
        )
        assertNull(
            IntradayTactics.evaluate(
                input(price = 21.20, volumeRatio = 1.5, entrySetup = setup)
            )
        )
    }

    @Test
    fun confirmedM20PullbackUsesMa20AndContractingVolume() {
        val setup = EntrySetup(
            type = EntrySetupType.M20_PULLBACK,
            confirmed = true,
            canBuy = true,
            priorHigh = 22.0,
            ma20SlopePct = 0.8,
            volumeConfirmed = true,
            marketFilter = MarketFilter.NEUTRAL,
            stopPrice = 18.5,
            reason = "回踩M20，缩量，M20向上",
        )
        val signal = IntradayTactics.evaluate(
            input(
                price = 20.20,
                volumeRatio = 0.7,
                score = 3,
                totalCapital = 100_000.0,
                entrySetup = setup,
                ma20 = 20.0,
            )
        )!!

        assertEquals(IntradaySignalType.PULLBACK, signal.type)
        assertTrue(signal.body.contains("回踩M20"))
    }

    @Test
    fun unknownVolumeDoesNotCountAsContractingPullback() {
        val setup = EntrySetup(
            type = EntrySetupType.M20_PULLBACK,
            confirmed = true,
            canBuy = true,
            priorHigh = 22.0,
            ma20SlopePct = 0.8,
            volumeConfirmed = true,
            marketFilter = MarketFilter.NEUTRAL,
            stopPrice = 18.5,
            reason = "回踩M20，缩量，M20向上",
        )

        assertNull(
            IntradayTactics.evaluate(
                input(price = 20.20, volumeRatio = null, score = 3, entrySetup = setup, ma20 = 20.0)
            )
        )
    }

    @Test
    fun confirmedBreakoutWithoutBuyPermissionDoesNotTriggerEmptyEntryReminder() {
        val setup = EntrySetup(
            type = EntrySetupType.BREAKOUT,
            confirmed = true,
            canBuy = false,
            priorHigh = 21.0,
            ma20SlopePct = 1.2,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 19.0,
            reason = "突破结构成立，但仅作观察",
        )

        assertNull(
            IntradayTactics.evaluate(
                input(price = 21.20, volumeRatio = 1.5, entrySetup = setup)
            )
        )
    }

    @Test
    fun tSellFiresOnExpandedRallyAndSuggestsOneThird() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
        )!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("可先减约 600 股"))
        assertTrue(signal.body.contains("回踩企稳后再评估接回"))
    }

    @Test
    fun tSellQuantityUsesConservativeRatioWhenVolatilityIsHigh() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
                .copy(holdingTContext = input().holdingTContext!!.copy(atrPct = 6.0))
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("波动偏大"))
        assertTrue(signal.body.contains("可先减约 400 股"))
    }

    @Test
    fun tSellSkipsQuantityWhenTurnoverTooLow() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
                .copy(todayTurnoverYi = 0.0001)
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("流动性不足"))
        assertFalse(signal.body.contains("可先减约"))
    }

    @Test
    fun tSellQuantityDropsWhenMarketOrSectorIsWeak() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
                .copy(marketRegime = MarketRegime.WEAK, sectorMainNetRatio = -1.2)
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("按1/4"))
        assertTrue(signal.body.contains("500 股"))
    }

    @Test
    fun tSellQuantityDropsWhenSingleStockPositionIsTooHigh() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 20.70,
                changePct = 3.5,
                volumeRatio = 1.5,
                resistance = 22.0,
                quantity = 2_000.0,
                totalCapital = 100_000.0,
                maxPositionPct = 20.0,
            )
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("仓位偏高"))
        assertTrue(signal.body.contains("500 股"))
        assertFalse(signal.body.contains("600 股"))
    }

    @Test
    fun tSellQuantityDropsWhenMarketHasHighTurnoverButBroadDivergence() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 20.70,
                changePct = 3.5,
                volumeRatio = 1.5,
                resistance = 22.0,
                quantity = 2_000.0,
                predictedMarketTurnoverYi = 22_000.0,
                marketChangePct = 1.2,
                marketIndexPrice = 3200.0,
                marketIndexHighPrice = 3210.0,
                industryBreadth = 0.40,
                conceptBreadth = 0.45,
            )
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("大盘放量分歧"))
        assertTrue(signal.body.contains("500 股"))
    }

    @Test
    fun tSellQuantityDropsWhenSectorTurnoverContracts() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 20.70,
                changePct = 3.5,
                volumeRatio = 1.5,
                resistance = 22.0,
                quantity = 2_000.0,
                sectorChangePct = 0.8,
                sectorTurnoverRatio = 0.65,
            )
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("板块成交额收缩"))
        assertTrue(signal.body.contains("500 股"))
    }

    @Test
    fun tSellAlsoFiresAtResistanceWithoutVolume() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.90, changePct = 3.4, volumeRatio = 0.9, resistance = 21.0, quantity = 2_000.0)
        )!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    @Test
    fun tSellThresholdScalesWithPriceLimit() {
        // 创业板 20% 涨跌幅：3.5% 只是普通波动，不该提示做 T。
        assertNull(
            IntradayTactics.evaluate(
                input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, limitPct = 20.0,
                    resistance = 25.0, quantity = 2_000.0)
            )
        )
        // 6% 以上才是创业板的「冲高」。
        val signal = IntradayTactics.evaluate(
            input(price = 21.30, changePct = 6.5, volumeRatio = 1.5, limitPct = 20.0,
                resistance = 25.0, quantity = 2_000.0)
        )!!
        assertEquals(IntradaySignalType.T_SELL, signal.type)
    }

    @Test
    fun tSellRequiresRecentAnalysisResult() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.6, support = 0.0,
                resistance = 0.0, quantity = 2_000.0).copy(holdingTContext = null)
        )
        assertNull(signal)
    }

    @Test
    fun tBuyFiresOnPullbackThatHoldsSupport() {
        val signal = IntradayTactics.evaluate(
            input(price = 19.10, changePct = -3.6, volumeRatio = 0.8, support = 19.0, quantity = 2_000.0)
        )!!
        assertEquals(IntradaySignalType.T_BUY, signal.type)
        assertTrue(signal.body.contains("真实减出记录"))
        assertFalse(signal.body.contains("600 股"))
    }

    @Test
    fun tBuyDegradesWhenAvailableCapitalIsShort() {
        val signal = IntradayTactics.evaluate(
            input(price = 19.10, changePct = -3.6, volumeRatio = 0.8, support = 19.0,
                quantity = 2_000.0, availableCapital = 1_000.0)
        )!!
        assertEquals(IntradaySignalType.T_BUY, signal.type)
        assertTrue(signal.body.contains("可用资金"))
        assertFalse(signal.body.contains("补资金"))
    }

    @Test
    fun tBuyIsSilentOnceSupportIsLost() {
        // 跌了 3.6% 但已经跌穿支撑：那是减仓问题，不是接回机会。
        val signal = IntradayTactics.evaluate(
            input(price = 18.50, changePct = -3.6, volumeRatio = 0.8, support = 19.0, quantity = 2_000.0)
        )
        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal!!.type)
    }

    @Test
    fun pullbackNeedsPositiveScoreAndNoPosition() {
        val signal = IntradayTactics.evaluate(
            input(price = 19.20, support = 19.0, score = 3, totalCapital = 100_000.0)
        )!!
        assertEquals(IntradaySignalType.PULLBACK, signal.type)
        assertTrue(signal.body.contains("可考虑试仓约 200 股"))

        assertNull(IntradayTactics.evaluate(input(price = 19.20, support = 19.0, score = 1)))
        // 已经持仓就不再提示「试仓」。
        assertNull(IntradayTactics.evaluate(input(price = 19.20, support = 19.0, score = 3, quantity = 2_000.0)))
    }

    @Test
    fun tinyPositionFallsBackToTextInsteadOfRoundingUpToALot() {
        // 100 股的 1/3 不足一手，不能凑成 100 股把仓位全卖了。
        val signal = IntradayTactics.evaluate(
            input(price = 18.80, changePct = -3.2, quantity = 100.0)
        )!!
        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal.type)
        assertFalse(signal.body.contains("减约 100 股"))
        assertTrue(signal.body.contains("实际可卖数量"))
    }

    @Test
    fun breakingSupportDeepLossGivesCurrentPriorityInsteadOfCut() {
        // 成本 45、现价 30：浮亏 -33% 已属深套，破位处不该再喊「先割一部分」。
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0, totalCapital = 200_000.0)
        )!!
        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal.type)
        assertTrue(!signal.body.contains("减约 600"))
        assertTrue(signal.body.contains("当前优先"))
        assertTrue(signal.body.contains("基本面暂无数据"))
        assertTrue(signal.body.contains("先不补仓"))
        assertTrue(signal.body.contains("待核实"))
        assertFalse(signal.body.contains("剧本"))
    }

    @Test
    fun breakingSupportDeepLossWithoutCapitalStillGivesActionWithoutQuantity() {
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0)
        )!!
        assertTrue(signal.body.contains("当前优先"))
        assertFalse(signal.body.contains("不给具体股数"))
    }

    @Test
    fun breakingSupportDeepLossDoesNotInventRebuyQuantityFromCash() {
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0,
                totalCapital = 100_000.0, availableCapital = 2_000.0,
                fundamentalHealth = FundamentalHealth(
                    level = HealthLevel.SOUND,
                    score = 76,
                    verdict = "良好",
                    reasons = listOf("评分 76（良好）"),
                    analyzedAt = "2026-10-09 10:00",
                ))
        )!!
        assertTrue(signal.body.contains("先不补仓"))
        assertFalse(signal.body.contains("追加"))
    }

    @Test
    fun breakingSupportShallowLossKeepsCutAdvice() {
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 32.0, quantity = 2_000.0)
        )!!
        assertTrue(signal.body.contains("确认未收回则分批减仓"))
        assertFalse(signal.body.contains("先别急着割"))
    }

    @Test
    fun breakingSupportDeepLossWithRedFundamentalsGivesExitPriority() {
        val health = FundamentalHealth(
            level = HealthLevel.ALERT,
            score = 40,
            verdict = "较差",
            reasons = listOf("评分 40（较差）"),
            analyzedAt = "2026-10-09 10:00",
        )
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0,
                totalCapital = 100_000.0, fundamentalHealth = health)
                .copy(breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true))
        )!!
        assertTrue(signal.body.contains("亮红灯"))
        assertEquals(IntradaySignalType.EXIT_POSITION, signal.type)
        assertTrue(signal.body.contains("清仓退出"))
        assertFalse(signal.body.contains("保留底仓"))
        assertFalse(signal.body.contains("剧本①"))
    }

    @Test
    fun breakingSupportDeepLossWithSoundFundamentalsKeepsConditionalTWatch() {
        val health = FundamentalHealth(
            level = HealthLevel.SOUND,
            score = 76,
            verdict = "良好",
            reasons = listOf("评分 76（良好）"),
            analyzedAt = "2026-10-09 10:00",
        )
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0,
                totalCapital = 100_000.0, fundamentalHealth = health)
        )!!
        assertTrue(signal.body.contains("未见恶化"))
        assertTrue(signal.body.contains("滚动做T"))
        assertTrue(signal.body.contains("降低成本"))
        assertFalse(signal.body.contains("小仓补仓"))
        assertFalse(signal.body.contains("剧本②"))
    }

    @Test
    fun breakingSupportShallowLossAppendsFundamentalTendency() {
        val health = FundamentalHealth(
            level = HealthLevel.ALERT,
            score = 40,
            verdict = "较差",
            reasons = listOf("评分 40（较差）"),
            analyzedAt = "2026-10-09 10:00",
        )
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 32.0, quantity = 2_000.0,
                fundamentalHealth = health)
                .copy(breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true))
        )!!
        assertEquals(IntradaySignalType.EXIT_POSITION, signal.type)
        assertTrue(signal.body.contains("亮红灯"))
        assertTrue(signal.body.contains("不再只减1/3"))
    }

    @Test
    fun breakingSupportDeepLossWithoutFundamentalsSaysSo() {
        val signal = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 45.0, quantity = 500.0, totalCapital = 100_000.0)
        )!!
        assertTrue(signal.body.contains("基本面暂无数据"))
    }

    @Test
    fun reclaimSupportIsTheAddWindowForDeepLoss() {
        // 跌破后站回：深套的企稳确认窗口，有股数。
        val signal = IntradayTactics.evaluate(
            input(price = 30.20, support = 30.0, cost = 45.0, quantity = 500.0,
                totalCapital = 200_000.0, brokenSupport = true)
        )!!
        assertEquals(IntradaySignalType.RECLAIM_SUPPORT, signal.type)
        assertTrue(signal.body.contains("站回支撑"))
        assertTrue(signal.body.contains("滚动做T"))
        assertTrue(signal.body.contains("降低成本"))
        assertFalse(signal.body.contains("补仓"))
    }

    @Test
    fun reclaimSupportWithRedFundamentalsPrefersExitNotAdd() {
        val health = FundamentalHealth(
            level = HealthLevel.ALERT, score = 40, verdict = "较差",
            reasons = listOf("评分 40（较差）"), analyzedAt = "2026-10-09 10:00",
        )
        val signal = IntradayTactics.evaluate(
            input(price = 30.20, support = 30.0, cost = 45.0, quantity = 500.0,
                totalCapital = 200_000.0, brokenSupport = true, fundamentalHealth = health)
        )!!
        assertEquals(IntradaySignalType.RECLAIM_WATCH, signal.type)
        assertTrue(signal.body.contains("不补仓"))
        assertTrue(signal.body.contains("分批离场"))
    }

    @Test
    fun reclaimSupportShallowStillWarnsOfRebreak() {
        val signal = IntradayTactics.evaluate(
            input(price = 30.20, support = 30.0, cost = 32.0, quantity = 2_000.0, brokenSupport = true)
        )!!
        assertEquals(IntradaySignalType.RECLAIM_SUPPORT, signal.type)
        assertTrue(signal.body.contains("仍需防止再次跌破"))
    }

    @Test
    fun reclaimNeedsPriorBreak() {
        // 没破过位、价格在支撑上方是「回踩」，不是「站回」。
        assertNull(IntradayTactics.evaluate(input(price = 30.20, support = 30.0, quantity = 2_000.0)))
    }

    @Test
    fun exitWindowFiresForDeepRedAtResistance() {
        val health = FundamentalHealth(
            level = HealthLevel.ALERT, score = 40, verdict = "较差",
            reasons = listOf("评分 40（较差）"), analyzedAt = "2026-10-09 10:00",
        )
        val signal = IntradayTactics.evaluate(
            input(price = 32.00, support = 28.0, resistance = 32.2, cost = 45.0, quantity = 2_000.0,
                totalCapital = 200_000.0, fundamentalHealth = health, changePct = 1.0)
        )!!
        assertEquals(IntradaySignalType.EXIT_WINDOW, signal.type)
        assertTrue(signal.body.contains("分批离场"))
    }

    @Test
    fun exitWindowNeedsRedFundamentals() {
        // 深套但基本面没亮红灯：压力位不喊离场，留给「冲高做T」自己判断。
        assertNull(
            IntradayTactics.evaluate(
                input(price = 32.00, support = 28.0, resistance = 32.2, cost = 45.0, quantity = 2_000.0,
                    totalCapital = 200_000.0, changePct = 1.0)
            )
        )
    }

    @Test
    fun deepLossThresholdScalesWithVolatility() {
        // ATR 3.5% 的题材股：深套阈值 -28%，-22% 浮亏还走减仓口径。
        val shallow = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 38.5, quantity = 2_000.0, atrPct = 3.5)
        )!!
        assertTrue(shallow.body.contains("尚缺持续确认"))
        assertFalse(shallow.body.contains("当前优先"))
        // 同样 -22% 浮亏，ATR 1% 的稳态股阈值只有 -12%，已是深套。
        val deep = IntradayTactics.evaluate(
            input(price = 30.0, support = 30.5, cost = 38.5, quantity = 2_000.0, atrPct = 1.0)
        )!!
        assertTrue(deep.body.contains("当前优先"))
    }

    @Test
    fun reBreakAfterReclaimSaysCutNow() {
        val signal = IntradayTactics.evaluate(
            input(price = 18.80, support = 19.0, cost = 21.0, quantity = 2_000.0,
                brokenSupport = true, reclaimedSupport = true)
                .copy(breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(19.0, 18.62, true))
        )!!
        assertEquals(IntradaySignalType.REDUCE_POSITION, signal.type)
        assertTrue(signal.body.contains("连续有效行情未收回"))
        assertTrue(signal.body.contains("分批减仓"))
    }

    @Test
    fun quietMarketProducesNoSignal() {
        assertNull(IntradayTactics.evaluate(input(price = 20.0, changePct = 0.4, volumeRatio = 1.0,
            quantity = 2_000.0)))
    }

    @Test
    fun riskSignalIsImmediateButOtherSignalsNeedTwoRounds() {
        assertEquals(1, IntradayTactics.confirmationRounds(IntradaySignalType.BREAK_SUPPORT))
        assertEquals(2, IntradayTactics.confirmationRounds(IntradaySignalType.BREAKOUT))
        assertEquals(2, IntradayTactics.confirmationRounds(IntradaySignalType.T_SELL))
        assertEquals(2, IntradayTactics.confirmationRounds(IntradaySignalType.T_BUY))
        assertEquals(2, IntradayTactics.confirmationRounds(IntradaySignalType.PULLBACK))
    }

    // ── 环境改写：塌陷期买入侧一律收紧，减仓侧不动 ───────────────────────────

    @Test
    fun tBuyIsSuppressedInCollapse() {
        // 回踩接回是「加回来」，塌陷期那就是接飞刀——直接不提。
        val neutral = IntradayTactics.evaluate(
            input(price = 19.2, changePct = -3.5, quantity = 2_000.0),
        )
        val collapse = IntradayTactics.evaluate(
            input(price = 19.2, changePct = -3.5, quantity = 2_000.0, marketRegime = MarketRegime.COLLAPSE),
        )

        assertNotNull(neutral)
        assertEquals(IntradaySignalType.T_BUY, neutral!!.type)
        assertNull(collapse)
    }

    @Test
    fun tBuyIsSuppressedInWeak() {
        val signal = IntradayTactics.evaluate(
            input(price = 19.2, changePct = -3.5, quantity = 2_000.0, marketRegime = MarketRegime.WEAK),
        )

        assertNull(signal)
    }

    @Test
    fun pullbackIsSuppressedWhenEnvironmentIsWeak() {
        // 空仓试仓：转弱/塌陷都不该开新仓。
        val neutral = IntradayTactics.evaluate(input(price = 19.2, changePct = 0.0, score = 2))
        val weak = IntradayTactics.evaluate(
            input(price = 19.2, changePct = 0.0, score = 2, marketRegime = MarketRegime.WEAK),
        )
        val collapse = IntradayTactics.evaluate(
            input(price = 19.2, changePct = 0.0, score = 2, marketRegime = MarketRegime.COLLAPSE),
        )

        assertEquals(IntradaySignalType.PULLBACK, neutral!!.type)
        assertNull(weak)
        assertNull(collapse)
    }

    @Test
    fun deepReclaimAddOnIsSuppressedInCollapse() {
        // 成本 30、现价 19.2 = 深套；站回支撑本该给补仓窗口，塌陷期整条压掉。
        val neutral = IntradayTactics.evaluate(
            input(price = 19.2, changePct = 0.0, quantity = 2_000.0, cost = 30.0, brokenSupport = true),
        )
        val collapse = IntradayTactics.evaluate(
            input(
                price = 19.2, changePct = 0.0, quantity = 2_000.0, cost = 30.0, brokenSupport = true,
                marketRegime = MarketRegime.COLLAPSE,
            ),
        )

        assertEquals(IntradaySignalType.RECLAIM_SUPPORT, neutral!!.type)
        assertTrue(neutral.body.contains("企稳确认窗口"))
        assertEquals(IntradaySignalType.RECLAIM_WATCH, collapse!!.type)
        assertTrue(collapse.body.contains("站回也不补仓"))
        assertFalse(collapse.body.contains("补仓窗口"))
    }

    @Test
    fun breakoutWithNoPositionDoesNotChaseInWeakEnvironment() {
        val signal = IntradayTactics.evaluate(
            input(
                price = 21.5, changePct = 2.0, volumeRatio = 2.0,
                marketRegime = MarketRegime.WEAK,
            ),
        )

        if (signal?.type == IntradaySignalType.BREAKOUT) {
            assertTrue(signal.body.contains("突破也不追"))
            assertFalse(signal.body.contains("试仓约"))
        }
    }

    @Test
    fun reduceSideStillFiresInCollapse() {
        // 减仓侧不受环境压制——塌陷期反而更该提醒。
        val signal = IntradayTactics.evaluate(
            input(price = 18.80, changePct = -3.2, quantity = 2_000.0, marketRegime = MarketRegime.COLLAPSE),
        )

        assertEquals(IntradaySignalType.BREAK_SUPPORT, signal!!.type)
    }

    /**
     * 板块**龙头**强势途中，冲高后的第一次回落不该直接给减仓股数——容易被震下车。
     * 2026-10-19 中天科技冲高做T后继续冲高，同板块亨通光电当时已涨停，就是这个缺口。
     * 只认龙头，跟风小票强势不参与（上游 [com.example.stockmonitor.core.SectorLeaders] 挑人）。
     */
    @Test
    fun strongLeaderDefersShallowTSellWithoutGivingQuantity() {
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
                .copy(
                    strongLeaderCount = 1,
                    strongLeaderNames = listOf("亨通光电"),
                    dailyHighPrice = 21.5,
                    // 回落 0.7%：过了普通阈值（0.5%），没到龙头强势时要求的加深阈值（1.0%）。
                    holdingTObservation = HoldingTObservation(20.0, 21.5, 1.0, 0.7, rising = false, falling = true),
                ),
        )!!

        assertEquals(IntradaySignalType.T_SELL_DEFERRED, signal.type)
        assertTrue(signal.title.contains("板块状态参考"))
        assertTrue(signal.body.contains("非买卖指令"))
        assertTrue(signal.body.contains("亨通光电"))
        assertTrue(signal.body.contains("暂缓减仓"))
        assertTrue(signal.body.contains("回落加深"))
        assertTrue(signal.body.contains("龙头转弱"))
        assertFalse(signal.body.contains("可先减约"))
        assertFalse(signal.type.isRisk)
        assertEquals(2, IntradayTactics.confirmationRounds(signal.type))
    }

    @Test
    fun deepRetreatStillGivesTSellQuantityWhenLeaderIsStrong() {
        // 只降优先级，不静音：回落真加深到 2 倍阈值，照样给做T股数。
        val signal = IntradayTactics.evaluate(
            input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)
                .copy(
                    strongLeaderCount = 2,
                    strongLeaderNames = listOf("亨通光电", "烽火通信"),
                    holdingTObservation = HoldingTObservation(20.0, 21.5, 1.0, 2.0, rising = false, falling = true),
                ),
        )!!

        assertEquals(IntradaySignalType.T_SELL, signal.type)
        assertTrue(signal.body.contains("可先减约 600 股"))
        assertTrue(signal.body.contains("回落已加深"))
    }

    @Test
    fun weakenedLeaderOrMissingLeaderDataRestoresOrdinaryTSell() {
        // 龙头转弱（强势家数归 0）就恢复做T；拿不到龙头名单也一样，不能把减仓提示吞掉。
        val shallow = HoldingTObservation(20.0, 21.5, 1.0, 0.7, rising = false, falling = true)
        val base = input(price = 20.70, changePct = 3.5, volumeRatio = 1.5, resistance = 22.0, quantity = 2_000.0)

        // 龙头名单暂缺（null）。
        assertEquals(
            IntradaySignalType.T_SELL,
            IntradayTactics.evaluate(base.copy(holdingTObservation = shallow))!!.type,
        )
        // 龙头转弱：之前还强，现在不强了 → 强势家数 0，立刻恢复做T。
        assertEquals(
            IntradaySignalType.T_SELL,
            IntradayTactics.evaluate(base.copy(holdingTObservation = shallow, strongLeaderCount = 0))!!.type,
        )
    }
}
