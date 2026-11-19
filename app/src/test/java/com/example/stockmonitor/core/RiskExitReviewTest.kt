package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.*
import org.junit.Assert.*
import org.junit.Test

class RiskExitReviewTest {
    private val evidence = TimeframeEvidence(
        complete = true, m15Confirmations = 3, m30Aligned = true,
        riskComplete = true, riskConfirmed = true,
        riskConfirmations = 3, riskLevel = DailyRiskLevel.CRITICAL,
        lastCompleted15m = "2026-11-17 14:30:00",
        lastCompleted30m = "2026-11-17 14:30:00",
    )

    private fun evaluate(intent: String, input: TimeframeEvidence? = evidence) = TradeActionGuard.evaluate(
        intent, input,
        eligibility = TradeActionEligibility(totalQuantity = 600.0, actionableSellableQuantity = 600.0),
        tradeDate = "2026-11-17",
    )

    @Test fun criticalWithThreeBarsCannotAuthorizeAnyExitAlias() {
        for (intent in listOf("exit_position", "exit_priority", "risk_exit", "stop")) {
            val action = evaluate(intent)
            assertEquals(intent, GuardedActionLevel.RISK_FACT, action.level)
            assertEquals(intent, GuardedAction.HOLD, action.action)
            assertEquals(0.0, action.maxSellQuantity, 0.0)
            assertTrue(action.text.contains("重大风险待复核"))
            assertFalse(action.text.contains("分批离场"))
            assertFalse(action.text.contains("最多减"))
        }
    }

    @Test fun exitCandidateNeverFallsBackToReductionEvenWithIncompleteEvidence() {
        for (input in listOf(null, evidence.copy(riskComplete = false), evidence.copy(riskConfirmations = 2))) {
            val action = evaluate("exit_position", input)
            assertEquals(GuardedActionLevel.RISK_FACT, action.level)
            assertEquals(0.0, action.maxSellQuantity, 0.0)
            assertTrue(action.text.contains("复核"))
        }
    }

    @Test fun independentReductionStillUsesItsOwnGate() {
        assertEquals(200.0, evaluate("risk_reduce").maxSellQuantity, 0.0)
        assertEquals(0.0, evaluate("risk_reduce", evidence.copy(riskConfirmations = 1)).maxSellQuantity, 0.0)
    }

    @Test fun broadRiskFlagsIndividuallyAndTogetherNeverAuthorizeClearance() {
        val base = DailyRiskContext("2026-11-17")
        val contexts = listOf(
            base.copy(systemicRisk = true), base.copy(portfolioRisk = true), base.copy(eventRisk = true),
            base.copy(invalidationPrice = 34.0), base.copy(rangeLow = 34.0),
            base.copy(highExitRisk = true), base.copy(liquidityRisk = true),
            base.copy(majorTrend = "DOWN", fiveDayNoNewLow = false),
            base.copy(systemicRisk = true, eventRisk = true, invalidationPrice = 34.0),
        )
        for (context in contexts) {
            val level = context.riskLevel(32.0)
            assertEquals(DailyRiskLevel.CRITICAL, level)
            assertTrue(context.criticalReasons(32.0).isNotEmpty())
            val action = evaluate("exit_position", evidence.copy(riskLevel = level))
            assertEquals(0.0, action.maxSellQuantity, 0.0)
            assertTrue(action.requiresExitReview)
        }
    }

    @Test fun oldAlertRecordsRemainReadableWithoutEvidenceFields() {
        val old = """[{"id":"old","title":"历史记录","body":"保留原文","created_at_ms":100}]"""
        val record = AlertRecordBook.decode(old).single()
        assertNull(record.evidenceKey)
        assertEquals("保留原文", record.body)
    }

