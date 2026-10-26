package com.example.stockmonitor.ui

import com.example.stockmonitor.TestProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioLeaderCardUiTest {
    @Test
    fun leaderCardUsesShortActionLabels() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/PortfolioFragment.kt").readText()
        val card =
            source
                .substringAfter("UiKit.card(context, \"📌 板块龙头\").apply {")
                .substringBefore("container.addView(view)")

        assertTrue(card.contains("actionButton(context, \"📌 调整龙头\")"))
        assertTrue(card.contains("actionButton(context, \"🗂 板块管理\")"))
    }
}
