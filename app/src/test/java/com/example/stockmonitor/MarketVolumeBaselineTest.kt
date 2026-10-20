package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketVolumeBaseline
import com.example.stockmonitor.analysis.MarketVolumeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketVolumeBaselineTest {

    @Test
    fun requiresTenHistoricalSameTimeSamplesBeforeCalculatingRatio() {
        val baseline = MarketVolumeBaseline()
        repeat(9) { day ->
            baseline.record("2026-09-${10 + day}", 600, 100.0)
        }

        assertNull(baseline.ratio("2026-09-30", 600, 150.0))

        baseline.record("2026-09-19", 600, 100.0)
        assertEquals(1.5, baseline.ratio("2026-09-30", 600, 150.0)!!, 0.0001)
    }

    @Test
    fun classifiesVolumeConfirmationAtConfiguredThresholds() {
        assertEquals(MarketVolumeLevel.ELEVATED, MarketVolumeBaseline.classify(1.5))
        assertEquals(MarketVolumeLevel.HIGH, MarketVolumeBaseline.classify(2.0))
        assertEquals(MarketVolumeLevel.EXTREME, MarketVolumeBaseline.classify(3.0))
        assertEquals(MarketVolumeLevel.NONE, MarketVolumeBaseline.classify(1.49))
    }
}
