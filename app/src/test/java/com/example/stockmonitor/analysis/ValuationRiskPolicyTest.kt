package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuationRiskPolicyTest {

    @Test
    fun highPeAndPbPercentileAreIndependentRiskWarnings() {
        val result = ValuationRiskPolicy.assess(
            peTtm = 120.0,
            pbMrq = 12.0,
            pePercentile = 92.0,
            pbPercentile = 95.0,
        )

        assertEquals(ValuationRiskLevel.ALERT, result.level)
        assertTrue(result.summary.contains("PE"))
        assertTrue(result.summary.contains("PB"))
    }

    @Test
    fun missingValuationIsNotPenalizedAsTechnicalWeakness() {
        val result = ValuationRiskPolicy.assess(
            peTtm = null,
            pbMrq = null,
            pePercentile = null,
            pbPercentile = null,
        )

        assertEquals(ValuationRiskLevel.MISSING, result.level)
        assertEquals(0, result.technicalImpact)
    }

    @Test
    fun valuationRiskNeverChangesTechnicalPoints() {
        val high = ValuationRiskPolicy.assess(200.0, 20.0, 99.0, 99.0)
        val low = ValuationRiskPolicy.assess(10.0, 1.0, 10.0, 10.0)

        assertEquals(0, high.technicalImpact)
        assertEquals(0, low.technicalImpact)
    }
}
