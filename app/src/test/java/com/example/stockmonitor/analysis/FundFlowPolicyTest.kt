package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FundFlowPolicyTest {

    @Test
    fun risingPriceWithOutflowIsDivergentAndLosesOnePoint() {
        val assessment = FundFlowPolicy.assess(2.0, flow(mainNetPct = -4.0))

        assertEquals(FundFlowTone.DIVERGENT, assessment.tone)
        assertEquals(-1, FundFlowPolicy.scoreDelta(assessment))
        assertFalse(assessment.strong)
    }

    @Test
    fun boundariesMapToSupportiveDivergentRiskAndAbsorptionWatch() {
        assertEquals(
            FundFlowTone.SUPPORTIVE,
            FundFlowPolicy.assess(0.6, flow(mainNetPct = 3.0)).tone,
        )
        assertEquals(
            FundFlowTone.DIVERGENT,
            FundFlowPolicy.assess(0.6, flow(mainNetPct = -3.0)).tone,
        )
        assertEquals(
            FundFlowTone.RISK,
            FundFlowPolicy.assess(-0.6, flow(mainNetPct = -3.0)).tone,
        )
        assertEquals(
            FundFlowTone.ABSORPTION_WATCH,
            FundFlowPolicy.assess(-0.6, flow(mainNetPct = 3.0)).tone,
        )
    }

    @Test
    fun tenPercentIsStrongButDoesNotChangeActionBoundary() {
        val supportive = FundFlowPolicy.assess(1.0, flow(mainNetPct = 10.0))
        val divergent = FundFlowPolicy.assess(1.0, flow(mainNetPct = -10.0))

        assertTrue(supportive.strong)
        assertTrue(divergent.strong)
        assertEquals(1, FundFlowPolicy.scoreDelta(supportive))
        assertEquals(-1, FundFlowPolicy.scoreDelta(divergent))
    }

    @Test
    fun lowConfidenceEvidenceCannotChangeScore() {
        val supportive = FundFlowPolicy.assess(
            2.0,
            flow(mainNetPct = 5.0, amountYuan = 4_999_999.0),
        )

        assertTrue(supportive.lowConfidence)
        assertEquals(0, FundFlowPolicy.scoreDelta(supportive))
    }

    @Test
    fun neutralAndUnavailableStayNeutral() {
        assertEquals(FundFlowTone.NEUTRAL, FundFlowPolicy.assess(0.2, flow(mainNetPct = 1.0)).tone)
        assertEquals(FundFlowTone.UNAVAILABLE, FundFlowPolicy.assess(2.0, null).tone)
        assertEquals(FundFlowTone.UNAVAILABLE, FundFlowPolicy.assess(Double.NaN, flow()).tone)
        assertEquals(0, FundFlowPolicy.scoreDelta(FundFlowPolicy.assess(2.0, null)))
        assertTrue(FundFlowPolicy.assess(2.0, null).reason.contains("订单规模"))
    }

    private fun flow(
        mainNetPct: Double? = 0.0,
        amountYuan: Double? = 10_000_000.0,
    ) = FundFlow(
        code = "sh600089",
        name = "特变电工",
        asOfMs = 1L,
        amountYuan = amountYuan,
        mainNetPct = mainNetPct,
    )
}
