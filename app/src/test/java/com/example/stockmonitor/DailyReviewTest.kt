package com.example.stockmonitor

import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.analysis.DailyReview
import com.example.stockmonitor.analysis.BoardTag
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketState
import com.example.stockmonitor.analysis.OpeningStructure
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.analysis.StrongContinuationEvent
import com.example.stockmonitor.analysis.PositionResult
import com.example.stockmonitor.analysis.ReportMarkup
import com.example.stockmonitor.analysis.TrendStrength
import com.example.stockmonitor.analysis.Volatility
import com.example.stockmonitor.analysis.VolumePriceSignal
import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「持仓复盘」的口径锁：
 * - 四层分析各有结论行，章节标题必须以中文数字开头（否则 [ReportSectionAssertions] 会切不出章节）；
 * - **当日盈亏（较昨收）与浮盈浮亏（较成本）永远分开**，文案里带口径后缀；
 * - 贡献占比只在同号集合内算，正负对冲时只给金额排序，不给失真的百分比；
 * - 缺行情写「未计入 / 暂缺」，绝不写 0。
 */
class DailyReviewTest {

    private fun quote(
        code: String,
        price: Double,
        prevClose: Double,
        name: String = "测试",
        quoteDate: String? = null,
    ): Quote = Quote(
        code = code,
        name = name,
        price = price,
        prevClose = prevClose,
        open = prevClose,
        high = price,
        low = price,
        volume = 1_000.0,
        turnover = 10_000.0,
        quoteDate = quoteDate,
    )

    private fun entrySetup(trendWeak: Boolean = false, stopPrice: Double? = 8.5) = EntrySetup(
        type = EntrySetupType.NONE,
        confirmed = false,
        canBuy = false,
        trendWeak = trendWeak,
        priorHigh = null,
        ma20SlopePct = null,
        volumeConfirmed = false,
        marketFilter = MarketFilter.NEUTRAL,
        stopPrice = stopPrice,
        reason = "暂无有效入场结构",
    )

    @Suppress("LongParameterList")
    private fun result(
        code: String = "sh600000",
        name: String = "甲",
        quantity: Double = 1_000.0,
        cost: Double = 8.5,
        price: Double = 8.8,
        changePct: Double = 2.33,
        signal: String = "持有观察",
        action: String = "暂不做激进操作，观察支撑、压力与量能是否继续配合。",
        support: Double = 8.5,
        resistance: Double = 9.2,
        ma20: Double = 8.6,
        score: Int = 2,
        volumeRatio: Double = 1.45,
        volumeSignal: VolumePriceSignal = VolumePriceSignal.EXPAND_UP,
        trendWeak: Boolean = false,
        atrPct: Double? = 1.2,
        adx: Double? = 28.0,
        relativeStrength: Double? = 3.0,
        changeNote: String? = null,
        sectorNote: String? = null,
    ) = PositionResult(
        code = code,
        name = name,
        quantity = quantity,
        cost = cost,
        price = price,
        changePct = changePct,
        marketValue = quantity * price,
        positionPct = null,
        signal = signal,
        action = action,
        support = support,
        resistance = resistance,
        ma20 = ma20,
        entrySetup = entrySetup(trendWeak = trendWeak),
        suggestedQuantity = 0.0,
        score = score,
        volumeRatio = volumeRatio,
        volumeSignal = volumeSignal,
        opening = OpeningStructure(
            previousClose = price / (1 + changePct / 100),
            open = price,
            gapPct = 0.0,
            openToCurrentPct = 0.0,
            type = "平开",
            structure = "平开平走",
        ),
        volatility = Volatility(
            atr = 0.2,
            atrPct = atrPct,
            bollMid = price,
            bollUpper = price * 1.05,
            bollLower = price * 0.95,
            bollPosition = 0.5,
        ),
        trendStrength = TrendStrength(adx = adx, plusDi = 20.0, minusDi = 10.0),
        relativeStrength = relativeStrength,
        analyzedAt = "2026-10-12 16:05",
        changeNote = changeNote,
        sectorNote = sectorNote,
    )

