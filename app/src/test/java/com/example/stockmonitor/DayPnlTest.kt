package com.example.stockmonitor

import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.TradeBook
import com.example.stockmonitor.core.TradeLot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当日盈亏口径：较**昨收**的日内浮动，组合收益率的分母用**昨日市值**。
 * 这里锁的是数字口径本身——分母换掉，同样的行情会给出不同的收益率，报告与卡片也就对不上了。
 */
class DayPnlTest {

    private fun quote(
        code: String,
        price: Double,
        prevClose: Double,
        name: String = "测试",
    ): Quote = Quote(
        code = code,
        name = name,
        price = price,
        prevClose = prevClose,
        open = prevClose,
        high = price,
        low = price,
        volume = 1_000.0,
        turnover = 10_000.0,
    )

    @Test
    fun perStockPnlIsPriceGapTimesQuantity() {
        val summary = DayPnl.summarize(
            positions = listOf(Position("sh600000", "浦发银行", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", price = 8.8, prevClose = 8.6)),
        )

        val row = summary.rows.single()
        assertEquals(200.0, row.pnl!!, 1e-9) // (8.8 - 8.6) × 1000
        assertEquals(200.0, summary.totalPnl, 1e-9)
        assertEquals(8_600.0, summary.prevMarketValue, 1e-9)
        assertEquals(8_800.0, summary.marketValue, 1e-9)
    }

    @Test
    fun todayAcquisitionUsesBuyCostInsteadOfPrevClose() {
        val summary = DayPnl.summarize(
            positions = listOf(Position("sz002421", "达实智能", 6_000.0, 3.517)),
            quotes = mapOf("sz002421" to quote("sz002421", price = 3.44, prevClose = 3.43)),
            todayAcquisitions = mapOf(
                "sz002421" to DayPnl.Acquisition(quantity = 6_000.0, unitCost = 3.517),
            ),
        )

        assertEquals(-462.0, summary.rows.single().pnl!!, 1e-9) // (3.44 - 3.517) × 6000
        assertEquals(-462.0, summary.totalPnl, 1e-9)
        assertEquals(0.0, summary.prevMarketValue, 1e-9)
    }

    @Test
    fun acquisitionsAggregateSameDayLotsAtWeightedCost() {
        val acquisitions = DayPnl.acquisitions(
            TradeBook(
                lots = listOf(
                    TradeLot("sz002421", "达实智能", 2_000.0, 3.50, "2026-10-26"),
                    TradeLot("sz002421", "达实智能", 4_000.0, 3.525, "2026-10-26"),
                    TradeLot("sh600000", "浦发银行", 100.0, 10.0, "2026-10-23"),
                ),
            ),
            date = "2026-10-26",
        )

        assertEquals(1, acquisitions.size)
        assertEquals(6_000.0, acquisitions.getValue("sz002421").quantity, 1e-9)
        assertEquals(3.5166666666666666, acquisitions.getValue("sz002421").unitCost, 1e-9)
    }

    @Test
    fun totalPctDividesByYesterdayMarketValueNotToday() {
        // 昨日市值 100×100 + 200×50 = 20,000；今日盈亏 (110-100)×100 + (190-200)×50 = 500
        // 收益率 = 500 / 20,000 = 2.5%。若误用今日市值 11,000+9,500 = 20,500 当分母会得到 2.44%。
        val summary = DayPnl.summarize(
            positions = listOf(
                Position("sh600000", "甲", 100.0, 10.0),
                Position("sz000001", "乙", 50.0, 20.0),
            ),
            quotes = mapOf(
                "sh600000" to quote("sh600000", price = 110.0, prevClose = 100.0),
                "sz000001" to quote("sz000001", price = 190.0, prevClose = 200.0),
            ),
        )

        assertEquals(500.0, summary.totalPnl, 1e-9)
        assertEquals(20_000.0, summary.prevMarketValue, 1e-9)
        assertEquals(2.5, summary.totalPct!!, 1e-9)
    }

    @Test
    fun missingQuoteIsExcludedFromBothTotalsAndCounted() {
        val summary = DayPnl.summarize(
            positions = listOf(
                Position("sh600000", "有行情", 100.0, 10.0),
                Position("sz000001", "停牌", 50.0, 20.0),
            ),
            quotes = mapOf("sh600000" to quote("sh600000", price = 11.0, prevClose = 10.0)),
        )

        assertEquals(100.0, summary.totalPnl, 1e-9)
        // 停牌那 1,000 元昨日市值也不进分母，否则收益率会被拉小。
        assertEquals(1_000.0, summary.prevMarketValue, 1e-9)
        assertEquals(10.0, summary.totalPct!!, 1e-9)
        assertEquals(1, summary.missing)
        assertEquals(1, summary.counted)
        assertNull(summary.rows.first { it.code == "sz000001" }.pnl)
    }

    @Test
    fun zeroPrevCloseCountsAsMissing() {
        val summary = DayPnl.summarize(
            positions = listOf(Position("sz300001", "新股", 1_000.0, 0.0)),
            quotes = mapOf("sz300001" to quote("sz300001", price = 30.0, prevClose = 0.0)),
        )

        assertEquals(1, summary.missing)
        assertEquals(0, summary.counted)
        assertNull(summary.totalPct)
    }

    @Test
    fun watchOnlyPositionsAreIgnored() {
        val summary = DayPnl.summarize(
            positions = listOf(
                Position("sh600000", "持仓", 100.0, 10.0),
                Position("sz000001", "观察", 0.0, 0.0),
            ),
            // 观察股连行情都不给，也不该被算成「缺行情」。
            quotes = mapOf("sh600000" to quote("sh600000", price = 11.0, prevClose = 10.0)),
        )

        assertEquals(1, summary.rows.size)
        assertEquals(0, summary.missing)
    }

    @Test
    fun summaryTextIsNullWhenNothingCounted() {
        val summary = DayPnl.summarize(
            positions = listOf(Position("sz000001", "停牌", 50.0, 20.0)),
            quotes = emptyMap(),
        )

        // 一只都算不出来时不印 +0.00 元，那会把「没行情」显示成「没亏没赚」。
        assertNull(DayPnl.summaryText(summary))
    }

    @Test
    fun summaryTextCarriesSignedTotalsAndDenominatorBasis() {
        val summary = DayPnl.summarize(
            positions = listOf(Position("sh600000", "浦发银行", 1_000.0, 8.5)),
            quotes = mapOf("sh600000" to quote("sh600000", price = 8.8, prevClose = 8.6)),
        )

        val text = DayPnl.summaryText(summary)
        assertNotNull(text)
        assertTrue(text!!.contains("今日盈亏 +200.00 元"))
        assertTrue(text.contains("较昨日市值 +2.33%")) // 200 / 8,600
        assertTrue(text.contains("持仓市值 8,800 元"))
        assertTrue(text.contains("昨日市值 8,600 元"))
    }
}
