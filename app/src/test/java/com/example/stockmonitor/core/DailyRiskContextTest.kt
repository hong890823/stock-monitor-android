package com.example.stockmonitor.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRiskContextTest {
    private fun input(date: String = "2026-10-23") = DailyRiskContextInput(
        quoteDate = date,
        priceLocation = "MID",
        rangeLow = 18.0,
        invalidationPrice = 17.5,
        majorTrend = "UP",
        fiveDayNoNewLow = true,
        closesBelowMa20 = 0,
        highExitRisk = false,
        systemicRisk = false,
        portfolioRisk = false,
        eventRisk = false,
        positionRisk = false,
        liquidityRisk = false,
    )

    @Test
    fun cacheIsKeyedByCodeAndQuoteTradingDate() {
        val cache = DailyRiskContextCache()
        var builds = 0
        val builder = { builds++; input() }

        val first = cache.getOrBuild("sz000001", "2026-10-23", builder)!!
        val second = cache.getOrBuild("sz000001", "2026-10-23", builder)!!

        assertEquals(first, second)
        assertEquals(1, builds)
        cache.getOrBuild("sz000001", "2026-10-24") { builds++; input("2026-10-24") }
        assertEquals(2, builds)
    }

    @Test
    fun invalidOrMissingQuoteDateCannotCreateCachedContext() {
        val cache = DailyRiskContextCache()
        assertNull(cache.getOrBuild("sz000001", "") { input() })
        assertNull(cache.getOrBuild("sz000001", "2026/10/23") { input() })
    }

    @Test
    fun cacheRejectsStaleAnalysisInsteadOfRelabelingItAsToday() {
        val cache = DailyRiskContextCache()

        assertNull(
            cache.getOrBuild("sz000001", "2026-10-23") {
                input("2026-10-22")
            },
        )
    }

    @Test
    fun cachedDailyMetricsCanRefreshDynamicRiskFlags() {
        val cache = DailyRiskContextCache()
        val cached = cache.getOrBuild("sz000001", "2026-10-23") { input() }!!
        val current = cached.withCurrentInput(input().copy(eventRisk = true))

        assertEquals(DailyRiskLevel.NORMAL, cached.riskLevel(19.0))
        assertEquals(DailyRiskLevel.CRITICAL, current.riskLevel(19.0))
    }

    @Test
    fun dailyMetricsUseFiveCompletedDaysAndTrailingMa20Closes() {
        val bars = (0 until 30).map { index ->
            val close = if (index < 22) 10.0 else 9.0
            DayBar(
                date = LocalDate.of(2026, 9, 1).plusDays(index.toLong()).toString(),
                open = close,
                high = close + 0.2,
                low = close - when (index) {
                    25 -> 0.6
                    29 -> 0.1
                    else -> 0.4
                },
                close = close,
                volume = 100.0,
            )
        }

        val metrics = DailyRiskMetrics.fromBars(bars, bars.last().date)

        assertEquals(false, metrics.fiveDayNoNewLow)
        assertEquals(8, metrics.closesBelowMa20)
    }

    @Test
    fun dailyMetricsStayUnknownWhenHistoryIsInsufficient() {
        val metrics = DailyRiskMetrics.fromBars(
            listOf(DayBar("2026-10-23", 10.0, 10.1, 9.9, 10.0, 100.0)),
            "2026-10-23",
        )

        assertNull(metrics.fiveDayNoNewLow)
        assertEquals(0, metrics.closesBelowMa20)
    }

    @Test
    fun historicalFlagsEscalateRiskLevel() {
        assertEquals(DailyRiskLevel.NORMAL, input().toContext().riskLevel(19.0))
        assertEquals(
            DailyRiskLevel.ELEVATED,
            input().copy(priceLocation = "LOW").toContext().riskLevel(19.0),
        )
        assertEquals(
            DailyRiskLevel.CRITICAL,
            input().copy(eventRisk = true).toContext().riskLevel(19.0),
        )
        assertEquals(
            DailyRiskLevel.ELEVATED,
            input().copy(positionRisk = true).toContext().riskLevel(19.0),
        )
        assertEquals(
            DailyRiskLevel.CRITICAL,
            input().copy(liquidityRisk = true).toContext().riskLevel(19.0),
        )
        assertEquals(
            DailyRiskLevel.CRITICAL,
            input().copy(fiveDayNoNewLow = false, majorTrend = "DOWN").toContext().riskLevel(19.0),
        )
        assertTrue(input().toContext().riskLevel(17.0) == DailyRiskLevel.CRITICAL)
    }
}
