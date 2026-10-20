package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationEvidence
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationEvidenceTest {
    @Test
    fun preBreakoutEvidenceSeparatesSupportAndInvalidation() {
        val evidence = ObservationEvidenceFactory.from(
            ObservationSignal(
                date = "2026-10-14",
                price = 10.0,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.5,
                changePct = 2.0,
                strategy = ObservationStrategy.PRE_BREAKOUT,
                setupScore = 3,
                relativeStrengthPct = 4.0,
                volumeRatio = 1.6,
            ),
        )

        assertTrue(evidence.supporting.any { it.contains("选股信号") })
        assertTrue(evidence.supporting.any { it.contains("相对大盘") })
        assertTrue(evidence.counter.isEmpty())
        assertTrue(evidence.missing.isEmpty())
        assertTrue(evidence.invalidators.any { it.contains("止损") })
    }

    @Test
    fun leaderEvidenceMakesRiskAndMissingDataExplicit() {
        val evidence = ObservationEvidenceFactory.from(
            ObservationSignal(
                date = "2026-10-14",
                price = 10.0,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = false,
                trendWeak = true,
                stopPrice = null,
                changePct = -3.5,
                strategy = ObservationStrategy.LEADER,
                strongScore = 1,
                recentLimitUpDays = 2,
                relativeStrengthPct = null,
                volumeRatio = null,
                riskFlag = true,
            ),
        )

        assertTrue(evidence.supporting.any { it.contains("涨停") })
        assertTrue(evidence.counter.any { it.contains("走弱") || it.contains("分歧") })
        assertTrue(evidence.missing.any { it.contains("相对大盘") })
        assertTrue(evidence.missing.any { it.contains("量比") })
        assertTrue(evidence.invalidators.any { it.contains("3个交易日") })
    }

    @Test
    fun limitUpEvidenceExplainsWhyBuyIsDeferred() {
        val evidence = ObservationEvidenceFactory.from(
            ObservationSignal(
                date = "2026-10-14",
                price = 10.0,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = false,
                trendWeak = false,
                stopPrice = 9.5,
                changePct = 10.0,
                strategy = ObservationStrategy.LEADER,
                strongScore = 3,
                recentLimitUpDays = 1,
                relativeStrengthPct = 5.0,
                volumeRatio = 1.5,
                limitUpBlocked = true,
            ),
        )

        assertTrue(evidence.counter.any { it.contains("涨停封板") })
    }
}
