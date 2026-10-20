package com.example.stockmonitor

import com.example.stockmonitor.analysis.EntrySetup
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.FiveOneThreeHit
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.analysis.MarketFilter
import com.example.stockmonitor.analysis.PriceContext
import com.example.stockmonitor.analysis.PriceLocation
import com.example.stockmonitor.core.CachedScreen
import com.example.stockmonitor.core.FiveOneThreeStats
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.ScreenResult
import com.example.stockmonitor.core.ScreenResultJson
import com.example.stockmonitor.core.TrendScreenHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选股结果落盘格式：两组各自的刷新时间、四档列表和生成时间都要能原样回来；
 * 旧格式（单一 generated_at）退回两组同源；保质期判定锁住「旧命中超期不保留」。
 */
class ScreenResultJsonTest {

    private fun sampleResult() = ScreenResult(
        strict = listOf(
            ScreenHit(
                code = "sh600000", name = "测试股份", price = 12.3,
                rsi = 25.5, k = 20.0, d = 18.0, dif = 0.12,
                relaxedScore = 3, macdDetail = "柱体转正", rsiOk = true, kdjOk = true,
                changePct = -1.2, volumeRatio = 1.5,
                priceContext = PriceContext(PriceLocation.LOW, 0.08),
            ),
        ),
        relaxed = emptyList(),
        fiveOneThree = listOf(
            FiveOneThreeHit(
                code = "sz000001", name = "样本二", price = 8.8,
                stage = FiveOneThreeStage.CONFIRMED, progress = "形态已确认",
                detail = "5连阳 5/5｜倍量阳 1/1｜缩量确认 3/3", gain60Pct = 12.0,
            ),
        ),
        fiveOneThreeStats = FiveOneThreeStats(
            totalStocks = 5400, historyAvailable = 5300, historicalFiveBullish = 40,
            recentFiveBullish = 12, waitingConfirmation = 3, confirmed = 2,
        ),
        trend = listOf(
            TrendScreenHit(
                code = "sz002185", name = "样本三", price = 18.0,
                changePct = 2.5, volumeRatio = 1.8,
                entrySetup = EntrySetup(
                    type = EntrySetupType.BREAKOUT,
                    confirmed = true,
                    canBuy = false,
                    trendWeak = false,
                    priorHigh = 17.5,
                    ma20SlopePct = 0.8,
                    volumeConfirmed = true,
                    marketFilter = MarketFilter.UP,
                    stopPrice = 16.2,
                    reason = "站上前高，放量，M20向上",
                ),
            ),
        ),
    )

    private fun sampleCache() = CachedScreen(
        indicatorAt = "2026-10-09 10:00",
        indicatorAtMs = 1_000_000L,
        patternAt = "2026-10-09 20:00",
        patternAtMs = 2_000_000L,
        result = sampleResult(),
    )

    @Test
    fun roundTripKeepsAllListsAndGroupTimestamps() {
        val decoded = ScreenResultJson.decode(ScreenResultJson.encode(sampleCache()))!!

        assertEquals("2026-10-09 10:00", decoded.indicatorAt)
        assertEquals(1_000_000L, decoded.indicatorAtMs)
        assertEquals("2026-10-09 20:00", decoded.patternAt)
        assertEquals(2_000_000L, decoded.patternAtMs)
        assertEquals(sampleResult().strict, decoded.result.strict)
        assertEquals(sampleResult().fiveOneThree, decoded.result.fiveOneThree)
        assertEquals(sampleResult().fiveOneThreeStats, decoded.result.fiveOneThreeStats)
        val setup = decoded.result.trend.first().entrySetup
        assertEquals(EntrySetupType.BREAKOUT, setup.type)
        assertEquals(16.2, setup.stopPrice!!, 0.001)
        assertEquals(MarketFilter.UP, setup.marketFilter)
    }

    @Test
    fun nanIndicatorValuesSurviveRoundTrip() {
        // RSI 在「平均跌幅为 0」时算不出（NaN），命中项照样进宽松档；落盘不能因此抛异常。
        val withNan = sampleResult().let {
            it.copy(
                strict = it.strict.map { hit ->
                    hit.copy(rsi = Double.NaN, k = Double.NaN, d = Double.NaN, dif = Double.NaN)
                },
            )
        }
        val decoded = ScreenResultJson.decode(
            ScreenResultJson.encode(sampleCache().copy(result = withNan, indicatorAt = null, indicatorAtMs = 0L))
        )!!
        val hit = decoded.result.strict.first()
        assertTrue(hit.rsi.isNaN())
        assertTrue(hit.k.isNaN())
        assertTrue(hit.d.isNaN())
        assertTrue(hit.dif.isNaN())
    }

    @Test
    fun nanChangePctSurvivesRoundTrip() {
        val withNan = sampleResult().let {
            it.copy(trend = it.trend.map { hit -> hit.copy(changePct = Double.NaN) })
        }
        val decoded = ScreenResultJson.decode(
            ScreenResultJson.encode(sampleCache().copy(result = withNan, patternAt = null, patternAtMs = 0L))
        )!!
        assertTrue(decoded.result.trend.first().changePct.isNaN())
        assertNull(decoded.patternAt)
    }

    @Test
    fun legacySingleTimestampFallsBackToBothGroups() {
        // 旧版本只存了 generated_at：两组同源，时间照样解析出来。
        val legacy = """{"generated_at":"2026-10-01 20:00","result":{"strict":[],"relaxed":[],"five_one_three":[],"stats":{},"trend":[]}}"""
        val decoded = ScreenResultJson.decode(legacy)!!
        assertEquals("2026-10-01 20:00", decoded.indicatorAt)
        assertEquals("2026-10-01 20:00", decoded.patternAt)
        assertTrue(decoded.indicatorAtMs > 0)
        assertEquals(decoded.indicatorAtMs, decoded.patternAtMs)
    }

    @Test
    fun retentionGatesOldResults() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertTrue(ScreenResultJson.isRetained(now - ScreenResultJson.STALE_RETENTION_MS, now))
        assertFalse(ScreenResultJson.isRetained(now - ScreenResultJson.STALE_RETENTION_MS - 1, now))
        // 未知时间（0）按不保留处理，宁可空着不拿旧命中断章取义。
        assertFalse(ScreenResultJson.isRetained(0L, now))
    }

    @Test
    fun corruptPayloadDecodesToNull() {
        assertNull(ScreenResultJson.decode("not json"))
        assertNull(ScreenResultJson.decode(""))
        assertNull(ScreenResultJson.decode(null))
    }
}
