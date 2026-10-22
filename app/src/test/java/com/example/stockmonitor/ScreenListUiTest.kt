package com.example.stockmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenListUiTest {
    @Test
    fun selectionListsUseExpandableRowsAndSectionControls() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("UiKit.expandableQuoteRow("))
        assertTrue(source.contains("QuoteRowExpansionState.toggleAll(rowKeys)"))
        assertTrue(source.contains("val pageSize = ObservationEligibility.PAGE_SIZE"))
        assertTrue(source.contains("pageKey = \"observation:"))
        assertTrue(source.contains("pageKey = \"indicator:strict\""))
        assertTrue(source.contains("pageKey = \"indicator:relaxed\""))
        assertTrue(source.contains("pageKey = \"deep:513\""))
        assertTrue(source.contains("pageKey = \"deep:trend\""))
        assertFalse(source.contains("MaterialAlertDialogBuilder("))
        assertFalse(source.contains("result.fiveOneThree.take(100)"))
    }

    @Test
    fun selectionDetailsKeepEvidenceAndMetricsStructured() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("private fun screenDetails(hit: ScreenHit)"))
        assertTrue(source.contains("private fun fiveOneThreeDetails(hit: FiveOneThreeHit)"))
        assertTrue(source.contains("private fun trendDetails(hit: TrendScreenHit)"))
        assertTrue(source.contains("UiKit.DetailRow(\"支持证据\""))
        assertTrue(source.contains("UiKit.DetailRow(\"失效条件\""))
        assertTrue(source.contains("UiKit.DetailRow(\"价格位置\""))
        assertTrue(source.contains("UiKit.DetailRow(\"大盘过滤\""))
    }

    @Test
    fun scanRulesMoveOutOfPrimaryListCards() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()
        val copy = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenHelpCopy.kt")
            .readText()

        assertTrue(source.contains("ScreenHelpCopy.INDICATOR_SCAN_SUMMARY"))
        assertTrue(source.contains("ScreenHelpCopy.DEEP_SCAN_SUMMARY"))
        assertTrue(source.contains("ScreenHelpCopy.indicatorScanDetailText()"))
        assertTrue(source.contains("ScreenHelpCopy.deepScanDetailText()"))
        assertFalse(source.contains("DIF/DEA 零轴上方金叉"))
        assertFalse(source.contains("按 5 连阳 → 倍量阳 → 3 天缩量的顺序扫描"))
        assertTrue(copy.contains("fun observationDetailText()"))
        assertTrue(copy.contains("fun indicatorScanDetailText()"))
        assertTrue(copy.contains("fun deepScanDetailText()"))
    }
}
