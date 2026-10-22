package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitRankingIntegrationTest {

    @Test
    fun limitPoolSortsOnlyLimitUpsBySealQuality() {
        val monitor = TestProjectFiles
            .source("com/example/stockmonitor/monitor/MonitorService.kt")
            .readText()
        val ranking = TestProjectFiles
            .source("com/example/stockmonitor/analysis/LimitStockRanking.kt")
            .readText()
        val models = TestProjectFiles
            .source("com/example/stockmonitor/core/Models.kt")
            .readText()

        assertTrue(ranking.contains("fun sortUp(stocks: List<LimitStock>)"))
        assertTrue(monitor.contains("up = LimitStockRanking.sortUp(enrichedUp)"))
        assertTrue(monitor.contains("down = enrichedDown"))
        assertFalse(monitor.contains("LimitStockRanking.sortDown"))
        assertTrue(models.contains("val sealQualityScore: Int? = null"))
        assertTrue(models.contains("val sealQualityCoveragePct: Int? = null"))
    }

    @Test
    fun limitReportShowsQualityScoreWithoutTreatingItAsBuySignal() {
        val report = TestProjectFiles
            .source("com/example/stockmonitor/analysis/LimitReport.kt")
            .readText()

        assertTrue(report.contains("封板质量 ${'$'}{stock.sealQualityScore}"))
        assertTrue(report.contains("数据覆盖 ${'$'}{stock.sealQualityCoveragePct}%"))
        assertTrue(report.contains("仅用于涨停观察排序，不代表买入信号"))
    }
}
