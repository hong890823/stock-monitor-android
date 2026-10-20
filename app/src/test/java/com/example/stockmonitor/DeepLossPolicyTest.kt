package com.example.stockmonitor

import com.example.stockmonitor.analysis.DeepLossPolicy
import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.HealthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深套口径：阈值按 ATR% 缩放（银行股和题材股不用同一把尺子），
 * 补仓数量只给初始试仓的一半（下跌中加仓风险更高）。
 */
class DeepLossPolicyTest {

    @Test
    fun thresholdFallsBackWithoutAtr() {
        assertEquals(-20.0, DeepLossPolicy.deepLossThresholdPct(null), 0.001)
        assertEquals(-20.0, DeepLossPolicy.deepLossThresholdPct(0.0), 0.001)
    }

    @Test
    fun thresholdScalesWithAtrAndClips() {
        // ATR 2.5%：8 个 ATR 日 = -20%，和默认一致。
        assertEquals(-20.0, DeepLossPolicy.deepLossThresholdPct(2.5), 0.001)
        // 波动很小的股不能 -8% 就喊深套，截到 -12%。
        assertEquals(-12.0, DeepLossPolicy.deepLossThresholdPct(1.0), 0.001)
        // 波动很大的股 -32% 太迟钝，截到 -30%。
        assertEquals(-30.0, DeepLossPolicy.deepLossThresholdPct(5.0), 0.001)
    }

    @Test
    fun isDeepLossUsesScaledThreshold() {
        // -22% 浮亏：题材股（阈值 -28%）不算深套，稳态股（阈值 -12%）算。
        assertFalse(DeepLossPolicy.isDeepLoss(cost = 38.5, price = 30.0, atrPct = 3.5))
        assertTrue(DeepLossPolicy.isDeepLoss(cost = 38.5, price = 30.0, atrPct = 1.0))
    }

    @Test
    fun addQuantityIsHalfOfInitialEntry() {
        val qty = DeepLossPolicy.suggestedAddQuantity(
            price = 30.0,
            holdingQuantity = 0.0,
            totalCapital = 400_000.0,
            availableCapital = 0.0,
            initialEntryPct = 5.0,
            maxPositionPct = 20.0,
        )
        // 5% 的一半 = 10000 元 → 333 股 → 按手取整 300。
        assertEquals(300.0, qty, 0.001)
    }

    @Test
    fun addQuantityRespectsPositionHeadroomAndCash() {
        // 仓位已到上限：不给补仓数量。
        assertEquals(
            0.0,
            DeepLossPolicy.suggestedAddQuantity(
                price = 30.0, holdingQuantity = 700.0,
                totalCapital = 100_000.0, availableCapital = 50_000.0,
                initialEntryPct = 5.0, maxPositionPct = 20.0,
            ),
            0.001,
        )
        // 可用资金不足一手：不给数量，调用方说「资金不足」。
        assertEquals(
            0.0,
            DeepLossPolicy.suggestedAddQuantity(
                price = 30.0, holdingQuantity = 100.0,
                totalCapital = 100_000.0, availableCapital = 1_000.0,
                initialEntryPct = 5.0, maxPositionPct = 20.0,
            ),
            0.001,
        )
    }

    @Test
    fun healthyDeepLossPrioritizesRollingTOverCutOrAveragingDown() {
        val action = DeepLossPolicy.primaryBreakAction(
            support = 30.0,
            resistance = 34.0,
            addNote = "约 100 股",
            health = FundamentalHealth(
                level = HealthLevel.SOUND,
                score = 76,
                verdict = "良好",
                reasons = listOf("盈利稳定"),
                analyzedAt = "2026-10-14 10:00",
            ),
            collapse = false,
        )

        assertTrue(action.contains("滚动做T"))
        assertTrue(action.contains("降低成本"))
        assertTrue(action.contains("机动仓"))
        assertFalse(action.contains("建议割肉"))
        assertFalse(action.contains("补仓（约 100 股）"))
    }
}
