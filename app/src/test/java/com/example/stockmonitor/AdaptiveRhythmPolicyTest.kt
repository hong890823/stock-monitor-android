package com.example.stockmonitor

import com.example.stockmonitor.analysis.AdaptiveRhythmEvidence
import com.example.stockmonitor.analysis.AdaptiveRhythmPhase
import com.example.stockmonitor.analysis.AdaptiveRhythmPolicy
import com.example.stockmonitor.analysis.ImpulseContext
import com.example.stockmonitor.analysis.MajorTrend
import com.example.stockmonitor.analysis.MarketRegime
import com.example.stockmonitor.analysis.MarketTurnoverSource
import com.example.stockmonitor.analysis.ShortTermTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRhythmPolicyTest {
    private fun evidence(
        turnoverRatio: Double? = 1.35,
        marketChangePct: Double? = 0.8,
        industryBreadth: Double? = 0.72,
        conceptBreadth: Double? = 0.68,
        marketRegime: MarketRegime = MarketRegime.NEUTRAL,
        shortTermTrend: ShortTermTrend = ShortTermTrend.UP,
        impulse: ImpulseContext = ImpulseContext(upDays = 3, downDays = 0, gain5Pct = 7.0),
        volumeRatio: Double? = 1.2,
        changePct: Double? = 1.0,
        biasFromMa5Pct: Double? = 3.0,
        lowerHighs: Boolean? = false,
        volumeWithoutProgress: Boolean? = false,
    ) = AdaptiveRhythmEvidence(
        marketTurnoverRatio = turnoverRatio,
        marketChangePct = marketChangePct,
        industryBreadth = industryBreadth,
        conceptBreadth = conceptBreadth,
        marketRegime = marketRegime,
        shortTermTrend = shortTermTrend,
        impulse = impulse,
        atrPct = 2.0,
        volumeRatio = volumeRatio,
        changePct = changePct,
        biasFromMa5Pct = biasFromMa5Pct,
        lowerHighs = lowerHighs,
        volumeWithoutProgress = volumeWithoutProgress,
    )

    @Test
    fun healthyMarketAllowsMoreContinuationThanWeakMarket() {
        val strong = AdaptiveRhythmPolicy.evaluate(evidence())
        val weak = AdaptiveRhythmPolicy.evaluate(
            evidence(
                turnoverRatio = 0.75,
                marketChangePct = -1.2,
                industryBreadth = 0.35,
                conceptBreadth = 0.30,
                marketRegime = MarketRegime.WEAK,
            ),
        )

        assertEquals(AdaptiveRhythmPhase.EARLY, strong.phase)
        assertEquals(AdaptiveRhythmPhase.DIVERGENCE_WATCH, weak.phase)
        assertTrue(strong.retreatFactor > weak.retreatFactor)
        assertTrue(strong.reason.contains("不按固定"))
    }

    @Test
    fun distributionEvidenceCanTriggerBeforeAnyFixedDayCount() {
        val result = AdaptiveRhythmPolicy.evaluate(
            evidence(
                impulse = ImpulseContext(upDays = 1, downDays = 0, gain5Pct = 2.0),
                lowerHighs = true,
            ),
        )

        assertEquals(AdaptiveRhythmPhase.DIVERGENCE_RISK, result.phase)
        assertTrue(result.reason.contains("结构证据"))
    }

    @Test
    fun downCycleEntersReboundWatchOnlyWithStabilizationEvidence() {
        val rebound = AdaptiveRhythmPolicy.evaluate(
            evidence(
                marketRegime = MarketRegime.WEAK,
                shortTermTrend = ShortTermTrend.DOWN,
                impulse = ImpulseContext(upDays = 0, downDays = 3, gain5Pct = -9.0),
                changePct = 0.6,
                volumeRatio = 0.8,
            ),
        )
        val stillFalling = AdaptiveRhythmPolicy.evaluate(
            evidence(
                marketRegime = MarketRegime.WEAK,
                shortTermTrend = ShortTermTrend.DOWN,
                impulse = ImpulseContext(upDays = 0, downDays = 8, gain5Pct = -18.0),
                changePct = -1.0,
                volumeRatio = 1.2,
            ),
        )

        assertEquals(AdaptiveRhythmPhase.REBOUND_WATCH, rebound.phase)
        assertEquals(AdaptiveRhythmPhase.DOWN, stillFalling.phase)
        assertTrue(rebound.reason.contains("不做固定天数预测"))
    }

    @Test
    fun missingDynamicMarketDataDoesNotInventTiming() {
        val result = AdaptiveRhythmPolicy.evaluate(
            evidence(turnoverRatio = null, industryBreadth = null, conceptBreadth = null),
        )

        assertEquals(AdaptiveRhythmPhase.UNKNOWN, result.phase)
        assertTrue(result.reason.contains("数据不足"))
    }

    @Test
    fun structuralDistributionRiskSurvivesMissingMarketData() {
        val result = AdaptiveRhythmPolicy.evaluate(
            evidence(
                turnoverRatio = null,
                industryBreadth = null,
                conceptBreadth = null,
                lowerHighs = true,
            ),
        )

        assertEquals(AdaptiveRhythmPhase.DIVERGENCE_RISK, result.phase)
        assertTrue(result.reason.contains("结构证据"))
    }

    @Test
    fun volumeWithoutProgressSurvivesMissingMarketData() {
        val result = AdaptiveRhythmPolicy.evaluate(
            evidence(
                turnoverRatio = null,
                industryBreadth = null,
                conceptBreadth = null,
                volumeWithoutProgress = true,
            ),
        )

        assertEquals(AdaptiveRhythmPhase.DIVERGENCE_RISK, result.phase)
    }

    @Test
    fun majorTrendChangesRhythmPressureWithoutDiscardingBottomReversal() {
        val ordinary = AdaptiveRhythmPolicy.evaluate(evidence())
        val down = AdaptiveRhythmPolicy.evaluate(evidence().copy(majorTrend = MajorTrend.DOWN))
        val extended = evidence(
            impulse = ImpulseContext(upDays = 5, downDays = 0, gain5Pct = 18.0),
            biasFromMa5Pct = 9.0,
        )
        val bottoming = AdaptiveRhythmPolicy.evaluate(extended.copy(majorTrend = MajorTrend.BOTTOMING))

        assertTrue(down.pressure > ordinary.pressure)
        assertTrue(bottoming.pressure < AdaptiveRhythmPolicy.evaluate(extended).pressure)
        assertTrue(bottoming.reason.contains("筑底"))
    }

    @Test
    fun fullDayFallbackCannotPushRhythmIntoStrongMarket() {
        val result = AdaptiveRhythmPolicy.evaluate(
            evidence(turnoverRatio = 4.0).copy(
                marketTurnoverSource = MarketTurnoverSource.FULL_DAY_FALLBACK,
            ),
        )

        assertEquals(1.0, result.retreatFactor, 0.0)
        assertTrue(result.reason.contains("降级"))
    }
}
