package com.example.stockmonitor

import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.analysis.TechnicalSummary
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalSummaryTest {

    @Test
    fun summaryKeepsTheKeyDecisionDataFromTheFullAnalysis() {
        val result = StockAnalysis.analyze(bars(), "sh600089", "特变电工", liveQuote())

        val summary = TechnicalSummary.from(result)

        assertEquals("中性", summary.scoreLabel)
        assertTrue(summary.trend.contains("偏弱"))
        assertTrue(summary.volume.contains("量比 0.79"))
        assertTrue(summary.levels.contains("支撑 17.83"))
        assertTrue(summary.entry.contains("暂无有效入场结构") || summary.entry.contains("M20") || summary.entry.contains("突破"))
        // 底背离 +1 后评分 -1 → action 落在中性分支
        assertTrue(summary.action.contains("暂以观察为主"))
        // 摘要带一行量价背离结论
        assertTrue(summary.divergence.contains("底背离"))
    }

    private fun bars(): List<DayBar> =
        javaClass.classLoader!!.getResourceAsStream("kline_sh600089.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(",")
                DayBar(
                    date = parts[0],
                    open = parts[1].toDouble(),
                    high = parts[2].toDouble(),
                    low = parts[3].toDouble(),
                    close = parts[4].toDouble(),
                    volume = parts[5].toDouble(),
                )
            }

    private fun liveQuote(): Quote = Quote(
        code = "sh600089",
        name = "特变电工",
        price = 18.21,
        prevClose = 18.30,
        open = 18.35,
        high = 18.38,
        low = 18.18,
        volume = 39_869_351.0,
        turnover = 728_209_800.0,
    )
}
