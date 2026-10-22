package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandableQuoteRowUiTest {

    @Test
    fun quoteRowProvidesStatefulCollapsedAndExpandedLayouts() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/UiKit.kt")
            .readText()

        assertTrue(source.contains("fun expandableQuoteRow("))
        assertTrue(source.contains("data class DetailRow(val label: String, val value: String)"))
        assertTrue(source.contains("details: List<DetailRow>"))
        assertTrue(source.contains("onExpansionChanged: (Boolean) -> Unit = {}"))
        assertTrue(source.contains("fun setExpanded(expanded: Boolean)"))
        assertTrue(source.contains("ExpandableQuoteRowView(view = root"))
        assertTrue(source.contains("QuoteRowExpansionState.isExpanded(stateKey)"))
        assertTrue(source.contains("QuoteRowExpansionState.toggle(stateKey)"))
        assertTrue(source.contains("detailsView.visibility = if (expanded) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("secondaryAction: (() -> Boolean)? = null"))
        assertTrue(source.contains("fun setSecondaryExpanded(allExpanded: Boolean)"))
    }

    @Test
    fun marketWatchlistUsesCoreSummaryAndStructuredDetails() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/MarketFragment.kt")
            .readText()
        val watchlist = source
            .substringAfter("// ── 自选股 ──")
            .substringBefore("// ── 行业涨幅榜 / 跌幅榜 ──")

        assertTrue(watchlist.contains("UiKit.expandableQuoteRow("))
        assertTrue(watchlist.contains("summary = watchSummary("))
        assertTrue(watchlist.contains("details = watchDetails("))
        assertTrue(watchlist.contains("onSecondaryAction = {"))
        assertTrue(watchlist.contains("QuoteRowExpansionState.toggleAll("))
        assertTrue(watchlist.contains("watchRows.forEach { it.setExpanded(expanded) }"))
        assertTrue(watchlist.contains("onExpansionChanged = {"))
        assertTrue(watchlist.contains("setSecondaryExpanded("))
        assertFalse(watchlist.contains("note = watchNote("))
    }

    @Test
    fun portfolioHoldingsUseCoreSummaryAndStructuredDetails() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/PortfolioFragment.kt")
            .readText()
        val holdings = source
            .substringAfter("\"📦 持仓股\"")
            .substringBefore("UiKit.card(context, \"💰 交易工具\")")

        assertTrue(holdings.contains("UiKit.expandableQuoteRow("))
        assertTrue(holdings.contains("summary = portfolioSummary("))
        assertTrue(holdings.contains("details = portfolioDetails("))
        assertTrue(holdings.contains("onSecondaryAction = {"))
        assertTrue(holdings.contains("QuoteRowExpansionState.toggleAll("))
        assertTrue(holdings.contains("holdingRows.forEach { it.setExpanded(expanded) }"))
        assertTrue(holdings.contains("onExpansionChanged = {"))
        assertTrue(holdings.contains("setSecondaryExpanded("))
        assertTrue(source.contains("UiKit.DetailRow(\"入场结构\", result.entrySetup.compactText())"))
        assertTrue(source.contains("UiKit.DetailRow(\"结构依据\", result.entrySetup.reason)"))
        assertTrue(source.contains("UiKit.DetailRow(\"失效 / 止损\""))
    }
}
