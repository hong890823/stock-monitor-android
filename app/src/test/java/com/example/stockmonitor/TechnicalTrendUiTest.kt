package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalTrendUiTest {

    @Test
    fun watchlistAndHoldingsExposeTheSameTechnicalTrendEvidence() {
        val market = TestProjectFiles.source("com/example/stockmonitor/ui/MarketFragment.kt").readText()
        val portfolio = TestProjectFiles.source("com/example/stockmonitor/ui/PortfolioFragment.kt").readText()
        val snapshot = TestProjectFiles.source("com/example/stockmonitor/analysis/TechnicalTrendSnapshotPolicy.kt").readText()

        assertTrue(market.contains("Repo.technicalTrendSnapshots"))
        assertTrue(market.contains("技术综合评分"))
        assertTrue(market.contains("周/月趋势"))
        assertTrue(market.contains("技术分构成"))
        assertTrue(snapshot.contains("风险控制"))
        assertTrue(market.contains("操作建议"))
        assertTrue(market.contains("primaryAdvice"))
        assertTrue(portfolio.contains("Repo.technicalTrendSnapshots"))
        assertTrue(portfolio.contains("技术综合评分"))
        assertTrue(portfolio.contains("周/月趋势"))
        assertTrue(portfolio.contains("技术分构成"))
        assertTrue(portfolio.contains("操作建议"))
        // 持仓的操作建议是合并项：技术面 primaryAdvice 由 HoldingAdvicePolicy 收口持仓语境后输出。
        assertTrue(portfolio.contains("HoldingAdvicePolicy.mergedAdvice"))
        assertTrue(snapshot.contains("primaryAdvice"))
        assertFalse(portfolio.contains("补仓参考"))
        listOf("日K依据", "趋势档位", "评分依据").forEach { repeatedRow ->
            assertFalse(market.contains("UiKit.DetailRow(\"$repeatedRow\""))
            assertFalse(portfolio.contains("UiKit.DetailRow(\"$repeatedRow\""))
        }
    }
}
