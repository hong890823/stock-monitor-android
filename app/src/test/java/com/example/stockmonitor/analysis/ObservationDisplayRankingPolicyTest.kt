package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStructureQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 展示排序的次级键顺序：档位 → 结构 → 入场资格 → 相对强度。
 *
 * 资格分（`score`）必须真实影响顺序，不能只是行内文案；否则“资格 3/3 却排在后面”
 * 会让用户无法理解排序依据。
 */
class ObservationDisplayRankingPolicyTest {
    @Test
    fun entryScoreBreaksTieBeforeRelativeStrength() {
        val fullScoreWeakStrength = candidate("sh600001", score = 3, relativeStrengthPct = -6.0)
        val lowScoreStrongStrength = candidate("sh600002", score = 1, relativeStrengthPct = 12.0)

        val ranked = ObservationDisplayRankingPolicy.rank(
            listOf(lowScoreStrongStrength, fullScoreWeakStrength),
            tierOf = { 2 },
        )

        assertEquals(fullScoreWeakStrength.code, ranked.first().code)
    }

    @Test
    fun structureStaysAheadOfEntryScore() {
        val fullScoreNoStructure = candidate(
            "sh600001",
            score = 3,
            structure = ObservationStructureQuality.NONE,
            relativeStrengthPct = 20.0,
        )
        val zeroScoreConfirmedStructure = candidate(
            "sh600002",
            score = 0,
            structure = ObservationStructureQuality.BREAKOUT_CONFIRMED,
            relativeStrengthPct = -3.0,
        )

        val ranked = ObservationDisplayRankingPolicy.rank(
            listOf(fullScoreNoStructure, zeroScoreConfirmedStructure),
            tierOf = { 2 },
        )

        assertEquals(zeroScoreConfirmedStructure.code, ranked.first().code)
    }

    @Test
    fun stageTierStaysAheadOfEntryScore() {
        val buyCandidateWithoutScore = candidate(
            "sh600001",
            score = 0,
            stage = ObservationStage.BUY_CANDIDATE,
        )
        val observingWithFullScore = candidate(
            "sh600002",
            score = 3,
            stage = ObservationStage.OBSERVING,
        )

        val ranked = ObservationDisplayRankingPolicy.rank(
            listOf(observingWithFullScore, buyCandidateWithoutScore),
            tierOf = ObservationEligibility::displayTier,
        )

        assertEquals(buyCandidateWithoutScore.code, ranked.first().code)
    }

    @Test
    fun equalTierAndStructureRanksByScoreDescending() {
        val first = candidate("sh600001", score = 1)
        val second = candidate("sh600002", score = 3)
        val third = candidate("sh600003", score = 2)

        val ranked = ObservationDisplayRankingPolicy.rank(
            listOf(first, second, third),
            tierOf = { 2 },
        )

        assertEquals(listOf(second.code, third.code, first.code), ranked.map { it.code })
    }

    private fun candidate(
        code: String,
        score: Int,
        stage: ObservationStage = ObservationStage.OBSERVING,
        structure: ObservationStructureQuality = ObservationStructureQuality.NONE,
        relativeStrengthPct: Double? = null,
    ): ObservationCandidate = ObservationCandidate(
        code = code,
        name = code,
        addedDate = "2026-11-19",
        lastEvaluatedDate = "2026-11-20",
        stage = stage,
        score = score,
        weakDays = 0,
        structureQuality = structure,
        relativeStrengthPct = relativeStrengthPct,
    )
}