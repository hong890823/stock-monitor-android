package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketRiskContext
import com.example.stockmonitor.analysis.MarketTimingContext
import com.example.stockmonitor.analysis.NorthboundFlowSummary
import com.example.stockmonitor.analysis.TurnoverContext
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRiskContextTest {

    @Test
    fun timingContextMarksFridayMonthEndQuarterEndAndReportSeason() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.APRIL, 24, 10, 0, 0)
        }
        val quarterEnd = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.MARCH, 31, 10, 0, 0)
        }

        val context = MarketTimingContext.from(calendar)
        val quarterEndContext = MarketTimingContext.from(quarterEnd)

        assertTrue(context.isFriday)
        assertTrue(context.reportSeason != null)
        assertTrue(context.aiText().contains("周五"))
        assertTrue(context.aiText().contains("节假日数据暂缺"))
        assertTrue(context.aiText().contains("出金/资金可取规则暂缺"))
        assertTrue(quarterEndContext.isMonthEndWindow)
        assertTrue(quarterEndContext.isQuarterEndWindow)
    }

    @Test
    fun combinedNegativeEvidenceProducesCautionButTimingAloneDoesNot() {
        val timing = MarketTimingContext(
            date = "2026-10-30",
            weekday = "周五",
            isFriday = true,
            isMonthEndWindow = true,
            isQuarterEndWindow = false,
            reportSeason = "三季报常见披露窗口",
        )
        val northbound = NorthboundFlowSummary(
            latestDate = "2026-10-30",
            latestNet = -8.0,
            fiveDayNet = -32.0,
            twentyDayNet = -55.0,
            source = "测试数据",
        )
        val turnover = TurnoverContext(
            today = 8.0e11,
            previousSameTime = 1.0e12,
            predictedFullDay = 1.1e12,
        )

        val cautious = MarketRiskContext(timing, northbound, turnover)
        val timingOnly = MarketRiskContext(timing, null, null)

        assertTrue(cautious.portfolioNote()!!.contains("降低追涨/补仓优先级"))
        assertTrue(cautious.aiText().contains("北向"))
        assertFalse(timingOnly.portfolioNote()?.contains("降低追涨/补仓优先级") == true)
    }

    @Test
    fun singleWeakEvidenceDoesNotLowerAddOnPriority() {
        val timing = MarketTimingContext(
            date = "2026-10-29",
            weekday = "周四",
            isFriday = false,
            isMonthEndWindow = false,
            isQuarterEndWindow = false,
            reportSeason = null,
        )

        val northboundOnly = MarketRiskContext(
            timing = timing,
            northbound = NorthboundFlowSummary("2026-10-29", -8.0, -32.0, -55.0, "测试数据"),
        )
        val turnoverOnly = MarketRiskContext(
            timing = timing,
            turnover = TurnoverContext(8.0e11, 1.0e12, 1.1e12, sameTimeRatio = 0.75),
        )

        assertTrue(northboundOnly.portfolioNote() == null)
        assertTrue(turnoverOnly.portfolioNote() == null)
    }

    @Test
    fun timingUsesLastTradingDaysWhenCalendarFallsOnWeekend() {
        val weekend = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.MARCH, 29, 10, 0, 0)
        }

        val context = MarketTimingContext.from(
            weekend,
            tradingDates = listOf("2026-03-26", "2026-03-27", "2026-03-30", "2026-03-31"),
        )

        assertTrue(context.isMonthEndWindow)
        assertTrue(context.isQuarterEndWindow)
    }

    @Test
    fun timingDoesNotTreatMidMonthTradingDatesAsMonthOrQuarterEnd() {
        val midMonth = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.MARCH, 15, 10, 0, 0)
        }

        val context = MarketTimingContext.from(
            midMonth,
            tradingDates = listOf("2026-03-12", "2026-03-13"),
        )

        assertFalse(context.isMonthEndWindow)
        assertFalse(context.isQuarterEndWindow)
    }

    @Test
    fun turnoverTextIncludesTwentyDaySameTimeBaseline() {
        val text = TurnoverContext(
            today = 8.0e11,
            previousSameTime = 1.0e12,
            predictedFullDay = 1.1e12,
            sameTimeRatio = 0.75,
        ).aiText()

        assertTrue(text.contains("近20日同期中位数"))
        assertTrue(text.contains("0.75倍"))
    }

    @Test
    fun riskContextExpiresByTradingDateAndAge() {
        val timing = MarketTimingContext(
            date = "2026-10-12",
            weekday = "周一",
            isFriday = false,
            isMonthEndWindow = false,
            isQuarterEndWindow = false,
            reportSeason = null,
        )
        val now = 1_000_000L
        val context = MarketRiskContext(timing = timing, generatedAtMs = now)

        assertTrue(context.isFresh(today = "2026-10-12", nowMs = now + 10 * 60 * 1000L))
        assertTrue(!context.isFresh(today = "2026-10-12", nowMs = now + 16 * 60 * 1000L))
        assertTrue(!context.isFresh(today = "2026-10-13", nowMs = now + 1_000L))
    }

    @Test
    fun missingHolidayAndWithdrawalDataAreExplicit() {
        val context = MarketTimingContext(
            date = "2026-10-12",
            weekday = "周一",
            isFriday = false,
            isMonthEndWindow = false,
            isQuarterEndWindow = false,
            reportSeason = null,
            holidayNote = null,
            withdrawalNote = null,
        )

        val text = context.aiText()

        assertTrue(text.contains("节假日数据暂缺"))
        assertTrue(text.contains("出金/资金可取规则暂缺"))
    }
}
