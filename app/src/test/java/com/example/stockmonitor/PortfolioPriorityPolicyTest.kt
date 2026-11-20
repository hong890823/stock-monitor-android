package com.example.stockmonitor

import com.example.stockmonitor.analysis.PortfolioPriorityPolicy
import com.example.stockmonitor.analysis.PriorityInput
import com.example.stockmonitor.analysis.PriorityState
import org.junit.Assert.*
import org.junit.Test

class PortfolioPriorityPolicyTest {
    private val today = "2026-10-26"
    private fun input(code: String = "sh600522", quantity: Double = 1400.0) = PriorityInput(
        code = code, name = code, quantity = quantity, price = 26.66, support = 26.01,
        ma20 = 30.18, quoteDate = today, evidenceDate = today,
    )

    @Test fun weakTrendAndFinancialReviewAreNotSellInstructions() {
        val weak = input()
        val financial = input("sz000625", 1000.0).copy(price = 7.18, support = 6.90, ma20 = 7.07,
            fundamentalAlert = true, fundamentalReason = "现金流需要核实")
        val decisions = PortfolioPriorityPolicy.rank(listOf(financial, weak), today)
        assertEquals(weak.code, decisions.first().input.code)
        assertEquals(PriorityState.WATCH, decisions.first().state)
        assertEquals(PriorityState.HOLD, decisions.last().state)
        val lines = PortfolioPriorityPolicy.summary(listOf(financial, weak), today)
        assertTrue(lines.first().contains("暂无已确认的买卖动作"))
        assertTrue(lines.any { it.startsWith("风险核实：") && it.contains("sz000625") })
        assertFalse(lines.first().contains("sz000625"))
    }

    @Test fun fundamentalAlertNeverApprovesAddition() {
        // 价格健康、但基本面亮红灯：order 会到 3，旧实现因此放行追加。
        val healthy = input().copy(price = 26.0, ma20 = 25.0, fundamentalAlert = true)
        assertFalse(PortfolioPriorityPolicy.canApproveAddition(healthy, today))
        assertTrue(PortfolioPriorityPolicy.canApproveAddition(healthy.copy(fundamentalAlert = false), today))
    }

    @Test fun mitigatedLightLowPositionDoesNotEscalateToReduceOrExit() {
        val risk = input().copy(
            price = 24.0,
            riskConfirmed = true,
            exitTriggered = true,
            mitigated = true,
        )
        val decision = PortfolioPriorityPolicy.evaluate(risk, today)
        assertEquals(PriorityState.WATCH, decision.state)
        assertFalse(decision.actionConfirmed)
        assertTrue(decision.reason.contains("去留自定"))
        // 未缓释的同一确认风险仍然按 EXIT 处理，缓释不能被滥用。
        assertEquals(PriorityState.EXIT, PortfolioPriorityPolicy.evaluate(risk.copy(mitigated = false), today).state)
    }

    @Test fun confirmedRiskWinsRegardlessOfCostOrFundamentalScore() {
        val risk = input().copy(price = 24.0, riskConfirmed = true, exitTriggered = true)
        val unconfirmed = input("sz000625", 100000.0).copy(price = 20.0, fundamentalAlert = true)
        val ranked = PortfolioPriorityPolicy.rank(listOf(unconfirmed, risk), today)
        assertEquals(PriorityState.EXIT, ranked.first().state)
        assertEquals(risk.code, ranked.first().input.code)
        assertEquals(PriorityState.WATCH, ranked.last().state)
    }

    @Test fun sameRiskUsesLargerExposureThenCodeNotNameOrInputOrder() {
        val small = input("sz000625", 100.0)
        val large = input("sh600522", 1400.0)
        assertEquals(large.code, PortfolioPriorityPolicy.rank(listOf(small, large), today).first().input.code)
        val tied = large.copy(code = "sz000001", name = "AAA")
        assertEquals(large.code, PortfolioPriorityPolicy.rank(listOf(tied, large), today).first().input.code)
    }

    @Test fun staleOrMissingDatesNeverAuthorizeTrades() {
        for (date in listOf("", "2026-10-23", "2026-10-27")) {
            val risk = input().copy(price = 24.0, riskConfirmed = true, exitTriggered = true, evidenceDate = date)
            val decision = PortfolioPriorityPolicy.evaluate(risk, today)
            assertEquals(PriorityState.WATCH, decision.state)
            assertFalse(decision.fresh)
            assertTrue(decision.reason.contains("数据"))
        }
    }

    @Test fun safeHoldAndInvalidPositionsDoNotManufactureARecommendation() {
        val hold = input().copy(ma20 = 25.0)
        val lines = PortfolioPriorityPolicy.summary(listOf(hold, input("bad").copy(quantity = Double.NaN)), today)
        assertEquals("当前优先事项：暂无已确认的买卖动作", lines.first())
        assertEquals(1, PortfolioPriorityPolicy.rank(listOf(hold, input("bad").copy(quantity = -1.0)), today).size)
        assertEquals(PriorityState.WATCH, PortfolioPriorityPolicy.evaluate(hold.copy(price = Double.NaN), today).state)
    }

    @Test fun buysRequireExplicitBudgetApprovalAndNoUnresolvedPortfolioRisk() {
        val buy = input().copy(ma20 = 25.0, buyApproved = true)
        assertEquals(PriorityState.BUY, PortfolioPriorityPolicy.evaluate(buy, today).state)
        assertEquals(PriorityState.HOLD, PortfolioPriorityPolicy.evaluate(buy.copy(buyApproved = false), today).state)
        val lines = PortfolioPriorityPolicy.summary(listOf(buy, input("risk")), today)
        assertTrue(lines.any { it == "买入机会：先处理未解决风险，暂不新增" })
    }

    @Test fun fundamentalAlertCannotPromoteConfirmedBreakToExit() {
        val risk = input().copy(price = 25.0, riskConfirmed = true, fundamentalAlert = true)
        assertEquals(PriorityState.REDUCE, PortfolioPriorityPolicy.evaluate(risk, today).state)
    }

    @Test fun unresolvedRiskBlocksOnlyThatHoldingFromAdditionalCapital() {
        val safe = input("sh600522").copy(ma20 = 25.0)
        val risky = input("sz000625").copy(
            price = 24.0,
            riskConfirmed = true,
            exitTriggered = true,
        )

        assertTrue(PortfolioPriorityPolicy.canApproveAddition(safe, today))
        assertFalse(PortfolioPriorityPolicy.canApproveAddition(risky, today))
    }
}
