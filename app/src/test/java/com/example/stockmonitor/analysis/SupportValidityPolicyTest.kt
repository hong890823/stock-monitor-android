package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportValidityPolicyTest {

    @Test
    fun touchedTwiceWithoutBreakIsValid() {
        val bars = bars(lows = listOf(9.95, 10.05, 10.20, 10.30, 10.40, 10.50))
        val result = SupportValidityPolicy.evaluate(support = 10.0, bars = bars)

        assertTrue(result.valid)
        assertEquals(SupportValidityPolicy.VIA_TOUCH_RULE, result.via)
        assertEquals(2, result.touches)
        assertFalse(result.closedBelow)
    }

    @Test
    fun closedBelowInvalidatesEvenWithTouches() {
        // 曾收破（close < 支撑×0.995）：即便触及多次也算无效。
        val bars = bars(
            lows = listOf(9.95, 10.05, 9.95, 10.20),
            closes = listOf(10.05, 10.05, 9.80, 10.20),
        )
        val result = SupportValidityPolicy.evaluate(support = 10.0, bars = bars)

        assertFalse(result.valid)
        assertTrue(result.closedBelow)
        assertEquals("曾收破支撑", result.reason)
    }

    @Test
    fun boundaryTouchCountsAndBoundaryCloseDoesNotBreak() {
        val touchLine = 10.0 * (1 + SupportValidityPolicy.TOUCH_TOLERANCE_PCT / 100) // 10.05
        val breakLine = 10.0 * (1 - SupportValidityPolicy.BREAK_TOLERANCE_PCT / 100) // 9.95

        // low 恰好在触及线上 → 算触及（两根即有效）。
        val onLine = bars(lows = listOf(touchLine, touchLine), closes = listOf(10.20, 10.20))
        val touched = SupportValidityPolicy.evaluate(support = 10.0, bars = onLine)
        assertTrue(touched.valid)
        assertEquals(2, touched.touches)

        // close 恰好在破位线上 → 不算收破。
        val onBreak = bars(lows = listOf(9.90, 10.05), closes = listOf(breakLine, 10.20))
        val notBroken = SupportValidityPolicy.evaluate(support = 10.0, bars = onBreak)
        assertTrue(notBroken.valid)
        assertFalse(notBroken.closedBelow)
    }

    @Test
    fun strongAnchorIsEnoughWithoutTouches() {
        val bars = bars(lows = listOf(10.50, 10.60, 10.70))
        val result = SupportValidityPolicy.evaluate(support = 10.0, bars = bars, anchorStrong = true)

        assertTrue(result.valid)
        assertEquals(SupportValidityPolicy.VIA_ANCHOR_STRONG, result.via)
    }

    @Test
    fun insufficientTouchesAreInvalid() {
        val bars = bars(lows = listOf(10.50, 10.60, 10.70))
        val result = SupportValidityPolicy.evaluate(support = 10.0, bars = bars)

        assertFalse(result.valid)
        assertEquals("触及不足2次", result.reason)
    }

    @Test
    fun missingSupportIsConservativelyInvalid() {
        val result = SupportValidityPolicy.evaluate(support = 0.0, bars = emptyList())

        assertFalse(result.valid)
        assertEquals("数据不足：缺少有效支撑价", result.reason)
    }

    private fun bars(lows: List<Double>, closes: List<Double> = lows): List<DayBar> =
        lows.mapIndexed { index, low ->
            val close = closes[index]
            DayBar(
                date = "2026-11-${(index + 1).toString().padStart(2, '0')}",
                open = close,
                high = maxOf(low, close) + 0.05,
                low = low,
                close = close,
                volume = 1000.0,
            )
        }
}
