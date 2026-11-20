package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningClockTest {

    @Test
    fun weekendOrHolidaySnapshotIsNotAppendedAsToday() {
        // 周六 10:00：指数行情日期仍是周五，不能补成周六 K 线。
        assertFalse(
            ScreeningClock.shouldAppendLiveBar(
                indexQuoteDate = "2026-11-19 15:00:00",
                today = "2026-11-21",
                elapsedTradingMinutes = 30,
            ),
        )
    }

    @Test
    fun preMarketOrNightDoesNotAppendZeroVolumeBar() {
        // 盘前 08:00：日期可能是今天，但还没有交易分钟。
        assertFalse(ScreeningClock.shouldAppendLiveBar("2026-11-20 08:00:00", "2026-11-20", 0))
    }

    @Test
    fun intradayTradingDayAppendsLiveBar() {
        assertTrue(ScreeningClock.shouldAppendLiveBar("2026-11-20 10:15:00", "2026-11-20", 45))
    }

    @Test
    fun afterCloseUsesCompletedDayAndFullDayRatio() {
        // 15:30 收盘后：可以补当日完成 K 线，但量比不按墙钟折算。
        assertTrue(ScreeningClock.shouldAppendLiveBar("2026-11-20 15:00:00", "2026-11-20", 240))
        assertEquals(240, ScreeningClock.ratioElapsedMinutes(240, appendLiveBar = true))
    }

    @Test
    fun unsupportedQuoteDateFailsClosed() {
        assertFalse(ScreeningClock.shouldAppendLiveBar(null, "2026-11-20", 45))
        assertEquals(240, ScreeningClock.ratioElapsedMinutes(45, appendLiveBar = false))
    }

    @Test
    fun intradayRatioUsesElapsedTradingMinutes() {
        assertEquals(45, ScreeningClock.ratioElapsedMinutes(45, appendLiveBar = true))
    }
}