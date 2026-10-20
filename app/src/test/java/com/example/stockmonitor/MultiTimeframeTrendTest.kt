package com.example.stockmonitor

import com.example.stockmonitor.analysis.MultiTimeframeTrend
import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.core.DayBar
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MultiTimeframeTrendTest {
    private fun history(): List<DayBar> = generateSequence(LocalDate.parse("2023-01-02")) { it.plusDays(1) }
        .takeWhile { it < LocalDate.parse("2026-10-15") }
        .filter { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        .mapIndexed { index, date ->
            val close = 10.0 + index * 0.01
            DayBar(date.toString(), close - 0.02, close + 0.1, close - 0.1, close, 1000.0)
        }.toList()

    @Test
    fun risingHistorySupportsWeekMonthAndAnnualAverage() {
        val result = MultiTimeframeTrend.evaluate(history(), "2026-10-15")
        assertNull(result.issue)
        assertEquals(SwingTrend.UP, result.weekTrend)
        assertEquals(SwingTrend.UP, result.monthTrend)
        assertEquals(history().takeLast(250).map { it.close }.average(), result.ma250!!, 1e-8)
        assertTrue(result.weeks.all { it.date < "2026-10-12" })
        assertTrue(result.months.all { it.date < "2026-10-01" })
        assertEquals(2, result.years.size)
    }

    @Test
    fun unfinishedWeekMonthAndYearCannotChangeClosedTrend() {
        val original = history()
        val changed = original.map { if (it.date >= "2026-10-12") it.copy(high = 1000.0, close = 999.0) else it }
        val first = MultiTimeframeTrend.evaluate(original, "2026-10-15")
        val second = MultiTimeframeTrend.evaluate(changed, "2026-10-15")
        assertEquals(first.weeks, second.weeks)
        assertEquals(first.months, second.months)
        assertEquals(first.years, second.years)
    }

    @Test
    fun weeklyAggregationUsesCalendarWeekAcrossYearBoundary() {
        val bars = listOf("2024-12-23", "2024-12-30", "2024-12-31", "2025-01-02", "2025-01-03")
            .mapIndexed { index, date -> DayBar(date, 10.0 + index, 15.0 + index, 9.0, 11.0 + index, 100.0) }
        val week = MultiTimeframeTrend.evaluate(bars, "2025-01-06").weeks.single()
        assertEquals("2025-01-03", week.date)
        assertEquals(11.0, week.open, 0.0)
        assertEquals(15.0, week.close, 0.0)
        assertEquals(19.0, week.high, 0.0)
        assertEquals(9.0, week.low, 0.0)
        assertEquals(400.0, week.volume, 0.0)
    }

    @Test
    fun shortInvalidDuplicateOrStaleHistoryIsNotQualified() {
        val bars = history()
        assertNotNull(MultiTimeframeTrend.evaluate(bars.takeLast(240), "2026-10-15").issue)
        assertNotNull(MultiTimeframeTrend.evaluate(bars + bars.last(), "2026-10-15").issue)
        assertNotNull(MultiTimeframeTrend.evaluate(bars.dropLast(1) + bars.last().copy(close = Double.NaN), "2026-10-15").issue)
        assertNotNull(MultiTimeframeTrend.evaluate(bars.dropLast(30), "2026-10-15").issue)
    }

    @Test
    fun missingRecentTradingSessionsCannotBeSplicedIntoTodaysSignal() {
        val bars = history()
        val tradingDates = bars.takeLast(30).map { it.date }
        assertNotNull(MultiTimeframeTrend.evaluate(bars.dropLast(3), "2026-10-15", tradingDates).issue)
        assertNotNull(MultiTimeframeTrend.evaluate(bars.filter { it.date != "2026-10-12" }, "2026-10-15", tradingDates).issue)
        assertNull(MultiTimeframeTrend.evaluate(bars, "2026-10-15", tradingDates).issue)
        val afterHoliday = bars.filter { it.date <= "2026-09-30" }
        assertNull(MultiTimeframeTrend.evaluate(afterHoliday, "2026-10-09", afterHoliday.takeLast(30).map { it.date }).issue)
    }
}
