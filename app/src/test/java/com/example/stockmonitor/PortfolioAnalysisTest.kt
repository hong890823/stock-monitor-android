package com.example.stockmonitor

import com.example.stockmonitor.analysis.BoardTag
import com.example.stockmonitor.analysis.ChipPeaks
import com.example.stockmonitor.analysis.DivergenceResult
import com.example.stockmonitor.analysis.DivergenceState
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.EnvContext
import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.HealthLevel
import com.example.stockmonitor.analysis.HighPositionDecision
import com.example.stockmonitor.analysis.HighPositionPhase
import com.example.stockmonitor.analysis.KdjValues
import com.example.stockmonitor.analysis.MacdValues
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.MajorTrend
import com.example.stockmonitor.analysis.NorthboundFlowSummary
import com.example.stockmonitor.analysis.OpeningStructure
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.StrongContinuationEvent
import com.example.stockmonitor.analysis.ShortTermTrend
import com.example.stockmonitor.analysis.TechnicalResult
import com.example.stockmonitor.analysis.TurnoverContext
import com.example.stockmonitor.analysis.TrendStrength
import com.example.stockmonitor.analysis.TrendAssessment
import com.example.stockmonitor.analysis.Volatility
import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.RowSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持仓「减仓风险」的应对文案：浅亏走「不能快速收回再减」，深套只给当前优先方案。
 * 这里锁住的是策略口径——改文案前先想清楚是不是有意为之。
 */
class PortfolioAnalysisTest {
    private fun todayQuote(code: String, price: Double, previous: Double, date: String = com.example.stockmonitor.analysis.TimeUtils.todayText()) =
        com.example.stockmonitor.core.Quote(code, code, price, previous, previous,
            maxOf(price, previous), minOf(price, previous), 1000.0, 10000.0, date)

    @Test fun todayBriefAddsDailyProfitAndSameDayIndexComparison() {
        val config = portfolio()
        val quotes = mapOf("sh600522" to todayQuote("sh600522", 30.6, 30.0),
            "sh000001" to todayQuote("sh000001", 3030.0, 3000.0))
        val lines = PortfolioAnalysis.todayBrief(emptyList(), config, quotes = quotes)
        val daily = lines.first { it.startsWith("今日持仓：") }
        assertTrue(daily, daily.contains("+300.00元（+2.00%）"))
        assertTrue(daily, daily.contains("沪指 +1.00%"))
        assertTrue(daily, daily.contains("跑赢1.00个百分点"))
        val losing = PortfolioAnalysis.todayBrief(emptyList(), config,
            quotes = quotes + ("sh600522" to todayQuote("sh600522", 29.4, 30.0))).joinToString()
        assertTrue(losing.contains("-300.00元（-2.00%）"))
        assertTrue(losing.contains("跑输3.00个百分点"))
        val flat = PortfolioAnalysis.todayBrief(emptyList(), config,
            quotes = quotes + ("sh600522" to todayQuote("sh600522", 30.3, 30.0))).joinToString()
        assertTrue(flat.contains("与沪指基本持平"))
    }

    @Test fun todayBriefDoesNotComparePartialOrStaleHoldingsWithIndex() {
        val config = portfolio().copy(positions = (portfolio().positions + Position("sz000001", "另一只", 100.0, 10.0)).toMutableList())
        val quotes = mapOf("sh600522" to todayQuote("sh600522", 30.6, 30.0),
            "sh000001" to todayQuote("sh000001", 3030.0, 3000.0))
        val partial = PortfolioAnalysis.todayBrief(emptyList(), config, quotes = quotes).joinToString()
        assertTrue(partial.contains("已获取的1只持仓"))
        assertTrue(partial.contains("1只缺少今日有效行情"))
        assertFalse(partial.contains("跑赢"))
        val stale = PortfolioAnalysis.todayBrief(emptyList(), portfolio(), quotes = quotes +
            ("sh600522" to todayQuote("sh600522", 30.6, 30.0, "2000-01-01"))).joinToString()
        assertTrue(stale.contains("今日持仓：暂时算不出"))
        assertFalse(stale.contains("跑赢"))
        val noIndex = PortfolioAnalysis.todayBrief(emptyList(), portfolio(), quotes = quotes - "sh000001").joinToString()
        assertTrue(noIndex.contains("沪指今日行情暂缺"))
        assertFalse(noIndex.contains("跑赢"))
        val staleIndex = PortfolioAnalysis.todayBrief(emptyList(), portfolio(), market = env(MarketRegime.BULL).market,
            quotes = quotes + ("sh000001" to todayQuote("sh000001", 3030.0, 3000.0, "2000-01-01"))).joinToString()
        assertTrue(staleIndex.contains("沪指今日行情暂缺"))
        assertFalse(staleIndex.contains("跑赢"))
        val invalid = PortfolioAnalysis.todayBrief(emptyList(), portfolio(),
            quotes = quotes + ("sh600522" to todayQuote("sh600522", 30.6, 0.0))).joinToString()
        assertTrue(invalid.contains("今日持仓：暂时算不出"))
    }

