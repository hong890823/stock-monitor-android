package com.example.stockmonitor

import com.example.stockmonitor.core.AnalysisReportCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisReportCacheTest {

    @Test
    fun cacheMergesTechnicalAndFundamentalReportsByCode() {
        var raw = "{}"
        raw = AnalysisReportCache.merge(raw, "sh600089", "特变电工", technical = "技术报告", fundamental = null, updatedAt = "2026-09-29")
        raw = AnalysisReportCache.merge(raw, "sh600089", "特变电工", technical = null, fundamental = "基本面报告", updatedAt = "2026-09-29")

        val entry = AnalysisReportCache.get(raw, "sh600089")!!

        assertEquals("技术报告", entry.technicalReport)
        assertEquals("基本面报告", entry.fundamentalReport)
    }

    @Test
    fun cacheReturnsNullForUnknownCode() {
        assertNull(AnalysisReportCache.get("{}", "sh600089"))
    }
}
