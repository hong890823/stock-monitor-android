package com.example.stockmonitor

import com.example.stockmonitor.analysis.LimitReport
import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.SinaClient
import com.example.stockmonitor.core.FundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitReportTest {

    @Test
    fun separateReportsExposeMetricsWithoutMixingDirections() {
        val upStock = stock(1, "涨").copy(
            sealAmountYuan = 326_898_805.0,
            turnoverYuan = 23_462_397.0,
            floatMarketValueYuan = 2_331_499_297.32,
            openCount = 2,
            firstSealTime = "09:25:00",
            lastSealTime = "10:00:00",
            limitStreak = 3,
            industry = "专用设备",
            source = LimitDataSource.EASTMONEY_LIMIT_POOL,
        )
        val downStock = stock(1, "跌").copy(
            pct = -10.0,
            sealAmountYuan = 3_171_746.0,
            turnoverYuan = 995_743_120.0,
            floatMarketValueYuan = 7_467_183_980.4,
            openCount = 1,
            lastSealTime = "15:00:00",
            source = LimitDataSource.EASTMONEY_LIMIT_POOL,
        )
        val data = LimitData(listOf(upStock), listOf(downStock), LimitDataSource.EASTMONEY_LIMIT_POOL)

        val formatUp = LimitReport::class.java.methods.singleOrNull {
            it.name == "formatUp" && it.parameterCount == 1
        }
        val formatDown = LimitReport::class.java.methods.singleOrNull {
            it.name == "formatDown" && it.parameterCount == 1
        }
        assertNotNull("应提供涨停独立报告", formatUp)
        assertNotNull("应提供跌停独立报告", formatDown)

        val upReport = formatUp!!.invoke(LimitReport, data) as String
        val downReport = formatDown!!.invoke(LimitReport, data) as String
        assertTrue(upReport.contains("涨停（1只）"))
        assertTrue(upReport.contains("封单 3.27亿"))
        assertTrue(upReport.contains("封单/成交 1393.29%"))
        assertTrue(upReport.contains("炸板 2次"))
        assertTrue(upReport.contains("首封 09:25"))
        assertTrue(upReport.contains("3连板"))
        assertFalse(upReport.contains("跌1"))
        assertTrue(downReport.contains("跌停（1只）"))
        assertTrue(downReport.contains("开板 1次"))
        assertTrue(downReport.contains("首封 —"))
        assertFalse(downReport.contains("涨1"))
    }

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

    @Test
    fun fundFlowAmountUsesYiAndWanForNegativeValues() {
        val stock = stock(1, "涨").copy(
            fundFlow = FundFlow(
                code = "sh600001",
                name = "星光股份",
                asOfMs = 1L,
                amountYuan = 10_000_000.0,
                mainNetYuan = -144_897_649.0,
                mainNetPct = -12.0,
            ),
        )

        val line = LimitReport.stockLine(stock)

        assertTrue(line.contains("主力净流向 -12.00%（-1.45亿）"))
        assertTrue(line.contains("炸板"))
    }

    private fun stock(index: Int, prefix: String): LimitStock = LimitStock(
        code = "sh600%03d".format(index),
        name = "$prefix$index",
        price = 10.0,
        prev = 9.0,
        pct = if (prefix == "涨") 10.0 else -10.0,
    )
}
