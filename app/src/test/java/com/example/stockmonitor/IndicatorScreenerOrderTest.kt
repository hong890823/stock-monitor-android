package com.example.stockmonitor

import com.example.stockmonitor.analysis.orderRelaxedHits
import com.example.stockmonitor.analysis.orderStrictHits
import com.example.stockmonitor.analysis.orderTrendHits
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

    private fun trendHit(code: String, confirmed: Boolean, volume: Double) = TrendScreenHit(
        code = code,
        name = code,
        price = 10.0,
        changePct = 1.0,
        volumeRatio = volume,
        entrySetup = EntrySetup(
            type = if (confirmed) EntrySetupType.BREAKOUT else EntrySetupType.BREAKOUT_WATCH,
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
