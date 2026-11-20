package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationPoolJsonTest {

    private fun candidate(firstBoardDate: String?) = ObservationCandidate(
        code = "sh600000",
        name = "测试",
        addedDate = "2026-11-10",
        lastEvaluatedDate = "2026-11-18",
        stage = ObservationStage.OBSERVING,
        score = 1,
        weakDays = 0,
        limitStreak = 1,
        firstBoardDate = firstBoardDate,
    )

    @Test
    fun firstBoardDateRoundTrips() {
        val decoded = ObservationPoolJson.decode(
            ObservationPoolJson.encode(listOf(candidate("2026-11-18"))),
        ).single()

        assertEquals("2026-11-18", decoded.firstBoardDate)
    }

    @Test
    fun missingFirstBoardDateDecodesAsNullForOldData() {
        val decoded = ObservationPoolJson.decode(
            ObservationPoolJson.encode(listOf(candidate(null))),
        ).single()

        assertEquals(null, decoded.firstBoardDate)
    }

    @Test
    fun oneCorruptEntryDoesNotWipeTheWholePool() {
        val valid = ObservationPoolJson.encode(listOf(candidate("2026-11-18")))
        // 在合法数组末尾塞一条非对象数据，模拟单条损坏。
        val raw = valid.dropLast(1) + ",\"broken\"]"

        val decoded = ObservationPoolJson.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals("sh600000", decoded.single().code)
    }
}