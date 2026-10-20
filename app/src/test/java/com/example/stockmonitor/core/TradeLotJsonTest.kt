package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TradeLotJsonTest {
    private val record = TradeRecord(
        id = "trade-1",
        code = "sz000001",
        name = "测试股",
        side = TradeSide.BUY,
        date = "2026-10-20",
        quantity = 100.0,
        price = 12.34,
        source = TradeSource.MANUAL,
    )

    @Test
    fun blankPayloadDecodesToEmptyBook() {
        assertEquals(TradeBook(), TradeLotJson.decode(null))
        assertEquals(TradeBook(), TradeLotJson.decode("   "))
    }

    @Test
    fun bookRoundTripsEntriesAndOpenLots() {
        val book = TradeBook(
            entries = listOf(record),
            lots = listOf(TradeLot("sz000001", "测试股", 100.0, 12.34, "2026-10-20")),
        )

        val decoded = TradeLotJson.decode(TradeLotJson.encode(book))

        assertEquals(book, decoded)
    }

    @Test
    fun decodingSkipsInvalidRowsButKeepsValidRows() {
        val payload = """
            {
              "entries":[
                {"id":"","code":"sz000001","name":"坏记录","side":"BUY","date":"2026-10-20","quantity":0,"price":1,"source":"MANUAL"},
                {"id":"trade-1","code":"sz000001","name":"测试股","side":"BUY","date":"2026-10-20","quantity":100,"price":12.34,"source":"MANUAL"}
              ],
              "lots":[
                {"code":"sz000001","name":"坏批次","quantity":-1,"unit_cost":1,"buy_date":"2026-10-20"},
                {"code":"sz000001","name":"测试股","quantity":100,"unit_cost":12.34,"buy_date":"2026-10-20"}
              ]
            }
        """.trimIndent()

        val decoded = TradeLotJson.decode(payload)

        assertEquals(listOf(record), decoded.entries)
        assertEquals(
            listOf(TradeLot("sz000001", "测试股", 100.0, 12.34, "2026-10-20")),
            decoded.lots,
        )
    }

    @Test
    fun malformedTopLevelJsonIsRejectedInsteadOfBecomingLegacyShares() {
        assertThrows(IllegalArgumentException::class.java) {
            TradeLotJson.decode("{not-json")
        }
    }
}
