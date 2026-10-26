package com.example.stockmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsMonitoringCardTest {
    @Test
    fun monitoringCardMovesExplanationsToSecondaryDialog() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/ToolsFragment.kt").readText()
        val card =
            source
                .substringAfter("UiKit.card(context, \"⚙️ 监控与提醒\").apply {")
                .substringBefore("container.addView(view)")

        assertFalse(card.contains("MonitoringCopy.rules("))
        assertFalse(card.contains("划掉最近任务卡片会立即停止后台监控"))
        assertTrue(card.contains("button(context, \"监控规则与说明\")"))
        assertTrue(card.contains("MonitoringCopy.detailText(store.dropAlertPct)"))
        assertTrue(card.contains("Dialogs.showDetails("))
    }

    @Test
    fun monitoringRulesEntryIsLastFeatureInMonitoringCard() {
        val source =
            TestProjectFiles.source("com/example/stockmonitor/ui/ToolsFragment.kt").readText()
        val card =
            source
                .substringAfter("UiKit.card(context, \"⚙️ 监控与提醒\").apply {")
                .substringBefore("container.addView(view)")
        val labels =
            Regex("button\\(context, \"([^\"]+)\"\\)")
                .findAll(card)
                .map { it.groupValues[1] }
                .toList()

        assertEquals("监控规则与说明", labels.lastOrNull())
    }
}
