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
