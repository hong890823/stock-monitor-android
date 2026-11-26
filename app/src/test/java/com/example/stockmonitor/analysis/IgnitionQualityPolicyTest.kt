package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnitionQualityPolicyTest {

    @Test
    fun missingDataIsNeutralNotTrap() {
        // 缺数据的维度记中性，不误判诱多。
        val quality = IgnitionQualityPolicy.assess()
        assertEquals(IgnitionQualityPolicy.QUALITY_GOOD, quality.rating)
        assertEquals(0, quality.riskCount)
    }

    @Test
    fun eachDimensionCountsIndependently() {
        val env = IgnitionQualityPolicy.assess(marketChangePct = -1.2)
        assertEquals(1, env.riskCount)
        assertTrue(env.reasons.any { it.startsWith("环境") })

        val position = IgnitionQualityPolicy.assess(
            resistance = 10.0,
            price = 10.05, // 贴压力（1% 以内）
            recentChange5dPct = 20.0, // 加速末端同维度，不重复计数
        )
        assertEquals(1, position.riskCount)

        val pulse = IgnitionQualityPolicy.assess(burstConcentration = 0.8)
        assertEquals(1, pulse.riskCount)

        val fund = IgnitionQualityPolicy.assess(fundFlowTone = "divergent")
        assertEquals(1, fund.riskCount)
    }

    @Test
    fun ratingScalesWithRiskCount() {
        val two = IgnitionQualityPolicy.assess(marketChangePct = -0.5, burstConcentration = 0.9)
        assertEquals(IgnitionQualityPolicy.QUALITY_CAUTION, two.rating)

        val three = IgnitionQualityPolicy.assess(
            marketChangePct = -0.5,
            resistance = 10.0,
            price = 10.0,
            fundFlowTone = "mixed",
        )
        assertEquals(IgnitionQualityPolicy.QUALITY_TRAP, three.rating)
        assertTrue(three.summary.contains("疑似诱多"))
    }
}
