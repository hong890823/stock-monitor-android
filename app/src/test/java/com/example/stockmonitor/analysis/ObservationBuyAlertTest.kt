package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationBuyAlertTest {
    @Test
    fun lowerStagesAndDeferredCandidatesNeverNotify() {
        val previous = candidate(ObservationStage.OBSERVING)
        listOf(ObservationStage.OBSERVING, ObservationStage.NEAR_ENTRY, ObservationStage.ELIMINATED).forEach {
            assertFalse(ObservationBuyAlert.shouldNotify(previous, candidate(it)))
        }
        assertFalse(ObservationBuyAlert.shouldNotify(previous,
            candidate(ObservationStage.BUY_CANDIDATE).copy(limitUpBlocked = true)))
        assertFalse(ObservationBuyAlert.shouldNotify(previous,
            candidate(ObservationStage.BUY_CANDIDATE).copy(limitUpShrinkAcceleration = true)))
    }

    @Test
    fun stageSectionAndNotificationUseConfirmedAndPendingLabels() {
        assertEquals("买点已确认", ObservationStage.BUY_CANDIDATE.label)
        assertEquals("买点待确认", ObservationStage.NEAR_ENTRY.label)
        assertEquals(ObservationStage.BUY_CANDIDATE.label, ObservationSection.BUY.label)
        assertEquals(ObservationStage.NEAR_ENTRY.label, ObservationSection.NEAR.label)
        val confirmed = candidate(ObservationStage.BUY_CANDIDATE)
        assertTrue(ObservationBuyAlert.title(confirmed).contains("买点已确认"))
        assertTrue(ObservationBuyAlert.body(confirmed).contains("买点已确认"))
        assertFalse(ObservationBuyAlert.body(confirmed).contains("可介入候选"))
        assertEquals("BUY_CANDIDATE", ObservationStage.BUY_CANDIDATE.name)
        assertEquals("NEAR_ENTRY", ObservationStage.NEAR_ENTRY.name)
    }

    @Test
    fun legacyLeaderBuyLabelAloneCannotTriggerNotification() {
        assertFalse(ObservationBuyAlert.shouldNotify(
            candidate(ObservationStage.NEAR_ENTRY).copy(strategy = ObservationStrategy.LEADER),
            candidate(ObservationStage.BUY_CANDIDATE).copy(strategy = ObservationStrategy.LEADER),
        ))
    }

    private fun candidate(stage: ObservationStage) = ObservationCandidate(
        code = "sh600000",
        name = "浦发银行",
        addedDate = "2026-10-14",
        lastEvaluatedDate = "2026-10-14",
        stage = stage,
        score = 3,
        weakDays = 0,
        observedDays = 1,
        lastPrice = 10.3,
        stopPrice = 9.6,
    )

    @Test
    fun enteringBuyCandidateTriggersAlert() {
        assertTrue(
            ObservationBuyAlert.shouldNotify(
                previous = candidate(ObservationStage.NEAR_ENTRY),
                current = candidate(ObservationStage.BUY_CANDIDATE),
            ),
        )
    }

    @Test
    fun stayingInBuyCandidateDoesNotTriggerDuplicateAlert() {
        assertFalse(
            ObservationBuyAlert.shouldNotify(
                previous = candidate(ObservationStage.BUY_CANDIDATE),
                current = candidate(ObservationStage.BUY_CANDIDATE),
            ),
        )
    }

    @Test
    fun returningToBuyCandidateTriggersAlertAgain() {
        assertTrue(
            ObservationBuyAlert.shouldNotify(
                previous = candidate(ObservationStage.OBSERVING),
                current = candidate(ObservationStage.BUY_CANDIDATE),
            ),
        )
    }
}
