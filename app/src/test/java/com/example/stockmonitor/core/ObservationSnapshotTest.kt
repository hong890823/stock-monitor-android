package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationSnapshotTest {
    private fun snapshot(code: String, date: String, score: Int) = ObservationSnapshot(
        code = code,
        name = "测试股",
        date = date,
        stage = ObservationStage.OBSERVING,
        strategy = ObservationStrategy.PRE_BREAKOUT,
        score = score,
        price = 10.0 + score,
        changePct = score.toDouble(),
        weakDays = 0,
        observedDays = score,
        stopPrice = 9.0,
        reason = "测试",
        evidence = ObservationEvidence(
            supporting = listOf("趋势确认"),
            counter = listOf("高位分歧"),
            missing = listOf("量比"),
            invalidators = listOf("跌破止损"),
        ),
    )

    @Test
    fun appendingSameTradingDayReplacesPreviousSnapshot() {
        val first = snapshot("sh600000", "2026-10-14", 1)
        val replacement = snapshot("sh600000", "2026-10-14", 3)

        val result = ObservationSnapshotHistory.append(listOf(first), replacement)

        assertEquals(1, result.size)
        assertEquals(3, result.single().score)
    }

    @Test
    fun historyKeepsTheLatestThirtyTradingDaysPerStock() {
        val history = (1..31).map { day ->
            snapshot("sh600000", "2026-09-${String.format("%02d", day)}", day)
        }

        val result = history.dropLast(1).fold(emptyList<ObservationSnapshot>()) { acc, item ->
            ObservationSnapshotHistory.append(acc, item)
        }.let { ObservationSnapshotHistory.append(it, history.last()) }

        assertEquals(30, result.size)
        assertTrue(result.none { it.date == "2026-09-01" })
        assertEquals("2026-09-31", result.last().date)
    }

    @Test
    fun jsonRoundTripPreservesSnapshotFields() {
        val source = listOf(snapshot("sz000001", "2026-10-14", 2))

        val decoded = ObservationSnapshotJson.decode(ObservationSnapshotJson.encode(source))

        assertEquals(source, decoded)
    }
}
