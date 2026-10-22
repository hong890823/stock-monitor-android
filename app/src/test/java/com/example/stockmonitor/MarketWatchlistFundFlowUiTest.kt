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
        assertTrue(source.contains("LimitReport.fundFlowSummary(current)"))
        assertTrue(source.contains("\"近3日${'$'}{window.recent3PositiveDays}/${'$'}{minOf(window.sampleCount, 3)}日净流入\""))
        assertTrue(source.contains("\"多日样本1/3\""))
        assertTrue(source.contains("builder.append(\"｜${'$'}fundFlow\")"))
    }
}
