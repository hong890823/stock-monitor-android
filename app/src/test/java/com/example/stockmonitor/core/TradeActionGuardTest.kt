package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeActionGuardTest {
    private fun evidence(
        riskConfirmations: Int = 0,
        riskLevel: DailyRiskLevel = DailyRiskLevel.NORMAL,
        riskComplete: Boolean = riskConfirmations > 0,
        riskConfirmed: Boolean = riskConfirmations > 0,
    ) = TimeframeEvidence(
        m15Confirmations = 2,
        m30Aligned = true,
        complete = true,
        riskComplete = riskComplete,
        riskConfirmed = riskConfirmed,
        riskConfirmations = riskConfirmations,
        riskLevel = riskLevel,
        clearanceConfirmed = true,
    )

    private fun eligibility(sellable: Double = 900.0, consistent: Boolean = true) =
        TradeActionEligibility(
            totalQuantity = 1000.0,
            actionableSellableQuantity = sellable,
            buyCapacityQuantity = 1000.0,
            ledgerConsistent = consistent,
        )

    @Test
    fun oneBarRiskIsFactTwoBarsReduceAndThreeCriticalCanExit() {
        val one = TradeActionGuard.evaluate(
            intent = "exit_position",
            evidence = evidence(riskConfirmations = 1),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )
        val two = TradeActionGuard.evaluate(
            intent = "exit_position",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )
        val critical = TradeActionGuard.evaluate(
            intent = "exit_position",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.RISK_FACT, one.level)
        assertEquals(0.0, one.maxSellQuantity, 0.0)
        assertEquals(300.0, two.maxSellQuantity, 0.0)
        assertEquals(900.0, critical.maxSellQuantity, 0.0)
    }

    @Test
    fun riskReduceNeverEscalatesIntoFullExit() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.RISK_REDUCE, decision.action)
        assertEquals(300.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun riskExitAliasNeverEscalatesWithoutExplicitExitPositionIntent() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_exit",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.RISK_REDUCE, decision.action)
        assertEquals(300.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun explicitExitPriorityCanUseFullExitAfterCriticalConfirmation() {
        val decision = TradeActionGuard.evaluate(
            intent = "exit_priority",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.RISK_EXIT, decision.action)
        assertEquals(900.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun highReduceUsesFreshCompletedEvidenceWithoutRequiringSupportBreach() {
        val decision = TradeActionGuard.evaluate(
            intent = "high_reduce",
            evidence = evidence(riskComplete = true, riskConfirmed = false),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.ACTIONABLE, decision.level)
        assertEquals(GuardedAction.RISK_REDUCE, decision.action)
        assertEquals(300.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.text.contains("高位转弱"))
    }

    @Test
    fun breakSupportUsesRiskReduceInsteadOfUpgradingToFullExit() {
        val decision = TradeActionGuard.evaluate(
            intent = "break_support",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.RISK_REDUCE, decision.action)
        assertEquals(300.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun allExecutableSellQuantitiesAreLotRounded() {
        val decision = TradeActionGuard.evaluate(
            intent = "exit_position",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(sellable = 1050.0).copy(totalQuantity = 1050.0),
            tradeDate = "2026-10-23",
        )

        assertEquals(1000.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun absorptionCapsFirstReductionAtOneLot() {
        val decision = TradeActionGuard.evaluate(
            intent = "exit_position",
            evidence = evidence(riskConfirmations = 2),
            volume = IntradayVolumeContext(absorptionConfirmed = true, barVolumeConfirming = true),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(100.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.text.contains("承接"))
        assertTrue(!decision.text.contains("大资金入场"))
    }

    @Test
    fun tSellDoesNotFallBackToWholePositionWhenOneThirdIsLessThanOneLot() {
        val decision = TradeActionGuard.evaluate(
            intent = "t_sell",
            evidence = evidence(),
            eligibility = eligibility(sellable = 200.0).copy(totalQuantity = 250.0),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.OBSERVATION, decision.level)
        assertEquals(0.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.text.contains("不足100股"))
    }

    @Test
    fun buyNeedsStockAndMarketVolumeConfirmation() {
        val missing = TradeActionGuard.evaluate(
            intent = "buy",
            evidence = evidence(),
            volume = IntradayVolumeContext(marketExpanding = true),
            eligibility = eligibility(),
            invalidationPrice = 9.5,
            tradeDate = "2026-10-23",
        )
        val confirmed = TradeActionGuard.evaluate(
            intent = "buy",
            evidence = evidence(),
            volume = IntradayVolumeContext(
                marketExpanding = true,
                stockVolumeConfirming = true,
                barVolumeConfirming = true,
            ),
            eligibility = eligibility(),
            invalidationPrice = 9.5,
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.OBSERVATION, missing.level)
        assertEquals(GuardedActionLevel.ACTIONABLE, confirmed.level)
    }

    @Test
    fun t1AndInconsistentLedgerBlockSellableQuantity() {
        val nextDay = TradeActionGuard.evaluate(
            intent = "risk_exit",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(sellable = 0.0),
            tradeDate = "2026-10-23",
        )
        val inconsistent = TradeActionGuard.evaluate(
            intent = "risk_exit",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(consistent = false),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.NEXT_DAY, nextDay.level)
        assertEquals(GuardedActionLevel.OBSERVATION, inconsistent.level)
    }
}
