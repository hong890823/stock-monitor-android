package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldingAdvicePolicyTest {

    @Test
    fun deepLossWithCrashAndHeavyOutflowGivesFrameworkNotOrders() {
        val advice = evaluate(
            cost = 88.38,
            price = 50.71,
            prevClose = 51.44, // -1.42%
            flowPct = -6.55,
        )

        // 深套给的是决策框架：不割肉摊成本、不接飞刀；压力位提示分批减的参考。
        assertTrue(advice!!.contains("深套"))
        assertTrue(advice.contains("先别急着割肉"))
        assertTrue(advice.contains("补仓摊成本"))
        assertTrue(advice.contains("13.20")) // 反弹到压力附近分批减
    }

    @Test
    fun nearCostMentionsBreakeven() {
        val advice = evaluate(
            cost = 16.25,
            price = 16.26,
            prevClose = 16.15, // +0.68%
            flowPct = -9.37,
        )

        assertTrue(advice!!.contains("贴着成本"))
        assertTrue(advice.contains("保本"))
        assertNullFlowNotMentioned(advice)
    }

    @Test
    fun bigProfitMentionsDrawdownProtection() {
        val advice = evaluate(
            cost = 5.42,
            price = 12.34,
            prevClose = 12.05, // +2.38%
            flowPct = 1.37,
        )

        assertTrue(advice!!.contains("浮盈"))
        assertTrue(advice.contains("回撤保护"))
    }

    @Test
    fun crashDayAndHeavyOutflowAreCalledOutEvenWithoutPosition() {
        val advice = HoldingAdvicePolicy.evaluate(
            position = Position("sh600522", "中天科技", quantity = 0.0, cost = 0.0),
            quote = quote(price = 26.66, prevClose = 28.36), // -5.99% 急跌
            technicalTrend = null,
            fundFlow = fundFlow(-16.83),
        )

        // 未持仓观察也提示当日异动与资金面，但不会出现浮盈亏话术。
        assertTrue(advice!!.contains("急跌"))
        assertTrue(advice.contains("主力净流出"))
        assertTrue(!advice.contains("浮亏") && !advice.contains("浮盈"))
    }

    @Test
    fun quietStockWithMidBandPnlReturnsNull() {
        // 浮盈亏在 ±2%~5% 中间带、无当日异动、无资金异动：没有值得念叨的内容。
        val advice = evaluate(
            cost = 10.00,
            price = 10.30,
            prevClose = 10.28, // +0.19%
            flowPct = -2.0,
        )

        assertNull(advice)
    }

    // ---- 合并项（操作建议 + 持仓视角）：口径冲突收口 ----

    @Test
    fun actionClassificationCoversTechnicalTemplates() {
        assertEquals(
            HoldingAdvicePolicy.TechnicalAction.EXIT_REDUCE,
            HoldingAdvicePolicy.actionOf("减仓或退出：现价 50.71 已跌破支撑 48.10，先控制仓位。"),
        )
        assertEquals(
            HoldingAdvicePolicy.TechnicalAction.NO_ADD,
            HoldingAdvicePolicy.actionOf("暂不补仓：周K偏弱、月K偏弱。"),
        )
        assertEquals(
            HoldingAdvicePolicy.TechnicalAction.ADD_PARTICIPATE,
            HoldingAdvicePolicy.actionOf("可分批参与：短中期均线多头排列，回踩不破 MA5（13.14）再加。"),
        )
        assertEquals(
            HoldingAdvicePolicy.TechnicalAction.ADD_PARTICIPATE,
            HoldingAdvicePolicy.actionOf("可小仓观察：周K趋势修复，等回踩企稳和量能配合后再分批。"),
        )
        assertEquals(
            HoldingAdvicePolicy.TechnicalAction.HOLD_WATCH,
            HoldingAdvicePolicy.actionOf("持有观察：价格仍在区间内，不追高。"),
        )
    }

    @Test
    fun mergedAddPointSuspendedOnCrashDay() {
        val advice = merged(
            advice = ADD_ADVICE,
            cost = 10.00,
            price = 10.30,
            prevClose = 10.95, // -5.94% 急跌
            flowPct = -2.0,
        )

        // 技术买点原文保留在前，但急跌日的上下文显式暂停买点——不出现「再加」和「先别接刀」干瞪眼。
        assertTrue(advice.startsWith("可分批参与"))
        assertTrue(advice.contains("回踩买点今天先不执行"))
        assertTrue(advice.contains("急跌"))
    }

    @Test
    fun mergedAddPointSuspendedOnHeavyOutflow() {
        val advice = merged(
            advice = ADD_ADVICE,
            cost = 10.00,
            price = 10.30,
            prevClose = 10.35, // -0.48%
            flowPct = -12.5,
        )

        assertTrue(advice.contains("技术买点先缓一缓"))
        assertTrue(advice.contains("主力净流出"))
    }

    @Test
    fun mergedDeepLossAddBecomesTOnlyNotAveragingDown() {
        val advice = merged(
            advice = ADD_ADVICE,
            cost = 88.38,
            price = 50.71,
            prevClose = 51.44, // -1.42%
            flowPct = -6.55,
        )

        // 深套时技术买点降级为做T口径：接回机动仓、不补仓摊成本，与「回踩再加」不打架。
        assertTrue(advice.startsWith("可分批参与"))
        assertTrue(advice.contains("只按做T口径轻仓接回机动仓"))
        assertTrue(advice.contains("不补仓摊成本"))
        assertTrue(advice.contains("再创新低取消接回"))
    }

    @Test
    fun mergedExitWithDeepLossReducesInBatchesNotPanicSell() {
        val advice = merged(
            advice = "减仓或退出：现价 50.71 已跌破支撑 48.10，先控制仓位；重新站稳后再评估是否接回。",
            cost = 88.38,
            price = 50.71,
            prevClose = 51.44,
            flowPct = -6.55,
        )

        // 减仓成立（破位风险独立成立），但按分批减机动仓执行，不和「深套别割肉」对打。
        assertTrue(advice.startsWith("减仓或退出"))
        assertTrue(advice.contains("分批减机动仓"))
        assertTrue(advice.contains("不一次恐慌清仓"))
        assertFalse(advice.contains("先别急着割肉"))
    }

    @Test
    fun mergedExitWithBigProfitTakesProfitInsteadOfHold() {
        val advice = merged(
            advice = "减仓或退出：现价 12.34 已跌破支撑 11.20，先控制仓位；重新站稳后再评估是否接回。",
            cost = 5.42,
            price = 12.34,
            prevClose = 12.05,
            flowPct = 1.37,
        )

        // 大浮盈 + 技术减仓：落袋口径，不再说「支撑不破可继续持有」。
        assertTrue(advice.contains("落袋"))
        assertFalse(advice.contains("继续持有"))
    }

    @Test
    fun mergedHoldWithBigProfitKeepsHoldWording() {
        val advice = merged(
            advice = "持有观察：价格仍在区间内，压力 12.30、支撑 11.20 之间没有形成一致方向，不追高。",
            cost = 5.42,
            price = 12.34,
            prevClose = 12.05,
            flowPct = 1.37,
        )

        assertTrue(advice.startsWith("持有观察"))
        assertTrue(advice.contains("回撤保护"))
        assertTrue(advice.contains("不破可继续持有"))
    }

    @Test
    fun mergedWithoutContextIsJustTechnicalAdvice() {
        val advice = merged(
            advice = ADD_ADVICE,
            cost = 10.00,
            price = 10.30,
            prevClose = 10.28, // +0.19%
            flowPct = -2.0,
        )

        assertEquals("可分批参与：周K向上、月K向上，短中期均线多头排列；回踩不破 MA5（13.14）再加", advice)
    }

    @Test
    fun mergedWithoutTechnicalFallsBackToHoldingContext() {
        val advice = HoldingAdvicePolicy.mergedAdvice(
            position = Position("sh600522", "中天科技", quantity = 0.0, cost = 0.0),
            quote = quote(price = 26.66, prevClose = 28.36),
            technicalTrend = null,
            fundFlow = fundFlow(-16.83),
        )

        assertTrue(advice!!.contains("急跌"))
        assertTrue(advice.contains("主力净流出"))
    }

    private val ADD_ADVICE = "可分批参与：周K向上、月K向上，短中期均线多头排列；回踩不破 MA5（13.14）再加"

    private fun assertNullFlowNotMentioned(advice: String) {
        // -9.37% 未到 -10% 阈值，不应出现资金流提示。
        assertTrue(!advice.contains("主力净流出"))
    }

    private fun evaluate(cost: Double, price: Double, prevClose: Double, flowPct: Double): String? =
        HoldingAdvicePolicy.evaluate(
            position = Position("sh600118", "中国卫星", quantity = 1100.0, cost = cost),
            quote = quote(price, prevClose),
            technicalTrend = trend(),
            fundFlow = fundFlow(flowPct),
        )

    private fun merged(
        advice: String,
        cost: Double,
        price: Double,
        prevClose: Double,
        flowPct: Double,
    ): String = HoldingAdvicePolicy.mergedAdvice(
        position = Position("sh600118", "中国卫星", quantity = 1100.0, cost = cost),
        quote = quote(price, prevClose),
        technicalTrend = trend(advice),
        fundFlow = fundFlow(flowPct),
    )!!

    private fun quote(price: Double, prevClose: Double): Quote = Quote(
        code = "sh600118",
        name = "中国卫星",
        price = price,
        prevClose = prevClose,
        open = prevClose,
        high = price * 1.01,
        low = price * 0.99,
        volume = 1_000_000.0,
        turnover = 6.81e8,
    )

    private fun fundFlow(pct: Double): FundFlow = FundFlow(
        code = "sh600118",
        name = "中国卫星",
        asOfMs = 0L,
        mainNetPct = pct,
    )

    private fun trend(advice: String = "暂不补仓"): TechnicalTrendSnapshot = TechnicalTrendSnapshot(
        code = "sh600118",
        name = "中国卫星",
        asOf = "2026-10-27",
        technicalScore = 30,
        trendPoints = 0,
        structurePoints = 8,
        momentumPoints = 12,
        riskPoints = 10,
        weeklyTrend = SwingTrend.WEAK,
        monthlyTrend = SwingTrend.WEAK,
        trendWeak = true,
        allowsAddPosition = false,
        primaryAdvice = advice,
        adviceReason = "测试",
        price = 50.71,
        support = 48.10,
        resistance = 13.20,
        ma20 = 53.74,
        ma60 = 58.62,
        atrPct = 3.0, // 深套阈值 = -24%
    )
}
