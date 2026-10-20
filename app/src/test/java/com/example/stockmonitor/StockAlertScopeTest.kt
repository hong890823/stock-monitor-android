package com.example.stockmonitor

import com.example.stockmonitor.analysis.StockAlertScope
import com.example.stockmonitor.core.Position
import org.junit.Assert.*
import org.junit.Test

class StockAlertScopeTest {
    @Test
    fun onlyPositiveFinitePositionsReceiveHoldingAlerts() {
        val positions = listOf(
            Position("sh600001", "持仓", 100.0, 10.0),
            Position("sh600002", "清仓", 0.0, 10.0),
            Position("sh600003", "异常", -100.0, 10.0),
            Position("sh600004", "异常", Double.NaN, 10.0),
            Position("sh600005", "异常", Double.POSITIVE_INFINITY, 10.0),
        )
        assertEquals(setOf("sh600001"), StockAlertScope.heldCodes(positions))
        assertFalse("sh600006" in StockAlertScope.heldCodes(positions))
    }

    @Test
    fun clearingOrRemovingPositionImmediatelyRemovesAlertEligibility() {
        val position = Position("sh600001", "持仓", 100.0, 10.0)
        assertTrue(position.code in StockAlertScope.heldCodes(listOf(position)))
        assertFalse(position.code in StockAlertScope.heldCodes(listOf(position.copy(quantity = 0.0))))
        assertTrue(StockAlertScope.heldCodes(emptyList()).isEmpty())
    }
}
