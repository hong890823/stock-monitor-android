package com.example.stockmonitor

import com.example.stockmonitor.analysis.TrendEntryStrategy
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趋势入场的「有效跌破 M20」口径：收在 M20 下方要用和模块一致的 0.5% 容差。
 * 贴线震荡两天不该把自己压成「趋势转弱」，否则回踩 M20 的信号会被同一条规则吃掉。
 */
class TrendEntryStrategyTest {

    @Test
    fun twoClosesSlightlyBelowMa20DoNotCountAsTrendWeak() {
        // 末两日收在 M20 下方约 0.3%（容差内）：不算有效跌破。
        val bars = flatBars(dropLastTwoTo = 9.97)
        assertFalse(TrendEntryStrategy.evaluateDaily(bars).trendWeak)
    }

    @Test
    fun twoClosesClearlyBelowMa20CountAsTrendWeak() {
        // 末两日收在 M20 下方约 1.5%（超容差）：连续两日有效跌破，趋势转弱。
        val bars = flatBars(dropLastTwoTo = 9.85)
        assertTrue(TrendEntryStrategy.evaluateDaily(bars).trendWeak)
    }

    private fun flatBars(dropLastTwoTo: Double): List<DayBar> = (0 until 30).map { index ->
        val close = if (index >= 28) dropLastTwoTo else 10.0
        DayBar(
            date = "2026-09-${String.format("%02d", index + 1)}",
            open = close * 1.005,
            high = close * 1.01,
            low = close * 0.99,
            close = close,
            volume = 1_000.0,
        )
    }
}
