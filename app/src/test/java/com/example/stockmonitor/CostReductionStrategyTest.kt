package com.example.stockmonitor

import com.example.stockmonitor.analysis.AddLevel
import com.example.stockmonitor.analysis.AddPositionStrategy
import com.example.stockmonitor.analysis.CostReductionMarket
import com.example.stockmonitor.analysis.CostReductionTrend
import com.example.stockmonitor.analysis.CostStrategyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostReductionStrategyTest {

    @Test
    fun buysDipWhenUptrendIsConfirmed() {
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            market = CostReductionMarket(
                currentPrice = 10.5,
                support = 8.0,
                resistance = 12.0,
                ma20 = 9.0,
                entryLow = 8.2,
                entryHigh = 11.5,
                stopPrice = 7.5,
                trend = CostReductionTrend.UPTREND,
                buyConfirmed = true,
                signal = "买入确认",
            ),
        )

        assertEquals(CostStrategyType.BUY_DIP, plan.recommended?.type)
        assertTrue(plan.recommended!!.legs.single().action.label == "买入")
    }

    @Test
    fun sellsHighAndBuysBackLowInRange() {
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            market = CostReductionMarket(
                currentPrice = 9.5,
                support = 8.0,
                resistance = 11.0,
                ma20 = 9.2,
                entryLow = 8.2,
                entryHigh = 10.8,
                stopPrice = 7.5,
                trend = CostReductionTrend.RANGE,
                buyConfirmed = false,
                signal = "持有观察",
            ),
        )

        val recommended = plan.recommended!!
        assertEquals(CostStrategyType.SELL_REBUY, recommended.type)
        assertEquals("卖出", recommended.legs[0].action.label)
        assertEquals("买入", recommended.legs[1].action.label)
        assertEquals(1000.0, recommended.afterShares, 1e-9)
        assertTrue(recommended.afterCost!! <= 9.15)
    }

    @Test
    fun reducesRiskInsteadOfAveragingDownInDowntrend() {
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 8.5,
            market = CostReductionMarket(
                currentPrice = 7.2,
                support = 7.0,
                resistance = 8.4,
                ma20 = 8.2,
                entryLow = 6.8,
                entryHigh = 8.1,
                stopPrice = 7.4,
                trend = CostReductionTrend.DOWNTREND,
                buyConfirmed = false,
                signal = "减仓风险",
            ),
        )

        assertEquals(CostStrategyType.REDUCE_RISK, plan.recommended?.type)
        assertTrue(plan.options.none { it.type == CostStrategyType.BUY_DIP && it.feasible })
        assertTrue(plan.warnings.any { it.contains("不盲目") })
    }

    @Test
    fun explicitReferencePriceCanDriveDipBuy() {
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            market = CostReductionMarket(
                currentPrice = 9.8,
                trend = CostReductionTrend.UPTREND,
                buyConfirmed = true,
            ),
            extraBuyLevels = listOf(AddLevel("自定义价位", 8.0)),
        )

        assertEquals(CostStrategyType.BUY_DIP, plan.recommended?.type)
        assertTrue(plan.recommended!!.legs.single().price == 8.0)
    }

    @Test
    fun reportExplainsSequenceAndTPlusOneRisk() {
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            market = CostReductionMarket(
                currentPrice = 9.5,
                support = 8.0,
                resistance = 11.0,
                trend = CostReductionTrend.RANGE,
                buyConfirmed = false,
            ),
        )

        val report = AddPositionStrategy.formatCostReductionReport(plan, "测试股票")
        assertTrue(report.contains("降低持仓成本买卖策略"))
        assertTrue(report.contains("先卖后买"))
        assertTrue(report.contains("T+1"))
        assertTrue(report.contains("不构成投资建议"))
    }

    @Test
    fun shallowAnchorLabelOutranksDeepLevelsAndDeepGapGetsGuardNote() {
        // 强势浅锚优先于支撑/MA20 深位；深位过不了可达性护栏时给出「大概率到不了」注记。
        val plan = AddPositionStrategy.costReductionPlan(
            holdingShares = 1000.0,
            averageCost = 13.0,
            targetCost = 12.0,
            market = CostReductionMarket(
                currentPrice = 12.0,
                support = 9.0,
                resistance = 15.0,
                ma20 = 9.5,
                entryLow = 11.6,
                entryHigh = 12.2,
                stopPrice = 8.5,
                trend = CostReductionTrend.UPTREND,
                atrPct = 2.0,
                anchorLabel = "强势浅回踩锚（MA5）",
            ),
        )

        assertEquals("强势浅回踩锚（MA5）", plan.recommended?.legs?.single()?.label)
        val warnings = plan.warnings.joinToString("\n")
        assertTrue(warnings.contains("大概率到不了"))
        assertTrue(warnings.contains("支撑位"))
    }
}
