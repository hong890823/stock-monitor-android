package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradayAdditionPolicyTest {
    @Test
    fun derivesStrictWindowWithConservativeRounding() {
        val base = base()
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = null,
        )

        assertEquals(9.95, decision.lowerBound ?: Double.NaN, 0.000001)
        assertEquals(10.15, decision.upperBound ?: Double.NaN, 0.000001)
        assertEquals(IntradayAdditionStatus.READY, decision.status)
    }

    @Test
    fun requiresThreeSamplesWithTwoMinuteSpanAndBoundedIntervals() {
        val base = base()
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs).drop(1),
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.WAITING_CONFIRMATION, decision.status)
        assertTrue(decision.reason.contains("3 个独立样本"))
    }

    @Test
    fun highestAcceptedPriceOnlyTightensAcrossRounds() {
        val base = base()
        val previous = IntradayAdditionState(
            code = base.code,
            date = base.tradingDate,
            maxAcceptedPrice = 10.10,
            invalidated = false,
        )

        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.05,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = previous,
        )

        assertEquals(10.10, decision.upperBound ?: Double.NaN, 0.000001)
        assertEquals(500.0, decision.quantity, 0.000001)
    }

    @Test
    fun crossingStopLossInvalidatesWindowForTheDay() {
        val base = base()
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 9.49,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.INVALIDATED, decision.status)
        assertEquals(0.0, decision.quantity, 0.000001)
    }

    @Test
    fun staleBaseEvaluationExpiresAfterThirtyFiveMinutes() {
        val base = base()
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 35 * 60_000L + 1L,
            samples = readySamples(base.createdAtMs),
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.EXPIRED, decision.status)
        assertEquals(0.0, decision.quantity, 0.000001)
    }

    @Test
    fun staleIndexOrTimeframeEvidenceWaitsInsteadOfNotifying() {
        val base = base()
        val samples = readySamples(base.createdAtMs).map {
            it.copy(indexAgeMs = IntradayAdditionPolicy.MAX_QUOTE_AGE_MS + 1)
        }
        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = samples,
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.WAITING_CONFIRMATION, decision.status)
    }

    @Test
    fun quantityIsRecomputedAtUpperBoundFromAvailableCash() {
        val base = base().copy(availableCapital = 4_000.0)

        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.READY, decision.status)
        // 4000 元按最高接受价 10.15 + 费用重算 ≈393 股 → 整手 300 股。
        assertEquals(300.0, decision.quantity, 0.0)
    }

    @Test
    fun insufficientCashAtUpperBoundBlocksInsteadOfSuggestingUnaffordableShares() {
        val base = base().copy(availableCapital = 500.0)

        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = null,
        )

        assertEquals(IntradayAdditionStatus.BLOCKED, decision.status)
        assertEquals(0.0, decision.quantity, 0.0)
    }

    @Test
    fun changedEvaluationFingerprintBlocksOldWindowState() {
        val base = base().copy(evaluationFingerprint = "new-evaluation")
        val previous = IntradayAdditionState(
            code = base.code,
            date = base.tradingDate,
            maxAcceptedPrice = 10.10,
            invalidated = false,
            evaluationFingerprint = "old-evaluation",
        )

        val decision = IntradayAdditionPolicy.evaluate(
            base = base,
            currentPrice = 10.00,
            quoteDate = "2026-11-17",
            marketQuoteDate = "2026-11-17",
            nowMs = base.createdAtMs + 120_000L,
            samples = readySamples(base.createdAtMs),
            previousState = previous,
        )

        assertEquals(IntradayAdditionStatus.BLOCKED, decision.status)
        assertTrue(decision.reason.contains("指纹"))
    }

    private fun base() = IntradayAdditionBase(
        code = "sh600000",
        name = "浦发银行",
        tradingDate = "2026-11-17",
        createdAtMs = 1_000_000L,
        support = 10.0,
        pressure = 12.0,
        stopPrice = 9.50,
        baselineAddQuantity = 800.0,
        maxQuantityAtHighestPrice = 500.0,
        riskRewardApproved = true,
        estimatedFeeRate = 0.0003,
        estimatedFeeFlat = 5.0,
    )

    private fun readySamples(startMs: Long) = listOf(
        IntradayAdditionSample(startMs, 9.98, "2026-11-17", "2026-11-17", 1, true, true, true, 30_000L, 30_000L),
        IntradayAdditionSample(startMs + 60_000L, 10.00, "2026-11-17", "2026-11-17", 2, true, true, true, 30_000L, 30_000L),
        IntradayAdditionSample(startMs + 120_000L, 10.00, "2026-11-17", "2026-11-17", 3, true, true, true, 30_000L, 30_000L),
    )
}
