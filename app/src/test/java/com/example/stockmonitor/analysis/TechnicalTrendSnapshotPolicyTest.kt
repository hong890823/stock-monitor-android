package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TechnicalTrendSnapshotPolicyTest {

    @Test
    fun snapshotUsesUnifiedTechnicalScoreAndWeeklyMonthlyTrend() {
        val bars = risingBars()
        val result = TechnicalTrendSnapshotPolicy.evaluate(
            history = bars,
            code = "sh600000",
            name = "测试股票",
            asOf = bars.last().date,
            entrySetup = confirmedSetup(),
            trendAssessment = trendAssessment(allowsAddPosition = true),
        )

        assertTrue(result.technicalScore >= 80)
        assertEquals(
            result.technicalScore,
            result.trendPoints + result.structurePoints + result.momentumPoints + result.riskPoints,
        )
        assertEquals(SwingTrend.UP, result.weeklyTrend)
        assertEquals(SwingTrend.UP, result.monthlyTrend)
        assertTrue(result.summary.contains("技术 ${result.technicalScore}/100"))
        assertTrue(result.summary.contains("周K向上"))
        assertTrue(result.summary.contains("月K向上"))
        // canBuy=false 的结构只能给观察建议，不能写成「可分批参与」。
        assertTrue(result.primaryAdvice.contains("入场结构仅作观察"))
        assertTrue(result.adviceReason.isNotBlank())
    }

    @Test
    fun weakTrendIsCappedAndDoesNotSuggestAddingPosition() {
        val result = TechnicalTrendSnapshotPolicy.evaluate(
            history = risingBars(),
            code = "sh600000",
            name = "测试股票",
            asOf = risingBars().last().date,
            entrySetup = weakSetup(),
            trendAssessment = trendAssessment(allowsAddPosition = true),
        )

        assertTrue(result.technicalScore <= 45)
        assertTrue(result.primaryAdvice.contains("暂不补仓"))
    }

    @Test
    fun snapshotCarriesPriceLevelsAndVolumeRatio() {
        val bars = risingBars()
        val result = TechnicalTrendSnapshotPolicy.evaluate(
            history = bars,
            code = "sh600000",
            name = "测试股票",
            asOf = bars.last().date,
            entrySetup = weakSetup(),
            trendAssessment = trendAssessment(allowsAddPosition = false),
        )

        // 持仓页等待盘中分析时靠这些字段展示支撑/压力、量比与均线结构。
        assertEquals(bars.last().close, result.price, 0.0001)
        assertTrue(result.ma5 > 0)
        assertTrue(result.ma10 > 0)
        assertTrue(result.ma20 > 0)
        assertTrue(result.ma60 > 0)
        assertTrue(result.support >= 0.0 && result.resistance >= 0.0)
        assertNotNull(result.volumeRatio)
        assertTrue(result.volumeRatio!! > 0)
        assertTrue(result.atrPct == null || result.atrPct!! > 0)
    }

    @Test
    fun dailyKStillScoresWhenThereIsNoEntrySetup() {
        val bars = risingBars()
        val result = TechnicalTrendSnapshotPolicy.evaluate(
            history = bars,
            code = "sh600000",
            name = "测试股票",
            asOf = bars.last().date,
            entrySetup = weakSetup().copy(trendWeak = false),
            trendAssessment = trendAssessment(allowsAddPosition = true),
        )

        assertTrue(result.technicalScore > 0)
        assertTrue(result.evidenceSummary.contains("趋势"))
        assertTrue(result.evidenceSummary.contains("量价动量"))
        assertTrue(result.trendScaleSummary.contains("共5档"))
        assertTrue(result.trendScaleSummary.contains("下跌企稳"))
    }

    private fun confirmedSetup() = EntrySetup(
        type = EntrySetupType.BREAKOUT,
        confirmed = true,
        canBuy = false,
        trendWeak = false,
        priorHigh = 20.0,
        ma20SlopePct = 1.0,
        volumeConfirmed = true,
        marketFilter = MarketFilter.NEUTRAL,
        stopPrice = 18.0,
        reason = "测试",
    )

    private fun weakSetup() = confirmedSetup().copy(
        type = EntrySetupType.NONE,
        confirmed = false,
        trendWeak = true,
        volumeConfirmed = false,
    )

    private fun trendAssessment(allowsAddPosition: Boolean) = TrendAssessment(
        shortTerm = if (allowsAddPosition) ShortTermTrend.UP else ShortTermTrend.DOWN,
        major = if (allowsAddPosition) MajorTrend.UP else MajorTrend.DOWN,
        reason = "测试",
        allowsOrdinaryBuy = allowsAddPosition,
        allowsAddPosition = allowsAddPosition,
        allowsTBuy = false,
        allowsTSell = false,
        allowsBottomReversal = false,
        riskOnly = !allowsAddPosition,
    )

    private fun risingBars(): List<DayBar> {
        var date = LocalDate.parse("2024-01-01")
        return generateSequence {
            val current = date
            date = date.plusDays(1)
            current
        }
            .filter { it.dayOfWeek.value <= 5 }
            .take(520)
            .mapIndexed { index, day ->
                val close = 10.0 + index * 0.03
                DayBar(
                    date = day.toString(),
                    open = close - 0.02,
                    high = close + 0.04,
                    low = close - 0.05,
                    close = close,
                    volume = 1_000_000.0 + index,
                )
            }
            .toList()
    }
}
