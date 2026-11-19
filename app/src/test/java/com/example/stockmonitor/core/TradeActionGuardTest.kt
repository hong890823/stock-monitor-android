package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun independentReductionNeedsTwoBarsButCriticalExitStillRequiresReview() {
        val one = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 1),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )
        val two = TradeActionGuard.evaluate(
            intent = "risk_reduce",
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
        assertEquals(0.0, critical.maxSellQuantity, 0.0)
        assertTrue(critical.requiresExitReview)
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
    fun riskExitAliasRequiresReviewWithoutFallingBackToReduction() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_exit",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.HOLD, decision.action)
        assertEquals(0.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.requiresExitReview)
    }

    @Test
    fun explicitExitPriorityStillRequiresIndependentAuthorization() {
        val decision = TradeActionGuard.evaluate(
            intent = "exit_priority",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedAction.HOLD, decision.action)
        assertEquals(0.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.requiresExitReview)
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
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL),
            eligibility = eligibility(sellable = 1050.0).copy(totalQuantity = 1050.0),
            tradeDate = "2026-10-23",
        )

        assertEquals(300.0, decision.maxSellQuantity, 0.0)
    }

    @Test
    fun absorptionCapsFirstReductionAtOneLot() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_reduce",
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
        assertTrue(decision.text.contains("做T预计净收益未通过双边费用、滑点及最低收益门槛校验"))
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
    fun allBuyIntentsRequireMarketExpansionEvenWithConfirmedStockRebound() {
        listOf("buy", "add", "t_buy", "reentry").forEach { intent ->
            listOf(false, null).forEach { expanding ->
                val decision = TradeActionGuard.evaluate(
                    intent = intent,
                    evidence = evidence(),
                    volume = IntradayVolumeContext(
                        marketExpanding = expanding,
                        stockVolumeConfirming = true,
                        barVolumeConfirming = true,
                    ),
                    eligibility = eligibility().copy(reentryObservationConfirmed = true),
                    invalidationPrice = 9.5,
                    tradeDate = "2026-11-17",
                )

                assertEquals("$intent / $expanding", GuardedActionLevel.OBSERVATION, decision.level)
                assertEquals(0.0, decision.maxBuyQuantity, 0.0)
                assertTrue(decision.text.contains("大盘"))
                assertFalse(decision.text.contains("可接回约"))
            }
        }
    }

    @Test
    fun reentryWithoutMarketVolumeContextCannotBecomeActionable() {
        val decision = TradeActionGuard.evaluate(
            intent = "t_buy",
            evidence = evidence(),
            eligibility = eligibility().copy(reentryObservationConfirmed = true),
            invalidationPrice = 9.5,
            tradeDate = "2026-11-17",
        )

        assertEquals(GuardedActionLevel.OBSERVATION, decision.level)
        assertEquals(0.0, decision.maxBuyQuantity, 0.0)
    }

    @Test
    fun expandingMarketKeepsCompletedPullbackVolumeAsReentryStockEvidence() {
        val decision = TradeActionGuard.evaluate(
            intent = "t_buy",
            evidence = evidence(),
            volume = IntradayVolumeContext(
                marketExpanding = true,
                stockVolumeConfirming = false,
                barVolumeConfirming = false,
            ),
            eligibility = eligibility().copy(reentryObservationConfirmed = true),
            invalidationPrice = 9.5,
            tradeDate = "2026-11-17",
        )

        assertEquals(GuardedActionLevel.ACTIONABLE, decision.level)
        assertEquals(1000.0, decision.maxBuyQuantity, 0.0)
    }

    @Test
    fun pendingRiskLineBlocksBuyBeforeTreatingPullbackAsEntry() {
        val decision = TradeActionGuard.evaluate(
            intent = "buy",
            evidence = evidence().copy(
                riskLine = 10.0,
                riskLineConfirmations = 2,
                riskLineM30Below = false,
            ),
            volume = IntradayVolumeContext(
                marketExpanding = true,
                stockVolumeConfirming = true,
                barVolumeConfirming = true,
            ),
            eligibility = eligibility(),
            invalidationPrice = 9.5,
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.OBSERVATION, decision.level)
        assertTrue(decision.text.contains("30分钟确认"))
    }

    @Test
    fun currentPriceBelowRiskLineBlocksBuyBeforeConfirmation() {
        val decision = TradeActionGuard.evaluate(
            intent = "buy",
            evidence = evidence().copy(
                riskLine = 10.0,
                riskLineBelow = true,
            ),
            volume = IntradayVolumeContext(
                marketExpanding = true,
                stockVolumeConfirming = true,
                barVolumeConfirming = true,
            ),
            eligibility = eligibility(),
            invalidationPrice = 9.5,
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.OBSERVATION, decision.level)
        assertTrue(decision.text.contains("尚未完成30分钟确认"))
    }

    @Test
    fun t1AndInconsistentLedgerBlockSellableQuantity() {
        val nextDay = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(sellable = 0.0),
            tradeDate = "2026-10-23",
        )
        val inconsistent = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(consistent = false),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.NEXT_DAY, nextDay.level)
        assertEquals(GuardedActionLevel.OBSERVATION, inconsistent.level)
    }

    // 以下三个用例自 TradeNotificationTest 迁移：旧短信文案路径
    // TradeNotification.exit() 已删除（无生产调用），锁定口径断言改在生产路径上验证。

    /** 今天买入的锁定批次：不误报成「未记录日期」，也不直接给卖量，改下一交易日复核。 */
    @Test
    fun sameDayLockedSharesDeferDisposalToNextTradingDay() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(sellable = 0.0).copy(todayLockedQuantity = 100.0),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.NEXT_DAY, decision.level)
        assertTrue(decision.text.contains("今日新增100股"))
        assertTrue(decision.text.contains("下一交易日"))
    }

    /** 手工加仓没记批次的残股不能说成「今天买入的」，它不会自动解锁。 */
    @Test
    fun untrackedLockedSharesAreNotCalledTodaysBuys() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(sellable = 0.0).copy(
                todayLockedQuantity = 300.0,
                untrackedQuantity = 300.0,
            ),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.NEXT_DAY, decision.level)
        assertTrue(decision.text.contains("未记录买入日期300股"))
        assertFalse(decision.text.contains("今日新增"))
    }

    /** 同日买入与未记录残股并存时，两种锁定来源要在文案里分开点名。 */
    @Test
    fun mixedLockedCopyNamesBothSourcesSeparately() {
        val decision = TradeActionGuard.evaluate(
            intent = "risk_reduce",
            evidence = evidence(riskConfirmations = 2),
            eligibility = eligibility(sellable = 0.0).copy(
                todayLockedQuantity = 300.0,
                untrackedQuantity = 200.0,
            ),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.NEXT_DAY, decision.level)
        assertTrue(decision.text.contains("今日新增100股"))
        assertTrue(decision.text.contains("未记录买入日期200股"))
    }

    /** 可卖股数按当日可卖口径表述，不把锁定部分算进去凑成总持仓。 */
    @Test
    fun normalExitCopyStatesSellableSharesNotTotalPosition() {
        val decision = TradeActionGuard.evaluate(
            intent = "normal_exit",
            evidence = evidence(),
            eligibility = eligibility(sellable = 500.0).copy(todayLockedQuantity = 500.0),
            tradeDate = "2026-10-23",
        )

        assertEquals(GuardedActionLevel.ACTIONABLE, decision.level)
        assertEquals(500.0, decision.maxSellQuantity, 0.0)
        assertTrue(decision.text.contains("原有可卖500股"))
        assertTrue(decision.text.contains("T+1"))
        assertFalse(decision.text.contains("1000股"))
    }
}
