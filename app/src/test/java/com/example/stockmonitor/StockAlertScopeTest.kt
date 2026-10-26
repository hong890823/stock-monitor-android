package com.example.stockmonitor

import com.example.stockmonitor.analysis.HoldingExitStage
import com.example.stockmonitor.analysis.StockAlertScope
import com.example.stockmonitor.analysis.StockAlertType
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

    @Test
    fun nonHeldStocksOnlyReceiveConfirmedBuyAlerts() {
        StockAlertType.entries.filter { it != StockAlertType.CONFIRMED_BUY }.forEach { type ->
            assertFalse(type.name, StockAlertScope.shouldNotify(type, held = false))
        }
        assertFalse(StockAlertScope.shouldNotify(StockAlertType.CONFIRMED_BUY, held = false))
        assertTrue(
            StockAlertScope.shouldNotify(
                StockAlertType.CONFIRMED_BUY,
                held = false,
                buyPointConfirmed = true,
            ),
        )
    }

    @Test
    fun heldStocksKeepAllAlertTypes() {
        StockAlertType.entries.forEach { type ->
            assertTrue(
                type.name,
                StockAlertScope.shouldNotify(
                    type,
                    held = true,
                    buyPointConfirmed = type == StockAlertType.CONFIRMED_BUY,
                ),
            )
        }
    }

    @Test
    fun deepLossStateDoesNotCreateStandalonePortfolioNotification() {
        assertTrue(StockAlertScope.shouldNotifyPortfolioSignal("减仓风险", HoldingExitStage.REDUCE))
        assertTrue(StockAlertScope.shouldNotifyPortfolioSignal("减仓风险", HoldingExitStage.EXIT))
        assertFalse(StockAlertScope.shouldNotifyPortfolioSignal("减仓风险", HoldingExitStage.NOTICE))
        assertFalse(StockAlertScope.shouldNotifyPortfolioSignal("减仓风险", HoldingExitStage.DEEP_T))
        assertFalse(StockAlertScope.shouldNotifyPortfolioSignal("趋势转弱观察", null))
    }
}
