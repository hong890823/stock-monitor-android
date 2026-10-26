package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationPoolJson
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.Quote
import org.junit.Assert.*
import org.junit.Test

class ObservationEligibilityTest {
    private val candidate = ObservationCandidate(
        "sh600000", "测试", "2026-10-13", "2026-10-14",
        ObservationStage.BUY_CANDIDATE, 3, 0, observedDays = 2,
    )
    private val sealed = Quote(candidate.code, candidate.name, 11.0, 10.0, 10.2,
        11.0, 10.1, 1000.0, 10000.0, "2026-10-14")

    @Test
    fun ordinaryObservingCandidateNeedsIntradayRefreshToo() {
        assertTrue(ObservationEligibility.needsRefresh(
            candidate.copy(stage = ObservationStage.OBSERVING), "2026-10-14", sealed.copy(price = 10.2),
        ))
    }

    @Test
    fun staleOrInvalidQuotesCannotAdvanceObservation() {
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-15", sealed))
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-14", sealed.copy(price = Double.NaN)))
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-14", null))
        assertFalse(ObservationEligibility.needsRefresh(
            candidate.copy(stage = ObservationStage.ELIMINATED), "2026-10-14", sealed,
        ))
    }

    @Test
    fun legacyLeaderBuyCandidateMustBeReconfirmed() {
        val legacy = candidate.copy(strategy = ObservationStrategy.LEADER)
        val displayed = ObservationEligibility.forDisplay(listOf(legacy), emptyMap()).single()
        assertEquals(ObservationStage.NEAR_ENTRY, displayed.stage)
    }

    @Test
    fun legacyThreeBoardBuyCandidateNeedsThirdRound() {
        val legacy = candidate.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            entryConfirmationRounds = 2,
        )
        val displayed = ObservationEligibility.applyQuoteGate(legacy, sealed.copy(price = 10.5))

