package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketRiskPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRiskPolicyTest {

    @Test
    fun earlyWeaknessRequiresTheEarlyWarningThreshold() {
        assertTrue(MarketRiskPolicy.isEarlyTriggered(-0.50, -0.40))
        assertTrue(MarketRiskPolicy.isEarlyTriggered(-0.40, -0.50))
        assertFalse(MarketRiskPolicy.isEarlyTriggered(-0.49, -0.40))
    }

    @Test
    fun riskAlertUsesTheHigherImmediateThreshold() {
        assertTrue(MarketRiskPolicy.isRiskTriggered(-0.80, -0.70))
        assertTrue(MarketRiskPolicy.isRiskTriggered(-0.70, -0.80))
        assertFalse(MarketRiskPolicy.isRiskTriggered(-0.79, -0.70))
    }

    @Test
    fun alertRearmsOnlyAfterBothIndexesRecover() {
        assertFalse(MarketRiskPolicy.isRearmed(-0.40, -0.50))
        assertTrue(MarketRiskPolicy.isRearmed(-0.39, -0.39))
    }
}
