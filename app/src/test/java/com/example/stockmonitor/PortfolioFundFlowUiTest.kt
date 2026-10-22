package com.example.stockmonitor

import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioFundFlowUiTest {

    @Test
    fun holdingListShowsHumanReadableFundFlowUnderRenamedCard() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/PortfolioFragment.kt")
            .readText()

        assertTrue(source.contains("\"📦 持仓股\""))
        assertTrue(source.contains("LimitReport.fundFlowSummary(current)"))
        assertTrue(source.contains("近3日${'$'}{window.recent3PositiveDays}"))
        assertTrue(source.contains("currentFundFlowSummary(result.code)"))
        assertTrue(source.contains("UiKit.DetailRow(\"资金流\", currentFundFlowSummary(result.code))"))
        assertTrue(source.contains("UiKit.DetailRow(\n                \"近3日资金流\","))
    }
}
