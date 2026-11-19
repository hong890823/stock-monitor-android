package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.GuardedActionLevel
import com.example.stockmonitor.core.TimeframeEvidence
import com.example.stockmonitor.core.DailyAdviceJson
import com.example.stockmonitor.core.DailyRiskLevel
import com.example.stockmonitor.core.GuardedAction
import com.example.stockmonitor.core.GuardedTradeAction
import com.example.stockmonitor.core.PositionAllocationConfig
import com.example.stockmonitor.core.TradeActionGuard
import com.example.stockmonitor.core.TradeActionEligibility
import org.junit.Assert.*
import org.junit.Test

class IntradayAdviceConsistencyTest {
    private fun plan() = DailyAdvicePolicy.prepare(
        "2026-11-17", "sh600111", AdviceDirection.SELL, AdviceKind.T_SELL,
        400.0, 1200.0, null,
    ).plan

    private fun input() = IntradayTactics.Input(
        "sh600111", "测试", 10.0, 3.0, 2.0, 10.0,
        9.0, 11.0, 3, 1200.0, 10.0, 100000.0, 20000.0, 5.0, 20.0,
        sellableQuantity = 1200.0,
        tradeDate = "2026-11-17",
        timeframeEvidence = TimeframeEvidence(complete = true, m15Confirmations = 1, m30Aligned = true),
    )

    @Test fun sentPartialPlanDoesNotRepeatAnOrder() {
        val sent = DailyAdvicePolicy.markSent(plan(), 1L)
        val partial = DailyAdvicePolicy.applyQuantityFeedback(sent, 1200.0, 1100.0, 2L)
        assertFalse(DailyAdvicePolicy.canNotify(partial))
        assertFalse(DailyAdvicePolicy.reserve(partial, listOf(partial), partial.date, partial.code).accepted)
    }

    @Test fun reservationCannotBeRepeatedOrResurrectCompletedPlan() {
        val original = plan()
        val reserved = DailyAdvicePolicy.reserve(original, listOf(original), original.date, original.code).plan
        assertFalse(DailyAdvicePolicy.reserve(reserved, listOf(reserved), reserved.date, reserved.code).accepted)
        val completed = original.copy(state = AdvicePlanState.COMPLETED, executedQuantity = 400.0)
        assertFalse(DailyAdvicePolicy.reserve(completed, listOf(completed), completed.date, completed.code).accepted)
    }

