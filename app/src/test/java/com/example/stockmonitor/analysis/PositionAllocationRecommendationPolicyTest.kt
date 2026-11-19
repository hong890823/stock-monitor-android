package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.PositionAllocationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionAllocationRecommendationPolicyTest {
    @Test
    fun soundFundamentalsAndStrongTechnicalRecommendHealthyBaseAndRollingSplit() {
        val recommendation = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(
                quantity = 1_000.0,
                technicalScore = 82,
                technicalAllowsAdd = true,
                majorTrend = MajorTrend.UP,
                fundamentalLevel = HealthLevel.SOUND,
            ),
        )

        assertEquals(PositionAllocationMode.NORMAL, recommendation.mode)
        assertEquals(0.60, recommendation.baseRatio!!, 0.000001)
        assertEquals(600.0, recommendation.recommendedBaseQuantity!!, 0.000001)
        assertEquals(400.0, recommendation.recommendedRollingQuantity!!, 0.000001)
        assertTrue(recommendation.reasons.any { it.contains("基本面") })
        assertTrue(recommendation.reasons.any { it.contains("技术") })
    }

    @Test
    fun fundamentalAlertDoesNotAutomaticallyEraseTheHoldingPlan() {
        val recommendation = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(
                quantity = 1_000.0,
                technicalScore = 88,
                technicalAllowsAdd = true,
                majorTrend = MajorTrend.UP,
                fundamentalLevel = HealthLevel.ALERT,
            ),
        )

        assertEquals(PositionAllocationMode.NORMAL, recommendation.mode)
        assertTrue(recommendation.recommendedBaseQuantity!! > 0.0)
        assertTrue(recommendation.recommendedRollingQuantity!! > 0.0)
        assertTrue(recommendation.reasons.any { it.contains("亮红灯") })
    }

    @Test
    fun weakTechnicalTrendChangesTradingCautionButDoesNotEraseSuggestedBaseOrRollingPlan() {
        val recommendation = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(
                quantity = 1_000.0, technicalScore = 25, trendWeak = true,
                majorTrend = MajorTrend.DOWN, fundamentalLevel = HealthLevel.NEUTRAL,
            ),
        )

        assertEquals(PositionAllocationMode.NORMAL, recommendation.mode)
        assertEquals(400.0, recommendation.recommendedBaseQuantity!!, 0.000001)
        assertEquals(600.0, recommendation.recommendedRollingQuantity!!, 0.000001)
        assertTrue(recommendation.reasons.any { it.contains("暂缓") })
        assertTrue(recommendation.reasons.any { it.contains("持有价值") })
    }

    @Test
    fun technicalScoreAloneDoesNotCreateAnAllocationRecommendation() {
        val low = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(quantity = 1_000.0, technicalScore = 25, trendWeak = false),
        )
        val high = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(quantity = 1_000.0, technicalScore = 82, trendWeak = false),
        )

        assertNull(low.recommendedBaseQuantity)
        assertNull(high.recommendedBaseQuantity)
        assertTrue(low.dataGaps.any { it.contains("基本面") })
    }

    @Test
    fun missingFundamentalEvidenceLeavesRecommendationPendingAndExplainsDataGaps() {
        val recommendation = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(quantity = 500.0),
        )

        assertEquals(PositionAllocationMode.NORMAL, recommendation.mode)
        assertNull(recommendation.baseRatio)
        assertNull(recommendation.recommendedBaseQuantity)
        assertNull(recommendation.recommendedRollingQuantity)
        assertTrue(recommendation.summary.contains("待确认"))
        assertTrue(recommendation.dataGaps.any { it.contains("基本面") })
        assertTrue(recommendation.dataGaps.any { it.contains("技术") })
    }

    @Test
    fun recommendationWithFundamentalEvidenceRoundsBaseToLots() {
        val recommendation = PositionAllocationRecommendationPolicy.recommend(
            PositionAllocationRecommendationInput(quantity = 800.0, fundamentalLevel = HealthLevel.NEUTRAL),
        )

        assertEquals(300.0, recommendation.recommendedBaseQuantity!!, 0.000001)
        assertEquals(500.0, recommendation.recommendedRollingQuantity!!, 0.000001)
        assertTrue(recommendation.summary.contains("建议底仓 300 股"))
        assertTrue(recommendation.summary.contains("滚动仓 500 股"))
    }
}
