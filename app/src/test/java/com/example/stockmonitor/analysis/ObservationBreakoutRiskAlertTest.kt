package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.ObservationStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationBreakoutRiskAlertTest {
    @Test
    fun bodyShowsStockNameInsteadOfCode() {
        val body = ObservationBreakoutRiskAlert.body(candidate)

        assertTrue(body.startsWith("华升股份｜"))
        assertFalse(body.contains(candidate.code))
    }

    @Test
    fun bodyUsesResolvedNameWhenCandidateNameIsMissing() {
        val body = ObservationBreakoutRiskAlert.body(candidate.copy(name = ""), "华升股份")

        assertTrue(body.startsWith("华升股份｜"))
        assertFalse(body.contains(candidate.code))
    }

    private val candidate = ObservationCandidate(
        code = "sh600156",
        name = "华升股份",
        addedDate = "2026-11-19",
        lastEvaluatedDate = "2026-11-20",
        stage = ObservationStage.OBSERVING,
        score = 0,
        weakDays = 0,
        lastPrice = 12.34,
        strategy = ObservationStrategy.LEADER,
        breakoutRisk = BreakoutRiskDecision(true, "跌破5日线，突破延续转为下杀风险"),
    )
}