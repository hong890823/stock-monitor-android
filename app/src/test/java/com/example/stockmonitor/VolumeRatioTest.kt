package com.example.stockmonitor

import com.example.stockmonitor.analysis.VolumeRatio
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 量比口径：自选股、选股、个股报告、持仓报告共用这一个数字，所以这里锁住折算规则与边界。
 */
class VolumeRatioTest {

    private fun bars(volumes: List<Double>, lastDate: String = "2026-09-22"): List<DayBar> =
        volumes.mapIndexed { index, volume ->
            val date = if (index == volumes.size - 1) lastDate else "2026-09-01"
            DayBar(date = date, open = 10.0, high = 10.0, low = 10.0, close = 10.0, volume = volume)
        }

    @Test
    fun intradayVolumeIsScaledToFullDay() {
        // 10:00 已走 30 分钟：今日 1000 手先按 240/30 折算到全天的 8000，再比 20 日均量 1000。
        assertEquals(8.0, VolumeRatio.of(1_000.0, 1_000.0, 30)!!, 1e-9)
        assertEquals(4.0, VolumeRatio.of(1_000.0, 1_000.0, 60)!!, 1e-9)
        // 11:30 已走 120 分钟。
        assertEquals(2.0, VolumeRatio.of(1_000.0, 1_000.0, 120)!!, 1e-9)
    }

    @Test
    fun earlyMinutesUseTheFiveMinuteFloor() {
        // 折算分钟数下限 5 分钟：09:31（elapsed = 1）按 5 分钟算，避免开盘头几分钟放大成天文数字。
        assertEquals(48.0, VolumeRatio.of(1_000.0, 1_000.0, 1)!!, 1e-9)
        assertEquals(48.0, VolumeRatio.of(1_000.0, 1_000.0, 5)!!, 1e-9)
        assertEquals(24.0, VolumeRatio.of(1_000.0, 1_000.0, 10)!!, 1e-9)
    }

    @Test
    fun afterCloseIsNotScaled() {
        // 收盘后（240）与过了收盘时间（240 以上）都用当日全天量直接比。
        assertEquals(1.0, VolumeRatio.of(1_000.0, 1_000.0, 240)!!, 1e-9)
        assertEquals(1.0, VolumeRatio.of(1_000.0, 1_000.0, 300)!!, 1e-9)
        assertFalse(VolumeRatio.isIntraday(240))
        assertTrue(VolumeRatio.isIntraday(239))
    }

    @Test
    fun beforeOpenNeverReportsARatio() {
        // 隔夜到集合竞价结束：行情里的成交量要么是上一交易日的全天量、要么只是集合竞价的一小段，
        // 都不是「今日累计」，硬算会得到 0.0x 这样的极小值，把所有股票标成缩量。
        assertNull(VolumeRatio.of(200_000.0, 10_000_000.0, 0))
        assertNull(VolumeRatio.of(200_000.0, 10_000_000.0, -1))
        assertFalse(VolumeRatio.isIntraday(0))
        // 09:31 起恢复计算。
        assertTrue(VolumeRatio.of(200_000.0, 10_000_000.0, 1)!! > 0)
    }

    @Test
    fun missingDataGivesNull() {
        assertNull(VolumeRatio.of(null, 1_000.0, 60))
        assertNull(VolumeRatio.of(0.0, 1_000.0, 60))
        assertNull(VolumeRatio.of(-1.0, 1_000.0, 60))
        assertNull(VolumeRatio.of(1_000.0, 0.0, 60))
    }

    @Test
    fun tooFewBarsGivesNoAverage() {
        // 新股：不足 5 个交易日不给均量，也就不做量比判断。
        assertNull(VolumeRatio.averageVolume(bars(listOf(1.0, 2.0, 3.0, 4.0))))
        assertEquals(3.0, VolumeRatio.averageVolume(bars(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))!!, 1e-9)
    }

    @Test
    fun todayIsExcludedFromTheAverage() {
        // 收盘后新浪日线补上当日：分母必须剔掉，否则今天的量同时出现在分子和分母里。
        val withToday = bars(List(19) { 1_000.0 } + listOf(99_000.0), lastDate = "2026-09-23")
        assertEquals(1_000.0, VolumeRatio.averageVolumeExcludingToday(withToday, "2026-09-23")!!, 1e-9)
        // 盘中日线本来就不含当日：不剔除，均量不变。
        val withoutToday = bars(List(19) { 1_000.0 } + listOf(99_000.0), lastDate = "2026-09-22")
        assertEquals(
            VolumeRatio.averageVolume(withoutToday)!!,
            VolumeRatio.averageVolumeExcludingToday(withoutToday, "2026-09-23")!!,
            1e-9,
        )
    }
}