    @Test fun intradayReviewDoesNotEchoOldOrderOrCreateProfitEstimate() {
        val input = IntradayTactics.Input(
            "sh600111", "北方稀土", 32.01, -7.0, 1.5, 10.0,
            34.09, 37.38, 3, 600.0, 50.802, 100000.0, 20000.0, 5.0, 20.0,
            sellableQuantity = 600.0, tradeDate = "2026-11-17", timeframeEvidence = evidence,
        )
        val action = TradeNotification.intradayGuarded(
            input, IntradaySignal(IntradaySignalType.EXIT_POSITION, "退出优先", "全部卖出600股，亏损11294元"),
        )
        assertEquals(0.0, action.maxSellQuantity, 0.0)
        assertEquals(0, TradeNotification.riskPriority(action))
        assertTrue(action.requiresExitReview)
        assertFalse(action.text.contains("600股"))
        assertFalse(action.text.contains("11294"))
        assertFalse(action.text.contains("卖后剩余"))
    }

    @Test fun portfolioExitUsesTheSameNonExecutableReviewGate() {
        val result = PositionResult(
            code = "sh600111", name = "北方稀土", quantity = 600.0, cost = 50.802,
            price = 32.01, changePct = -7.0, marketValue = 19206.0, positionPct = 3.0,
            signal = "退出优先", action = "旧退出文案", support = 34.09, resistance = 37.38, ma20 = 35.74,
            entrySetup = EntrySetup(
                type = EntrySetupType.NONE, confirmed = false, trendWeak = true,
                priorHigh = null, ma20SlopePct = null, volumeConfirmed = false,
                marketFilter = MarketFilter.NEUTRAL, stopPrice = 33.0, reason = "技术转弱",
            ),
            suggestedQuantity = 600.0, score = -7, volumeRatio = 1.5,
            volumeSignal = VolumePriceSignal.NEUTRAL,
            opening = OpeningStructure(34.63, 33.0, -4.0, -3.0, "低开", "走弱"),
            volatility = Volatility(1.0, 3.0, 35.0, 38.0, 32.0, 0.0),
            trendStrength = TrendStrength(null, null, null), relativeStrength = null,
            analyzedAt = "2026-11-17 14:35:55", analysisDate = "2026-11-17",
            priorityEvidenceDate = "2026-11-17", priorityRiskConfirmed = true, priorityExitTriggered = true,
            priorityExitAudit = "触发采样14:30:00，14:00/14:15/14:30收盘32.0，退出参考32.51",
        )
        val action = TradeNotification.portfolio(
            result, ledgerConsistent = true, tradeDate = "2026-11-17", evidence = evidence,
        )!!
        assertTrue(action.requiresExitReview)
        assertEquals(GuardedActionLevel.RISK_FACT, action.level)
        assertEquals(0.0, action.maxSellQuantity, 0.0)
        assertTrue(action.text.contains(result.priorityExitAudit))
        assertTrue(action.text.contains("触发时证据"))
    }

    @Test fun oscillatingRiskReasonsDoNotRepeatAnAlreadyDeliveredReview() {
        val gate = ExitReviewDedup()
        val first = RiskExitReview.evidenceKey("2026-11-17", evidence.copy(riskReasonKey = "组合普跌"))
        val second = RiskExitReview.evidenceKey("2026-11-17", evidence.copy(riskReasonKey = "组合普跌；跌破参考"))
        assertTrue(gate.shouldEmit("2026-11-17", "sh600111", first))
        assertTrue(gate.shouldEmit("2026-11-17", "sh600111", first))
        gate.markSent("2026-11-17", "sh600111", first)
        assertTrue(gate.shouldEmit("2026-11-17", "sh600111", second))
        gate.markSent("2026-11-17", "sh600111", second)
        assertFalse(gate.shouldEmit("2026-11-17", "sh600111", first))
        assertFalse(gate.shouldEmit("2026-11-17", "sh600111", second))
        assertTrue(gate.shouldEmit("2026-11-18", "sh600111", first))
    }

