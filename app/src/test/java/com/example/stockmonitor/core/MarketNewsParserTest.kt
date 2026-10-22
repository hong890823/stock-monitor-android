package com.example.stockmonitor.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketNewsParserTest {

    @Test
    fun eastMoneyParserPreservesActualSourceAndTimestamp() {
        val items = MarketNewsClient.parseEastMoney(
            """
            callback({"data":{"list":[
              {"title":"政策标题","showtime":"2026-10-22 10:25:00","url":"//finance.eastmoney.com/a/policy.html","media":"财联社"}
            ]}})
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        assertEquals("政策标题", items[0].title)
        assertEquals("财联社", items[0].source)
        assertEquals("https://finance.eastmoney.com/a/policy.html", items[0].url)
        assertEquals(timestamp("2026-10-22 10:25:00"), items[0].publishedAtMs)
    }

    @Test
    fun sinaParserPreservesSourceAndEpochTimestamp() {
        val items = MarketNewsClient.parseSina(
            """
            {"result":{"data":[{"title":"市场快讯","ctime":1761099900,"media_name":"新浪财经","url":"https://finance.sina.com.cn/news.html"}]}}
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        assertEquals("市场快讯", items[0].title)
        assertEquals("新浪财经", items[0].source)
        assertEquals(1761099900000L, items[0].publishedAtMs)
    }

    @Test
    fun malformedRowsAreIgnoredAndMissingUrlStaysNull() {
        val items = MarketNewsClient.parseSina(
            """
            {"result":{"data":[
              {"title":"","url":"https://example.com/empty"},
              {"title":"有效","ctime":1761099900,"media_name":"新浪财经"}
            ]}}
            """.trimIndent(),
        )

        assertEquals(listOf("有效"), items.map { it.title })
        assertNull(items.single().url)
    }

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .parse(value)!!.time
}
