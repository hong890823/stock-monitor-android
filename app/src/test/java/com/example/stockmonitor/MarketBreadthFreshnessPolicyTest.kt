package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketBreadthFreshnessPolicy
import com.example.stockmonitor.analysis.MarketBreadthSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketBreadthFreshnessPolicyTest {
    private val now = 1_760_000_000_000L

    @Test
    fun freshBreadthReturnsObservedRisingRatio() {
        val result = MarketBreadthFreshnessPolicy.freshRatio(
            MarketBreadthSnapshot(rising = 2, total = 4, updatedAtMs = now - 1_000L),
            nowMs = now,
        )

        assertEquals(0.5, result!!, 0.0)
    }

    @Test
    fun staleBreadthReturnsMissingInsteadOfOldRatio() {
        val result = MarketBreadthFreshnessPolicy.freshRatio(
            MarketBreadthSnapshot(rising = 4, total = 4, updatedAtMs = now - (35 * 60_000L + 1L)),
            nowMs = now,
        )

        assertNull(result)
    }

    @Test
    fun futureOrInvalidBreadthIsNotAccepted() {
        assertNull(
            MarketBreadthFreshnessPolicy.freshRatio(
                MarketBreadthSnapshot(rising = 1, total = 2, updatedAtMs = now + 1_000L),
                nowMs = now,
            ),
        )
        assertNull(
            MarketBreadthFreshnessPolicy.freshRatio(
                MarketBreadthSnapshot(rising = 1, total = 0, updatedAtMs = now),
                nowMs = now,
            ),
        )
    }
}
