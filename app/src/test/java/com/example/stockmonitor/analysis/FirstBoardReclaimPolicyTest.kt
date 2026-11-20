package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstBoardReclaimPolicyTest {
    @Test
    fun tradingDaysSkipWeekend() {
        assertEquals(1, FirstBoardReclaimPolicy.tradingDaysAfter("2026-11-13", "2026-11-16"))
        assertEquals(0, FirstBoardReclaimPolicy.tradingDaysAfter("2026-11-16", "2026-11-16"))
        assertEquals(5, FirstBoardReclaimPolicy.tradingDaysAfter("2026-11-13", "2026-11-20"))
    }

    @Test
    fun tradingDatesSkipNationalDayHolidayInsteadOfCountingWeekdays() {
        // 9-30 首板，10-1~10-8 休市，10-9 复市：旧口径把假期工作日算成 9 天，窗口直接过期。
        val tradingDates = listOf(
            "2026-09-28", "2026-09-29", "2026-09-30",
            "2026-10-09", "2026-10-12", "2026-10-13",
        )
        assertEquals(1, FirstBoardReclaimPolicy.tradingDaysAfter("2026-09-30", "2026-10-09", tradingDates))
        assertTrue(FirstBoardReclaimPolicy.windowEligible("2026-09-30", 0, "2026-10-09", tradingDates))
        assertEquals(2, FirstBoardReclaimPolicy.tradingDaysAfter("2026-09-30", "2026-10-12", tradingDates))
        assertEquals(3, FirstBoardReclaimPolicy.tradingDaysAfter("2026-09-30", "2026-10-13", tradingDates))
        assertFalse(
            FirstBoardReclaimPolicy.windowEligible(
                "2026-09-30", 0, "2026-10-14", tradingDates + "2026-10-14",
            ),
        )
    }

    @Test
    fun benchmarkMissingTodayStillCountsTodayAsOneTradingDay() {
        // 盘中新浪日线可能只到昨天；调用方只在真实交易时段调用，今天必须补算。
        val throughYesterday = listOf("2026-09-28", "2026-09-29", "2026-09-30")
        assertEquals(
            1,
            FirstBoardReclaimPolicy.tradingDaysAfter("2026-09-30", "2026-10-09", throughYesterday),
        )
    }

    @Test
    fun windowUsesFirstBoardDateInsteadOfReviewAge() {
        // 华胜天成 11-13 首板：11-18 是第 3 个交易日，旧口径 observedDays=4 会漏掉。
        assertTrue(FirstBoardReclaimPolicy.windowEligible("2026-11-13", 4, "2026-11-18"))
        assertFalse(FirstBoardReclaimPolicy.windowEligible("2026-11-13", 4, "2026-11-19"))
        // 海立股份 11-17 首板：11-18、11-19 都在窗口内。
        assertTrue(FirstBoardReclaimPolicy.windowEligible("2026-11-17", 2, "2026-11-18"))
        assertTrue(FirstBoardReclaimPolicy.windowEligible("2026-11-17", 3, "2026-11-19"))
        // 首板当日不发承接观察。
        assertFalse(FirstBoardReclaimPolicy.windowEligible("2026-11-17", 1, "2026-11-17"))
    }

    @Test
    fun fallsBackToObservedDaysWhenBoardDateMissing() {
        assertTrue(FirstBoardReclaimPolicy.windowEligible(null, 1, "2026-11-19"))
        assertTrue(FirstBoardReclaimPolicy.windowEligible(null, 3, "2026-11-19"))
        assertFalse(FirstBoardReclaimPolicy.windowEligible(null, 0, "2026-11-19"))
        assertFalse(FirstBoardReclaimPolicy.windowEligible(null, 4, "2026-11-19"))
    }

    @Test
    fun priceBoundBlocksExtendedObservationButNotMissingData() {
        assertTrue(FirstBoardReclaimPolicy.withinPriceBound(5.72))
        assertTrue(FirstBoardReclaimPolicy.withinPriceBound(10.0))
        assertFalse(FirstBoardReclaimPolicy.withinPriceBound(10.63))
        assertFalse(FirstBoardReclaimPolicy.withinPriceBound(11.0))
        assertTrue(FirstBoardReclaimPolicy.withinPriceBound(null))
        assertTrue(FirstBoardReclaimPolicy.withinPriceBound(Double.NaN))
    }

    @Test
    fun describesWhichClockWasUsed() {
        assertEquals("tradingDays=2", FirstBoardReclaimPolicy.describeWindow("2026-11-17", 3, "2026-11-19"))
        assertEquals("observedDays=3", FirstBoardReclaimPolicy.describeWindow(null, 3, "2026-11-19"))
    }
}
