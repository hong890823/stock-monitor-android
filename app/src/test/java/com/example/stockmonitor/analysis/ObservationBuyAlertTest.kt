package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationAlertState
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.analysis.WatchlistBuyCandidatePool
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
            assertFalse(ObservationBuyAlert.canNotify(candidate(it)))
        }
        assertTrue(ObservationBuyAlert.canNotify(candidate(ObservationStage.BUY_CANDIDATE)))
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

    @Test
    fun watchlistBuyAlertUsesWatchlistSourceInsteadOfStrategyLabel() {
        val confirmed = candidate(ObservationStage.BUY_CANDIDATE).copy(
            source = WatchlistBuyCandidatePool.SOURCE,
        )

        assertEquals("自选股", ObservationBuyAlert.body(confirmed).substringAfter("｜").substringBefore("｜"))
    }

    @Test
    fun ordinaryThreeBoardCandidateCanAlertAfterTwoRounds() {
        val previous = candidate(ObservationStage.NEAR_ENTRY).copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
        )
        val twoRounds = candidate(ObservationStage.BUY_CANDIDATE).copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            entryConfirmationRounds = 2,
        )
        val threeRounds = twoRounds.copy(entryConfirmationRounds = 3)

        assertTrue(ObservationBuyAlert.shouldNotify(previous, twoRounds))
    }

    @Test
    fun weakSealThreeBoardCandidateRequiresThreeRoundsBeforeAlert() {
        val previous = candidate(ObservationStage.NEAR_ENTRY).copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            sealQualityScore = 20,
            sealQualityCoveragePct = 100,
        )
        val twoRounds = candidate(ObservationStage.BUY_CANDIDATE).copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            sealQualityScore = 20,
            sealQualityCoveragePct = 100,
            entryConfirmationRounds = 2,
        )
        val threeRounds = twoRounds.copy(entryConfirmationRounds = 3)

        assertFalse(ObservationBuyAlert.shouldNotify(previous, twoRounds))
        assertTrue(ObservationBuyAlert.shouldNotify(previous, threeRounds))
    }

    @Test
    fun alertIdentityIsStockCodeSoDifferentRoutesCannotDuplicateAlerts() {
        val leader = candidate(ObservationStage.BUY_CANDIDATE).copy(strategy = ObservationStrategy.LEADER)
        val limit = leader.copy(strategy = ObservationStrategy.LIMIT_UP_CONTINUATION)

        assertEquals(ObservationBuyAlert.alertKey(leader), ObservationBuyAlert.alertKey(limit))
    }

    @Test
    fun confirmedBuyWithBlockedExecutionStillBuildsCautionAlert() {
        val confirmed = candidate(ObservationStage.BUY_CANDIDATE)

        val title = ObservationBuyAlert.title(confirmed, executionReady = false)
        val body = ObservationBuyAlert.body(
            confirmed,
            "仅观察：个股量能证据不足，仅观察，不执行买入。",
            executionReady = false,
        )

        assertTrue(title.contains("执行条件待满足"))
        assertTrue(body.contains("买点结构已确认"))
        assertTrue(body.contains("个股量能证据不足"))
        assertFalse(body.contains("仅观察：个股量能"))
    }

    @Test
    fun deliveryTextExplainsBlockedAndCautionNotifications() {
        val blocked = candidate(ObservationStage.BUY_CANDIDATE).copy(
            alertState = ObservationAlertState.BLOCKED,
            alertDetail = "不在 09:45–14:45 买点提醒时段",
            alertUpdatedAtMs = 1_000_000L,
        )
        val caution = blocked.copy(
            alertState = ObservationAlertState.SENT_CAUTION,
            alertDetail = "仓位边界不允许至少一手",
        )

        assertTrue(ObservationBuyAlert.deliveryText(blocked).contains("未通知"))
        assertTrue(ObservationBuyAlert.deliveryText(blocked).contains("09:45–14:45"))
        assertTrue(ObservationBuyAlert.deliveryText(caution).contains("执行条件待满足"))
        assertTrue(ObservationBuyAlert.deliveryText(caution).contains("仓位边界"))
    }
}
