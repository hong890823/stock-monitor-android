package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分歧期状态矩阵（模块 C）与分批进场参数（模块 E）。
 * 对应 docs/2026-11-24-divergence-execution-strategy.md。
 */
class DivergenceExecutionTest {

    // ---------- 模块 C：状态矩阵 ----------

    @Test
    fun nonePhaseProducesNoNoise() {
        assertTrue(DivergenceActionMatrix.promptsFor(DivergencePhase.NONE, hasHoldings = true).isEmpty())
    }

    @Test
    fun reboundHoldHoldingKeepsCorePosition() {
        val text = DivergenceActionMatrix.promptsFor(DivergencePhase.REBOUND_HOLD, hasHoldings = true).joinToString("\n")
        assertTrue(text.contains("留底仓不全兑现"))
        assertTrue(text.contains("已有浮盈不是卖出理由"))
    }

    @Test
    fun reboundHoldFlatEntersInBatchesNotAllIn() {
        val text = DivergenceActionMatrix.promptsFor(DivergencePhase.REBOUND_HOLD, hasHoldings = false).joinToString("\n")
        assertTrue(text.contains("分批进场"))
        assertTrue(text.contains("不是一把梭"))
    }

    @Test
    fun watchHoldingDoesNothingAndFlatOnlyPrepares() {
        val holding = DivergenceActionMatrix.promptsFor(DivergencePhase.WATCH, hasHoldings = true).joinToString("\n")
        assertTrue(holding.contains("不动"))
        assertTrue(holding.contains("一根 K 线定方向"))
        val flat = DivergenceActionMatrix.promptsFor(DivergencePhase.WATCH, hasHoldings = false).joinToString("\n")
        assertTrue(flat.contains("只准备不进场"))
        assertTrue(flat.contains("宁慢勿追"))
    }

    @Test
    fun downgradePromptsByRiskFlagAndFreezesFlat() {
        val heavy = DivergenceActionMatrix.promptsFor(DivergencePhase.DOWNGRADE, hasHoldings = true, riskFlag = true).joinToString("\n")
        assertTrue(heavy.contains("重仓/红灯"))
        assertTrue(heavy.contains("提示不命令"))
        val light = DivergenceActionMatrix.promptsFor(DivergencePhase.DOWNGRADE, hasHoldings = true, riskFlag = false).joinToString("\n")
        assertTrue(light.contains("轻仓/无红灯只提示"))
        assertTrue(light.contains("不摊平"))
        val flat = DivergenceActionMatrix.promptsFor(DivergencePhase.DOWNGRADE, hasHoldings = false).joinToString("\n")
        assertTrue(flat.contains("冻结进场"))
    }

    @Test
    fun everyNonNonePhaseCarriesTheNoChaseBan() {
        listOf(DivergencePhase.WATCH, DivergencePhase.REBOUND_HOLD, DivergencePhase.DOWNGRADE).forEach { phase ->
            val text = DivergenceActionMatrix.promptsFor(phase, hasHoldings = true).joinToString("\n")
            assertTrue(text.contains("禁止：等连续大阳线追确认"))
        }
    }

    // ---------- 模块 E：分批参数 ----------

    @Test
    fun batchesSplitFortyThirtyThirty() {
        assertEquals(3, AddBatchPolicy.BATCHES.size)
        assertEquals(AddBatchPolicy.FIRST_BATCH_PCT, AddBatchPolicy.BATCHES[0].sharePct, 0.0)
        assertEquals(AddBatchPolicy.SECOND_BATCH_PCT, AddBatchPolicy.BATCHES[1].sharePct, 0.0)
        assertEquals(AddBatchPolicy.THIRD_BATCH_PCT, AddBatchPolicy.BATCHES[2].sharePct, 0.0)
        assertEquals(100.0, AddBatchPolicy.BATCHES.sumOf { it.sharePct }, 0.0)
    }

    @Test
    fun summaryCarriesTriggersAndInvalidation() {
        val summary = AddBatchPolicy.summary()
        assertTrue(summary.contains("分批执行，不一次打满"))
        assertTrue(summary.contains("缩量企稳"))
        assertTrue(summary.contains("证伪确认"))
        assertTrue(summary.contains("作废") || summary.contains("取消全部后续批次"))
        assertTrue(summary.contains(AddBatchPolicy.INVALIDATION_NOTE))
        assertFalse(summary.contains("一把梭"))
    }
}
