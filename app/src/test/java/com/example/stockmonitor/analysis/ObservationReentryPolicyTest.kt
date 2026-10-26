package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStatusChange
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationReentryPolicyTest {
    @Test
    fun freshCandidateCanReviveEliminatedStockWhileKeepingHighestHistory() {
        val eliminated = ObservationCandidate(
            code = "sz003021",
            name = "兆威机电",
            addedDate = "2026-10-01",
            lastEvaluatedDate = "2026-10-10",
            stage = ObservationStage.ELIMINATED,
            score = 0,
            weakDays = 3,
            observedDays = 10,
            highestStage = ObservationStage.BUY_CANDIDATE,
            highestStageDate = "2026-10-03",
            highestStageAtMs = 3_000L,
            statusChanges = listOf(
                ObservationStatusChange(
                    date = "2026-10-03",
                    atMs = 3_000L,
                    previousStage = ObservationStage.NEAR_ENTRY,
                    stage = ObservationStage.BUY_CANDIDATE,
                    reason = "确认买点",
                ),
            ),
        )
        val fresh = ObservationCandidate(
            code = "sz003021",
            name = "兆威机电",
            addedDate = "2026-10-20",
            lastEvaluatedDate = null,
            stage = ObservationStage.OBSERVING,
            score = 0,
            weakDays = 0,
            observedDays = 0,
        )

        val revived = ObservationReentryPolicy.revive(eliminated, fresh)

        assertEquals(ObservationStage.OBSERVING, revived.stage)
        assertEquals(0, revived.observedDays)
        assertEquals(0, revived.weakDays)
        assertEquals(ObservationStage.BUY_CANDIDATE, revived.highestStage)
        assertEquals("2026-10-03", revived.highestStageDate)
        assertEquals(2, revived.statusChanges.size)
        assertEquals(ObservationStage.ELIMINATED, revived.statusChanges.last().previousStage)
        assertEquals(ObservationStage.OBSERVING, revived.statusChanges.last().stage)
    }
}
