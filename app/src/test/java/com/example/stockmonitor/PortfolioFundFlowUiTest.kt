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
        assertTrue(source.contains("FundFlowDisplayPolicy.current(Repo.fundFlowHistory[code].orEmpty().lastOrNull())"))
        assertTrue(source.contains("FundFlowDisplayPolicy.multiDay(Repo.fundFlowHistory[code].orEmpty())"))
        assertTrue(source.contains("currentFundFlowSummary(result.code)"))
        assertTrue(source.contains("UiKit.DetailRow(\"资金流\", currentFundFlowSummary(result.code))"))
        assertTrue(source.contains("UiKit.DetailRow(\n                \"近3日资金流\","))
    }
}
