package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import com.example.stockmonitor.core.*
import org.junit.Assert.*
import org.junit.Test

class PortfolioSwingTest {
    @Test
    fun highPositionReductionBlocksBothNewCapitalAndAutomaticDeepLossT() {
        listOf(10.0, 30.0).forEach { cost ->
            val result = plan(evidence().copy(exitStage = HoldingExitStage.HIGH_REDUCE),
                portfolio.copy(positions = mutableListOf(stock.copy(cost = cost)))).rows.single()
            assertEquals(SwingStatus.BLOCKED, result.status)
            assertEquals(0.0, result.addQuantity, 0.0)
            assertTrue(result.reason.contains("高位转弱"))
        }
    }

    @Test
    fun exitPriorityBlocksDeepLossTAndNewCapital() {
        val deep = portfolio.copy(positions = mutableListOf(stock.copy(cost = 30.0)))
        val result = plan(evidence().copy(exitStage = HoldingExitStage.EXIT), deep).rows.single()
        assertEquals(SwingStatus.BLOCKED, result.status)
        assertEquals(0.0, result.addQuantity, 0.0)
        assertTrue(result.reason.contains("退出优先"))
    }

    private val now = 1_800_000_000_000L
    private val date = "2026-10-15"
    private val market = MarketState(MarketRegime.BULL, "向上", 0.5, 1.0, 2.0, 1.0, 2.0)
    private val stock = Position("sh600000", "测试持仓", 500.0, 10.0)
    private val portfolio = Portfolio(100_000.0, 95_000.0, 5.0, 20.0, mutableListOf(stock))
    private val cycles = MultiTimeframeSummary(
        weekTrend = SwingTrend.UP, monthTrend = SwingTrend.UP, ma250 = 9.0, asOf = date,
    )
    private val entry = EntrySetup(EntrySetupType.M20_PULLBACK, true, true, false,
        12.0, 1.0, true, MarketFilter.UP, 9.5, "回踩企稳")
    private fun evidence(code: String = stock.code) = SwingEvidence(
        quote = Quote(code, "测试持仓", 10.0, 10.0, 9.9, 10.1, 9.8, 1000.0, 10000.0, date),
        cycles = cycles, entry = entry, ma20 = 10.0, resistance = 12.0, atrPct = 2.0,
        health = FundamentalHealthSnapshot(FundamentalHealth(HealthLevel.SOUND, 80, "良好", emptyList(), date), now),
        sectorRegime = MarketRegime.BULL,
    )
    private fun plan(input: SwingEvidence = evidence(), config: Portfolio = portfolio, state: MarketState? = market) =
        PortfolioSwing.evaluate(config, mapOf(stock.code to input), state, date, now)

    @Test
    fun qualifiedHoldingGetsIncrementalQuantityNotTargetPosition() {
        val result = plan()
        assertEquals(stock.code, result.preferredCode)
        assertEquals(500.0, result.rows.single().addQuantity, 0.0)
        assertTrue(result.rows.single().estimatedCash <= 20_000.0)
        assertTrue(result.rows.single().afterPositionPct!! <= 20.0)
        assertTrue(result.rows.single().entryHigh!! <= 10.2 + 1e-8)
    }

    @Test
    fun cashAndExistingExposureAndRiskLimitQuantity() {
        assertEquals(0.0, plan(config = portfolio.copy(availableCapitalOverride = 0.0)).rows.single().addQuantity, 0.0)
        assertEquals(0.0, plan(config = portfolio.copy(availableCapitalOverride = 1000.0)).rows.single().addQuantity, 0.0)
        val nearlyFull = portfolio.copy(availableCapital = 81_000.0, positions = mutableListOf(stock.copy(quantity = 1900.0)))
        assertEquals(100.0, plan(config = nearlyFull).rows.single().addQuantity, 0.0)
        assertEquals(0.0, plan(config = portfolio.copy(availableCapitalOverride = 80_000.0, positions = mutableListOf(stock.copy(quantity = 2000.0)))).rows.single().addQuantity, 0.0)
        val riskLimited = evidence().copy(entry = entry.copy(stopPrice = 8.0), resistance = 15.0)
        val result = plan(riskLimited, portfolio.copy(initialEntryPct = 50.0, maxPositionPct = 80.0)).rows.single()
        assertTrue(result.addQuantity * (10.0 - 8.0) <= 1000.0)
    }

    @Test
    fun noBuyOnUnconfirmedTrendWeakLimitUpOrPoorRewardRisk() {
        val original = evidence()
        listOf(
            original.copy(entry = entry.copy(confirmed = false)),
            original.copy(entry = entry.copy(canBuy = false)),
            original.copy(entry = entry.copy(type = EntrySetupType.BREAKOUT)),
            original.copy(entry = entry.copy(trendWeak = true)),
            original.copy(cycles = cycles.copy(weekTrend = SwingTrend.WEAK)),
            original.copy(cycles = cycles.copy(monthTrend = SwingTrend.WEAK)),
            original.copy(cycles = cycles.copy(ma250 = 11.0)),
            original.copy(resistance = 10.5),
            original.copy(entry = entry.copy(stopPrice = 10.5)),
            original.copy(quote = original.quote.copy(price = 11.0, high = 11.0)),
            original.copy(sectorRegime = MarketRegime.COLLAPSE),
        ).forEach { input -> assertEquals(input.toString(), 0.0, plan(input).rows.single().addQuantity, 0.0) }
    }

