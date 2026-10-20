package com.example.stockmonitor

import com.example.stockmonitor.analysis.LimitReport
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.SinaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitReportTest {

    @Test
    fun limitPercentHandlesStAndNewBeijingExchangeCodes() {
        assertEquals(10.0, SinaClient.limitPctFor("sh600000", "ST测试"), 0.0)
        assertEquals(30.0, SinaClient.limitPctFor("bj920001", "测试股份"), 0.0)
        assertEquals(20.0, SinaClient.limitPctFor("sz300001", "测试股份"), 0.0)
        assertEquals(20.0, SinaClient.limitPctFor("sh688001", "ST测试"), 0.0)
    }

    @Test
    fun fullReportIncludesEveryStockInSeparateSections() {
        val data = LimitData(
            up = (1..13).map { stock(it, "涨") },
            down = (1..13).map { stock(it, "跌") },
        )

        val report = LimitReport.format(data)

        assertTrue(report.contains("涨停（13只）"))
        assertTrue(report.contains("跌停（13只）"))
        assertTrue(report.contains("涨13（sh600013）"))
        assertTrue(report.contains("跌13（sh600013）"))
    }

    private fun stock(index: Int, prefix: String): LimitStock = LimitStock(
        code = "sh600%03d".format(index),
        name = "$prefix$index",
        price = 10.0,
        prev = 9.0,
        pct = if (prefix == "涨") 10.0 else -10.0,
    )
}