    @Test fun todayBriefExplainsActualFinancialConcernWithoutOrderingSale() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false,
            health = FundamentalHealth(HealthLevel.ALERT, 50, "偏弱", listOf("经营现金流持续为负"), "2026-10-16"))
        val text = PortfolioAnalysis.todayBrief(listOf(result), portfolio()).joinToString()
        assertTrue(text.contains("经营现金流持续为负"))
        assertTrue(text.contains("先别加仓，查看最新财报或公告"))
        assertFalse(text.contains("基本面风险先核实"))
        assertFalse(text.contains("清仓"))
        assertFalse(text.contains("单股集中度"))
        val missing = PortfolioAnalysis.todayBrief(listOf(result.copy(fundamentalHealth = result.fundamentalHealth!!.copy(reasons = emptyList()))), portfolio()).joinToString()
        assertTrue(missing.contains("具体原因未提供"))
        assertFalse(missing.contains("经营现金流"))
    }

    @Test
    fun todayBriefShowsAccountPnlRatioWithoutExposingPerStockPosition() {
        val result = evaluate(price = 100.0, support = 90.0, cost = 80.0, trendWeak = false).copy(
            quantity = 500.0,
            price = 100.0,
            marketValue = 50_000.0,
            positionPct = 50.0,
        )
        val configured = Portfolio(
            totalCapital = 100_000.0,
            availableCapital = 50_000.0,
            maxPositionPct = 60.0,
            positions = mutableListOf(Position(result.code, result.name, 500.0, 80.0)),
        )
        val brief = PortfolioAnalysis.todayBrief(listOf(result), configured).joinToString("\n")
        assertTrue(brief.contains("账户总盈亏：+0.00元（+0.00%）"))
        assertTrue(brief.contains("最值得操作：目前没有哪只需要优先买卖"))
        assertFalse(brief.contains("占总资金 50.0%"))
        assertFalse(brief.contains("中天科技："))
    }

    @Test
    fun todayBriefPrioritizesRiskStockAndUsesPositionConcentrationInOverallAdvice() {
        val risk = evaluate(price = 30.0, support = 30.5, cost = 32.0).copy(
            quantity = 2_000.0,
            marketValue = 60_000.0,
            positionPct = 60.0,
        )
        val steady = evaluate(price = 31.0, support = 20.0, cost = 30.0, trendWeak = false).copy(
            code = "sz000001",
            name = "稳健持仓",
            quantity = 500.0,
            marketValue = 10_000.0,
            positionPct = 10.0,
        )
        val configured = Portfolio(
            totalCapital = 100_000.0,
            availableCapital = 30_000.0,
            maxPositionPct = 20.0,
            positions = mutableListOf(
                Position(risk.code, risk.name, risk.quantity, risk.cost),
                Position(steady.code, steady.name, steady.quantity, steady.cost),
            ),
        )
        val brief = PortfolioAnalysis.todayBrief(listOf(risk, steady), configured).joinToString("\n")
        assertTrue(brief.contains("最值得操作：中天科技：刚跌破关键价位，先别加仓，看能否站回"))
        assertTrue(brief.contains("先别继续买入，看能否站回关键价位"))
        assertFalse(brief.contains("稳健持仓："))
    }

    @Test
    fun todayBriefDoesNotChangeFullReportBriefContract() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)
        val legacy = PortfolioAnalysis.brief(listOf(result))
        assertTrue(legacy.size == 5)
        assertTrue(legacy[0].startsWith("最高优先级操作："))
        assertTrue(legacy[1].startsWith("今日结果："))
        assertTrue(legacy[3].startsWith("持仓整体操作建议："))
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-16 10:00")
        PortfolioAnalysis.todayBrief(listOf(result), portfolio())
        assertEquals(report, PortfolioAnalysis.formatReport(listOf(result), "2026-10-16 10:00"))
    }

    @Test
    fun confirmedHighPositionBreakGetsExplicitReductionPriorityWithoutForcingExit() {
        val technical = technical(price = 30.0, support = 30.5, trendWeak = false).copy(
            priceContext = com.example.stockmonitor.analysis.PriceContext(
                com.example.stockmonitor.analysis.PriceLocation.HIGH, 0.5))
        val result = PortfolioAnalysis.evaluate(Position("sh600522", "中天科技", 500.0, 32.0),
            technical, null, portfolio(), breakObservation =
                com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true))
        assertTrue(result.exitDecision?.stage?.label == "高位转弱·优先减仓")
        assertTrue(result.action.contains("高位"))
        assertFalse(result.action.contains("清仓退出"))
        assertTrue(PortfolioAnalysis.overallAdvice(listOf(result)).joinToString().contains("高位"))
    }

    @Test
    fun highRolloverReportAndIntradayAgreeBeforeSupportBreak() {
        val recent = com.example.stockmonitor.analysis.HoldingTContext("2026-10-15", 1000L,
            com.example.stockmonitor.analysis.HoldingTTrend.EXTENDED, 8.0, 2.0,
            com.example.stockmonitor.analysis.SwingTrend.UP, com.example.stockmonitor.analysis.SwingTrend.UP)
        val observation = com.example.stockmonitor.analysis.HoldingTObservation(30.8, 31.7, 0.0, 2.0, false, true)
        val technical = technical(price = 31.0, support = 30.0, trendWeak = false).copy(
            priceChange = 0.5, volumeRatio = 1.5,
            priceContext = com.example.stockmonitor.analysis.PriceContext(
                com.example.stockmonitor.analysis.PriceLocation.HIGH, 0.5))
        val result = PortfolioAnalysis.evaluate(Position("sh600522", "中天科技", 500.0, 45.0),
            technical, null, portfolio(), holdingTContext = recent, holdingTObservation = observation)
        val signal = com.example.stockmonitor.analysis.IntradayTactics.evaluate(
            com.example.stockmonitor.analysis.IntradayTactics.Input(result.code, result.name, result.price,
                result.changePct, result.volumeRatio, 10.0, result.support, result.resistance, result.score,
                result.quantity, result.cost, 400000.0, 100000.0, 5.0, 20.0,
                priceContext = technical.priceContext, holdingTContext = recent, holdingTObservation = observation))!!
        assertTrue(result.exitDecision?.stage == com.example.stockmonitor.analysis.HoldingExitStage.HIGH_REDUCE)
        assertTrue(signal.body.contains(result.exitDecision!!.action))
        assertFalse(signal.body.contains("跌破支撑"))
        val advice = PortfolioAnalysis.overallAdvice(listOf(result)).joinToString()
        assertTrue(advice.contains("高位转弱"))
        assertFalse(advice.contains("深套处理"))
        assertTrue(PortfolioAnalysis.formatReport(listOf(result), "2026-10-15 10:00").contains("高位转弱·优先减仓"))
        val brief = PortfolioAnalysis.brief(listOf(result)).joinToString()
        assertFalse(brief.contains("破位 1 只"))
        assertTrue(brief.contains("高位转弱 1 只"))
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-15 10:00",
            marketEnvironment = com.example.stockmonitor.analysis.MarketEnvironmentQuality.evaluate(null, null))
        assertFalse(report.contains("持仓中有1只出现破位"))
        assertTrue(report.contains("持仓中有1只高位转弱"))
    }

    @Test
    fun reportAndIntradayShareExitDecisionAndSummaryDoesNotRecommendTForInvalidatedDeepHolding() {
        val observation = com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true)
        val result = evaluate(price = 29.5, support = 30.5, cost = 45.0, breakObservation = observation)
        val signal = com.example.stockmonitor.analysis.IntradayTactics.evaluate(
            com.example.stockmonitor.analysis.IntradayTactics.Input(result.code, result.name, result.price,
                result.changePct, result.volumeRatio, 10.0, result.support, result.resistance, result.score,
                result.quantity, result.cost, 400000.0, 100000.0, 5.0, 20.0, entrySetup = result.entrySetup,
                breakObservation = observation))!!
        assertTrue(result.action.contains(result.exitDecision!!.action))
        assertTrue(signal.body.contains(result.exitDecision.action))
        assertTrue(signal.type == com.example.stockmonitor.analysis.IntradaySignalType.EXIT_POSITION)
        val advice = PortfolioAnalysis.overallAdvice(listOf(result)).joinToString("\n")
        assertTrue(advice.contains("清仓退出"))
        assertFalse(advice.contains("深套处理"))
        assertFalse(advice.contains("企稳后再评估接回"))
    }

    @Test
    fun confirmedBreakReducesButDoesNotForceExitWithIntactStructure() {
        val result = evaluate(price = 30.0, support = 30.5, cost = 32.0, trendWeak = false,
            breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true))
        assertTrue(result.exitDecision?.stage == com.example.stockmonitor.analysis.HoldingExitStage.REDUCE)
        assertTrue(result.action.contains("分批减仓"))
        assertFalse(result.action.contains("清仓退出"))
    }

    @Test
    fun risingMa20IntactPullbackKeepsObservationPositionInsteadOfSellingForProfit() {
        val entry = EntrySetup(
            type = EntrySetupType.NONE,
            confirmed = false,
            trendWeak = false,
            priorHigh = null,
            ma20SlopePct = 1.2,
            volumeConfirmed = false,
            marketFilter = MarketFilter.NEUTRAL,
            stopPrice = 29.5,
            reason = "MA20上行且回踩未破坏结构",
        )
        val assessment = TrendAssessment(
            shortTerm = ShortTermTrend.UP,
            major = MajorTrend.UP,
            reason = "短中期趋势向上",
            allowsOrdinaryBuy = true,
            allowsAddPosition = true,
            allowsTBuy = true,
            allowsTSell = true,
            allowsBottomReversal = false,
            riskOnly = false,
        )
        val technical = technical(price = 31.0, support = 30.0, trendWeak = false).copy(
            priceChange = 3.5,
            ma5 = 30.8,
            ma20 = 30.0,
            entrySetup = entry,
            trendAssessment = assessment,
            score = 3,
        )

        val result = PortfolioAnalysis.evaluate(
            position = Position("sh600522", "中天科技", 500.0, 28.0),
            technical = technical,
            live = null,
            portfolio = portfolio(),
        )

        assertEquals("持有观察", result.signal)
        assertTrue(result.action.contains("MA20仍上行"))
        assertTrue(result.action.contains("保留观察仓等待二波"))
        assertTrue(result.action.contains("已有浮盈不是卖出理由"))
        val advice = PortfolioAnalysis.overallAdvice(listOf(result)).joinToString("\n")
        assertTrue(advice.contains("保留观察仓等待二波"))
        assertFalse(advice.contains("冲高到压力附近滞涨再考虑分批止盈"))
    }

    @Test
    fun positionReportCarriesAdaptiveRhythmAndUnknownWhenMarketBreadthIsMissing() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 28.0, trendWeak = false).copy(
            entrySetup = EntrySetup(
                type = EntrySetupType.NONE,
                confirmed = false,
                trendWeak = false,
                priorHigh = null,
                ma20SlopePct = 1.0,
                volumeConfirmed = false,
                marketFilter = MarketFilter.NEUTRAL,
                stopPrice = 29.5,
                reason = "结构完整",
            ),
        )

        assertEquals(
            com.example.stockmonitor.analysis.AdaptiveRhythmPhase.UNKNOWN,
            result.rhythm?.phase,
        )
        assertTrue(PortfolioAnalysis.formatReport(listOf(result), "2026-10-15 10:00").contains("市场节奏"))
    }

    @Test
    fun swingRecommendationPrecedesDetailsAndDoesNotAlsoSayHoldStill() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)
        val row = com.example.stockmonitor.analysis.SwingDecision(result.code, result.name,
            com.example.stockmonitor.analysis.SwingStatus.READY, 90, "周月向好且日线企稳",
            price = 31.0, entryLow = 30.0, entryHigh = 31.1, riskPrice = 29.0, target = 36.0,
            addQuantity = 100.0, estimatedCash = 3105.0, afterPositionPct = 6.0)
        val plan = com.example.stockmonitor.analysis.SwingPlan(listOf(row), "2026-10-15", null)
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-15 10:00", swingPlan = plan)
        assertFalse(report.contains("波段加仓优先级"))
        assertTrue(report.contains("追加100股"))
        assertFalse(report.contains("今天不用动"))
        assertFalse(report.contains("建议风险仓位"))
        assertTrue(PortfolioAnalysis.brief(listOf(result), swingPlan = plan)[3].contains("追加100股"))
    }

    @Test
    fun swingReportExplicitlyKeepsZeroWhenNoQualifiedHoldingExists() {
        val plan = com.example.stockmonitor.analysis.SwingPlan(emptyList(), "2026-10-15", "可用资金为0")
        val report = PortfolioAnalysis.formatReport(emptyList(), "2026-10-15 10:00", swingPlan = plan)
        assertTrue(report.contains("本轮不建议新增买入，追加0股"))
        assertTrue(report.indexOf("本轮不建议新增买入") == report.lastIndexOf("本轮不建议新增买入"))
        assertTrue(report.contains("可用资金为0"))
    }

    @Test
    fun overallAdviceIsAtReportTopAndSharedWithBriefWithoutRepeatingIt() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)
        val plan = com.example.stockmonitor.analysis.SwingPlan(emptyList(), "2026-10-15", "可用资金为0")
        val advice = PortfolioAnalysis.overallAdvice(listOf(result), plan)
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-15 10:00", swingPlan = plan)
        val brief = PortfolioAnalysis.brief(listOf(result), swingPlan = plan).joinToString("\n")
        assertTrue(report.indexOf("持仓整体操作建议") in 0 until report.indexOf("今日要点"))
        advice.forEach { line ->
            assertTrue(brief.contains(line))
            assertTrue(report.substringBefore("今日要点").contains(line))
        }
        assertFalse(report.substringAfter("今日要点").substringBefore("波段加仓优先级").contains("持仓整体操作建议"))
        assertTrue(advice.any { it.contains("追加0股") })
    }

    @Test
    fun briefOmitsBothAvailableAndMissingSectorDescriptions() {
        listOf("板块：半导体｜板块今日 -3.20%", "板块归属暂缺，接口无法获取").forEach { note ->
            val result = evaluate().copy(sectorNote = note)
            val brief = PortfolioAnalysis.brief(listOf(result), market = env(MarketRegime.WEAK).market).joinToString("\n")
            assertFalse(brief.contains("板块"))
            assertFalse(brief.contains("半导体"))
            assertFalse(brief.contains("接口"))
            assertTrue(brief.contains("大盘"))
        }
    }

    @Test
    fun preferredAdditionDoesNotHideOtherHoldingsRiskOrSayPreferredMustStayStill() {
        val risk = evaluate(cost = 32.0).copy(name = "风险持仓")
        val preferred = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)
            .copy(code = "sz000001", name = "优先持仓")
        val row = com.example.stockmonitor.analysis.SwingDecision(preferred.code, preferred.name,
            com.example.stockmonitor.analysis.SwingStatus.READY, 90, "基本面与技术条件通过",
            price = 31.0, addQuantity = 100.0)
        val plan = com.example.stockmonitor.analysis.SwingPlan(listOf(row), "2026-10-15", null)
        val brief = PortfolioAnalysis.brief(listOf(risk, preferred), swingPlan = plan)[3]
        assertTrue(brief.contains("风险持仓"))
        assertTrue(brief.contains("持续未收回则减仓"))
        assertTrue(brief.contains("优先持仓"))
        assertTrue(brief.contains("追加100股"))
        assertFalse(brief.contains("今天不用动"))
    }

    @Test
    fun overallAdviceSeparatesDeepLossTAndFundamentalRiskWithoutInventingAddition() {
        val deep = evaluate().copy(name = "深套持仓")
        val alert = deep.copy(code = "sz000001", name = "红灯持仓",
            fundamentalHealth = FundamentalHealth(HealthLevel.ALERT, 40, "较差", listOf("财务风险"), "2026-10-15"))
        val watch = deep.copy(code = "sz000002", name = "非持仓观察", quantity = 0.0)
        val advice = PortfolioAnalysis.overallAdvice(listOf(deep, alert, watch)).joinToString("\n")
        assertTrue(advice.contains("深套持仓"))
        assertTrue(advice.contains("机动仓做T"))
        assertTrue(advice.contains("深套不盲目补仓"))
        assertTrue(advice.contains("红灯持仓"))
        assertTrue(advice.contains("基本面"))
        assertFalse(advice.contains("非持仓观察"))
        assertTrue(advice.contains("暂不新增买入"))
        assertFalse(advice.contains("割肉"))
    }

    @Test fun eventCoilIsHighlightedInTodayBriefAndDetailedInReport() {
        // 事件后蓄势要在持仓页「今日要点」置顶高亮（用户靠它决定重点盯哪只），报告里给完整注记。
        val signal = com.example.stockmonitor.analysis.EventCoil.Signal(
            eventDate = "2026-10-20",
            eventTitle = "华天科技:…标的资产过户完成的公告",
            kind = "重组落地",
            note = "事件后蓄势：2026-10-20「…过户完成」（重组落地）后量缩价稳——放量突破时值得盯；属观察线索，不改趋势判断。",
            brief = "放量突破时值得盯（2026-10-20 重组落地 后缩量回踩）",
        )
        val result = PortfolioAnalysis.evaluate(
            Position("sh600522", "中天科技", 500.0, 32.0),
            technical(price = 30.0, support = 29.0, trendWeak = false),
            null,
            portfolio(),
            eventCoil = signal,
        )

        val highlights = PortfolioAnalysis.todayBriefHighlights(listOf(result))
        assertEquals(1, highlights.size)
        val line = highlights.single()
        assertTrue(line, line.contains("事件后蓄势"))
        assertTrue(line, line.contains("中天科技"))
        assertTrue(line, line.contains("放量突破时值得盯"))
        // 完整注记在个股报告行里，短句不重复。
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-22 10:00")
        assertTrue(report.contains("后量缩价稳"))
        assertTrue(PortfolioAnalysis.todayBriefHighlights(emptyList()).isEmpty())
    }


    private fun technical(
        price: Double,
        support: Double,
        resistance: Double = 32.0,
        atrPct: Double? = null,
        trendWeak: Boolean = true,
    ) = TechnicalResult(
        code = "sh600522",
        name = "中天科技",
        asOf = "2026-10-09 10:00",
        rows = 240,
        price = price,
        priceSource = "实时",
        priceChange = -1.2,
        opening = OpeningStructure(
            previousClose = price / 0.988,
            open = price / 0.99,
            gapPct = -0.2,
            openToCurrentPct = -0.8,
            type = "低开",
            structure = "低开低走",
        ),
        volumeRatio = 1.0,
        volumeRatioIntraday = true,
        priceContext = null,
        volatility = Volatility(
            atr = 0.9,
            atrPct = atrPct,
            bollMid = price,
            bollUpper = price * 1.05,
            bollLower = price * 0.95,
            bollPosition = 0.5,
        ),
        trendStrength = TrendStrength(adx = 25.0, plusDi = 12.0, minusDi = 22.0),
        obvChange = -2.0,
        divergence = DivergenceResult(
            state = DivergenceState.NEUTRAL,
            forming = false,
            scoreDelta = 0,
            prior = null,
            current = null,
            obvConfirmedNewHigh = null,
            obvConfirmedNewLow = null,
            reason = "暂无背离",
            meaning = "",
            short = "量价背离：暂无背离",
            method = "",
        ),
        relativeStrength = null,
        ma5 = price * 1.01,
        ma10 = price * 1.02,
        ma20 = price * 1.03,
        ma60 = price * 1.08,
        support = support,
        resistance = resistance,
        entrySetup = EntrySetup(
            type = EntrySetupType.NONE,
            confirmed = false,
            trendWeak = trendWeak,
            priorHigh = null,
            ma20SlopePct = -1.0,
            volumeConfirmed = false,
            marketFilter = MarketFilter.NEUTRAL,
            stopPrice = null,
            reason = "已有效跌破 M20，趋势转弱，等待重新站回并企稳",
        ),
        macd = MacdValues(dif = -0.2, dea = -0.1, hist = -0.1, previousHist = -0.05),
        rsi = 35.0,
        kdj = KdjValues(k = 30.0, d = 35.0, j = 20.0),
        chip = ChipPeaks(
            averageCost = price,
            peaks = emptyList(),
            chip70Lower = null,
            chip70Upper = null,
            chip70WidthPct = null,
            concentration = "数据不足",
            method = "无有效价格数据",
        ),
        score = -1,
        signals = emptyList(),
        action = "观望",
    )

    private fun portfolio() = Portfolio(
        totalCapital = 400_000.0,
        availableCapital = 100_000.0,
        initialEntryPct = 5.0,
        maxPositionPct = 20.0,
        positions = mutableListOf(Position("sh600522", "中天科技", 500.0, 45.0)),
    )

    private fun evaluate(
        price: Double = 30.0,
        support: Double = 30.5,
        cost: Double = 45.0,
        atrPct: Double? = null,
        health: FundamentalHealth? = null,
        env: EnvContext = EnvContext.NONE,
        trendWeak: Boolean = true,
        breakObservation: com.example.stockmonitor.analysis.HoldingBreakObservation? = null,
    ) = PortfolioAnalysis.evaluate(
        position = Position("sh600522", "中天科技", 500.0, cost),
        technical = technical(price = price, support = support, atrPct = atrPct, trendWeak = trendWeak),
        live = null,
        portfolio = portfolio(),
        fundamentalHealth = health,
        envContext = env,
        breakObservation = breakObservation,
    )

    @Test
    fun positionResultCarriesSharedTrendAndHighPositionState() {
        val trend = TrendAssessment(
            shortTerm = ShortTermTrend.UP,
            major = MajorTrend.UP,
            reason = "测试趋势向上",
            allowsOrdinaryBuy = true,
            allowsAddPosition = true,
            allowsTBuy = true,
            allowsTSell = true,
            allowsBottomReversal = false,
            riskOnly = false,
        )
        val highPosition = HighPositionDecision(
            phase = HighPositionPhase.CONSOLIDATION,
            reason = "测试高位整理",
            allowBuy = false,
            allowSecondWaveEntry = false,
            allowHoldBase = true,
            riskPriority = false,
        )
        val result = PortfolioAnalysis.evaluate(
            position = Position("sh600522", "中天科技", 500.0, 45.0),
            technical = technical(price = 30.0, support = 30.5).copy(
                trendAssessment = trend,
                highPosition = highPosition,
            ),
            live = null,
            portfolio = portfolio(),
        )

        assertEquals(trend, result.trendAssessment)
        assertEquals(highPosition, result.highPosition)
    }

    @Test
    fun deepLossBrokenSupportShowsOnePrimaryAction() {
        // 成本 45、现价 30：-33% 深套破位，报告只给当前优先动作。
        val result = evaluate(price = 30.0, support = 30.5)
        assertTrue(result.signal == "减仓风险")
        assertTrue(result.action.contains("当前优先"))
        assertFalse(result.action.contains("剧本①"))
        assertFalse(result.action.contains("剧本②"))
        assertTrue(result.action.contains("先不补仓"))
    }

    @Test
    fun shallowLossBrokenSupportKeepsConditionalCut() {
        val result = evaluate(price = 30.0, support = 30.5, cost = 32.0)
        assertTrue(result.signal == "减仓风险")
        assertTrue(result.action.contains("尚缺持续确认"))
        assertTrue(result.action.contains("分批减"))
        assertFalse(result.action.contains("先割一部分"))
    }

    @Test
    fun redFundamentalsPrefersExitScript() {
        val health = FundamentalHealth(
            level = HealthLevel.ALERT, score = 40, verdict = "较差",
            reasons = listOf("公告提示：业绩预亏"), analyzedAt = "2026-10-09 10:00",
        )
        val result = evaluate(price = 30.0, support = 30.5, health = health,
            breakObservation = com.example.stockmonitor.analysis.HoldingBreakObservation(30.5, 29.89, true))
        assertTrue(result.action.contains("退出优先"))
        assertTrue(result.action.contains("清仓退出"))
        assertFalse(result.action.contains("剧本②"))
        assertTrue(result.fundamentalHealth != null)
    }

    @Test
    fun reportShowsFundamentalHealthLine() {
        val health = FundamentalHealth(
            level = HealthLevel.SOUND, score = 76, verdict = "良好",
            reasons = listOf("评分 76（良好）"), analyzedAt = "2026-10-09 10:00",
        )
        val result = evaluate(price = 30.0, support = 30.5, health = health)
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-09 10:00")
        assertTrue(report.contains("基本面：未见恶化"))
        assertTrue(report.contains("不含行业景气与订单"))
    }

    @Test
    fun reportShowsStrongContinuationEventsWhenPresent() {
        val event = StrongContinuationEvent(
            date = "2026-10-12",
            code = "sh600522",
            name = "中天科技",
            triggeredAt = "2026-10-12 10:30",
            triggerPrice = 30.0,
            currentPrice = 31.0,
            predictedTurnoverYi = 22_000.0,
            marketChangePct = 0.8,
            sectorChangePct = 1.2,
        )
        val report = PortfolioAnalysis.formatReport(
            results = listOf(evaluate(price = 31.0, support = 30.0, cost = 30.0)),
            analyzedAt = "2026-10-12 10:35",
            strongContinuationEvents = listOf(event),
        )

        assertTrue(report.contains("强势环境暂缓做T记录"))
        assertTrue(report.contains("较触发 +3.33%"))
    }

    // ── 环境改写：判定要有后果，否则白判 ─────────────────────────────────────

    private fun env(regime: MarketRegime, prev: RowSnapshot? = null) = EnvContext(
        market = MarketState(
            regime = regime,
            reason = "测试环境",
            changePct = null,
            ma20Pct = -2.0,
            ma60Pct = -5.0,
            change5d = -3.0,
            change20d = -10.0,
        ),
        prev = prev,
    )

    @Test
    fun collapseSuppressesTheAddOnScriptForDeepLoss() {
        // 深套破位在塌陷期不能给「等站回支撑补仓」——支撑在系统性下跌里还会再破，那是接飞刀。
        val result = evaluate(price = 30.0, support = 30.5, env = env(MarketRegime.COLLAPSE))

        assertTrue(result.action.contains("塌陷"))
        assertTrue(result.action.contains("不补仓"))
        // 塌陷期给的是「等大盘站上 MA20 再谈补仓」，不是现在就可以小仓补。
        assertFalse(result.action.contains("考虑小仓补仓"))
    }

    @Test
    fun neutralEnvironmentKeepsOnePrimaryAction() {
        val result = evaluate(price = 30.0, support = 30.5, env = env(MarketRegime.NEUTRAL))

        assertTrue(result.action.contains("当前优先"))
        assertFalse(result.action.contains("剧本①"))
        assertFalse(result.action.contains("剧本②"))
        assertFalse(result.action.contains("塌陷"))
    }

    @Test
    fun weakFlowAndTurnoverAddCautionWithoutChangingSignal() {
        val risk = MarketRiskContext(
            timing = MarketTimingContext(
                date = "2026-10-30",
                weekday = "周五",
                isFriday = true,
                isMonthEndWindow = true,
                isQuarterEndWindow = false,
                reportSeason = "三季报常见披露窗口",
            ),
            northbound = NorthboundFlowSummary("2026-10-30", -8.0, -32.0, -55.0, "测试数据"),
            turnover = TurnoverContext(8.0e11, 1.0e12, 1.1e12),
        )

        val result = evaluate(
            price = 31.0,
            support = 30.0,
            cost = 30.0,
            env = EnvContext(
                market = env(MarketRegime.NEUTRAL).market,
                marketRisk = risk,
            ),
            trendWeak = false,
        )

        assertTrue(result.signal == "持有观察")
        assertTrue(result.action.contains("降低追涨/补仓优先级"))
    }

    @Test
    fun singleMarketRiskEvidenceDoesNotChangeHoldAdvice() {
        val timing = MarketTimingContext(
            date = "2026-10-29",
            weekday = "周四",
            isFriday = false,
            isMonthEndWindow = false,
            isQuarterEndWindow = false,
            reportSeason = null,
        )
        val result = evaluate(
            price = 31.0,
            support = 30.0,
            cost = 30.0,
            trendWeak = false,
            env = EnvContext(
                market = env(MarketRegime.NEUTRAL).market,
                marketRisk = MarketRiskContext(
                    timing = timing,
                    northbound = NorthboundFlowSummary("2026-10-29", -8.0, -32.0, -55.0, "测试数据"),
                ),
            ),
        )

        assertTrue(result.signal == "持有观察")
        assertTrue(!result.action.contains("降低追涨/补仓优先级"))
    }

    @Test
    fun holdAdviceEmbedsTodayNumbersInsteadOfFixedTemplate() {
        // 话术要嵌当天数字，否则天天同一句——这是「永远一套话术」的根因。
        val result = evaluate(
            price = 31.0, support = 30.0, cost = 30.0,
            env = env(MarketRegime.NEUTRAL), trendWeak = false,
        )

        assertTrue(result.signal == "持有观察")
        assertTrue(result.action.contains("今天"))
        assertTrue(result.action.contains("量比"))
        assertTrue(result.action.contains("较支撑"))
        assertTrue(result.action.contains("不动"))
    }

    @Test
    fun collapseAppendsEnvNoteToHoldAdvice() {
        val result = evaluate(
            price = 31.0, support = 30.0, cost = 30.0,
            env = env(MarketRegime.COLLAPSE), trendWeak = false,
        )

        assertTrue(result.action.contains("大盘塌陷"))
        assertTrue(result.action.contains("不补仓"))
    }

    @Test
    fun changeNoteDescribesWhatChangedToday() {
        val prev = RowSnapshot(
            code = "sh600522",
            signal = "持有观察",
            score = 1,
            support = 30.0,
            resistance = 32.0,
            price = 30.5,
            changePct = -0.5,
            volumeRatio = 0.9,
        )
        val result = evaluate(price = 30.0, support = 30.5, env = env(MarketRegime.NEUTRAL, prev = prev))

        assertNotNull(result.changeNote)
        // 信号从「持有观察」变成「减仓风险」——这正是复盘最该说的一句话。
        assertTrue(result.changeNote!!.contains("较昨日"))
        assertTrue(result.changeNote!!.contains("持有观察 → 减仓风险"))
    }

    @Test
    fun noPrevSnapshotMeansNoChangeNoteNotNoChange() {
        val result = evaluate(price = 30.0, support = 30.5, env = env(MarketRegime.NEUTRAL))

        // 没数据和没变化是两回事：缺昨日快照时返回 null，不能硬编成「无变化」。
        assertNull(result.changeNote)
    }

    @Test
    fun unchangedFromYesterdaySaysSo() {
        val prev = RowSnapshot(
            code = "sh600522",
            signal = "减仓风险",
            score = -1,
            support = 30.5,
            resistance = 32.0,
            price = 30.0,
            changePct = -1.0,
            volumeRatio = 1.0,
        )
        val result = evaluate(price = 30.0, support = 30.5, env = env(MarketRegime.NEUTRAL, prev = prev))

        assertTrue(result.changeNote!!.contains("与昨日无变化"))
    }

    @Test
    fun changeNoteMentionsMeaningfulPriceMovement() {
        val prev = RowSnapshot(
            code = "sh600522",
            signal = "减仓风险",
            score = -1,
            support = 30.5,
            resistance = 32.0,
            price = 30.0,
            changePct = -1.0,
            volumeRatio = 1.0,
        )
        val result = evaluate(price = 30.5, support = 30.5, env = env(MarketRegime.NEUTRAL, prev = prev))

        assertTrue(result.changeNote!!.contains("现价 30.00→30.50"))
    }

    @Test
    fun sectorNoteNamesTheIndustryAndBoardMove() {
        val context = SectorContext(
            industry = BoardTag("半导体", "BK0438", 1),
            changePct = -3.0,
            source = "东财F10",
        )
        val result = PortfolioAnalysis.evaluate(
            position = Position("sh600522", "中天科技", 500.0, 30.0),
            technical = technical(price = 31.0, support = 30.0, trendWeak = false),
            live = null,
            portfolio = portfolio(),
            envContext = EnvContext(market = env(MarketRegime.NEUTRAL).market, sector = context),
        )

        assertTrue(result.sectorNote!!.contains("半导体"))
        assertTrue(result.sectorNote!!.contains("板块今日 -3.00%"))
    }

    @Test
    fun missingSectorSaysSoInsteadOfGuessing() {
        val result = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)

        // 把「贵州板块」当行业去比涨跌会得出完全错的结论，所以归属查不到就明说。
        assertTrue(result.sectorNote!!.contains("板块归属暂缺"))
    }

    @Test
    fun reportDoesNotPrintTheSignalDictionary() {
        // 「状态含义」是按 signal 名查表的固定词典——同一个信号永远同一句，十只股读下来一模一样。
        // 「应对」里已经带了这只股当天的数字和环境，那行留着只稀释信息量。
        val result = evaluate(price = 30.0, support = 30.5)
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-12 16:05")

        assertFalse(report.contains("状态含义"))
        // 但「状态」「应对」这些有信息量的行必须还在。
        assertTrue(report.contains("状态：深套风险观察"))
        assertTrue(report.contains("应对："))
    }

    @Test
    fun reportOmitsRepeatedLowPriorityTechnicalDetails() {
        val report = PortfolioAnalysis.formatReport(
            listOf(evaluate(price = 30.0, support = 30.5)),
            "2026-10-12 16:05",
        )

        assertFalse(report.contains("指标含义："))
        assertFalse(report.contains("开盘："))
        assertFalse(report.contains("ATR "))
        assertTrue(report.contains("支撑 "))
        assertTrue(report.contains("应对："))
    }

    @Test
    fun reportDoesNotInventZeroPriceLevelsWhenTechnicalDataIsMissing() {
        val result = evaluate(price = 30.0, support = 0.0)
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-12 16:05")

        assertFalse(report.contains("支撑 0.00"))
        assertFalse(report.contains("压力 0.00"))
        assertFalse(report.contains("M20 0.00"))
        assertTrue(report.contains("支撑 —"))
    }

    @Test
    fun reportCarriesTheSectorLine() {
        // 「应对」尾部只有修饰语，看不到这只股属于哪个行业、行业今天涨跌多少——报告里补齐这一行。
        val context = SectorContext(
            industry = BoardTag("半导体", "BK0438", 1),
            changePct = -3.2,
            source = "东财F10",
        )
        val result = PortfolioAnalysis.evaluate(
            position = Position("sh600522", "中天科技", 500.0, 30.0),
            technical = technical(price = 31.0, support = 30.0, trendWeak = false),
            live = null,
            portfolio = portfolio(),
            envContext = EnvContext(market = env(MarketRegime.NEUTRAL).market, sector = context),
        )
        val report = PortfolioAnalysis.formatReport(listOf(result), "2026-10-12 16:05")

        assertTrue(report.contains("板块：半导体"))
        assertTrue(report.contains("板块今日 -3.20%"))
    }

    // ── 「今日要点」精要：报告开头与持仓页卡片共用一份 ─────────────────────────

    @Test
    fun briefCarriesTheFourBlocks() {
        // 长报告十来段，想看「今天要干什么」不该翻到底；卡片上的一两行又放不下。
        // 精要就四块：今日结果 / 市场环境 / 要做什么 / 风险点。
        val brief = PortfolioAnalysis.brief(
            results = listOf(evaluate(price = 30.0, support = 30.5)),
            market = env(MarketRegime.WEAK).market,
        )

        assertTrue(brief.size == 5)
        assertTrue(brief[0].startsWith("最高优先级操作："))
        assertTrue(brief[1].startsWith("今日结果："))
        assertTrue(brief[2].startsWith("市场环境："))
        assertTrue(brief[3].startsWith("持仓整体操作建议："))
        assertTrue(brief[4].startsWith("风险点："))
    }

    @Test
    fun briefSaysWhatToDoAndWhatNotToDo() {
        // 「不用动」也要点名——否则用户会以为每只都得处理。
        val brief = PortfolioAnalysis.brief(
            results = listOf(
                evaluate(price = 30.0, support = 30.5),            // 破位 → 先复核
                evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false), // 持有 → 不用动
            ),
        )

        assertTrue(brief[3].contains("机动仓做T"))
        assertTrue(brief[3].contains("持有观察"))
    }

    @Test
    fun briefListsTheRisksThatActuallyExist() {
        val brief = PortfolioAnalysis.brief(
            results = listOf(evaluate(price = 30.0, support = 30.5)), // 破位 + 深套
        )

        assertTrue(brief[4].contains("破位 1 只"))
        assertTrue(brief[4].contains("深套 1 只"))
    }

    @Test
    fun reportStartsWithTheBrief() {
        val report = PortfolioAnalysis.formatReport(
            listOf(evaluate(price = 30.0, support = 30.5)),
            "2026-10-12 16:05",
        )

        val briefAt = report.indexOf("📌 今日要点")
        val detailAt = report.indexOf("状态：深套风险观察")
        assertTrue(briefAt in 0 until detailAt)
    }

    @Test
    fun highestPriorityOperationUsesRiskBeforeOrdinaryHoldAndAppearsInTodayBrief() {
        val hold = evaluate(price = 31.0, support = 30.0, cost = 30.0, trendWeak = false)
        val risk = hold.copy(
            code = "sz000001",
            name = "破位股",
            signal = "减仓风险",
            action = "跌破支撑，分批减仓或退出",
        )

        val priority = PortfolioAnalysis.highestPriorityAction(listOf(hold, risk))
        val today = PortfolioAnalysis.todayBrief(listOf(hold, risk), portfolio())

        assertTrue(priority!!.contains("破位股"))
        assertTrue(priority.contains("分批减仓或退出"))
        assertTrue(today.first().startsWith("最高优先级操作："))
        assertTrue(today.first().contains("破位股"))
    }
}