    @Test
    fun missingStaleInvalidOrRedEvidenceDoesNotInventBuyQuantity() {
        val original = evidence()
        listOf(
            original.copy(health = null),
            original.copy(health = original.health!!.copy(fetchedAtMs = now - FundamentalHealthPolicy.CACHE_TTL_MS - 1)),
            original.copy(health = original.health!!.copy(health = original.health.health.copy(level = HealthLevel.ALERT))),
            original.copy(cycles = cycles.copy(issue = "历史不足")),
            original.copy(quote = original.quote.copy(quoteDate = "2026-10-14")),
            original.copy(quote = original.quote.copy(price = Double.NaN)),
            original.copy(quote = original.quote.copy(volume = 0.0)),
        ).forEach { assertEquals(0.0, plan(it).rows.single().addQuantity, 0.0) }
        assertNull(plan(state = null).preferredCode)
        assertNull(plan(state = market.copy(regime = MarketRegime.WEAK)).preferredCode)
        assertNull(plan(config = portfolio.copy(totalCapital = Double.NaN)).preferredCode)
        assertNull(plan(config = portfolio.copy(availableCapital = 99_000.0)).preferredCode)
    }

    @Test
    fun deepLossSeparatesTFromNewCapitalAndDoesNotInventRepurchaseShares() {
        val result = plan(config = portfolio.copy(positions = mutableListOf(stock.copy(cost = 20.0))))
        assertEquals(SwingStatus.T_ONLY, result.rows.single().status)
        assertEquals(0.0, result.rows.single().addQuantity, 0.0)
        assertTrue(result.reportLines().joinToString().contains("减出记录"))
        assertFalse(result.reportLines().joinToString().contains("建议割肉"))
    }

    @Test
    fun changedCapitalOrHoldingsRevokesOldQuantity() {
        val original = plan()
        assertEquals(stock.code, original.validatedFor(portfolio).preferredCode)
        val changed = original.validatedFor(portfolio.copy(availableCapital = 0.0))
        assertNull(changed.preferredCode)
        assertEquals(0.0, changed.rows.single().estimatedCash, 0.0)
        assertTrue(changed.headline().contains("0股"))
        portfolio.positions[0] = stock.copy(quantity = 2000.0)
        assertNull(original.validatedFor(portfolio).preferredCode)
    }

    @Test
    fun unverifiedCapitalCannotEnlargeTheAllocationBudget() {
        assertNull(plan(config = portfolio.copy(availableCapital = 20_000.0)).preferredCode)
        val result = plan(config = portfolio.copy(availableCapital = 24_000.0,
            positions = mutableListOf(stock.copy(quantity = 100.0))))
        assertEquals(100.0, result.rows.single().addQuantity, 0.0)
        assertEquals(8.0, result.rows.single().afterPositionPct!!, 0.0)
    }

    @Test
    fun existingPositionRiskVetoesOtherwiseQualifiedAddition() {
        val result = plan(evidence().copy(positionRisk = "持仓信号仍为减仓风险"))
        assertNull(result.preferredCode)
        assertTrue(result.rows.single().reason.contains("减仓风险"))
    }

    @Test
    fun malformedCurrentQuoteDoesNotReuseHistoricalBuyPoint() {
        val original = evidence()
        listOf(
            original.quote.copy(high = 0.0),
            original.quote.copy(prevClose = Double.NaN),
            original.quote.copy(low = 10.5),
        ).forEach { quote -> assertNull(plan(original.copy(quote = quote)).preferredCode) }
    }

    @Test
    fun deepLossDoesNotHideFundamentalAlertBehindTAdvice() {
        val original = evidence()
        val alert = original.copy(health = original.health!!.copy(health = original.health.health.copy(level = HealthLevel.ALERT)))
        val result = plan(alert, portfolio.copy(positions = mutableListOf(stock.copy(cost = 20.0))))
        assertNull(result.preferredCode)
        assertEquals(SwingStatus.BLOCKED, result.rows.single().status)
        assertTrue(result.rows.single().reason.contains("基本面"))
    }

    @Test
    fun onlyOneHoldingReceivesCapitalAndUnknownPortfolioExposureBlocksAll() {
        val second = stock.copy(code = "sz000001", name = "第二只")
        val config = portfolio.copy(availableCapital = 90_000.0, positions = mutableListOf(stock, second, stock.copy(code = "sz000002", quantity = 0.0)))
        val evidence = mapOf(stock.code to evidence(), second.code to evidence(second.code).copy(resistance = 11.2))
        val result = PortfolioSwing.evaluate(config, evidence, market, date, now)
        assertEquals(2, result.rows.size)
        assertEquals(stock.code, result.preferredCode)
        assertEquals(1, result.rows.count { it.addQuantity > 0 })
        val incomplete = PortfolioSwing.evaluate(config, evidence - second.code, market, date, now)
        assertNull(incomplete.preferredCode)
        assertTrue(incomplete.reportLines().joinToString().contains("缺少持仓现价"))
    }

    @Test
    fun boardMinimumsAndUnitsAreRespected() {
        assertEquals(0.0, PortfolioSwing.roundQuantity("sh688001", 199.0), 0.0)
        assertEquals(237.0, PortfolioSwing.roundQuantity("sh688001", 237.8), 0.0)
        assertEquals(200.0, PortfolioSwing.roundQuantity("sz000001", 237.8), 0.0)
        assertEquals(237.0, PortfolioSwing.roundQuantity("bj920001", 237.8), 0.0)
        assertEquals(0.0, PortfolioSwing.roundQuantity("usAAPL", 237.8), 0.0)
    }
}
