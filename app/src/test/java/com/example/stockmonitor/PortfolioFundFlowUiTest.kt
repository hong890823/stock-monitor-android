package com.example.stockmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioFundFlowUiTest {

    @Test
    fun holdingListShowsMainForceIntentInBothAnalysisStates() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/PortfolioFragment.kt")
            .readText()

        assertTrue(source.contains("\"📦 持仓股\""))
        assertTrue(source.contains("FundFlowDisplayPolicy.current(Repo.fundFlowHistory[code].orEmpty().lastOrNull())"))
        assertTrue(source.contains("currentFundFlowSummary(result.code)"))
        assertEquals(2, Regex("UiKit\\.DetailRow\\(\\s*\"主力意图\"").findAll(source).count())
        assertTrue(source.contains("mainForce.evidence.replace(\"｜\", \"\\n\")"))
        assertFalse(source.contains("可能主力动向"))
    }
}
