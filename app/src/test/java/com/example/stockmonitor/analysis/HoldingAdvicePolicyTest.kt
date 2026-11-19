package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
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

    private fun trend(): TechnicalTrendSnapshot = TechnicalTrendSnapshot(
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
        primaryAdvice = "暂不补仓",
        adviceReason = "测试",
        price = 50.71,
        support = 48.10,
        resistance = 13.20,
        ma20 = 53.74,
        ma60 = 58.62,
        atrPct = 3.0, // 深套阈值 = -24%
    )
}
