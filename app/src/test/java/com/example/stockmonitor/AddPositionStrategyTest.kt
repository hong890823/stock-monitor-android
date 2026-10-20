package com.example.stockmonitor

import com.example.stockmonitor.analysis.AddLevel
import com.example.stockmonitor.analysis.AddPositionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPositionStrategyTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidTarget() {
        AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 0.0,
        )
    }

    @Test
    fun solvesSharesForTargetCost() {
        // 1000股@10元，摊到9元：在8元买1000股即可 (10000+8000)/2000 = 9。
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            levels = listOf(AddLevel("支撑位", 8.0)),
        )
        val level = plan.levels.single()
        assertEquals(1000.0, level.requiredShares!!, 1e-9)
        assertEquals(1000.0, level.shares, 1e-9)
        assertEquals(8000.0, level.cashNeeded, 1e-9)
        assertEquals(9.0, level.afterAverageCost!!, 1e-9)
        assertTrue(level.feasible)
    }

    @Test
    fun buyPriceAboveTargetCannotConverge() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 8.0,
            levels = listOf(AddLevel("现价", 9.0)),
        )
        val level = plan.levels.single()
        assertNull(level.requiredShares)
        assertFalse(level.feasible)
        assertTrue(level.note.contains("高于目标成本"))
    }

    @Test
    fun roundsDownToLotAndReportsResidual() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            levels = listOf(AddLevel("支撑位", 8.2)),
        )
        // 精确解 1250 股，按手取整后 1200 股，补后成本略高于目标。
        val level = plan.levels.single()
        assertEquals(1200.0, level.shares, 1e-9)
        assertTrue(level.afterAverageCost!! > 9.0)
        assertTrue(level.note.contains("取整"))
    }

    @Test
    fun flagsCashShortage() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 10000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            levels = listOf(AddLevel("支撑位", 8.0)),
            availableCapital = 1000.0,
        )
        val level = plan.levels.single()
        assertFalse(level.feasible)
        assertTrue(level.note.contains("超过可用资金"))
    }

    @Test
    fun prefersPullbackZoneOverSpotPrice() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            levels = listOf(
                AddLevel("现价", 8.0),
                AddLevel("波段回踩区间下沿", 8.2),
            ),
        )
        assertEquals("波段回踩区间下沿", plan.suggested?.label)
    }

    @Test
    fun warnsDeepLossAgainstBlindAveraging() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 8.0,
            levels = listOf(AddLevel("支撑位", 6.0)),
            currentPrice = 7.0, // -30%，深套口径（默认阈值 -20%）
        )
        assertTrue(plan.warnings.any { it.contains("深套") })
    }

    @Test
    fun lotReversePricesGoDownWithSize() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
        )
        assertTrue(plan.lots.size >= 2)
        // 补得越多能接受的入手价越高；相邻手数严格递增。
        plan.lots.zipWithNext().forEach { (a, b) -> assertTrue(a.buyPrice < b.buyPrice) }
    }

    @Test
    fun reportContainsSectionsAndDisclaimer() {
        val plan = AddPositionStrategy.plan(
            code = "sh600000",
            holdingShares = 1000.0,
            averageCost = 10.0,
            targetCost = 9.0,
            levels = listOf(AddLevel("支撑位", 8.0)),
            currentPrice = 8.5,
        )
        val report = AddPositionStrategy.formatReport(plan, "浦发银行（sh600000）", "盘中持仓分析")
        assertTrue(report.contains("补仓策略（目标成本）"))
        assertTrue(report.contains("按整手反推入手价"))
        assertTrue(report.contains("不构成投资建议"))
    }
}