    private fun report(
        positions: List<Position>,
        quotes: Map<String, Quote>,
        results: List<PositionResult>,
        portfolio: Portfolio = Portfolio(totalCapital = 1_000_000.0, maxPositionPct = 20.0),
        indexQuote: Quote? = quote("sh000001", 3_200.0, 3_200.0, "上证指数"),
        analyzedAt: String = "2026-10-12 16:05",
        market: MarketState? = null,
        sectorContexts: Map<String, SectorContext> = emptyMap(),
        strongContinuationEvents: List<StrongContinuationEvent> = emptyList(),
    ): String = DailyReview.generate(
        DailyReview.Inputs(
            positions = positions,
            quotes = quotes,
            results = results,
            portfolio = portfolio,
            indexQuote = indexQuote,
            analyzedAt = analyzedAt,
            market = market,
            sectorContexts = sectorContexts,
            strongContinuationEvents = strongContinuationEvents,
        ),
    )

    @Test
    fun reviewShowsStrongContinuationSectionWhenPresent() {
        val event = StrongContinuationEvent(
            date = "2026-10-12",
            code = "sh600000",
            name = "甲",
            triggeredAt = "2026-10-12 10:30",
            triggerPrice = 8.8,
            currentPrice = 9.0,
            predictedTurnoverYi = 21_000.0,
            marketChangePct = 0.5,
            sectorChangePct = 1.1,
        )
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 9.0, 8.8, "甲")),
            results = listOf(result(price = 9.0)),
            strongContinuationEvents = listOf(event),
        )

        assertEquals(listOf("一", "二", "三"), Regex("(?m)^([一二三四五六七八九十]+)、")
            .findAll(text).map { it.groupValues[1] }.toList())
        assertTrue(text.contains("较触发 +2.27%"))
    }

    private fun market(regime: MarketRegime) = MarketState(
        regime = regime,
        reason = "跌破 MA20 与 MA60，近20日 -12.0%——系统性下跌，支撑位多半会一起破",
        changePct = -2.5,
        ma20Pct = -4.0,
        ma60Pct = -9.0,
        change5d = -6.0,
        change20d = -12.0,
    )

    /** 取某一节的正文（含标题行之后、下一节之前）。 */
    private fun sectionBody(text: String, titlePrefix: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith(titlePrefix) }
        assertTrue("找不到章节 $titlePrefix", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { Regex("^[一二三四五六七八九十]+、").containsMatchIn(it) }
        return (if (end >= 0) rest.take(end) else rest).joinToString("\n")
    }

    // ── 结构 ────────────────────────────────────────────────────────────────

    @Test
    fun reportHasTwoEvidenceSectionsAndOneSharedAdviceAtTop() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
        )

        ReportSectionAssertions.assertEverySectionHasConclusion(text, leastSections = 2)
        assertFalse(text.contains("个股技术面"))
        assertFalse(text.contains("操作优先级"))
        assertTrue(text.indexOf("持仓整体操作建议") in 0 until text.indexOf("一、"))
        com.example.stockmonitor.analysis.PortfolioAnalysis.overallAdvice(listOf(result())).forEach {
            assertTrue(text.substringBefore("一、").contains(it))
        }
    }

    @Test
    fun titlesStartWithChineseNumeralSoAssertionsCanSplitThem() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
        )

        val titles = ReportSectionAssertions.sections(text).keys
        // 标题前面放 emoji（如「📊 一、」）会让正则切不出章节，这里锁住写法。
        assertEquals(2, titles.size)
        titles.forEach { title ->
            assertTrue("章节标题必须以中文数字开头：$title", Regex("^[一二三四五六七八九十]+、").containsMatchIn(title))
        }
    }

    @Test
    fun emptyPortfolioSaysSoInsteadOfPrintingHollowSections() {
        val text = report(positions = emptyList(), quotes = emptyMap(), results = emptyList())

        assertTrue(text.contains("尚未配置监控股票"))
        assertFalse(text.contains("一、"))
    }

    @Test
    fun footerHasDisclaimerAndDataCutoff() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
        )

        assertTrue(text.contains("说明："))
        assertTrue(text.contains("不构成投资建议"))
        assertTrue(text.contains("--- 数据截至 2026-10-12 16:05，仅供参考 ---"))
    }

    @Test
    fun keyNumbersAreMarkedRed() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
        )

        assertTrue(text.contains(ReportMarkup.OPEN))
        assertTrue(text.contains(ReportMarkup.CLOSE))
        assertFalse(ReportMarkup.plain(text).contains(ReportMarkup.OPEN))
        assertFalse(ReportMarkup.plain(text).contains(ReportMarkup.CLOSE))
    }

    @Test
    fun isTradingDayRequiresMatchingQuoteDate() {
        val today = "2026-10-12"
        assertTrue(DailyReview.isTradingDay(quote("sh000001", 1.0, 1.0, quoteDate = "2026-10-12"), today))
        assertFalse(DailyReview.isTradingDay(quote("sh000001", 1.0, 1.0, quoteDate = "2026-10-11"), today))
        // 判不出交易日期时放行：让「全部缺行情」兜底，别因为一个缺失字段把每个交易日都挡掉。
        assertTrue(DailyReview.isTradingDay(quote("sh000001", 1.0, 1.0, quoteDate = null), today))
        assertTrue(DailyReview.isTradingDay(null, today))
    }

    // ── 一、盈亏归因 ─────────────────────────────────────────────────────────

    @Test
    fun attributionNamesTopProfitContributorWithShareOfGrossProfit() {
        // 甲 +800、乙 +200、丙 +100：甲占今日盈利 800/1100 = 73%，超过 60% 就点名主力。
        val text = report(
            positions = listOf(
                Position("sh600000", "甲", 1_000.0, 8.5),
                Position("sh600001", "乙", 1_000.0, 8.5),
                Position("sh600002", "丙", 1_000.0, 8.5),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 8.8, 8.0, "甲"),
                "sh600001" to quote("sh600001", 8.7, 8.5, "乙"),
                "sh600002" to quote("sh600002", 8.6, 8.5, "丙"),
            ),
            results = listOf(result(code = "sh600000", name = "甲"), result(code = "sh600001", name = "乙"), result(code = "sh600002", name = "丙")),
        )
        val body = sectionBody(text, "一、")

        assertTrue(body.contains("主要来自『甲』"))
        assertTrue(body.contains("占今日盈利"))
        assertTrue(body.contains("结论："))
    }

    @Test
    fun attributionNamesTopLossContributorOnLossDay() {
        // 甲 -500、乙 -200：甲占今日亏损 500/700 = 71%，亏损日也按毛额占比点名。
        val text = report(
            positions = listOf(
                Position("sh600000", "甲", 1_000.0, 8.5),
                Position("sh600001", "乙", 1_000.0, 8.5),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 7.5, 8.0, "甲"),
                "sh600001" to quote("sh600001", 8.3, 8.5, "乙"),
            ),
            results = listOf(result(code = "sh600000", name = "甲"), result(code = "sh600001", name = "乙")),
        )
        val body = sectionBody(text, "一、")

        assertTrue(body.contains("亏损主要来自『甲』"))
        assertTrue(body.contains("占今日亏损"))
    }

    @Test
    fun mixedSignNearZeroUsesHedgeWordingAndNoShare() {
        // 甲 +800、乙 -790，合计 +10；昨日市值 20 万，|合计| 远小于 0.1% → 对冲。
        // 此时「占今日盈利/亏损」的百分比会被互抵的分母放大成没意义的数字，只能给金额排序。
        val text = report(
            positions = listOf(
                Position("sh600000", "甲", 1_000.0, 100.0),
                Position("sh600001", "乙", 1_000.0, 100.0),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 100.8, 100.0, "甲"),
                "sh600001" to quote("sh600001", 99.21, 100.0, "乙"),
            ),
            results = listOf(result(code = "sh600000", name = "甲"), result(code = "sh600001", name = "乙")),
        )
        val body = sectionBody(text, "一、")

        assertTrue(body.contains("对冲"))
        assertTrue(body.contains("只给金额排序"))
        assertFalse(body.contains("占今日盈利"))
        assertFalse(body.contains("占今日亏损"))
    }

    @Test
    fun singleCountedStockSkipsShareWording() {
        // 只有一只算得出时，「占今日盈利 100%」是废话，不该出现。
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.0, "甲")),
            results = listOf(result()),
        )
        val body = sectionBody(text, "一、")

        assertFalse(body.contains("占今日盈利"))
        assertTrue(body.contains("今日盈亏"))
    }

    @Test
    fun missingQuoteNeverPrintedAsZero() {
        // 乙没行情：不能印 +0.00 假装没亏没赚，要写「未计入」。
        val text = report(
            positions = listOf(
                Position("sh600000", "甲", 1_000.0, 8.5),
                Position("sh600001", "乙", 1_000.0, 8.5),
            ),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
        )
        val body = sectionBody(text, "一、")

        assertTrue(body.contains("未计入"))
        assertFalse(body.contains("+0.00"))
    }

    @Test
    fun summaryLineMatchesDayPnlSummaryText() {
        // 复盘里的「合计」必须和 [DayPnl.summaryText] 是同一句口径，否则报告内部的数字会对不上。
        val positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5))
        val quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲"))
        val text = report(positions = positions, quotes = quotes, results = listOf(result()))
        val expected = DayPnl.summaryText(DayPnl.summarize(positions, quotes))

        val line = text.lines().first { it.startsWith("合计：") }
        assertEquals("合计：$expected", ReportMarkup.plain(line))
    }

    // ── 两个口径不混 ─────────────────────────────────────────────────────────

    @Test
    fun dayPnlAndFloatingPnlUseSeparateBasisLabels() {
        // 今日盈亏 = (8.8-8.6)×1000 = +200（较昨收）
        // 浮盈浮亏 = (8.8-8.5)×1000 = +300（较成本）
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result(cost = 8.5, price = 8.8)),
            portfolio = Portfolio(totalCapital = 1_000_000.0),
        )
        val first = sectionBody(text, "一、")
        val third = sectionBody(text, "二、")

        assertTrue(first.contains("较昨收"))
        assertTrue(first.contains("较成本的持仓浮盈浮亏见第二节"))
        assertTrue(third.contains("较成本"))
        assertTrue(third.contains("不是同一口径") || third.contains("不是一回事"))
        // 三个口径的数字要能分开对上。
        assertTrue(first.contains("+200.00"))
        assertTrue(third.contains("+300.00"))
    }

    @Test
    fun aggregateFloatingPnlExcludesInvalidCost() {
        val dayPnl = DayPnl.summarize(
            positions = listOf(
                Position("sh600000", "有成本", 1_000.0, 8.5),
                Position("sh600001", "没成本", 1_000.0, 0.0),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 8.8, 8.6, "有成本"),
                "sh600001" to quote("sh600001", 20.0, 19.0, "没成本"),
            ),
        )
        val aggregates = DailyReview.aggregate(
            positions = listOf(
                Position("sh600000", "有成本", 1_000.0, 8.5),
                Position("sh600001", "没成本", 1_000.0, 0.0),
            ),
            dayPnl = dayPnl,
            portfolio = Portfolio(totalCapital = 1_000_000.0),
            indexQuote = null,
        )

        // 只有「有成本」那笔进较成本口径：(8.8-8.5)×1000 = 300，成本基数 8,500。
        assertEquals(300.0, aggregates.floatingPnl!!, 1e-9)
        assertEquals(8_500.0, aggregates.costBasis, 1e-9)
    }

    @Test
    fun aggregateFloatingPnlIsNullWhenNoCostFilled() {
        val positions = listOf(Position("sh600000", "甲", 1_000.0, 0.0))
        val dayPnl = DayPnl.summarize(positions, mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")))
        val aggregates = DailyReview.aggregate(positions, dayPnl, Portfolio(), indexQuote = null)

        assertNull(aggregates.floatingPnl)
        assertNull(aggregates.floatingPnlPct)
    }

    // ── 三、组合体检 ─────────────────────────────────────────────────────────

    @Test
    fun healthReportsPositionRatioAgainstTotalCapital() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            // 总资金没填：总仓位算不出，不能印 0%。
            portfolio = Portfolio(totalCapital = 0.0),
        )
        val body = sectionBody(text, "二、")

        assertTrue(body.contains("总资金未设置"))
        assertTrue(body.contains("总仓位暂缺"))
        assertFalse(body.contains("占总资金 0"))
    }

    @Test
    fun healthFlagsConcentrationWhenTop1OverHalfOfHoldings() {
        // 甲 5 万、乙 1 万：甲占持仓市值 83%，属于高度集中。
        val text = report(
            positions = listOf(
                Position("sh600000", "甲", 1_000.0, 8.5),
                Position("sh600001", "乙", 200.0, 8.5),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 50.0, 50.0, "甲"),
                "sh600001" to quote("sh600001", 50.0, 50.0, "乙"),
            ),
            results = listOf(result(code = "sh600000", name = "甲"), result(code = "sh600001", name = "乙")),
        )
        val body = sectionBody(text, "二、")

        assertTrue(body.contains("高度集中"))
        assertTrue(body.contains("『甲』"))
    }

    @Test
    fun healthComparesDayReturnWithIndex() {
        // 组合 +2.00%，沪指 +0.50% → 跑赢 1.5pp；反过来跑输；两者接近则同步。
        fun build(indexChangePct: Double): String = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.0, "甲")), // +10% → 组合 +10%
            results = listOf(result()),
            indexQuote = quote("sh000001", 3_200.0 * (1 + indexChangePct / 100), 3_200.0, "上证指数"),
        )

        val outperform = sectionBody(build(0.5), "二、")
        assertTrue(outperform.contains("跑赢沪指"))

        val underperform = sectionBody(build(20.0), "二、")
        assertTrue(underperform.contains("跑输沪指"))
        assertTrue(underperform.contains("谁在拖后腿"))

        val inLine = sectionBody(build(9.8), "二、")
        assertTrue(inLine.contains("基本同步"))

        val noIndex = sectionBody(
            report(
                positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
                quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.0, "甲")),
                results = listOf(result()),
                indexQuote = null,
            ),
            "二、",
        )
        assertTrue(noIndex.contains("无法对比"))
    }

    @Test
    fun healthDeepLossCountAndWording() {
        // 成本 100、现价 70 = 浮亏 30%（较成本），超过默认 -20% 阈值 → 深套。
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 100.0)),
            quotes = mapOf("sh600000" to quote("sh600000", 70.0, 71.0, "甲")),
            results = listOf(result(cost = 100.0, price = 70.0, atrPct = 1.0)),
        )
        val body = sectionBody(text, "二、")

        assertTrue(body.contains("深套"))
        assertTrue(body.contains("较成本"))
        // 深套的正确动作是按当前优先应对走，不是在破位处割肉。
        assertFalse(body.contains("先割"))
    }

    // ── 四、操作优先级 ───────────────────────────────────────────────────────

    @Test
    fun overallAdviceOrdersRiskBeforeHoldAndExcludesWatchOnly() {
        val text = report(
            positions = listOf(
                Position("sh600000", "破位股", 1_000.0, 8.5),
                Position("sh600001", "转弱股", 1_000.0, 8.5),
                Position("sh600002", "持有股", 1_000.0, 8.5),
                Position("sh600003", "等待股", 0.0, 0.0),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 8.0, 8.6, "破位股"),
                "sh600001" to quote("sh600001", 8.2, 8.6, "转弱股"),
                "sh600002" to quote("sh600002", 8.8, 8.6, "持有股"),
                "sh600003" to quote("sh600003", 9.0, 8.6, "等待股"),
            ),
            results = listOf(
                result(code = "sh600000", name = "破位股", signal = "减仓风险"),
                result(code = "sh600001", name = "转弱股", signal = "趋势转弱观察", trendWeak = true),
                result(code = "sh600002", name = "持有股", signal = "持有观察"),
                result(code = "sh600003", name = "等待股", quantity = 0.0, signal = "等待"),
            ),
        )
        val body = text.substringBefore("一、")

        val iAct = body.indexOf("破位股")
        val iWeak = body.indexOf("转弱股")
        val iHold = body.indexOf("持有股")
        val iWait = body.indexOf("等待股")
        assertTrue("P1 应排在最前", iAct in 0 until iWeak)
        assertTrue("P2 应在 P1 之后", iWeak in 0 until iHold)
        assertTrue(iHold >= 0)
        assertEquals(-1, iWait)
        assertTrue(body.contains("暂不主动调仓"))
    }

    @Test
    fun priorityDeepLossBreakUsesCurrentPriorityAction() {
        val deepAction =
            "当前优先：基本面倾向不明确，先不补仓；反抽站不回压力位再分批减仓。"
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 100.0)),
            quotes = mapOf("sh600000" to quote("sh600000", 70.0, 71.0, "甲")),
            results = listOf(
                result(
                    cost = 100.0,
                    price = 70.0,
                    atrPct = 1.0,
                    signal = "减仓风险",
                    action = deepAction,
                ),
            ),
        )
        val body = text.substringBefore("一、")

        assertTrue(body.contains("机动仓做T"))
        assertFalse(body.contains("两种剧本"))
        assertFalse(body.contains("①逻辑/基本面已破坏"))
        assertFalse(body.contains("②逻辑还在"))
        assertFalse(body.contains("先割一部分"))
        assertFalse(body.contains("立即清仓"))
    }

    @Test
    fun priorityHoldNamesAreExplicitlyDoNothing() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result(signal = "持有观察")),
        )
        val body = text.substringBefore("一、")

        assertTrue(body.contains("以持有观察为主，暂不主动调仓"))
    }

    @Test
    fun watchOnlyStocksAreExcludedFromAdviceAndPnlAttribution() {
        val text = report(
            positions = listOf(
                Position("sh600000", "持仓股", 1_000.0, 8.5),
                Position("sh600001", "观察股", 0.0, 0.0),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", 8.8, 8.6, "持仓股"),
                "sh600001" to quote("sh600001", 9.0, 8.6, "观察股"),
            ),
            results = listOf(
                result(code = "sh600000", name = "持仓股"),
                result(code = "sh600001", name = "观察股", quantity = 0.0, signal = "等待"),
            ),
        )

        // 观察股没有仓位，不进盈亏归因（没有「今日盈亏」可言）。
        assertFalse(sectionBody(text, "一、").contains("观察股"))
        // 但技术面要覆盖它——你毕竟把它放进持仓页了。
        assertFalse(text.contains("个股技术面"))
        assertFalse(text.substringBefore("一、").contains("观察股"))
    }

    // ── 环境：塌陷期复盘要先看系统性风险，不是逐只慢慢看 ─────────────────────

    @Test
    fun marketEnvironmentLineAppearsInHealthSection() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            market = market(MarketRegime.COLLAPSE),
        )
        val body = sectionBody(text, "二、")

        assertTrue(body.contains("市场环境"))
        assertTrue(body.contains("塌陷"))
        assertTrue(body.contains("系统性下跌"))
    }

    @Test
    fun collapsePutsSystemicRiskFirst() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            market = market(MarketRegime.COLLAPSE),
        )
        val body = sectionBody(text, "二、")

        val risks = body.substringAfter("风险点：")
        val firstRisk = risks.lineSequence().first { it.isNotBlank() }
        // 系统性风险必须排第一条——顺序反了会让人先去看单股，正好给反了。
        assertTrue(firstRisk.contains("系统性风险"))
    }

    @Test
    fun collapseDoesNotRestoreRemovedTechnicalSection() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            market = market(MarketRegime.COLLAPSE),
        )
        val body = sectionBody(text, "二、")

        assertFalse(body.contains("支撑/压力都要打折看"))
    }

    @Test
    fun neutralMarketDoesNotAddCollapseCaveat() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            market = market(MarketRegime.NEUTRAL),
        )
        val body = sectionBody(text, "二、")

        assertFalse(body.contains("支撑/压力都要打折看"))
    }

    @Test
    fun removedTechnicalsDoNotRepeatChangeNote() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result(changeNote = "较昨日：评分 +1→+2｜信号 持有观察 → 减仓风险")),
        )
        val body = sectionBody(text, "二、")

        assertFalse(body.contains("持有观察 → 减仓风险"))
    }

    @Test
    fun removedTechnicalsDoNotExplainMissingChangeNote() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result(changeNote = null)),
        )
        val body = sectionBody(text, "二、")

        // 没数据和没变化是两回事。
        assertFalse(body.contains("无昨日快照"))
        assertFalse(body.contains("与昨日无变化"))
    }

    @Test
    fun sectorCollapseShowsUpAsARisk() {
        val collapsing = SectorContext(
            industry = BoardTag("半导体", "BK0438", 1),
            state = market(MarketRegime.COLLAPSE),
            changePct = -4.0,
            source = "东财F10",
        )
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            sectorContexts = mapOf("sh600000" to collapsing),
        )
        val body = sectionBody(text, "二、")

        assertTrue(body.contains("板块塌陷"))
        assertTrue(body.contains("半导体"))
        assertTrue(body.contains("独善其身"))
    }

    @Test
    fun removedTechnicalsDoNotRepeatSectorNote() {
        // 这里测的是「复盘把 result.sectorNote 印出来」；那句话本身由 PortfolioAnalysis 生成，
        // 口径在 PortfolioAnalysisTest 里锁。fixture 直接构造 PositionResult，不走 evaluate。
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result(sectorNote = "板块：半导体，板块今日 -3.00%（东财F10）")),
        )
        val body = sectionBody(text, "二、")

        assertFalse(body.contains("板块：半导体"))
        assertFalse(body.contains("板块今日 -3.00%"))
    }

    @Test
    fun removedTechnicalsDoNotExplainMissingSector() {
        val text = report(
            positions = listOf(Position("sh600000", "甲", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", 8.8, 8.6, "甲")),
            results = listOf(result()),
            sectorContexts = mapOf("sh600000" to SectorContext.MISSING),
        )
        val body = sectionBody(text, "二、")

        assertFalse(body.contains("板块归属暂缺"))
    }
}
