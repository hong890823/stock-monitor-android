package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstBoardReclaimObservationTest {

    private fun record(
        code: String = "sh600000",
        day: String = "2026-11-23",
        detectedAt: String = "2026-11-23 10:05",
        price: Double = 10.02,
    ) = FirstBoardReclaimObservation(
        code = code,
        name = "测试股",
        tradingDay = day,
        detectedAt = detectedAt,
        firstBoardDate = "2026-11-20",
        tradingDaysAfterBoard = 2,
        previousClose = 10.0,
        open = 9.8,
        low = 9.7,
        price = price,
        biasFromMa5Pct = 3.5,
        fundFlowEvidence = "当日主力+2.1%｜近5分钟+3.4%",
        fundFlowReason = "近5/15分钟大单净流入，反弹有承接",
        boards = listOf("银行", "中特估"),
    )

    @Test
    fun roundTripKeepsAllFields() {
        val decoded = FirstBoardReclaimObservationBook.decode(
            FirstBoardReclaimObservationBook.encode(listOf(record())),
        ).single()

        assertEquals("sh600000", decoded.code)
        assertEquals("测试股", decoded.name)
        assertEquals("2026-11-23", decoded.tradingDay)
        assertEquals("2026-11-23 10:05", decoded.detectedAt)
        assertEquals("2026-11-20", decoded.firstBoardDate)
        assertEquals(2, decoded.tradingDaysAfterBoard)
        assertEquals(3.5, decoded.biasFromMa5Pct!!, 0.0001)
        assertEquals(listOf("银行", "中特估"), decoded.boards)
    }

    @Test
    fun missingTradingDayOffsetDecodesAsNullInsteadOfZero() {
        val raw = FirstBoardReclaimObservationBook.encode(
            listOf(record().copy(tradingDaysAfterBoard = null)),
        )

        assertEquals(null, FirstBoardReclaimObservationBook.decode(raw).single().tradingDaysAfterBoard)
    }

    @Test
    fun sameDaySameCodeKeepsOnlyTheLatestHit() {
        val old = record(price = 10.02)
        val refreshed = record(detectedAt = "2026-11-23 13:20", price = 10.28)

        val merged = FirstBoardReclaimObservationBook.append(
            listOf(old),
            refreshed,
            today = "2026-11-23",
        )

        assertEquals(1, merged.size)
        assertEquals(10.28, merged.single().price, 0.0001)
        assertEquals("2026-11-23 13:20", merged.single().detectedAt)
    }

    @Test
    fun prunesRecordsOlderThanRetentionWindow() {
        val stale = record(day = "2026-11-01", detectedAt = "2026-11-01 10:00")
        val fresh = record(day = "2026-11-23")

        val merged = FirstBoardReclaimObservationBook.append(
            listOf(stale),
            fresh,
            today = "2026-11-23",
        )

        assertEquals(listOf("2026-11-23"), merged.map { it.tradingDay })
    }

    @Test
    fun corruptEntryDoesNotWipeTheWholeBook() {
        val valid = FirstBoardReclaimObservationBook.encode(listOf(record()))
        val raw = valid.dropLast(1) + ",\"broken\"]"

        val decoded = FirstBoardReclaimObservationBook.decode(raw)

        assertTrue(decoded.size == 1)
        assertEquals("sh600000", decoded.single().code)
    }
}