package com.example.stockmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketWatchlistFundFlowUiTest {

    @Test
    fun watchlistShowsMainForceIntentWithConcreteFundFlowEvidence() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/MarketFragment.kt")
            .readText()

        assertEquals(1, Regex("UiKit\\.DetailRow\\(\\s*\"主力意图\"").findAll(source).count())
        assertTrue(source.contains("mainForce.summary"))
        assertTrue(source.contains("UiKit.DetailRow(\n                \"资金依据\","))
        assertTrue(source.contains("mainForce.evidence.replace(\"｜\", \"\\n\")"))
        assertFalse(source.contains("可能主力动向"))
    }
}