        assertEquals(ObservationStage.NEAR_ENTRY, displayed.stage)
    }

    @Test
    fun freshNewDayQuoteDemotesConfirmedBuyUntilNextEvaluation() {
        ObservationStrategy.entries.forEach { strategy ->
            val old = candidate.copy(strategy = strategy, entryConfirmationRounds = 2)
            val quote = sealed.copy(price = 10.2, quoteDate = "2026-10-15")
            val display = ObservationEligibility.applyQuoteGate(old, quote)
            assertEquals(ObservationStage.NEAR_ENTRY, display.stage)
            assertEquals(0, display.entryConfirmationRounds)
            assertEquals(ObservationStage.BUY_CANDIDATE, old.stage)
        }
    }

    @Test
    fun everyStageHasIndependentPagesAndFullCounts() {
        assertEquals(8, ObservationEligibility.PAGE_SIZE)
        val buys = (1..105).map { candidate.copy(code = "sh${600000 + it}") }
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val sections = ObservationEligibility.sections(buys + near, emptyMap())
        assertEquals(105, sections.getValue(ObservationSection.BUY).size)
        assertEquals(listOf(near), sections.getValue(ObservationSection.NEAR))
        val lastPage = (buys.size - 1) / ObservationEligibility.PAGE_SIZE
        val pages = (0..lastPage).flatMap {
            ObservationEligibility.page(sections.getValue(ObservationSection.BUY), it)
        }
        assertEquals(buys, pages)
        assertEquals(listOf(near), ObservationEligibility.page(sections.getValue(ObservationSection.NEAR), 0))
    }

    @Test
    fun candidatesWithinTheSameSectionSortByScoreDescending() {
        val lowScore = candidate.copy(code = "sz000011", stage = ObservationStage.NEAR_ENTRY, score = 1)
        val highScore = candidate.copy(code = "sz000012", stage = ObservationStage.NEAR_ENTRY, score = 3)
        val middleScore = candidate.copy(code = "sz000013", stage = ObservationStage.NEAR_ENTRY, score = 2)

        val near = ObservationEligibility
            .entrySections(listOf(lowScore, highScore, middleScore), emptyMap())
            .getValue(ObservationSection.NEAR)

        assertEquals(listOf("sz000012", "sz000013", "sz000011"), near.map { it.code })
    }

    @Test
    fun strongNearEntryCandidateIsGroupedAboveOrdinaryWaitingConfirmation() {
        val strongNear = candidate.copy(code = "sh603278", stage = ObservationStage.NEAR_ENTRY)
        val ordinaryNear = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val strongObserving = candidate.copy(code = "sz000002", stage = ObservationStage.OBSERVING)
        val ordinaryObserving = candidate.copy(code = "sz000003", stage = ObservationStage.OBSERVING)
        val confirmedBuy = candidate.copy(code = "sz000004", stage = ObservationStage.BUY_CANDIDATE)

        val display = ObservationEligibility.prioritizedSections(
            candidates = listOf(strongNear, ordinaryNear, strongObserving, ordinaryObserving, confirmedBuy),
            quotes = emptyMap(),
            isStrong = { it.code == strongNear.code || it.code == strongObserving.code },
        )

        assertEquals(listOf(confirmedBuy), display.buy)
        assertEquals(listOf(strongNear, strongObserving), display.strong)
        assertEquals(listOf(ordinaryNear), display.waiting)
        assertEquals(listOf(ordinaryObserving), display.other)
    }

    @Test
    fun duplicateCodeCandidatesAreShownOnceWithBestStateAndMergedHistory() {
        val leaderBuy = candidate.copy(
            code = "sz003021",
            strategy = ObservationStrategy.LEADER,
            stage = ObservationStage.BUY_CANDIDATE,
            boards = listOf("通用设备"),
            highestStage = ObservationStage.BUY_CANDIDATE,
            highestStageDate = "2026-10-20",
        )
        val limitWatch = leaderBuy.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            stage = ObservationStage.OBSERVING,
            continuousObservation = true,
            limitUpBlocked = true,
            limitStreak = 2,
            boards = listOf("机器人"),
            highestStage = ObservationStage.NEAR_ENTRY,
            highestStageDate = "2026-10-19",
        )

        val result = ObservationEligibility.deduplicateCandidates(
            listOf(leaderBuy, limitWatch),
        )

        assertEquals(1, result.size)
        assertEquals(ObservationStage.BUY_CANDIDATE, result.single().stage)
        assertEquals(true, result.single().continuousObservation)
        assertEquals(2, result.single().limitStreak)
        assertEquals(listOf("通用设备", "机器人"), result.single().boards)
        assertEquals(ObservationStage.BUY_CANDIDATE, result.single().highestStage)

        val displayed = ObservationEligibility
            .sections(listOf(leaderBuy, limitWatch), emptyMap())
            .values
            .flatten()
        assertEquals(listOf("sz003021"), displayed.map { it.code })
    }

    @Test
    fun duplicateCodeDisplayUsesLatestEvaluationStateInsteadOfOlderHigherStage() {
        val staleBuy = candidate.copy(
            code = "sz003021",
            strategy = ObservationStrategy.LEADER,
            stage = ObservationStage.BUY_CANDIDATE,
            lastEvaluatedDate = "2026-10-20",
        )
        val freshRisk = staleBuy.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            stage = ObservationStage.OBSERVING,
            lastEvaluatedDate = "2026-10-21",
            reason = "连续2轮风险确认，降级观察",
        )

        val result = ObservationEligibility.deduplicateCandidates(listOf(staleBuy, freshRisk)).single()

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertEquals("2026-10-21", result.lastEvaluatedDate)
    }

    @Test
    fun entrySectionsOnlyShowTopTwoStagesWithoutDeferredStocksOrMutatingPool() {
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val observing = candidate.copy(code = "sz000002", stage = ObservationStage.OBSERVING)
        val deferred = candidate.copy(code = "sz000003", stage = ObservationStage.NEAR_ENTRY, limitUpBlocked = true)
        val shrinking = candidate.copy(code = "sz000004", limitUpShrinkAcceleration = true)
        val eliminated = candidate.copy(code = "sz000005", stage = ObservationStage.ELIMINATED)
        val atLimit = candidate.copy(code = "sh600001")
        val pool = listOf(candidate, near, observing, deferred, shrinking, eliminated, atLimit)
        val original = pool.toList()
        val sections = ObservationEligibility.entrySections(pool,
            mapOf(atLimit.code to sealed.copy(code = atLimit.code)))

        assertEquals(listOf(ObservationSection.BUY, ObservationSection.NEAR), sections.keys.toList())
        assertEquals(listOf(candidate), sections.getValue(ObservationSection.BUY))
        assertEquals(listOf(near), sections.getValue(ObservationSection.NEAR))
        assertEquals(original, pool)
        val empty = ObservationEligibility.entrySections(listOf(observing, deferred, eliminated), emptyMap())
        assertTrue(empty.values.all { it.isEmpty() })
    }

    @Test
    fun sealedCandidateIsDowngradedWithoutHistoryAndNotAutoRestoredOnOpening() {
        val blocked = ObservationEligibility.applyQuoteGate(candidate, sealed)
        assertEquals(ObservationStage.NEAR_ENTRY, blocked.stage)
        assertTrue(blocked.limitUpBlocked)
        assertEquals(2, blocked.observedDays)
        val reopened = ObservationEligibility.applyQuoteGate(blocked, sealed.copy(price = 10.7))
        assertEquals(ObservationStage.NEAR_ENTRY, reopened.stage)
        assertFalse(ObservationBuyAlert.shouldNotify(blocked, reopened))
    }

    @Test
    fun freshOpenQuoteClearsStaleLimitDefer() {
        val stale = candidate.copy(limitUpBlocked = true)
        val openQuote = sealed.copy(price = 10.7)

        assertFalse(ObservationEligibility.isLimitUpDeferred(stale, openQuote))
        assertFalse(ObservationEligibility.applyQuoteGate(stale, openQuote).limitUpBlocked)
    }

    @Test
    fun shrinkRiskPersistsUntilFreshVolumeEvidenceAndFullEvaluation() {
        assertTrue(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, 0.8))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, 1.1))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, -1.0))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(false, -0.2, 0.6))
        assertTrue(ObservationEligibility.isLimitUpShrinkAcceleration(false, 8.0, 0.6, true))
        assertTrue(ObservationEligibility.isLimitUpShrinkAcceleration(false, 8.0, null, true))
    }

    @Test
    fun displayedCountsAndOrderingExcludeSealedCandidatesBeforeTakingFirstHundred() {
        val sealedCandidates = (1..101).map { candidate.copy(code = "sh${600000 + it}") }
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val buy = candidate.copy(code = "sz000002")
        val quotes = sealedCandidates.associate { it.code to sealed.copy(code = it.code) }
        val display = ObservationEligibility.forDisplay(sealedCandidates + near + buy, quotes)
        assertEquals(1, display.count { it.stage == ObservationStage.BUY_CANDIDATE })
        assertEquals(buy.code, display[0].code)
        assertEquals(near.code, display[1].code)
        assertTrue(display.take(100).contains(near))
        assertEquals(101, display.count { ObservationEligibility.isLimitUpDeferred(it, quotes[it.code]) })
    }

    @Test
    fun bothStrategiesStayObservingThroughOpeningUntilRiskClearsThenNotifyOnce() {
        ObservationStrategy.entries.forEach { strategy ->
            val original = candidate.copy(strategy = strategy)
            val blockedSignal = ObservationSignal("2026-10-14", 11.0, true, true, true,
                false, 9.6, 10.0, setupScore = 3, strongScore = 3,
                limitUpBlocked = true, limitUpShrinkAcceleration = true)
            val blocked = ObservationPool.evaluate(original, blockedSignal)
            assertEquals(ObservationStage.NEAR_ENTRY, blocked.stage)
            assertFalse(ObservationBuyAlert.shouldNotify(original, blocked))
            val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(blocked))).single()
            assertTrue(restored.limitUpShrinkAcceleration)
            assertTrue(ObservationEligibility.needsRefresh(restored, "2026-10-14", sealed))
            val stillShrinking = ObservationPool.evaluate(restored,
                blockedSignal.copy(price = 10.8, limitUpBlocked = false))
            assertEquals(ObservationStage.OBSERVING, stillShrinking.stage)
            val entry = blockedSignal.copy(price = 10.6, limitUpBlocked = false,
                limitUpShrinkAcceleration = false, evaluatedAtMs = 1_000_000L, quoteVolume = 1000.0)
            val first = ObservationPool.evaluate(stillShrinking, entry)
            val confirmed = if (strategy == ObservationStrategy.PRE_BREAKOUT) {
                first
            } else {
                assertFalse(ObservationBuyAlert.shouldNotify(stillShrinking, first))
                ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_900_000L))
            }
            assertTrue(ObservationBuyAlert.shouldNotify(stillShrinking, confirmed))
            assertFalse(ObservationBuyAlert.shouldNotify(confirmed, confirmed))
            assertEquals(2, confirmed.observedDays)
        }
    }
}
