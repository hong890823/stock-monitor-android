package com.example.stockmonitor

import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.PriceLocation
import com.example.stockmonitor.analysis.VolumePrice
import com.example.stockmonitor.analysis.VolumePriceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 量价判定。界面标注（自选股、选股页、个股报告）共用这份逻辑，所以这里锁住边界值。
 *
 * 位置口径不参与「放量 / 缩量」的计算，只决定同样的组合往哪个方向解释：
 * 放量上涨在位置高时转为「高位放量上涨」；放量滞涨按位置与反弹幅度分三档
 * ——高位＝借量出货，低位贴底＝底部换手，低位但已从低点反弹起来＝冲高遇阻。
 */
class VolumePriceTest {

    @Test
    fun helpTextUsesConditionalLanguageInsteadOfCausalClaims() {
        val text = VolumePrice.helpText

        assertTrue(text.contains("可能"))
        assertTrue(text.contains("需结合位置"))
        assertTrue(text.contains("不单独构成买入或卖出依据"))
        assertFalse(text.contains("资金进场确认"))
        assertFalse(text.contains("基本可以先划掉"))
        assertFalse(text.contains("最值得盯"))
        assertFalse(text.contains("高度控盘"))
    }

    /** 位置上下文：低位 + 从区间低点反弹幅度（0.25 = 已反弹 25%）。 */
    private fun low(rebound: Double) = PriceContext(PriceLocation.LOW, rebound)

    private fun mid() = PriceContext(PriceLocation.MID, 0.10)

    private fun high() = PriceContext(PriceLocation.HIGH, 0.45)

    @Test
    fun expandWithRisingPriceIsInflow() {
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(2.0, 3.2))
        // 阈值边界：量比刚好 1.3 算放量。
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(1.3, 1.0))
        // 位置不高（低 / 中位 / 未知）都按资金进场处理。
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(2.0, 3.2, low(0.25)))
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(2.0, 3.2, mid()))
    }

    @Test
    fun highLocationTurnsExpansionIntoWarning() {
        assertEquals(VolumePriceSignal.EXPAND_UP_HIGH, VolumePrice.of(2.0, 3.2, high()))
        // 只有放量上涨受位置影响，缩量上涨另有三档（见下）。
        assertEquals(VolumePriceSignal.SHRINK_UP, VolumePrice.of(0.5, 3.2, mid()))
        assertEquals(VolumePriceSignal.SHRINK_UP_HIGH, VolumePrice.of(0.5, 3.2, high()))
        assertEquals(VolumePriceSignal.SHRINK_UP_LOW, VolumePrice.of(0.5, 3.2, low(0.25)))
    }

    @Test
    fun flatBandMeansStalling() {
        // 平盘带 ±0.5%：放量 + 价格不动就是滞涨，只有确认在高位才算警示。
        assertEquals(VolumePriceSignal.EXPAND_FLAT_HIGH, VolumePrice.of(2.0, 0.0, high()))
        assertEquals(VolumePriceSignal.EXPAND_FLAT_HIGH, VolumePrice.of(2.0, 0.5, high()))
        assertEquals(VolumePriceSignal.EXPAND_FLAT_HIGH, VolumePrice.of(2.0, -0.5, high()))
        // 中位与位置未知不替用户判断方向：退回不带位置的「放量滞涨」。
        assertEquals(VolumePriceSignal.EXPAND_FLAT, VolumePrice.of(2.0, 0.0, mid()))
        assertEquals(VolumePriceSignal.EXPAND_FLAT, VolumePrice.of(2.0, 0.0))
        // 低位贴底磨：底部换手，不做警示，但标签要自带位置。
        assertEquals(VolumePriceSignal.EXPAND_FLAT_LOW, VolumePrice.of(2.0, 0.0, low(0.05)))
        assertEquals(VolumePriceSignal.EXPAND_FLAT_LOW, VolumePrice.of(2.0, -0.5, low(0.0)))
        assertEquals("低位放量滞涨", VolumePrice.tag(VolumePrice.of(2.0, 0.0, low(0.05))))
        // 低位但已经从低点弹起来：改按冲高遇阻讲。10% 是分界。
        assertEquals(VolumePriceSignal.EXPAND_FLAT_REBOUND, VolumePrice.of(2.0, 0.0, low(0.10)))
        assertEquals(VolumePriceSignal.EXPAND_FLAT_LOW, VolumePrice.of(2.0, 0.0, low(0.0999)))
        assertEquals("反弹遇阻滞涨", VolumePrice.tag(VolumePrice.of(2.0, 0.0, low(0.25))))
        // 华天科技 sz002185 的实际形态：区间下 1/3（累计 -16%），已从低点反弹约 25%，量比 1.52、涨 +0.49%。
        assertEquals(VolumePriceSignal.EXPAND_FLAT_REBOUND, VolumePrice.of(1.52, 0.49, low(0.2497)))
        // 出了平盘带就按方向算。
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(2.0, 0.51))
        assertEquals(VolumePriceSignal.EXPAND_DOWN, VolumePrice.of(2.0, -0.51))
        // 量能正常或缩量横盘都不标注。
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(1.0, 0.0))
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(0.5, 0.0))
    }

    @Test
    fun expandWithFallingPriceIsDistribution() {
        assertEquals(VolumePriceSignal.EXPAND_DOWN, VolumePrice.of(2.0, -2.0))
        assertEquals(VolumePriceSignal.EXPAND_DOWN, VolumePrice.of(1.3, -2.0))
    }

    @Test
    fun shrinkCombinations() {
        assertEquals(VolumePriceSignal.SHRINK_UP, VolumePrice.of(0.5, 1.0))
        assertEquals(VolumePriceSignal.SHRINK_DOWN, VolumePrice.of(0.5, -1.0))
        // 阈值边界：量比刚好 0.8 算缩量。
        assertEquals(VolumePriceSignal.SHRINK_DOWN, VolumePrice.of(0.8, -1.0))
    }

    @Test
    fun crashIsSeparatedFromMildDrop() {
        // 跌 3% 是分界：以下算温和下跌，及以上算急跌。
        assertEquals(VolumePriceSignal.SHRINK_DOWN, VolumePrice.of(0.5, -2.99))
        assertEquals(VolumePriceSignal.SHRINK_CRASH, VolumePrice.of(0.5, -3.0))
        assertEquals(VolumePriceSignal.SHRINK_CRASH, VolumePrice.of(0.5, -9.5))
        assertEquals(VolumePriceSignal.EXPAND_DOWN, VolumePrice.of(2.0, -2.99))
        assertEquals(VolumePriceSignal.EXPAND_CRASH, VolumePrice.of(2.0, -3.0))
        // 急跌不受位置影响：跌停封死的含义与位置无关。
        assertEquals(VolumePriceSignal.SHRINK_CRASH, VolumePrice.of(0.5, -9.5, low(0.25)))
    }

    @Test
    fun risingSideHasNoCrashTier() {
        // 上涨侧不区分急涨，仍是放量上涨 / 缩量上涨。
        assertEquals(VolumePriceSignal.EXPAND_UP, VolumePrice.of(2.0, 9.9))
        assertEquals(VolumePriceSignal.SHRINK_UP, VolumePrice.of(0.5, 9.9))
    }

    @Test
    fun normalRangeOrMissingDataIsNeutral() {
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(1.0, 3.0))
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(1.29, 3.0))
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(0.81, -3.0))
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(null, 3.0))
        assertEquals(VolumePriceSignal.NEUTRAL, VolumePrice.of(2.0, Double.NaN))
    }
}
