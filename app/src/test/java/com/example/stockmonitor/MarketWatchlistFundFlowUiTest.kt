package com.example.stockmonitor

import org.junit.Assert.assertTrue
import org.junit.Test

class MarketWatchlistFundFlowUiTest {

    @Test
    fun watchlistRowShowsCurrentAndMultiDayFundFlow() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/MarketFragment.kt")
            .readText()
        val watchlist = source
            .substringAfter("// ── 自选股 ──")
            .substringBefore("// ── 行业涨幅榜 / 跌幅榜 ──")

        assertTrue(watchlist.contains("fundFlow = currentFundFlowSummary(code)"))
        assertTrue(source.contains("FundFlowDisplayPolicy.current(current)"))
        assertTrue(source.contains("FundFlowDisplayPolicy.multiDay(Repo.fundFlowHistory[code].orEmpty())"))
        assertTrue(source.contains("builder.append(\"｜${'$'}fundFlow\")"))
    }
}
