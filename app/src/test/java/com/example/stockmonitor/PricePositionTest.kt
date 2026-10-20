package com.example.stockmonitor

import com.example.stockmonitor.analysis.PriceLocation
import com.example.stockmonitor.analysis.PricePosition
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 价格位置判定（近 60 日区间位置 + 累计涨幅）。
 *
 * 只按区间位置判高低位会把慢牛股天天算成「高位」，所以高位还要求累计涨幅达标、
 * 低位还要求累计涨幅不为正，这些边界都在这里锁住。
 */
class PricePositionTest {

    private fun barsOf(closes: List<Double>): List<DayBar> = closes.mapIndexed { index, close ->
        DayBar(
            date = "2026-09-%02d".format(index % 28 + 1),
            open = close,
            high = close * 1.01,
            low = close * 0.99,
            close = close,
            volume = 1_000_000.0,
        )
    }

    private fun linear(from: Double, to: Double, count: Int): List<Double> =
        (0 until count).map { from + (to - from) * it / (count - 1) }

    @Test
    fun bigRunUpAtRangeTopIsHigh() {
        val bars = barsOf(linear(10.0, 16.0, 60))
        assertEquals(PriceLocation.HIGH, PricePosition.of(bars)!!.locationOf(16.0))
    }

    @Test
    fun slowGrindAtRangeTopIsNotHigh() {
        // 两个月涨 15%，虽然价格就在区间顶部，但不该按「高位」处理。
        val bars = barsOf(linear(10.0, 11.5, 60))
        assertEquals(PriceLocation.MID, PricePosition.of(bars)!!.locationOf(11.5))
    }

    @Test
    fun declineAtRangeBottomIsLow() {
        val bars = barsOf(linear(20.0, 12.0, 60))
        assertEquals(PriceLocation.LOW, PricePosition.of(bars)!!.locationOf(12.0))
    }

    @Test
    fun reboundFromLowIsNotLow() {
        // 从 20 跌到 12 再反弹到 18：区间位置在顶部但累计涨幅仍为负，按中位处理。
        val bars = barsOf(linear(20.0, 12.0, 30) + linear(12.0, 18.0, 30))
        assertEquals(PriceLocation.MID, PricePosition.of(bars)!!.locationOf(18.0))
    }

    @Test
    fun reboundFromLowIsCarriedInPriceContext() {
        // 区间低点是 12 的 0.99 倍（barsOf 里 low = close × 0.99）。
        val bars = barsOf(linear(20.0, 12.0, 30) + linear(12.0, 18.0, 30))
        val window = PricePosition.of(bars)!!
        assertEquals(18.0 / (12.0 * 0.99) - 1, window.reboundRatio(18.0), 1e-9)
        val context = window.contextOf(18.0)!!
        assertEquals(PriceLocation.MID, context.location)
        assertEquals(window.reboundRatio(18.0), context.reboundFromLow, 1e-9)
        // 位置判不出来（现价异常）时不给上下文。
        assertNull(window.contextOf(0.0))
    }

    @Test
    fun todayBarIsExcludedFromWindow() {
        val history = barsOf(linear(10.0, 16.0, 60))
        val today = "2026-09-23"
        val base = history.dropLast(1)
        // 当日那根被拉到 30：没剔掉的话区间和累计涨幅都会被污染。
        val withToday = base + base.last().copy(date = today, close = 30.0, high = 30.3, low = 29.7)
        val actual = PricePosition.of(withToday, today)!!
        val expected = PricePosition.of(base)!!
        assertEquals(expected.low, actual.low, 1e-9)
        assertEquals(expected.high, actual.high, 1e-9)
        assertEquals(expected.baseClose, actual.baseClose, 1e-9)
        assertEquals(PriceLocation.HIGH, actual.locationOf(16.0))
        // 60 日累计涨幅按区间起点的收盘价算：10 → 16 是 +60%。
        assertEquals(0.6, PricePosition.of(history)!!.gainRatio(16.0), 1e-9)
    }

    @Test
    fun missingOrDegenerateDataGivesNoLocation() {
        // 日线不足 20 根。
        assertNull(PricePosition.of(barsOf(linear(10.0, 12.0, 19))))
        // 一字横盘：区间无效。
        val flat = (0 until 60).map {
            DayBar(date = "2026-09-01", open = 10.0, high = 10.0, low = 10.0, close = 10.0, volume = 1_000.0)
        }
        assertNull(PricePosition.of(flat))
        // 价格缺失 / 区间无效时不给位置结论。
        assertNull(PricePosition.of(barsOf(linear(10.0, 16.0, 60)))!!.locationOf(0.0))
    }
}
