package com.example.stockmonitor

import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.QuoteCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收盘行情缓存的合并与编解码。
 *
 * 合并规则是「市场」页晚上/周末不空屏的关键：最近交易日的收盘快照（日期不是今天）
 * 必须能进内存显示，但更旧的响应绝不能把内存里更新的行情盖回去。
 */
class QuoteCacheTest {

    private fun quote(
        code: String,
        date: String?,
        price: Double = 10.0,
        name: String = "股票$code",
    ) = Quote(
        code = code, name = name, price = price, prevClose = 9.0,
        open = 9.5, high = 10.5, low = 9.2,
        volume = 1_000.0, turnover = 10_000.0, quoteDate = date,
    )

    @Test
    fun mergeAcceptsLastTradingDayWhenMemoryEmpty() {
        // 周六开屏：新浪返回周五收盘（日期不是今天），旧逻辑整批丢弃导致空屏。
        val merged = QuoteCache.mergeNewer(
            emptyMap(),
            mapOf("sh600519" to quote("sh600519", "2026-10-16")),
        )
        assertEquals("2026-10-16", merged["sh600519"]?.quoteDate)
    }

    @Test
    fun mergeNeverOverwritesNewerWithOlder() {
        // 内存里是今日盘中行情，延迟到达的昨日响应不能把它盖回去。
        val today = quote("sh600519", "2026-10-22", price = 20.0)
        val merged = QuoteCache.mergeNewer(
            mapOf("sh600519" to today),
            mapOf("sh600519" to quote("sh600519", "2026-10-21", price = 18.0)),
        )
        assertEquals(20.0, merged["sh600519"]!!.price, 0.0)
    }

    @Test
    fun mergeAcceptsSameDayAndNewerUpdates() {
        val older = quote("sh600519", "2026-10-21", price = 18.0)
        val sameDay = quote("sh600519", "2026-10-22", price = 19.0)
        val nextDay = quote("sh600519", "2026-10-23", price = 21.0)
        val once = QuoteCache.mergeNewer(mapOf("sh600519" to older), mapOf("sh600519" to sameDay))
        assertEquals(19.0, once["sh600519"]!!.price, 0.0)
        val twice = QuoteCache.mergeNewer(once, mapOf("sh600519" to nextDay))
        assertEquals(21.0, twice["sh600519"]!!.price, 0.0)
    }

    @Test
    fun mergeAddsCodesMissingFromMemory() {
        val merged = QuoteCache.mergeNewer(
            mapOf("sh600519" to quote("sh600519", "2026-10-22")),
            mapOf(
                "sh600519" to quote("sh600519", "2026-10-22"),
                "sz000001" to quote("sz000001", "2026-10-21"),
            ),
        )
        assertEquals(setOf("sh600519", "sz000001"), merged.keys)
    }

    @Test
    fun mergeKeepsMemoryWhenIncomingEmpty() {
        val memory = mapOf("sh600519" to quote("sh600519", "2026-10-22"))
        assertTrue(QuoteCache.mergeNewer(memory, emptyMap()) === memory)
    }

    @Test
    fun undatedResponseCannotOverwriteDatedQuote() {
        val merged = QuoteCache.mergeNewer(
            mapOf("sh600519" to quote("sh600519", "2026-10-16")),
            mapOf("sh600519" to quote("sh600519", null)),
        )
        assertEquals("2026-10-16", merged["sh600519"]?.quoteDate)
    }

    @Test
    fun decodeRoundTripPreservesQuoteFields() {
        val original = QuoteCache.Snapshot(
            savedAt = "2026-10-16 15:00",
            quotes = mapOf(
                "sh600519" to quote("sh600519", "2026-10-16", price = 1501.23, name = "贵州茅台"),
                "sz000001" to quote("sz000001", null, name = "平安银行"),
            ),
        )
        val decoded = QuoteCache.decode(QuoteCache.encode(original))!!
        assertEquals("2026-10-16 15:00", decoded.savedAt)
        assertEquals(2, decoded.quotes.size)
        decoded.quotes.getValue("sh600519").let {
            assertEquals("贵州茅台", it.name)
            assertEquals(1501.23, it.price, 1e-9)
            assertEquals(9.0, it.prevClose, 1e-9)
            assertEquals("2026-10-16", it.quoteDate)
        }
        assertEquals(null, decoded.quotes.getValue("sz000001").quoteDate)
    }

    @Test
    fun decodeRejectsJunk() {
        assertNull(QuoteCache.decode(null))
        assertNull(QuoteCache.decode(""))
        assertNull(QuoteCache.decode("not json"))
        assertNull(QuoteCache.decode("{}"))
    }
}
