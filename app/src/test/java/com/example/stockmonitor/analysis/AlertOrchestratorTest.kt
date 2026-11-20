package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertOrchestratorTest {
    private fun input(
        alertsEnabled: Boolean = true,
        intradayEnabled: Boolean = true,
        quoteFresh: Boolean = true,
        duplicate: Boolean = false,
        budgetAvailable: Boolean = true,
    ) = AlertOrchestrator.Input(alertsEnabled, intradayEnabled, quoteFresh, duplicate, budgetAvailable)

    @Test
    fun sendsWhenAllGatesPass() {
        assertEquals(AlertOrchestrator.Decision.SEND, AlertOrchestrator.decide(input()))
    }

    @Test
    fun switchOffBlocksEvenWithBudget() {
        assertEquals(
            AlertOrchestrator.Decision.SKIP_DISABLED,
            AlertOrchestrator.decide(input(alertsEnabled = false)),
        )
        assertEquals(
            AlertOrchestrator.Decision.SKIP_DISABLED,
            AlertOrchestrator.decide(input(intradayEnabled = false)),
        )
    }

    @Test
    fun staleQuoteBlocksBeforeSpendingBudget() {
        assertEquals(
            AlertOrchestrator.Decision.SKIP_STALE,
            AlertOrchestrator.decide(input(quoteFresh = false)),
        )
    }

    @Test
    fun duplicateBeatsBudgetCheck() {
        assertEquals(
            AlertOrchestrator.Decision.SKIP_DUPLICATE,
            AlertOrchestrator.decide(input(duplicate = true, budgetAvailable = false)),
        )
    }

    @Test
    fun exhaustedBudgetIsReported() {
        assertEquals(
            AlertOrchestrator.Decision.SKIP_BUDGET,
            AlertOrchestrator.decide(input(budgetAvailable = false)),
        )
    }
}
