package com.example.stockmonitor

import com.example.stockmonitor.analysis.StockDropLevel
import com.example.stockmonitor.analysis.StockRiskPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class StockRiskPolicyTest {

    @Test
    fun dailyDropWarningStartsAtThreePercent() {
        assertEquals(StockDropLevel.WARNING, StockRiskPolicy.levelFor(-3.0))
        assertEquals(StockDropLevel.NONE, StockRiskPolicy.levelFor(-2.99))
    }

    @Test
    fun dailyDropRiskEscalatesAtFivePercent() {
        assertEquals(StockDropLevel.DANGER, StockRiskPolicy.levelFor(-5.0))
        assertEquals(StockDropLevel.DANGER, StockRiskPolicy.levelFor(-8.0))
    }
}
