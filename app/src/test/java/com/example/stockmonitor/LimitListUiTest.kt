package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitListUiTest {

    @Test
    fun limitRowsUseExpandableMarketListFormWithSeparateControls() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/MarketFragment.kt")
            .readText()
        val card = source
            .substringAfter("// ── 涨跌停 ──")
            .substringBefore("UiKit.card(context, \"ℹ️ 数据说明\")")

        assertTrue(card.contains("UiKit.expandableQuoteRow("))
        assertTrue(card.contains("LimitReport.detailRows(stock)"))
        assertTrue(card.contains("QuoteRowExpansionState.toggleAll(upRowKeys)"))
        assertTrue(card.contains("QuoteRowExpansionState.toggleAll(downRowKeys)"))
        assertTrue(card.contains("onExpansionChanged ="))
        assertFalse(card.contains("LimitReport.metricLine(stock)"))
    }

    @Test
    fun limitDetailsExposeSealMetricsAndQualitySeparately() {
        val report = TestProjectFiles
            .source("com/example/stockmonitor/analysis/LimitReport.kt")
            .readText()

        assertTrue(report.contains("fun detailRows(stock: LimitStock): List<Pair<String, String>>"))
        assertTrue(report.contains("\"封板质量\" to"))
        assertTrue(report.contains("\"封单金额\" to"))
        assertTrue(report.contains("\"封单/成交\" to"))
        assertTrue(report.contains("\"开板\" to"))
        assertTrue(report.contains("\"资金流\" to"))
    }
}
