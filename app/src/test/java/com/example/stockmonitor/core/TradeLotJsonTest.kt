package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /** 回归：成本为 0 的批次以前读档时被静默丢掉，变成「未记录日期」残股被 T+1 一直锁定。 */
    @Test
    fun zeroCostLotSurvivesRoundTrip() {
        val book = TradeBook(lots = listOf(TradeLot("sz000001", "测试股", 300.0, 0.0, "2026-10-21")))

        val decoded = TradeLotJson.decode(TradeLotJson.encode(book))

        assertEquals(1, decoded.lots.size)
        assertEquals(0.0, decoded.lots.single().unitCost, 0.0)
        assertEquals(300.0, decoded.lots.single().quantity, 0.001)
        assertEquals("2026-10-21", decoded.lots.single().buyDate)
    }

    @Test
    fun negativeCostLotIsDropped() {
        val book = TradeBook(lots = listOf(TradeLot("sz000001", "测试股", 300.0, -1.0, "2026-10-21")))

        val decoded = TradeLotJson.decode(TradeLotJson.encode(book))

        assertTrue(decoded.lots.isEmpty())
    }
}
