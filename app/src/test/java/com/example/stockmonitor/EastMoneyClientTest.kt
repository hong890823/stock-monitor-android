package com.example.stockmonitor

import com.example.stockmonitor.core.EastMoneyClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EastMoneyClientTest {

    @Test
    fun parsesNorthboundDailyRowsAndKeepsDateOrder() {
        val rows = (1..20).joinToString(",", prefix = "[", postfix = "]") { day ->
            "{\"date\":\"2026-10-${day.toString().padStart(2, '0')}\",\"net\":${day * 10_000.0}}"
        }
        val json = "{\"data\":{\"northbound\":$rows}}"

        val result = EastMoneyClient.parseNorthboundDailyFlow(json)

        assertEquals(20, result.size)
        assertEquals("2026-10-20", result.last().date)
        assertEquals(20.0, result.last().netBillion, 0.0)
    }

    @Test
    fun parsesKlineLikeNorthboundRows() {
        val json = """
            {"data":{"klines":["2026-10-18,-25000","2026-10-19,30000"]}}
        """.trimIndent()

        val result = EastMoneyClient.parseNorthboundDailyFlow(json)

        assertEquals(2, result.size)
        assertEquals(-2.5, result.first().netBillion, 0.0)
    }

    @Test
    fun parsesEastMoneyFieldStyleNorthboundRows() {
        val json = """
            {"data":{"hk2sh":[{"f51":"2026-10-18","f52":"-25000"},{"f51":"2026-10-19","f52":"30000"}]}}
        """.trimIndent()

        val result = EastMoneyClient.parseNorthboundDailyFlow(json)

        assertEquals(2, result.size)
        assertEquals("2026-10-19", result.last().date)
        assertEquals(3.0, result.last().netBillion, 0.0)
    }

    @Test
    fun detectsLiveEastMoneyFrozenNorthboundFeedAsUnavailable() {
        val json = """
            {"data":{
              "hk2sh":[
                "2026-10-08,0.00,5200000.00,273757367.45",
                "2026-10-09,0.00,5200000.00,273757367.45",
                "2026-10-12,0.00,5200000.00,273757367.45",
                "2026-10-13,0.00,5200000.00,273757367.45",
                "2026-10-14,0.00,5200000.00,273757367.45"
              ],
              "hk2sz":[
                "2026-10-08,0.00,5200000.00,251431468.29",
                "2026-10-09,0.00,5200000.00,251431468.29",
                "2026-10-12,0.00,5200000.00,251431468.29",
                "2026-10-13,0.00,5200000.00,251431468.29",
                "2026-10-14,0.00,5200000.00,251431468.29"
              ]
            }}
        """.trimIndent()

        val points = EastMoneyClient.parseNorthboundDailyFlow(json)

        assertEquals(5, points.size)
        assertTrue(EastMoneyClient.isNorthboundFeedDead(points))
    }

    @Test
    fun malformedNorthboundDataReturnsEmpty() {
        assertTrue(EastMoneyClient.parseNorthboundDailyFlow("{}").isEmpty())
        assertTrue(EastMoneyClient.parseNorthboundDailyFlow("{\"data\":{\"northbound\":[{\"date\":\"2026-10-20\"}]}}").isEmpty())
    }
    @Test
    fun parsesIndustryBoardMainFlowFields() {
        val json = """
            {"data":{"diff":[
              {"f12":"BK0438","f14":"半导体","f62":"1250000000","f184":"3.25"},
              {"f12":"BK0001","f14":"银行","f62":"-800000000","f184":"-1.40"}
            ]}}
        """.trimIndent()

        val result = EastMoneyClient.parseIndustryBoardFlows(json)

        assertEquals(2, result.size)
        assertEquals(1_250_000_000.0, result["BK0438"]?.mainNetInflow!!, 0.0)
        assertEquals(3.25, result["BK0438"]?.mainNetRatio!!, 0.0)
        assertEquals(-1.40, result["BK0001"]?.mainNetRatio!!, 0.0)
    }
}
