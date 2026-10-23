package com.example.stockmonitor.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeframeEvidenceTest {
    private fun bar(hour: Int, minute: Int, close: Double, volume: Double = 100.0) = IntradayBar(
        timestamp = "2026-10-23 %02d:%02d:00".format(hour, minute),
        open = close,
        high = close + 0.2,
        low = close - 0.2,
        close = close,
        volume = volume,
    )

    @Test
    fun onlyLatestCompletedSlotsAreFreshAndCurrentBarsAreIgnored() {
        val completed = listOf(bar(9, 45, 10.0), bar(10, 0, 10.1), bar(10, 15, 10.2))
        val current = bar(10, 30, 12.0, 1000.0)
        val complete = TimeframeEvidence.build(
            bars15 = completed + current,
            bars30 = listOf(bar(9, 30, 10.0), bar(10, 0, 10.1)),
            price = 10.2,
            support = 9.8,
            resistance = 10.3,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
        )
        val stale = TimeframeEvidence.build(
            bars15 = listOf(bar(9, 45, 10.0), bar(10, 0, 10.1)),
            bars30 = listOf(bar(9, 30, 10.0), bar(10, 0, 10.1)),
            price = 10.2,
            support = 9.8,
            resistance = 10.3,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
        )

        assertTrue(complete.complete)
        assertEquals("2026-10-23 10:15:00", complete.lastCompleted15m)
        assertFalse(stale.complete)
    }

    @Test
    fun riskThresholdUsesHalfAtr15AndClampsToSafeBand() {
        val bars = listOf(
            bar(9, 0, 99.0),
            bar(9, 15, 99.0),
            bar(9, 30, 99.0),
            bar(9, 45, 99.0),
            bar(10, 0, 99.0),
            bar(10, 15, 99.0),
            bar(10, 30, 99.0),
            bar(10, 45, 99.0),
            bar(11, 0, 99.0),
            bar(11, 15, 99.0),
            bar(11, 30, 99.0),
            bar(13, 15, 99.0),
            bar(13, 30, 99.0),
            bar(13, 45, 99.0),
        )
        val evidence = TimeframeEvidence.build(
            bars15 = bars,
            bars30 = listOf(bar(9, 0, 100.0), bar(9, 30, 100.0)),
            price = 98.8,
            support = 100.0,
            resistance = 102.0,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
        )

        assertEquals(0.005, evidence.riskThresholdPct, 0.00001)
        assertTrue(evidence.riskConfirmed)
        assertTrue(evidence.riskConfirmations in 1..3)
    }

    @Test
    fun trailingRiskConfirmationIsCappedAtThree() {
        val bars = listOf(
            bar(9, 0, 99.0),
            bar(9, 15, 99.0),
            bar(9, 30, 99.0),
            bar(9, 45, 99.0),
            bar(10, 0, 99.0),
            bar(10, 15, 99.0),
        )
        val evidence = TimeframeEvidence.build(
            bars15 = bars,
            bars30 = listOf(bar(9, 30, 99.0), bar(10, 0, 99.0)),
            price = 98.8,
            support = 100.0,
            resistance = 102.0,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
            dailyContext = DailyRiskContextInput().toContext(),
        )

        assertEquals(3, evidence.riskConfirmations)
    }

    @Test
    fun dailyContextDateMayIncludeTimestampWithoutDroppingDynamicRisk() {
        val evidence = TimeframeEvidence.build(
            bars15 = listOf(bar(9, 45, 9.0)),
            bars30 = listOf(bar(9, 30, 9.0)),
            price = 9.0,
            support = 10.0,
            resistance = 11.0,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
            dailyContext = DailyRiskContextInput(
                quoteDate = "2026-10-23T15:00:00",
                invalidationPrice = 9.5,
            ).toContext(),
        )

        assertEquals(DailyRiskLevel.CRITICAL, evidence.riskLevel)
    }

    @Test
    fun unfinishedBarCannotConfirmEvenWhenItsTimestampLooksHistorical() {
        val evidence = TimeframeEvidence.build(
            bars15 = listOf(
                bar(9, 45, 10.0),
                bar(10, 0, 10.1).copy(completed = false),
            ),
            bars30 = listOf(bar(9, 30, 10.0), bar(10, 0, 10.1)),
            price = 10.1,
            support = 9.8,
            resistance = 10.3,
            now = LocalDateTime.parse("2026-10-23T10:18:00"),
            quoteDate = "2026-10-23",
        )

        assertFalse(evidence.complete)
        assertFalse(evidence.riskComplete)
    }
}
