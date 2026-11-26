package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipDistributionPolicyTest {

    @Test
    fun profitRatioSplitsByCurrentPrice() {
        // 全部筹码成交在 10 元：现价 12 全是获利盘，现价 8 全是套牢盘。
        val bars = bars(flat = 10.0, volume = 100.0, days = 10)
        val above = ChipDistributionPolicy.evaluate(bars, price = 12.0, todayDate = "2026-11-30", todayBarCompleted = true)
        val below = ChipDistributionPolicy.evaluate(bars, price = 8.0, todayDate = "2026-11-30", todayBarCompleted = true)

        assertEquals(100.0, above.profitRatioPct!!, 1e-6)
        assertEquals(0.0, below.profitRatioPct!!, 1e-6)
    }

    @Test
    fun pressureIsDensestBucketAbovePrice() {
        // 10 元大量、11 元少量：现价 10.05 时压力位在 11 一侧，10 元筹码全是获利盘。
        val bars = bars(flat = 10.0, volume = 100.0, days = 8) + bars(flat = 11.0, volume = 10.0, days = 2)
        val chip = ChipDistributionPolicy.evaluate(bars, price = 10.05, todayDate = "2026-11-30", todayBarCompleted = true)

        assertNotNull(chip.pressurePrice)
        assertTrue(chip.pressurePrice!! > 10.05)
        assertTrue(chip.pressurePrice!! <= 11.01)
        assertTrue(chip.pressureDistancePct!! > 0)
        // +3%/+5% 带内（10.35/10.55 以内）都没有上方筹码：11 元侧距现价 +9.3%。
        assertEquals(0.0, chip.trappedAbovePct[3]!!, 1e-6)
        assertEquals(0.0, chip.trappedAbovePct[5]!!, 1e-6)
    }

    @Test
    fun trappedBandCoversNearbyUpperChips() {
        // 筹码集中在 10.5 元（现价 10 的 +5% 内），+3% 带外、+5% 带内。
        val bars = bars(flat = 10.5, volume = 100.0, days = 10)
        val chip = ChipDistributionPolicy.evaluate(bars, price = 10.0, todayDate = "2026-11-30", todayBarCompleted = true)

        assertEquals(0.0, chip.trappedAbovePct[3]!!, 1e-6)
        assertEquals(100.0, chip.trappedAbovePct[5]!!, 1e-6)
        assertEquals(100.0, chip.trappedAbovePct[8]!!, 1e-6)
    }

    @Test
    fun recentBarsWeighMoreThroughTurnoverDecay() {
        // 同量的两堆筹码：旧的在 10、新的在 11——换手衰减后新的占比应更高。
        val bars = bars(flat = 10.0, volume = 100.0, days = 5, startDate = "2026-11-01") +
            bars(flat = 11.0, volume = 100.0, days = 5, startDate = "2026-11-06")
        val chip = ChipDistributionPolicy.evaluate(bars, price = 9.0, todayDate = "2026-11-30", todayBarCompleted = true)

        // 现价 9 时获利盘为 0，压力位应落在更重的 11 侧。
        assertNotNull(chip.pressurePrice)
        assertTrue(chip.pressurePrice!! > 10.5)
    }

    @Test
    fun todayBarExcludedFromDistributionBeforeClose() {
        // 当日未收盘：当日 K 线不进分布但进近 N 日成本。昨日筹码在 10 元、今日冲到 12 元：
        // 排除今日 → 获利盘只有 10 元侧（现价 11 全获利）；计入今日 → 12 元侧变成套牢，获利盘被摊薄。
        val bars = bars(flat = 10.0, volume = 100.0, days = 3, startDate = "2026-11-20") +
            bars(flat = 12.0, volume = 50.0, days = 1, startDate = "2026-11-26")
        val openBar = ChipDistributionPolicy.evaluate(bars, price = 11.0, todayDate = "2026-11-26", todayBarCompleted = false)
        val closed = ChipDistributionPolicy.evaluate(bars, price = 11.0, todayDate = "2026-11-26", todayBarCompleted = true)

        assertEquals(100.0, openBar.profitRatioPct!!, 1e-6)
        assertTrue(closed.profitRatioPct!! < 100.0)
        assertTrue(closed.profitRatioPct!! > 50.0)
        // 近 4 日成本含当日：(10*100*3 + 12*50)/350 ≈ 10.29，现价 11 浮盈为正。
        assertEquals(10.2857, openBar.recent5dCost!!, 0.01)
        assertTrue(openBar.recent5dFloatPct!! > 0)
    }

    @Test
    fun insufficientDataReturnsNullMetrics() {
        val empty = ChipDistributionPolicy.evaluate(emptyList(), price = 10.0, todayDate = "2026-11-26", todayBarCompleted = true)
        assertNull(empty.profitRatioPct)
        assertNull(empty.summaryLine)
        assertNull(empty.trappedLine)

        val flatPrices = bars(flat = 10.0, volume = 0.0, days = 5) // 全零成交 → 分布无密度
        val zeroVolume = ChipDistributionPolicy.evaluate(flatPrices, price = 10.0, todayDate = "2026-11-30", todayBarCompleted = true)
        assertNull(zeroVolume.profitRatioPct)
    }

    @Test
    fun summaryAndTrappedLinesFollowMacFormat() {
        val bars = bars(flat = 10.5, volume = 100.0, days = 10, startDate = "2026-11-01") +
            bars(flat = 9.5, volume = 100.0, days = 10, startDate = "2026-11-15")
        val chip = ChipDistributionPolicy.evaluate(bars, price = 10.0, todayDate = "2026-11-30", todayBarCompleted = true)

        val summary = chip.summaryLine!!
        assertTrue(summary.startsWith("筹码：获利盘"))
        assertTrue(summary.contains("压力位"))
        assertTrue(summary.contains("近20日浮盈"))
        val trapped = chip.trappedLine!!
        assertTrue(trapped.startsWith("上方套牢：+3%内"))
        assertTrue(trapped.contains("+5%内"))
        assertTrue(trapped.contains("+8%内"))
        assertTrue(trapped.endsWith("（成交量加权估算）"))
    }

    private fun bars(
        flat: Double,
        volume: Double,
        days: Int,
        startDate: String = "2026-11-20",
    ): List<DayBar> = (0 until days).map { i ->
        val day = java.time.LocalDate.parse(startDate).plusDays(i.toLong()).toString()
        DayBar(date = day, open = flat, high = flat, low = flat, close = flat, volume = volume)
    }
}
