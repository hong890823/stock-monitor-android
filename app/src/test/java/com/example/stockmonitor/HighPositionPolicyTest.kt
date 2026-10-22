package com.example.stockmonitor

import com.example.stockmonitor.analysis.HighPositionDecision
import com.example.stockmonitor.analysis.HighPositionEvidence
import com.example.stockmonitor.analysis.HighPositionPhase
import com.example.stockmonitor.analysis.HighPositionPolicy
import com.example.stockmonitor.analysis.MajorTrend
import com.example.stockmonitor.analysis.ShortTermTrend
import com.example.stockmonitor.analysis.TrendAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPositionPolicyTest {
    private val upwardTrend = TrendAssessment(
        shortTerm = ShortTermTrend.UP,
        major = MajorTrend.UP,
        reason = "测试趋势向上",
        allowsOrdinaryBuy = true,
        allowsAddPosition = true,
        allowsTBuy = true,
        allowsTSell = true,
        allowsBottomReversal = false,
        riskOnly = false,
    )

    private fun evidence(
        price: Double = 11.85,
        rangeHigh: Double = 12.0,
        rangeLow: Double = 11.6,
        atrPct: Double = 1.5,
        gain5Pct: Double = 1.0,
        volumeRatio: Double? = 0.8,
        pullbackVolumeRatio: Double? = 0.6,
        recentLows: List<Double> = listOf(11.60, 11.65, 11.70, 11.75),
        recentHighs: List<Double> = listOf(11.85, 11.90, 11.95, 12.00),
        recentCloses: List<Double> = listOf(11.70, 11.75, 11.80, 11.85),
        support: Double = 11.60,
        marketHealthy: Boolean = true,
        sectorHealthy: Boolean = true,
        dualTimeframeAllowed: Boolean = true,
        trend: TrendAssessment = upwardTrend,
    ) = HighPositionEvidence(
        price = price,
        rangeHigh = rangeHigh,
        rangeLow = rangeLow,
        atrPct = atrPct,
        gain5Pct = gain5Pct,
        volumeRatio = volumeRatio,
        pullbackVolumeRatio = pullbackVolumeRatio,
        recentLows = recentLows,
        recentHighs = recentHighs,
        recentCloses = recentCloses,
        support = support,
        marketHealthy = marketHealthy,
        sectorHealthy = sectorHealthy,
        dualTimeframeAllowed = dualTimeframeAllowed,
        trend = trend,
    )

    @Test
    fun elevatedVolumeWithoutPriceProgressIsDistributionRisk() {
        val result = HighPositionPolicy.evaluate(
            evidence(
                price = 11.95,
                gain5Pct = 0.4,
                volumeRatio = 1.8,
                pullbackVolumeRatio = 1.2,
                recentHighs = listOf(12.00, 11.98, 11.96, 11.95),
            ),
        )

        assertEquals(HighPositionPhase.DISTRIBUTION_RISK, result.phase)
        assertTrue(result.riskPriority)
        assertFalse(result.allowBuy)
        assertFalse(result.allowSecondWaveEntry)
    }

    @Test
    fun tightRangeWithContractedPullbackVolumeIsConsolidation() {
        val result = HighPositionPolicy.evaluate(evidence())

        assertEquals(HighPositionPhase.CONSOLIDATION, result.phase)
        assertTrue(result.allowHoldBase)
        assertFalse(result.allowBuy)
    }

    @Test
    fun healthyVolumeReboundPromotesConsolidationToSecondWaveCandidate() {
        val result = HighPositionPolicy.evaluate(evidence(volumeRatio = 1.2))

        assertEquals(HighPositionPhase.SECOND_WAVE_CANDIDATE, result.phase)
        assertTrue(result.allowHoldBase)
        assertFalse(result.allowSecondWaveEntry)
    }

    @Test
    fun rangeBreakoutNeedsVolumeAndDualTimeframeApproval() {
        val confirmed = HighPositionPolicy.evaluate(
            evidence(
                price = 12.07,
                volumeRatio = 1.5,
                recentCloses = listOf(11.80, 11.90, 12.00, 12.07),
                recentHighs = listOf(11.90, 11.95, 12.00, 12.07),
            ),
        )
        val waiting = HighPositionPolicy.evaluate(
            evidence(
                price = 12.07,
                volumeRatio = 1.5,
                recentCloses = listOf(11.80, 11.90, 12.00, 12.07),
                recentHighs = listOf(11.90, 11.95, 12.00, 12.07),
                dualTimeframeAllowed = false,
            ),
        )

        assertEquals(HighPositionPhase.SECOND_WAVE_CONFIRMED, confirmed.phase)
        assertTrue(confirmed.allowSecondWaveEntry)
        assertNotEquals(HighPositionPhase.SECOND_WAVE_CONFIRMED, waiting.phase)
        assertFalse(waiting.allowSecondWaveEntry)
    }

    private fun assertNotEquals(expected: HighPositionPhase, actual: HighPositionPhase) {
        org.junit.Assert.assertNotEquals(expected, actual)
    }

    @Test
    fun invalidEvidenceCannotAuthorizeSecondWave() {
        val result: HighPositionDecision = HighPositionPolicy.evaluate(
            evidence(volumeRatio = Double.NaN),
        )

        assertEquals(HighPositionPhase.UNCERTAIN, result.phase)
        assertFalse(result.allowSecondWaveEntry)
    }

    @Test
    fun intradayEvaluationUsesLivePriceVolumeAndRealDualTimeframeGate() {
        val staleEvidence = evidence(
            price = 95.0,
            volumeRatio = 0.8,
            dualTimeframeAllowed = true,
        )

        val confirmed = HighPositionPolicy.evaluateIntraday(
            evidence = staleEvidence,
            price = 101.0,
            volumeRatio = 1.5,
            dualTimeframeAllowed = true,
        )
        val waiting = HighPositionPolicy.evaluateIntraday(
            evidence = staleEvidence,
            price = 101.0,
            volumeRatio = 1.5,
            dualTimeframeAllowed = false,
        )

        assertEquals(HighPositionPhase.SECOND_WAVE_CONFIRMED, confirmed.phase)
        assertTrue(confirmed.allowSecondWaveEntry)
        assertNotEquals(HighPositionPhase.SECOND_WAVE_CONFIRMED, waiting.phase)
        assertFalse(waiting.allowSecondWaveEntry)
    }
}
