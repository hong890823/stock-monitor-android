package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPoolRetentionPolicyTest {

    private fun candidate(
        code: String,
        stage: ObservationStage,
        lastEvaluatedDate: String?,
        addedDate: String = "2026-11-01",
    ) = ObservationCandidate(
        code = code,
        name = code,
        addedDate = addedDate,
        lastEvaluatedDate = lastEvaluatedDate,
        stage = stage,
        score = 0,
        weakDays = 0,
    )

    @Test
    fun activeCandidatesAreNeverPruned() {
        val active = candidate("sh600000", ObservationStage.OBSERVING, "2026-01-01")
        val kept = ObservationPoolRetentionPolicy.prune(listOf(active), "2026-11-20")
        assertEquals(listOf(active), kept)
    }

    @Test
    fun eliminatedCandidateIsKeptDuringRetentionWindow() {
        val eliminated = candidate("sh600001", ObservationStage.ELIMINATED, "2026-11-18")
        val kept = ObservationPoolRetentionPolicy.prune(listOf(eliminated), "2026-11-20")
        assertEquals(listOf(eliminated), kept)
    }

    @Test
    fun eliminatedCandidateOlderThanRetentionIsDropped() {
        val fresh = candidate("sh600002", ObservationStage.ELIMINATED, "2026-11-19")
        val old = candidate("sh600003", ObservationStage.ELIMINATED, "2026-11-10")
        val kept = ObservationPoolRetentionPolicy.prune(listOf(fresh, old), "2026-11-20")
        assertEquals(listOf(fresh), kept)
    }

    @Test
    fun eliminatedCandidateFallsBackToAddedDateWhenLastEvaluatedMissing() {
        val old = candidate(
            "sh600004",
            ObservationStage.ELIMINATED,
            lastEvaluatedDate = null,
            addedDate = "2026-11-01",
        )
        assertTrue(ObservationPoolRetentionPolicy.prune(listOf(old), "2026-11-20").isEmpty())
    }

    @Test
    fun unparsableDateIsKeptInsteadOfSilentlyDeleted() {
        val broken = candidate("sh600005", ObservationStage.ELIMINATED, "not-a-date")
        val kept = ObservationPoolRetentionPolicy.prune(listOf(broken), "2026-11-20")
        assertEquals(listOf(broken), kept)
    }

    @Test
    fun activeOldCandidateSurvivesPruning() {
        val active = candidate("sh600006", ObservationStage.BUY_CANDIDATE, "2026-01-01")
        assertFalse(ObservationPoolRetentionPolicy.prune(listOf(active), "2026-11-20").isEmpty())
    }
}