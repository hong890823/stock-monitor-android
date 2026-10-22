package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.example.stockmonitor.core.ObservationPoolJson
import org.junit.Test

class ObservationPoolStrategyTest {
    private val entry = ObservationSignal(
        "2026-10-15", 10.2, true, true, true, false, 9.5, 1.0,
        strategy = ObservationStrategy.LEADER, strongScore = 3,
        evaluatedAtMs = 1_000_000L, quoteVolume = 1000.0,
    )

    @Test
    fun leaderNeedsTwoSpacedFreshEvaluationsAndNotifiesOnlyOnTransition() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        assertEquals(ObservationStage.NEAR_ENTRY, first.stage)
        val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(first))).single()
        assertEquals(first, restored)
        val second = ObservationPool.evaluate(restored, entry.copy(evaluatedAtMs = 1_900_000L))
        assertEquals(ObservationStage.BUY_CANDIDATE, second.stage)
        assertTrue(ObservationBuyAlert.shouldNotify(first, second))
        assertFalse(ObservationBuyAlert.shouldNotify(second, second))
    }

    @Test
    fun cumulativeVolumeAloneDoesNotCompleteConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val repeated = ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_060_000L, quoteVolume = 1100.0))
        assertEquals(ObservationStage.NEAR_ENTRY, repeated.stage)
        assertEquals(1, repeated.entryConfirmationRounds)

        val freshWithoutVolumeChange = ObservationPool.evaluate(
            first,
            entry.copy(evaluatedAtMs = 1_900_000L, quoteVolume = 1000.0),
        )
        assertEquals(ObservationStage.BUY_CANDIDATE, freshWithoutVolumeChange.stage)
    }

    @Test
    fun newDayDoesNotCompleteConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val nextDay = ObservationPool.evaluate(
            first,
            entry.copy(date = "2026-10-16", evaluatedAtMs = 1_900_000L),
        )
        assertEquals(ObservationStage.NEAR_ENTRY, nextDay.stage)
        assertEquals(1, nextDay.entryConfirmationRounds)
    }

    @Test
    fun threeBoardCandidateRequiresThreeSpacedRounds() {
        val highBoard = candidate(ObservationStrategy.LIMIT_UP_CONTINUATION).copy(
            continuousObservation = true,
            limitUpBlocked = true,
            limitStreak = 3,
        )
        val opened = entry.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            evaluatedAtMs = 1_000_000L,
        )
        val first = ObservationPool.evaluate(highBoard, opened)
        val second = ObservationPool.evaluate(first, opened.copy(evaluatedAtMs = 1_900_000L))
        val third = ObservationPool.evaluate(second, opened.copy(evaluatedAtMs = 2_800_000L))

        assertEquals(1, first.entryConfirmationRounds)
        assertEquals(2, second.entryConfirmationRounds)
        assertEquals(ObservationStage.NEAR_ENTRY, second.stage)
        assertEquals(ObservationStage.BUY_CANDIDATE, third.stage)
    }

    @Test
    fun riskAndLimitGateResetPendingConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val blocked = ObservationEligibility.applyQuoteGate(first.copy(limitUpBlocked = true), null)
        assertEquals(0, blocked.entryConfirmationRounds)
        val risk = ObservationPool.evaluate(first, entry.copy(riskFlag = true))
        assertEquals(0, risk.entryConfirmationRounds)
        val restarted = ObservationPool.evaluate(risk, entry.copy(evaluatedAtMs = 1_900_000L))
        assertEquals(ObservationStage.NEAR_ENTRY, restarted.stage)
    }

    private fun candidate(strategy: ObservationStrategy) = ObservationCandidate(
        code = "sh600000",
        name = "浦发银行",
        addedDate = "2026-10-14",
        lastEvaluatedDate = null,
        stage = ObservationStage.OBSERVING,
        score = 0,
        weakDays = 0,
        strategy = strategy,
    )

    @Test
    fun preBreakoutUsesTrendConfirmation() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.PRE_BREAKOUT),
            ObservationSignal(
                date = "2026-10-14",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 2.0,
                setupScore = 3,
                signalReason = "趋势突破后守住",
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, result.stage)
        assertEquals("启动前潜力", result.strategy.label)
    }

    @Test
    fun leaderNeedsStrengthAndNoRisk() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            ObservationSignal(
                date = "2026-10-14",
                price = 12.0,
                hasScreenSignal = false,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 11.2,
                changePct = 4.0,
                strongScore = 3,
                recentLimitUpDays = 2,
                relativeStrengthPct = 5.5,
                volumeRatio = 1.6,
                signalReason = "连板后强势承接",
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
        assertEquals(3, result.score)
    }

    @Test
    fun leaderRiskCannotBecomeBuyCandidate() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            ObservationSignal(
                date = "2026-10-14",
                price = 11.0,
                hasScreenSignal = false,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = null,
                changePct = 8.8,
                strongScore = 3,
                recentLimitUpDays = 3,
                relativeStrengthPct = 9.0,
                volumeRatio = 2.8,
                riskFlag = true,
                signalReason = "高位放量分歧",
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
    }
}
