package com.example.stockmonitor

import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.analysis.orderRelaxedHits
import com.example.stockmonitor.analysis.orderStrictHits
import com.example.stockmonitor.analysis.orderTrendHits
import com.example.stockmonitor.analysis.orderFiveOneThreeHits
import com.example.stockmonitor.analysis.selectRelaxedExcludingStrict
import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.TrendScreenHit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选股榜单排序：先看条件评分，评分相同时再按各策略的次级指标排序。
 */
class IndicatorScreenerOrderTest {

    private fun hit(code: String, rsi: Double, score: Int) = ScreenHit(
        code = code,
        name = code,
        price = 10.0,
        rsi = rsi,
        k = 20.0,
        d = 10.0,
        dif = 0.1,
        relaxedScore = score,
        macdDetail = "",
        rsiOk = true,
        kdjOk = true,
    )

    @Test
    fun `严格档先按评分降序 同分按 RSI 升序`() {
        val ordered = orderStrictHits(listOf(hit("a", 25.0, 3), hit("b", 12.0, 2), hit("c", 18.0, 3)))

        assertEquals(listOf("c", "a", "b"), ordered.map { it.code })
    }

    @Test
    fun `宽松档先按满足项数降序 同等修复条件不按低 RSI 优先`() {
        val ordered = orderRelaxedHits(listOf(hit("a", 31.0, 2), hit("b", 44.0, 3), hit("c", 28.0, 2)))

        assertEquals(listOf("b", "a", "c"), ordered.map { it.code })
    }

    @Test
    fun `宽松档算不出 RSI 的项排在同分末尾`() {
        val ordered = orderRelaxedHits(
            listOf(hit("a", Double.NaN, 2), hit("b", 40.0, 2), hit("c", 44.0, 3))
        )

        assertEquals(listOf("c", "b", "a"), ordered.map { it.code })
    }

    @Test
    fun `趋势策略先展示已确认 再展示观察项`() {
        val confirmed = trendHit("confirmed", confirmed = true, volume = 1.4)
        val watch = trendHit("watch", confirmed = false, volume = 2.0)

        val ordered = orderTrendHits(listOf(watch, confirmed))

        assertEquals(listOf("confirmed", "watch"), ordered.map { it.code })
    }

    @Test
    fun `513同阶段优先区间位置更低 不优先近60日涨幅更高`() {
        val lowerPosition = fiveOneThree("low", position = 20.0, gain = 18.0)
        val higherPosition = fiveOneThree("high", position = 80.0, gain = 5.0)
        val missingPosition = fiveOneThree("missing", position = Double.NaN, gain = 1.0)

        val ordered = orderFiveOneThreeHits(listOf(higherPosition, missingPosition, lowerPosition))

        assertEquals(listOf("low", "high", "missing"), ordered.map { it.code })
    }

    @Test
    fun `宽松档先排除严格档再截断二十只 不会漏掉后续候选`() {
        val hits = (1..30).map { index ->
            hit("r%02d".format(index), 30.0 + index, 2).copy(
                macdDetail = "MACD改善",
                relaxedTrendEligible = true,
            )
        }

        val selected = selectRelaxedExcludingStrict(hits, setOf("r01", "r02", "r03"))

        assertEquals(20, selected.size)
        assertEquals((4..23).map { "r%02d".format(it) }, selected.map { it.code })
    }

    @Test
    fun `趋势回踩优先缩量更充分 突破优先量能更强`() {
        val weakContraction = trendHit("weak", confirmed = true, volume = 0.78, pullback = true)
        val strongContraction = trendHit("strong", confirmed = true, volume = 0.45, pullback = true)
        val breakoutLowVolume = trendHit("breakout-low", confirmed = true, volume = 1.3)
        val breakoutHighVolume = trendHit("breakout-high", confirmed = true, volume = 2.1)

        assertEquals(
            listOf("strong", "weak"),
            orderTrendHits(listOf(weakContraction, strongContraction)).map { it.code },
        )
        assertEquals(
            listOf("breakout-high", "breakout-low"),
            orderTrendHits(listOf(breakoutLowVolume, breakoutHighVolume)).map { it.code },
        )
    }

    private fun fiveOneThree(code: String, position: Double, gain: Double) = FiveOneThreeHit(
        code = code,
        name = code,
        price = 10.0,
        stage = FiveOneThreeStage.CONFIRMED,
        progress = "形态已确认",
        detail = "",
        gain60Pct = gain,
        rangePositionPct = position,
    )

    private fun trendHit(
        code: String,
        confirmed: Boolean,
        volume: Double,
        pullback: Boolean = false,
    ) = TrendScreenHit(
        code = code,
        name = code,
        price = 10.0,
        changePct = 1.0,
        volumeRatio = volume,
        entrySetup = EntrySetup(
            type = when {
                pullback -> EntrySetupType.M20_PULLBACK
                confirmed -> EntrySetupType.BREAKOUT
                else -> EntrySetupType.BREAKOUT_WATCH
            },
            confirmed = confirmed,
            canBuy = confirmed,
            priorHigh = 9.5,
            ma20SlopePct = 1.0,
            volumeConfirmed = true,
            marketFilter = MarketFilter.UP,
            stopPrice = 9.0,
            reason = "测试",
        ),
    )
}
