package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场状态口径：大盘与板块共用同一套判定。
 *
 * 锁住的是**档位边界**——「塌陷」和「转弱」给出的建议完全相反
 * （塌陷期不补仓、不接回、反弹减），边界松一点就会在熊市里教人接飞刀。
 */
class MarketRegimeTest {

    private fun bars(closes: List<Double>): List<DayBar> = closes.mapIndexed { index, close ->
        DayBar(
            date = "2026-${index / 28 + 1}-${index % 28 + 1}",
            open = close,
            high = close,
            low = close,
            close = close,
            volume = 1_000.0,
        )
    }

    /** 等差收盘序列。 */
    private fun series(from: Double, step: Double, count: Int): List<Double> =
        (0 until count).map { from + step * it }

    @Test
    fun insufficientBarsIsNotJudged() {
        val state = MarketRegime.evaluate(bars(series(10.0, 0.1, 20)))

        // 数据不够就写「暂缺」，不猜方向。
        assertEquals(MarketRegime.NEUTRAL, state.regime)
        assertTrue(state.reason.contains("暂缺"))
        assertNull(state.ma20Pct)
    }

    @Test
    fun bullWhenAboveRisingMa20() {
        val state = MarketRegime.evaluate(bars(series(10.0, 0.1, 80)))

        assertEquals(MarketRegime.BULL, state.regime)
        assertTrue(state.reason.contains("站上 MA20"))
        assertFalse(state.belowMa20)
    }

    @Test
    fun weakWhenBelowFallingMa20WithoutCollapseDrop() {
        // 缓跌：跌破 MA20 且 MA20 走低，但近20日/近5日跌幅都不到塌陷门槛 → 转弱，不是塌陷。
        val state = MarketRegime.evaluate(bars(series(20.0, -0.05, 80)))

        assertEquals(MarketRegime.WEAK, state.regime)
        assertFalse(state.isCollapse)
        assertTrue(state.belowMa20)
        assertTrue(state.reason.contains("跌破 MA20"))
    }

    @Test
    fun collapseWhenBelowBothMasWithBig20DayDrop() {
        // 急跌：MA20 / MA60 双破 + 近20日跌幅超门槛 → 塌陷。
        val state = MarketRegime.evaluate(bars(series(20.0, -0.15, 80)))

        assertEquals(MarketRegime.COLLAPSE, state.regime)
        assertTrue(state.isCollapse)
        assertTrue(state.reason.contains("系统性下跌"))
    }

    @Test
    fun fastCrashCountsAsCollapseEvenWhen20DayDropIsSmall() {
        // 先涨一段再 5 日急跌：近20日还没到 -8%，但近5日已经 -20% → 同样算塌陷（急跌也是塌陷）。
        val closes = series(20.0, 0.0, 70) + listOf(21.0, 22.0, 23.0, 24.0, 25.0) +
            listOf(24.0, 23.0, 22.0, 21.0, 20.0)
        val state = MarketRegime.evaluate(bars(closes))

        assertEquals(MarketRegime.COLLAPSE, state.regime)
    }

    @Test
    fun collapseRequiresBothMasBroken() {
        // 早年长期低位、近期高位回落：跌破了 MA20、跌幅也够狠，但现价仍在 MA60 上方
        // ——那是回撤不是塌陷，不该给「不补仓、反弹减」那一套。
        val lowPlateau = series(5.0, 0.0, 40)
        val highPlateau = series(30.0, 0.0, 20)
        val pullback = series(30.0, -0.4, 20) // 30 → 22.4
        val state = MarketRegime.evaluate(bars(lowPlateau + highPlateau + pullback))

        assertNotNull(state.ma60Pct)
        assertTrue(state.ma60Pct!! > 0) // 现价仍在 MA60 上方
        assertTrue(state.change20d!! <= MarketRegime.COLLAPSE_20D_PCT) // 跌幅门槛是满足的
        assertEquals(MarketRegime.WEAK, state.regime)
    }

    @Test
    fun marketFilterMappingKeepsHeadwindForBothWeakAndCollapse() {
        // 塌陷比转弱更不该开新仓，两档都要拦；枚举名不变，旧的选股缓存才读得回来。
        assertEquals(MarketFilter.HEADWIND, MarketRegime.WEAK.toMarketFilter())
        assertEquals(MarketFilter.HEADWIND, MarketRegime.COLLAPSE.toMarketFilter())
        assertEquals(MarketFilter.UP, MarketRegime.BULL.toMarketFilter())
        assertEquals(MarketFilter.NEUTRAL, MarketRegime.NEUTRAL.toMarketFilter())
    }

    @Test
    fun explicitTodayChangeIsPreferredOverLastBar() {
        val state = MarketRegime.evaluate(bars(series(10.0, 0.1, 80)), todayChangePct = -3.5)

        // 日线带不带当日随数据源变动（腾讯盘中起就带、新浪收盘后才补），
        // 调用方给了当日涨跌就必须用调用方的，不能拿最后一根去猜。
        assertEquals(-3.5, state.changePct!!, 1e-9)
    }
}
