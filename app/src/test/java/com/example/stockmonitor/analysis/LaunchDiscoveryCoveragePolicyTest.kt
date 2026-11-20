package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDiscoveryCoveragePolicyTest {

    private fun candidate(code: String, stage: ObservationStage) = ObservationCandidate(
        code = code,
        name = code,
        addedDate = "2026-11-01",
        lastEvaluatedDate = "2026-11-19",
        stage = stage,
        score = 0,
        weakDays = 0,
    )

    @Test
    fun activeObservationAndWatchlistCandidatesAreCovered() {
        val covered = LaunchDiscoveryCoveragePolicy.coveredCodes(
            observationPool = listOf(candidate("sh600000", ObservationStage.OBSERVING)),
            watchlistCandidates = listOf(candidate("sh600001", ObservationStage.BUY_CANDIDATE)),
        )
        assertTrue("sh600000" in covered)
        assertTrue("sh600001" in covered)
    }

    @Test
    fun eliminatedCandidateCanBeRediscovered() {
        val covered = LaunchDiscoveryCoveragePolicy.coveredCodes(
            observationPool = listOf(candidate("sh600002", ObservationStage.ELIMINATED)),
            watchlistCandidates = listOf(candidate("sh600003", ObservationStage.ELIMINATED)),
        )
        assertFalse("淘汰条目不能永久拉黑启动发现", "sh600002" in covered)
        assertFalse("sh600003" in covered)
    }
}