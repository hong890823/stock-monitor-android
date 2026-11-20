package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationRiskSignal
import com.example.stockmonitor.core.ObservationRiskType
import com.example.stockmonitor.core.ObservationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstBoardClockPolicyTest {

    private fun candidate(
        addedDate: String,
        firstBoardDate: String? = null,
        limitStreak: Int? = 1,
        stage: ObservationStage = ObservationStage.BUY_CANDIDATE,
    ) = ObservationCandidate(
        code = "sh600000",
        name = "测试",
        addedDate = addedDate,
        lastEvaluatedDate = addedDate,
        stage = stage,
        score = 3,
        weakDays = 2,
        observedDays = 4,
        postConfirmationWatchDays = 2,
        limitStreak = limitStreak,
        firstBoardDate = firstBoardDate,
        entryConfirmationRounds = 2,
        entryConfirmationRequiredRounds = 2,
        entryConfirmedAtMs = 123L,
        entryTriggerPrice = 10.0,
        entryTriggerAtMs = 456L,
        riskSignals = listOf(
            ObservationRiskSignal(ObservationRiskType.ENTRY_CHASE, "追高", 10.0, 10.5),
        ),
    )

    @Test
    fun effectiveBoardDateFallsBackToAddedDateForOldData() {
        assertEquals("2026-11-10", FirstBoardClockPolicy.effectiveBoardDate(candidate("2026-11-10")))
        assertEquals(
            "2026-11-18",
            FirstBoardClockPolicy.effectiveBoardDate(
                candidate("2026-11-10", firstBoardDate = "2026-11-18"),
            ),
        )
    }

    @Test
    fun secondFirstBoardStartsANewCycleWhileSameDayRefreshDoesNot() {
        val old = candidate("2026-11-10", firstBoardDate = "2026-11-10")
        assertEquals(
            true,
            FirstBoardClockPolicy.startsNewCycle(1, old, "2026-11-18", degraded = false),
        )
        assertFalse(
            "同一天多次刷新不能反复重置",
            FirstBoardClockPolicy.startsNewCycle(1, old, "2026-11-10", degraded = false),
        )
        assertFalse(
            "降级数据不参与首板时钟",
            FirstBoardClockPolicy.startsNewCycle(1, old, "2026-11-18", degraded = true),
        )
        assertFalse(
            "连板数不是 1 不重置",
            FirstBoardClockPolicy.startsNewCycle(2, old, "2026-11-18", degraded = false),
        )
    }

    @Test
    fun newCycleClearsObservationAndConfirmationState() {
        val reset = FirstBoardClockPolicy.startNewCycle(
            candidate("2026-11-10", firstBoardDate = "2026-11-10"),
            "2026-11-18",
        )

        assertEquals("2026-11-18", reset.addedDate)
        assertEquals("2026-11-18", reset.firstBoardDate)
        assertNull(reset.lastEvaluatedDate)
        assertEquals(ObservationStage.OBSERVING, reset.stage)
        assertEquals(0, reset.observedDays)
        assertEquals(0, reset.weakDays)
        assertEquals(0, reset.postConfirmationWatchDays)
        assertEquals(0, reset.entryConfirmationRounds)
        assertEquals(0.0, reset.entryTriggerPrice, 0.0)
        assertTrue(reset.riskSignals.isEmpty())
    }
}