    @Test fun reviewIncludesEvidenceTimeAndDoesNotClaimFundamentalInvalidation() {
        val action = evaluate("exit_priority")
        assertTrue(action.text.contains("2026-11-17 14:30:00"))
        assertTrue(action.text.contains("CRITICAL"))
        assertTrue(action.text.contains("不等于持有逻辑失效"))
        assertTrue(action.text.contains("依据缺失"))
    }

    @Test fun reviewPausesEarlierTwoHundredSharesWithoutInventingExecution() {
        val old = DailyAdvicePolicy.prepare(
            "2026-11-17", "sh600111", AdviceDirection.SELL, AdviceKind.REDUCE, 200.0, 600.0, null, 1,
        ).plan
        val review = RiskExitReview.pause(old, 2L)
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, review.state)
        assertEquals(200.0, review.targetQuantity, 0.0)
        assertEquals(0.0, review.executedQuantity, 0.0)
        assertFalse(DailyAdvicePolicy.canNotify(review))
        val replacement = DailyAdvicePolicy.prepare(
            old.date, old.code, AdviceDirection.SELL, AdviceKind.EXIT, 600.0, 600.0, review, 2,
        ).plan
        assertEquals(review, replacement)
        assertFalse(DailyAdvicePolicy.reserve(replacement, listOf(replacement), old.date, old.code).accepted)
    }

    @Test fun highestPriorityCannotCreateExecutablePlanEvenIfGuardIsBypassed() {
        val plan = DailyAdvicePolicy.prepare(
            "2026-11-17", "sh600111", AdviceDirection.SELL, AdviceKind.EXIT, 600.0, 600.0, null, 2,
        ).plan
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, plan.state)
        assertFalse(DailyAdvicePolicy.canNotify(plan))
        assertFalse(DailyAdvicePolicy.canNotify(plan.copy(state = AdvicePlanState.PREPARED)))
    }

    @Test fun reviewPreservesActualFeedbackAndCompletedHistory() {
        val plan = DailyAdvicePlan(
            "2026-11-17", "sh600111", AdviceDirection.SELL, AdviceKind.REDUCE,
            200.0, 100.0, 500.0, state = AdvicePlanState.PARTIALLY_EXECUTED,
        )
        val review = RiskExitReview.pause(plan, 3L)
        assertEquals(100.0, review.executedQuantity, 0.0)
        assertEquals(500.0, review.lastKnownQuantity, 0.0)
        assertEquals(review, RiskExitReview.pause(review, 4L))
        val completed = plan.copy(state = AdvicePlanState.COMPLETED, executedQuantity = 200.0)
        assertEquals(completed, RiskExitReview.pause(completed, 4L))
    }

    @Test fun auditRecordsSourcePricesAndClosedBarsAndSurvivesHistoryRoundTrip() {
        val bars = listOf("14:00:00", "14:15:00", "14:30:00").map { time ->
            IntradayBar("2026-11-17 $time", 32.0, 32.2, 31.9, 32.0, 100.0)
        }
        val built = TimeframeEvidence.build(
            bars, bars.takeLast(1), 32.01, 34.09, 37.38,
            java.time.LocalDateTime.parse("2026-11-17T14:35:55"), "2026-11-17",
            DailyRiskContext("2026-11-17", systemicRisk = true, eventRisk = true),
        )
        val action = evaluate("exit_position", built)
        for (expected in listOf("32.01", "34.09", "14:00:00", "收盘32.0", "环境风险", "基本面红灯", "未提供")) {
            assertTrue(expected, action.text.contains(expected))
        }
        val record = AlertRecord("review", 1, "risk_exit_review", "RISK", "risk_exit_review", "待复核", action.text, 100L,
            evidenceKey = RiskExitReview.evidenceKey("2026-11-17", built),
            evidenceSummary = RiskExitReview.evidenceSummary(built))
        assertEquals(record, AlertRecordBook.decode(AlertRecordBook.encode(listOf(record))).single())
        assertEquals(record.evidenceKey, RiskExitReview.evidenceKey("2026-11-17", built.copy(lastCompleted15m = "2026-11-17 14:45:00")))
    }
}
