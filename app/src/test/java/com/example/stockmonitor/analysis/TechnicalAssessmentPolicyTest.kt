package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalAssessmentPolicyTest {

    @Test
    fun healthyIndicatorsProduceHighScoreAndParticipationAdviceWithoutBuyPoint() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical(),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(result.score >= 85)
        assertTrue(result.trendPoints > 0)
        assertTrue(result.primaryAdvice.contains("入场结构"))
        assertTrue(result.primaryAdvice.contains("观察"))
        assertEquals(
            result.score,
            result.trendPoints + result.structurePoints + result.momentumPoints + result.riskPoints,
        )
    }

    @Test
    fun collapseEnvironmentSuppressesParticipationAdvice() {
        val normal = TechnicalAssessmentPolicy.evaluate(
            technical = technical(),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )
        val collapse = TechnicalAssessmentPolicy.evaluate(
            technical = technical(),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
            marketRegime = MarketRegime.COLLAPSE,
        )

        assertFalse("正常环境不应给出塌陷措辞", normal.primaryAdvice.contains("暂缓补仓"))
        assertTrue(
            "同一只票在塌陷期不能与持仓页/AI 给出相反结论",
            collapse.primaryAdvice.contains("暂缓补仓"),
        )
    }

    @Test
    fun brokenSupportForcesRiskAdviceAndCapsScore() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                price = 8.0,
                support = 9.0,
                entrySetup = weakSetup(),
                trendAssessment = trendAssessment(MajorTrend.DOWN, ShortTermTrend.DOWN),
            ),
            weeklyTrend = SwingTrend.WEAK,
            monthlyTrend = SwingTrend.WEAK,
        )

        assertTrue(result.score <= 45)
        assertTrue(result.primaryAdvice.contains("减仓"))
        assertTrue(result.primaryAdvice.contains("退出"))
        assertTrue(result.primaryAdvice.contains("支撑"))
    }

    @Test
    fun weakTrendWithoutBreakSaysDoNotAddAndWaitForStability() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                entrySetup = weakSetup().copy(trendWeak = true),
                trendAssessment = trendAssessment(MajorTrend.DOWN, ShortTermTrend.DOWN),
            ),
            weeklyTrend = SwingTrend.WEAK,
            monthlyTrend = SwingTrend.STABILIZING,
        )

        assertTrue(result.score <= 45)
        assertTrue(result.primaryAdvice.contains("暂不补仓"))
        assertTrue(result.primaryAdvice.contains("企稳"))
        assertTrue(result.primaryAdvice.contains("MA20"))
        assertTrue(result.primaryAdvice.contains("MA60"))
        assertFalse(result.primaryAdvice.contains("仍偏弱"))
    }

    @Test
    fun highPositionRiskIsCappedAndWaitsForPullback() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                highPosition = HighPositionDecision(
                    phase = HighPositionPhase.EXTENDED_WATCH,
                    reason = "连续拉升后接近区间上沿",
                    allowBuy = false,
                    allowSecondWaveEntry = false,
                    allowHoldBase = true,
                    riskPriority = true,
                ),
            ),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(result.score <= 55)
        assertTrue(result.primaryAdvice.contains("回踩"))
        assertTrue(result.primaryAdvice.contains("不追高"))
    }

    @Test
    fun pullbackAdviceForStrongStockCitesShallowAnchorNotTwentyDayLow() {
        // 海立股份 2026-11-19 回放（entry-timing-2026-11-19/600619.json）：
        // 现价 15.07、20 日低点 11.04。「回踩支撑 11.04」意味着趋势已死，属误导；
        // 分档锚给 MA5 浅锚，操作建议不得再把 20 日低点当回踩参考。
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                price = 15.07,
                priceChange = 9.9,
                ma5 = 13.14,
                ma10 = 12.55,
                ma20 = 12.17,
                support = 11.04,
                resistance = 15.07,
                highPosition = HighPositionDecision(
                    phase = HighPositionPhase.EXTENDED_WATCH,
                    reason = "首板后连续拉升",
                    allowBuy = false,
                    allowSecondWaveEntry = false,
                    allowHoldBase = true,
                    riskPriority = true,
                ),
                pullbackAnchor = PullbackAnchorPolicy.anchorFor(
                    price = 15.07,
                    ma5 = 13.14,
                    ma10 = 12.55,
                    ma20 = 12.17,
                    recentCloses = listOf(11.95, 12.01, 12.07, 11.90, 11.90, 11.91, 13.10, 13.70, 15.07),
                ),
            ),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(result.primaryAdvice.contains("回踩"))
        assertTrue(result.primaryAdvice.contains("MA5（13.14）"))
        assertFalse("20日低点是破位口径，不能当回踩锚", result.primaryAdvice.contains("11.04"))
    }

    @Test
    fun pullbackAdviceFallsBackToMa20NotTwentyDayLowWithoutAnchor() {
        // 缺预计算锚位时按缺证据降级到 MA20 口径，同样不引20日低点当回踩锚。
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                highPosition = HighPositionDecision(
                    phase = HighPositionPhase.EXTENDED_WATCH,
                    reason = "连续拉升后接近区间上沿",
                    allowBuy = false,
                    allowSecondWaveEntry = false,
                    allowHoldBase = true,
                    riskPriority = true,
                ),
            ),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(result.primaryAdvice.contains("MA20（10.90）"))
        assertFalse(result.primaryAdvice.contains("10.5"))
    }

    @Test
    fun highPositionNormalDivergenceDoesNotGetRapidHeatCap() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                price = 13.0,
                priceChange = 6.0,
                volumeRatio = 2.0,
                priceContext = PriceContext(PriceLocation.HIGH, 35.0),
                ma5 = 12.0,
                ma10 = 11.5,
                ma20 = 10.5,
                ma60 = 10.0,
                support = 11.5,
                resistance = 13.5,
            ),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(result.score > 55)
        assertEquals(NormalDivergenceLevel.NORMAL, result.normalDivergenceLevel)
        assertTrue(result.primaryAdvice.contains("正常分歧"))
    }

    @Test
    fun confirmedDailyBreakStillCapsScoreAndExplainsRisk() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                price = 8.0,
                ma5 = 10.0,
                ma10 = 9.8,
                ma20 = 9.0,
                support = 9.5,
                entrySetup = weakSetup().copy(trendWeak = true),
                trendAssessment = trendAssessment(MajorTrend.DOWN, ShortTermTrend.DOWN),
            ),
            weeklyTrend = SwingTrend.WEAK,
            monthlyTrend = SwingTrend.WEAK,
        )

        assertTrue(result.score <= 45)
        assertEquals(NormalDivergenceLevel.CONFIRMED_BREAK, result.normalDivergenceLevel)
        assertTrue(result.reason.contains("破位"))
    }

    @Test
    fun componentScoresStayWithinTheirWeights() {
        val result = TechnicalAssessmentPolicy.evaluate(
            technical = technical(),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.REPAIR,
        )

        assertTrue(result.trendPoints in 0..35)
        assertTrue(result.structurePoints in 0..25)
        assertTrue(result.momentumPoints in 0..20)
        assertTrue(result.riskPoints in 0..20)
        assertTrue(result.level.label.isNotBlank())
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun confirmedEntryUsesParticipationWordingDifferentFromUnconfirmedObservation() {
        val confirmed = TechnicalAssessmentPolicy.evaluate(
            technical = technical().copy(
                entrySetup = weakSetup().copy(
                    type = EntrySetupType.BREAKOUT,
                    confirmed = true,
                    canBuy = true,
                    volumeConfirmed = true,
                ),
            ),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )
        val unconfirmed = TechnicalAssessmentPolicy.evaluate(
            technical = technical(),
            weeklyTrend = SwingTrend.UP,
            monthlyTrend = SwingTrend.UP,
        )

        assertTrue(confirmed.primaryAdvice.contains("分批"))
        assertTrue(confirmed.primaryAdvice.contains("确认"))
        assertTrue(unconfirmed.primaryAdvice.contains("入场结构"))
        assertTrue(unconfirmed.primaryAdvice.contains("观察"))
        assertTrue(confirmed.primaryAdvice != unconfirmed.primaryAdvice)
    }

    private fun weakSetup() = EntrySetup(
        type = EntrySetupType.NONE,
        confirmed = false,
        canBuy = false,
        trendWeak = false,
        priorHigh = null,
        ma20SlopePct = -1.0,
        volumeConfirmed = false,
        marketFilter = MarketFilter.NEUTRAL,
        stopPrice = 9.5,
        reason = "测试",
    )

    private fun trendAssessment(major: MajorTrend, shortTerm: ShortTermTrend) = TrendAssessment(
        shortTerm = shortTerm,
        major = major,
        reason = "测试",
        allowsOrdinaryBuy = major == MajorTrend.UP,
        allowsAddPosition = major == MajorTrend.UP,
        allowsTBuy = major == MajorTrend.UP,
        allowsTSell = major == MajorTrend.UP,
        allowsBottomReversal = major == MajorTrend.BOTTOMING,
        riskOnly = major == MajorTrend.DOWN,
    )

    private fun technical() = TechnicalResult(
        code = "sh600000",
        name = "测试股票",
        asOf = "2026-10-26 15:00",
        rows = 250,
        price = 12.0,
        priceSource = "测试",
        priceChange = 1.8,
        opening = OpeningStructure(11.5, 11.6, 0.87, 3.45, "高开", "高开高走"),
        volumeRatio = 1.5,
        volumeRatioIntraday = false,
        priceContext = null,
        volatility = Volatility(0.3, 2.5, 11.0, 12.4, 9.8, 0.85),
        trendStrength = TrendStrength(32.0, 30.0, 10.0),
        obvChange = 500.0,
        divergence = DivergenceResult(
            state = DivergenceState.SYNC,
            forming = false,
            scoreDelta = 1,
            prior = null,
            current = null,
            obvConfirmedNewHigh = true,
            obvConfirmedNewLow = null,
            reason = "量价同步",
            meaning = "上涨得到成交量确认",
            short = "量价同步",
            method = "测试",
        ),
        relativeStrength = 6.0,
        ma5 = 11.7,
        ma10 = 11.3,
        ma20 = 10.9,
        ma60 = 10.2,
        support = 10.5,
        resistance = 13.0,
        entrySetup = weakSetup(),
        macd = MacdValues(0.35, 0.20, 0.15, 0.10),
        rsi = 62.0,
        kdj = KdjValues(72.0, 63.0, 88.0),
        chip = ChipPeaks(11.0, emptyList(), null, null, null, "较集中", "估算"),
        score = 3,
        signals = emptyList(),
        action = "技术面偏强",
        trendAssessment = trendAssessment(MajorTrend.UP, ShortTermTrend.UP),
    )
}