    @Test fun laterQuantityFeedbackCannotEraseReview() {
        val review = plan().copy(state = AdvicePlanState.REVIEW_REQUIRED, reviewReason = "交易方向变化")
        val updated = DailyAdvicePolicy.applyQuantityFeedback(review, 1200.0, 1100.0, 2L)
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, updated.state)
        assertEquals("交易方向变化", updated.reviewReason)
    }

    @Test fun unreportedPositionChangeRequiresReviewNotSilentReuse() {
        val prepared = DailyAdvicePolicy.prepare(
            "2026-11-17", "sh600111", AdviceDirection.SELL, AdviceKind.T_SELL,
            600.0, 1000.0, plan(),
        ).plan
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, prepared.state)
        assertEquals(0.0, prepared.executedQuantity, 0.0)
    }

    @Test fun missingAllocationCannotGenerateOrdinaryTShares() {
        assertEquals(0.0, IntradayTactics.suggestedTSellQuantity(input()).quantity, 0.0)
        val action = TradeNotification.intradayGuarded(input(), IntradaySignal(IntradaySignalType.T_SELL, "", ""))
        assertTrue(action.text.contains("做T预计净收益未通过"))
    }

    @Test fun earlierLargerProfitableEstimateCannotAuthorizeSmallLosingT() {
        val earlier = IntradayTNetProfitPolicy.estimate(10.0, 9.0, null, 1200.0)!!
        assertTrue(earlier.meetsMinimum)
        val action = TradeNotification.intradayGuarded(
            input().copy(sellableQuantity = 300.0, positionAllocation = PositionAllocationConfig.normal(0.0)),
            IntradaySignal(IntradaySignalType.T_SELL, "", "", tSellNetProfitEstimate = earlier),
        )
        assertNotEquals(GuardedActionLevel.ACTIONABLE, action.level)
    }

    @Test fun zeroOrInfiniteEstimatedProfitIsNeverApproved() {
        val estimate = IntradayTNetProfitPolicy.estimate(10.0, 9.0, null, 1200.0)!!
        assertFalse(estimate.copy(expectedNetProfit = 0.0, requiredNetProfit = 0.0).meetsMinimum)
        assertFalse(estimate.copy(expectedNetProfit = Double.POSITIVE_INFINITY).meetsMinimum)
    }

    @Test fun allocationCapsOrdinaryTAndNeverRoundsUpIntoCore() {
        assertEquals(200.0, IntradayTactics.suggestedTSellQuantity(
            input().copy(positionAllocation = PositionAllocationConfig.normal(1000.0)),
        ).quantity, 0.0)
        listOf(1150.0, 1200.0, 1500.0).forEach { base ->
            assertEquals(0.0, IntradayTactics.suggestedTSellQuantity(
                input().copy(positionAllocation = PositionAllocationConfig.normal(base)),
            ).quantity, 0.0)
        }
    }

    @Test fun missingSellableAndRiskObservationModeCannotAuthorizeT() {
        assertEquals(0.0, IntradayTactics.suggestedTSellQuantity(
            input().copy(sellableQuantity = null, positionAllocation = PositionAllocationConfig.normal(0.0)),
        ).quantity, 0.0)
        assertEquals(0.0, IntradayTactics.suggestedTSellQuantity(
            input().copy(positionAllocation = PositionAllocationConfig.riskObservation()),
        ).quantity, 0.0)
    }

    @Test fun remainingPlanQuantityIsRepricedInsteadOfProratingOldProfit() {
        val current = input().copy(atrPct = 3.0, positionAllocation = PositionAllocationConfig.normal(0.0))
        val estimate = IntradayTNetProfitPolicy.estimate(10.0, 9.0, 3.0, 400.0)!!
        val signal = IntradaySignal(IntradaySignalType.T_SELL, "", "", tSellNetProfitEstimate = estimate)
        assertTrue(estimate.meetsMinimum)
        assertEquals(GuardedActionLevel.ACTIONABLE, TradeNotification.intradayGuarded(current, signal).level)
        val limited = TradeNotification.intradayGuarded(current, signal, maxActionQuantity = 100.0)
        assertEquals(GuardedActionLevel.OBSERVATION, limited.level)
        assertTrue(limited.text.contains("净收益"))
    }

    @Test fun profitableTTextUsesFinalSharesAndDisclosesEstimationRisk() {
        val current = input().copy(price = 32.31, support = 30.0, atrPct = 3.0, positionAllocation = PositionAllocationConfig.normal(1000.0))
        val signal = IntradaySignal(IntradaySignalType.T_SELL, "", "", tSellNetProfitEstimate =
            IntradayTNetProfitPolicy.estimate(32.31, 30.0, 3.0, 400.0))
        val action = TradeNotification.intradayGuarded(current, signal)
        val finalEstimate = IntradayTNetProfitPolicy.estimate(32.31, 30.0, 3.0, 200.0)!!
        assertEquals(200.0, action.maxSellQuantity, 0.0)
        assertTrue(action.text.contains(String.format(java.util.Locale.CHINA, "%.2f元", finalEstimate.expectedNetProfit)))
        assertTrue(action.text.contains("不保证实际盈利"))
        assertFalse(action.text.contains("最多减400股"))
    }

    @Test fun confirmedRiskCanUseCoreWithoutTProfitGate() {
        val current = input().copy(
            price = 8.0,
            positionAllocation = PositionAllocationConfig.riskObservation(),
            timeframeEvidence = TimeframeEvidence(riskComplete = true, riskConfirmed = true, riskConfirmations = 2),
        )
        val reduce = TradeNotification.intradayGuarded(current, IntradaySignal(IntradaySignalType.REDUCE_POSITION, "", ""))
        assertEquals(GuardedAction.RISK_REDUCE, reduce.action)
        assertEquals(400.0, reduce.maxSellQuantity, 0.0)
        assertEquals(1, TradeNotification.riskPriority(reduce))
        val exit = TradeNotification.intradayGuarded(current.copy(timeframeEvidence = current.timeframeEvidence!!.copy(
            riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL,
        )), IntradaySignal(IntradaySignalType.EXIT_POSITION, "", ""))
        assertEquals(0.0, exit.maxSellQuantity, 0.0)
        assertEquals(0, TradeNotification.riskPriority(exit))
        assertTrue(exit.requiresExitReview)
    }

    @Test fun confirmedRiskReplacesEarlierPlanWithoutInventingExecution() {
        val original = DailyAdvicePolicy.markSent(plan(), 1L)
        val upgraded = DailyAdvicePolicy.prepare(
            original.date, original.code, AdviceDirection.SELL, AdviceKind.REDUCE, 400.0, 1200.0,
            original, riskPriority = 1,
        ).plan
        assertEquals(AdviceKind.REDUCE, upgraded.kind)
        assertEquals(0.0, upgraded.executedQuantity, 0.0)
        assertTrue(DailyAdvicePolicy.canNotify(upgraded))
        assertTrue(upgraded.continuityReason!!.contains("不与旧计划叠加"))
        assertEquals(upgraded, DailyAdviceJson.decode(DailyAdviceJson.encode(listOf(upgraded))).single())
        val repeated = DailyAdvicePolicy.prepare(
            upgraded.date, upgraded.code, upgraded.direction, upgraded.kind, 800.0, 1200.0,
            DailyAdvicePolicy.markSent(upgraded, 2L), riskPriority = 1,
        ).plan
        assertFalse(DailyAdvicePolicy.canNotify(repeated))
        assertEquals(400.0, repeated.targetQuantity, 0.0)
        val exit = DailyAdvicePolicy.prepare(
            upgraded.date, upgraded.code, AdviceDirection.SELL, AdviceKind.EXIT, 1200.0, 1200.0,
            repeated, riskPriority = 2,
        ).plan
        assertFalse(DailyAdvicePolicy.canNotify(exit))
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, exit.state)
        assertEquals(400.0, exit.targetQuantity, 0.0)
    }

    @Test fun ordinaryReboundCannotCancelConfirmedRiskPlan() {
        val risk = plan().copy(kind = AdviceKind.REDUCE, riskPriority = 1, state = AdvicePlanState.SENT)
        val result = DailyAdvicePolicy.prepare(
            risk.date, risk.code, AdviceDirection.BUY, AdviceKind.HOLDING_ADD, 200.0, 1200.0, risk,
        ).plan
        assertEquals(risk, result)
    }

    @Test fun publicationCapsToRemainingAndNeverAddsAnotherBatch() {
        val partial = plan().copy(executedQuantity = 100.0, state = AdvicePlanState.PARTIALLY_EXECUTED)
        assertEquals(300.0, DailyAdvicePolicy.publicationQuantity(partial, 600.0), 0.0)
        assertEquals(100.0, DailyAdvicePolicy.publicationQuantity(partial, 150.0), 0.0)
        assertEquals(0.0, DailyAdvicePolicy.publicationQuantity(partial, Double.NaN), 0.0)
        assertTrue(DailyAdvicePolicy.continuityText(partial, 300.0).contains("不是追加"))
    }

    @Test fun genericQuantityClipCannotApproveTWithoutRecalculation() {
        val action = GuardedTradeAction(GuardedActionLevel.ACTIONABLE, GuardedAction.T_SELL, "最多减400股", maxSellQuantity = 400.0)
        assertEquals(GuardedActionLevel.OBSERVATION, TradeNotification.limitQuantity(action, 100.0).level)
    }

    @Test fun duplicateQuantityFeedbackDoesNotCountTwice() {
        val partial = DailyAdvicePolicy.applyQuantityFeedback(plan(), 1200.0, 1100.0, 1L)
        val duplicated = DailyAdvicePolicy.applyQuantityFeedback(partial, 1200.0, 1100.0, 2L)
        assertEquals(100.0, duplicated.executedQuantity, 0.0)
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, duplicated.state)
    }

    @Test fun finalGuardRefusesQuantityChangesAfterProfitApproval() {
        listOf(150.0, 400.0).forEach { approvedQuantity ->
            val action = TradeActionGuard.evaluate(
                intent = "t_sell",
                evidence = input().timeframeEvidence,
                eligibility = TradeActionEligibility(
                    totalQuantity = 1200.0, actionableSellableQuantity = 100.0,
                    plannedTSellQuantity = approvedQuantity, tSellNetProfitApproved = true,
                ),
                tradeDate = "2026-11-17",
            )
            assertEquals(GuardedActionLevel.OBSERVATION, action.level)
        }
    }
}
