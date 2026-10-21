package com.example.stockmonitor

import com.example.stockmonitor.analysis.BreakoutRiskEvidence
import com.example.stockmonitor.analysis.BreakoutRiskPolicy
import com.example.stockmonitor.analysis.EntrySetupType
import com.example.stockmonitor.analysis.HighPositionPhase
import com.example.stockmonitor.analysis.PriceLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakoutRiskPolicyTest {
    @Test
    fun detectsBreakdownAfterBreakoutContinuation() {
        val result = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(
                price = 10.8,
                dailyHighPrice = 11.6,
                breakoutReference = 11.0,
                changePct = -2.5,
                volumeRatio = 1.4,
                atrPct = 2.0,
                continuation = true,
            ),
        )

        assertTrue(result.risk)
        assertTrue(result.reason.contains("下杀风险"))
    }

    @Test
    fun waitsWithoutExpandedVolumeOrContinuationContext() {
        val quiet = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(10.8, 11.6, 11.0, -2.5, 0.8, 2.0, true),
        )
        val ordinary = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(10.8, 11.6, 11.0, -2.5, 1.4, 2.0, false),
        )

        assertFalse(quiet.risk)
        assertFalse(ordinary.risk)
    }

    @Test
    fun recognizesBreakoutAndSecondWaveContinuation() {
        assertTrue(BreakoutRiskPolicy.continuation(EntrySetupType.BREAKOUT, HighPositionPhase.NONE))
        assertTrue(BreakoutRiskPolicy.continuation(EntrySetupType.NONE, HighPositionPhase.SECOND_WAVE_CONFIRMED))
        assertFalse(BreakoutRiskPolicy.continuation(EntrySetupType.M20_PULLBACK, HighPositionPhase.NONE))
    }

    @Test
    fun lowPositionUsesMa20WhileHighBiasUsesMa5() {
        val low = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(
                price = 9.8, dailyHighPrice = 10.0, breakoutReference = 10.0,
                changePct = -1.0, volumeRatio = 0.8, atrPct = 2.0, continuation = true,
                priceLocation = PriceLocation.LOW, ma5 = 10.1, ma20 = 10.0,
            ),
        )
        val high = BreakoutRiskPolicy.evaluate(
            BreakoutRiskEvidence(
                price = 10.8, dailyHighPrice = 11.0, breakoutReference = 10.5,
                changePct = -1.0, volumeRatio = 0.8, atrPct = 2.0, continuation = true,
                priceLocation = PriceLocation.HIGH, ma5 = 11.0, ma20 = 9.0,
            ),
        )

        assertTrue(low.risk)
        assertTrue(low.reason.contains("20日线"))
        assertTrue(high.risk)
        assertTrue(high.reason.contains("5日线"))
    }
}
