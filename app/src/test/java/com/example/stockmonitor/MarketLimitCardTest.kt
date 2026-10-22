package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketLimitCardTest {
    @Test
    fun fullListActionsFollowTheirOwnDirectionPreviews() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/MarketFragment.kt").readText()
        val card =
            source
                .substringAfter("// ── 涨跌停 ──")
                .substringBefore("UiKit.card(context, \"ℹ️ 数据说明\")")

        val upRows = card.indexOf("limits.up.take(8).forEach")
        val upAction = card.indexOf("text = \"查看全部涨停")
        val divider = card.indexOf("addDivider()")
        val downRows = card.indexOf("limits.down.take(8).forEach")
        val downAction = card.indexOf("text = \"查看全部跌停")

        assertTrue(upRows in 0 until upAction)
        assertTrue(upAction < divider)
        assertTrue(divider < downRows)
        assertTrue(downRows < downAction)
        assertTrue(card.contains("topMargin = UiKit.dp(context, 8)"))
    }

    @Test
    fun refreshPreservesLimitPoolSourceAfterAttachingBoardLabels() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/monitor/MonitorService.kt").readText()
        val refresh =
            source
                .substringAfter("private fun refreshLimits()")
                .substringBefore("/** 从概念缓存构建")

        assertTrue(refresh.contains("source = scanned.source"))
    }

    @Test
    fun limitCardShowsEightMetricsRowsAndSeparateReports() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/MarketFragment.kt").readText()
        val card =
            source
                .substringAfter("// ── 涨跌停 ──")
                .substringBefore("UiKit.card(context, \"ℹ️ 数据说明\")")

        assertTrue(card.contains("limits.up.take(8)"))
        assertTrue(card.contains("limits.down.take(8)"))
        assertTrue(card.contains("LimitReport.detailRows(stock)"))
        assertTrue(card.contains("text = \"查看全部涨停（${'$'}{limits.up.size}只）\""))
        assertTrue(card.contains("text = \"查看全部跌停（${'$'}{limits.down.size}只）\""))
        assertTrue(card.contains("LimitReport.formatUp(limits)"))
        assertTrue(card.contains("LimitReport.formatDown(limits)"))
        assertFalse(card.contains("查看全部涨跌停"))
    }
}
