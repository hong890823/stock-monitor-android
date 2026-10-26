package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.LimitDataSource
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationPoolJson
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitPromotionPolicyTest {

    @Test
    fun admitsOnlyVerifiedStrongSealQualityWithoutFixedCount() {
        assertTrue(LimitCandidatePolicy.eligible(limitStock(sealToFloatPct = 0.5)))
        assertTrue(LimitCandidatePolicy.eligible(limitStock(sealToTurnoverPct = 10.0)))
        assertFalse(LimitCandidatePolicy.eligible(limitStock(sealToFloatPct = 0.49)))
        assertFalse(LimitCandidatePolicy.eligible(limitStock(openCount = 2)))
        assertFalse(
            LimitCandidatePolicy.eligible(
                limitStock().copy(source = LimitDataSource.SINA_APPROX, limitStreak = null),
            ),
        )
    }

    @Test
    fun sealedContinuousCandidateDoesNotTimeOutOrPromote() {
        val candidate = sealedCandidate().copy(observedDays = ObservationPool.MAX_OBSERVED_DAYS)
        val result = ObservationPool.evaluate(candidate, sealedSignal())

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertTrue(result.continuousObservation)
        assertTrue(result.reason.contains("开板后重新确认"))
    }

    @Test
    fun resealingResetsEntryConfirmationRounds() {
        val candidate = sealedCandidate().copy(entryConfirmationRounds = 2)
        val result = ObservationPool.evaluate(candidate, sealedSignal())

        assertEquals(0, result.entryConfirmationRounds)
        assertEquals(ObservationStage.OBSERVING, result.stage)
    }

    @Test
    fun openedCandidateNeedsTwoFreshRoundsBeforeBuyCandidate() {
        val first = ObservationPool.evaluate(sealedCandidate(), openSignal(evaluatedAtMs = 1_000_000L))
        assertEquals(ObservationStage.NEAR_ENTRY, first.stage)
        assertEquals(1, first.entryConfirmationRounds)

        val second = ObservationPool.evaluate(
            first,
            openSignal(evaluatedAtMs = 1_000_000L + 15 * 60 * 1000L),
        )
        assertEquals(ObservationStage.BUY_CANDIDATE, second.stage)
    }

    @Test
    fun fundFlowCanAddOnePointButDivergenceDelaysPromotion() {
        val supportive = FundFlowPolicy.assess(
            2.0,
            FundFlow("sh600000", "浦发银行", 1L, 10_000_000.0, mainNetPct = 5.0),
        )
        val divergent = FundFlowPolicy.assess(
            2.0,
            FundFlow("sh600000", "浦发银行", 1L, 10_000_000.0, mainNetPct = -5.0),
        )
        val supported = ObservationPool.evaluate(
            sealedCandidate(),
            openSignal(evaluatedAtMs = 1_000L).copy(fundFlow = supportive),
        )
        val delayed = ObservationPool.evaluate(
            sealedCandidate(),
            openSignal(evaluatedAtMs = 1_000L).copy(fundFlow = divergent),
        )

        assertTrue(supported.score in 0..3)
        assertEquals(ObservationStage.OBSERVING, delayed.stage)
        assertTrue(delayed.reason.contains("暂缓"))
    }

    @Test
    fun persistentOutflowWindowDelaysPromotionWithoutCreatingIntradaySignal() {
        val window = FundFlowWindowPolicy.assess(
            (1..5).map {
                FundFlow(
                    code = "sh600000",
                    name = "浦发银行",
                    asOfMs = it.toLong(),
                    amountYuan = 10_000_000.0,
                    mainNetPct = -5.0,
                )
            },
        )
        val delayed = ObservationPool.evaluate(
            sealedCandidate(),
            openSignal(evaluatedAtMs = 1_000L).copy(fundFlowWindow = window),
        )

        assertEquals(ObservationStage.OBSERVING, delayed.stage)
        assertTrue(delayed.reason.contains("暂缓"))
        assertTrue(delayed.reason.contains("不影响盘中提醒"))
    }

    @Test
    fun sealQualityDoesNotDoubleCountTechnicalScore() {
        val highQuality = ObservationPool.evaluate(
            sealedCandidate().copy(sealQualityScore = 80, sealQualityCoveragePct = 80),
            openSignal(evaluatedAtMs = 1_000L).copy(strongScore = 2),
        )
        val weakQuality = ObservationPool.evaluate(
            sealedCandidate().copy(sealQualityScore = 30, sealQualityCoveragePct = 80),
            openSignal(evaluatedAtMs = 1_000L).copy(strongScore = 2),
        )

        assertEquals(2, highQuality.score)
        assertEquals(2, weakQuality.score)
    }

    @Test
    fun weakHighBoardSealCannotPromote() {
        val result = ObservationPool.evaluate(
            sealedCandidate().copy(
                limitStreak = 3,
                sealQualityScore = 40,
                sealQualityCoveragePct = 80,
            ),
            openSignal(evaluatedAtMs = 1_000L),
        )

        assertFalse(result.stage == ObservationStage.BUY_CANDIDATE)
        assertTrue(result.reason.contains("封板质量偏弱"))
    }

    @Test
    fun continuousObservationSurvivesJsonRoundTrip() {
        val original = sealedCandidate().copy(
            limitStreak = 3,
            sealQualityScore = 72,
            sealQualityCoveragePct = 80,
            boards = listOf("新能源", "机器人"),
        )
        val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(original))).single()

        assertEquals(original, restored)
    }

    @Test
    fun continuousObservationSurvivesOpeningAndResealing() {
        val opened = ObservationPool.evaluate(
            sealedCandidate().copy(observedDays = ObservationPool.MAX_OBSERVED_DAYS),
            openSignal(evaluatedAtMs = 1_000L),
        )
        val resealed = ObservationPool.evaluate(
            opened,
            sealedSignal().copy(evaluatedAtMs = 1_900_000L),
        )

        assertTrue(opened.continuousObservation)
        assertTrue(resealed.continuousObservation)
        assertEquals(ObservationStage.OBSERVING, resealed.stage)
    }

    private fun limitStock(
        openCount: Int = 0,
        sealToFloatPct: Double = 0.5,
        sealToTurnoverPct: Double = 5.0,
    ): LimitStock {
        val seal = 1_000_000.0
        return LimitStock(
            code = "sh600000",
            name = "浦发银行",
            price = 11.0,
            prev = 10.0,
            pct = 10.0,
            sealAmountYuan = seal,
            turnoverYuan = seal / (sealToTurnoverPct / 100.0),
            floatMarketValueYuan = seal / (sealToFloatPct / 100.0),
            openCount = openCount,
            limitStreak = 1,
            source = LimitDataSource.EASTMONEY_LIMIT_POOL,
        )
    }

    private fun sealedCandidate() = ObservationCandidate(
        code = "sh600000",
        name = "浦发银行",
        addedDate = "2026-10-22",
        lastEvaluatedDate = null,
        stage = ObservationStage.OBSERVING,
        score = 0,
        weakDays = 0,
        strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
        continuousObservation = true,
        limitUpBlocked = true,
    )

    private fun sealedSignal() = ObservationSignal(
        date = "2026-10-22",
        price = 11.0,
        hasScreenSignal = true,
        confirmedEntry = true,
        canBuy = true,
        trendWeak = false,
        stopPrice = 9.5,
        changePct = 10.0,
        strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
        strongScore = 3,
        limitUpBlocked = true,
    )

    private fun openSignal(
        evaluatedAtMs: Long,
        quoteVolume: Double = 1_000.0,
    ) = ObservationSignal(
        date = "2026-10-22",
        price = 10.8,
        hasScreenSignal = true,
        confirmedEntry = true,
        canBuy = true,
        trendWeak = false,
        stopPrice = 9.5,
        changePct = 8.0,
        strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
        strongScore = 3,
        evaluatedAtMs = evaluatedAtMs,
        quoteVolume = quoteVolume,
    )
}
