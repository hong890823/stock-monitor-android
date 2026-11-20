package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class FundamentalsReportDirectionTest {
    private val prefix = "下一财报期趋势预判（历史同比外推，近4季净利同比均值 -30.0%）："

    @Test
    fun fullSentenceOutlookStillResolvesToTheRightDirection() {
        assertEquals(
            "下一财报期利润趋势偏承压",
            FundamentalsReport.directionFromOutlook("${prefix}利润趋势偏承压｜经营现金流数据不足"),
        )
        assertEquals(
            "下一财报期利润趋势偏支持",
            FundamentalsReport.directionFromOutlook("${prefix}利润趋势偏支持｜现金流正常"),
        )
        assertEquals(
            "下一财报期利润趋势基本稳定",
            FundamentalsReport.directionFromOutlook("${prefix}利润趋势基本稳定｜现金流正常"),
        )
    }
}