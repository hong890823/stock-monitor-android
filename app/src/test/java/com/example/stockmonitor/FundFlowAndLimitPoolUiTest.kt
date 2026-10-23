package com.example.stockmonitor

import com.example.stockmonitor.analysis.LimitReport
import com.example.stockmonitor.analysis.ObservationEligibility
import com.example.stockmonitor.analysis.ObservationSection
import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FundFlowAndLimitPoolUiTest {

    @Test
    fun limitLineShowsMainFlowAfterSealMetricsAndFlagsLowConfidence() {
        val stock = limitStock().copy(
            fundFlow = FundFlow(
                code = "sh600000",
                name = "浦发银行",
                asOfMs = 1L,
                amountYuan = 4_000_000.0,
                mainNetYuan = 200_000.0,
                mainNetPct = 2.0,
            ),
        )

        val line = LimitReport.stockLine(stock)

        assertTrue(line.indexOf("主力净流向") > line.indexOf("封单/流通市值"))
        assertTrue(line.contains("按订单规模归类"))
        assertTrue(line.contains("样本偏小"))
    }

    @Test
    fun sealedLimitItemsAppearInDedicatedSectionBeforeOrdinaryObservations() {
        val candidates = listOf(
            ObservationCandidate(
                code = "sh600000",
                name = "浦发银行",
                addedDate = "2026-10-22",
                lastEvaluatedDate = "2026-10-22",
                stage = ObservationStage.OBSERVING,
                score = 0,
                weakDays = 0,
                strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
                continuousObservation = true,
                limitUpBlocked = true,
                reason = "涨停封板，持续观察；开板后重新确认，不执行买入",
            ),
        )

        val sections = ObservationEligibility.sections(candidates, emptyMap())

        assertEquals(1, sections.getValue(ObservationSection.LIMIT_CONTINUOUS).size)
        assertTrue(
            sections.keys.toList().indexOf(ObservationSection.LIMIT_CONTINUOUS) <
                sections.keys.toList().indexOf(ObservationSection.OBSERVING),
        )
    }

    @Test
    fun candidateRowsShowHumanReadableFundFlowAndWindowSummary() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("LimitReport.fundFlowSummary(currentFlow)"))
        assertTrue(source.contains("近3日${'$'}{flowWindow.recent3PositiveDays}"))
        assertTrue(source.contains("val shortNote = listOf(stageText, item.reason, flowSummary)"))
    }

    private fun limitStock() = LimitStock(
        code = "sh600000",
        name = "浦发银行",
        price = 11.0,
        prev = 10.0,
        pct = 10.0,
        sealAmountYuan = 1_000_000.0,
        turnoverYuan = 20_000_000.0,
        floatMarketValueYuan = 200_000_000.0,
        openCount = 0,
        firstSealTime = "09:35:00",
        lastSealTime = "09:35:00",
        limitStreak = 1,
        source = LimitDataSource.EASTMONEY_LIMIT_POOL,
    )
}
