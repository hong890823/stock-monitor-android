package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FundFlowHistoryJsonTest {

    @Test
    fun roundTripsDailySnapshotsAndKeepsOnlyFiveTradingDays() {
        val flows = (1..6).map { day ->
            FundFlow(
                code = "sh600000",
                name = "浦发银行",
                asOfMs = day.toLong(),
                amountYuan = 10_000_000.0,
                mainNetPct = day.toDouble(),
                tradingDate = "2026-10-%02d".format(day),
            )
        }
        val encoded = FundFlowHistoryJson.encode(mapOf("sh600000" to flows))

        val restored = FundFlowHistoryJson.decode(encoded).getValue("sh600000")

        assertEquals(5, restored.size)
        assertEquals("2026-10-02", restored.first().tradingDate)
        assertEquals("2026-10-06", restored.last().tradingDate)
    }

    @Test
    fun sameTradingDateReplacesEarlierSnapshot() {
        val oldFlow = flow(asOfMs = 1L, netPct = 1.0)
        val newFlow = flow(asOfMs = 2L, netPct = 2.0)

        val merged = FundFlowHistoryJson.withSnapshot(
            mapOf("sh600000" to listOf(oldFlow)),
            mapOf("sh600000" to newFlow),
        ).getValue("sh600000")

        assertEquals(listOf(newFlow), merged)
    }

    private fun flow(asOfMs: Long, netPct: Double) = FundFlow(
        code = "sh600000",
        name = "浦发银行",
        asOfMs = asOfMs,
        amountYuan = 10_000_000.0,
        mainNetPct = netPct,
        tradingDate = "2026-10-22",
    )
}
