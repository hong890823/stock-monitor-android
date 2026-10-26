package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPoolTest {
    private val majorDown = TrendAssessment(
        shortTerm = ShortTermTrend.DOWN,
        major = MajorTrend.DOWN,
        reason = "测试大趋势向下",
        allowsOrdinaryBuy = false,
        allowsAddPosition = false,
        allowsTBuy = false,
        allowsTSell = false,
        allowsBottomReversal = false,
        riskOnly = true,
    )

    private val majorBottoming = TrendAssessment(
        shortTerm = ShortTermTrend.REBOUND,
        major = MajorTrend.BOTTOMING,
        reason = "测试连续5日不创新低",
        allowsOrdinaryBuy = false,
        allowsAddPosition = false,
        allowsTBuy = false,
        allowsTSell = false,
        allowsBottomReversal = true,
        riskOnly = false,
    )

    private fun candidate() = ObservationCandidate(
        code = "sh600000",
        name = "浦发银行",
        addedDate = "2026-10-09",
        lastEvaluatedDate = null,
        stage = ObservationStage.OBSERVING,
        score = 0,
        weakDays = 0,
        lastPrice = 10.0,
        reason = "刚加入观察池",
    )

    @Test
    fun confirmedSignalMovesCandidateToBuyPool() {
        val result = ObservationPool.evaluate(
            candidate(),
            ObservationSignal(
                date = "2026-10-13",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 2.0,
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, result.stage)
        assertEquals(3, result.score)
        assertEquals(0, result.weakDays)
    }

    @Test
    fun majorDownCannotPromoteObservationCandidate() {
        val result = ObservationPool.evaluate(
            candidate(),
            ObservationSignal(
                date = "2026-10-13",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 2.0,
                trendAssessment = majorDown,
            ),
        )

        assertNotEquals(ObservationStage.BUY_CANDIDATE, result.stage)
        assertTrue(result.reason.contains("大趋势向下"))
    }

    @Test
    fun bottomingUsesBottomReversalReasonInsteadOfNormalBuy() {
        val result = ObservationPool.evaluate(
            candidate(),
            ObservationSignal(
                date = "2026-10-13",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 2.0,
                trendAssessment = majorBottoming,
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
        assertTrue(result.reason.contains("底部反转"))
        assertTrue(result.reason.contains("25%"))
    }

    @Test
    fun limitUpBlockedSignalWaitsAndReentersBuyPoolAfterOpening() {
        val blocked = ObservationPool.evaluate(
            candidate(),
            ObservationSignal(
                date = "2026-10-13",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = false,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 10.0,
                setupScore = 3,
                limitUpBlocked = true,
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, blocked.stage)
        assertTrue(blocked.reason.contains("涨停封板"))

        val reopened = ObservationPool.evaluate(
            blocked,
            ObservationSignal(
                date = "2026-10-14",
                price = 10.1,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 1.5,
                setupScore = 3,
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, reopened.stage)
    }

    @Test
    fun threeConsecutiveWeakDaysEliminateCandidate() {
        var current = candidate()
        repeat(3) { index ->
            current = ObservationPool.evaluate(
                current,
                ObservationSignal(
                    date = "2026-10-${10 + index}",
                    price = 9.8 - index * 0.2,
                    hasScreenSignal = false,
                    confirmedEntry = false,
                    canBuy = false,
                    trendWeak = true,
                    stopPrice = null,
                    changePct = -1.0,
                ),
            )
        }

        assertEquals(ObservationStage.ELIMINATED, current.stage)
        assertTrue(current.reason.contains("连续"))
    }

    @Test
    fun sameTradingDayDoesNotAccumulateWeakDaysTwice() {
        val first = ObservationPool.evaluate(
            candidate(),
            ObservationSignal("2026-10-13", 9.8, false, false, false, true, null, -1.0),
        )
        val second = ObservationPool.evaluate(
            first,
            ObservationSignal("2026-10-13", 9.7, false, false, false, true, null, -2.0),
        )

        assertEquals(1, second.weakDays)
        assertEquals(ObservationStage.OBSERVING, second.stage)
    }

    @Test
    fun alreadyEvaluatedBuyCandidateAtLimitUpNeedsIntradayRefresh() {
        val buyCandidate = candidate().copy(
            lastEvaluatedDate = "2026-10-14",
            stage = ObservationStage.BUY_CANDIDATE,
        )
        val quote = Quote(
            code = buyCandidate.code,
            name = buyCandidate.name,
            price = 11.0,
            prevClose = 10.0,
            open = 10.2,
            high = 11.0,
            low = 10.1,
            volume = 1_000_000.0,
            turnover = 10_000_000.0,
            quoteDate = "2026-10-14",
        )

        assertTrue(
            ObservationEligibility.needsRefresh(
                candidate = buyCandidate,
                today = "2026-10-14",
                quote = quote,
            ),
        )
    }

    @Test
    fun limitUpShrinkAccelerationDowngradesToObserving() {
        val result = ObservationPool.evaluate(
            candidate(),
            ObservationSignal(
                date = "2026-10-14",
                price = 11.0,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = false,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 10.0,
                setupScore = 3,
                limitUpBlocked = true,
                limitUpShrinkAcceleration = true,
            ),
        )

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertTrue(result.reason.contains("缩量加速"))
    }

    @Test
    fun limitBlockOverridesAnOtherwiseConfirmedBuySignal() {
        val result = ObservationPool.evaluate(
            candidate().copy(stage = ObservationStage.BUY_CANDIDATE),
            ObservationSignal("2026-10-14", 11.0, true, true, true, false, 9.6, 10.0,
                limitUpBlocked = true),
        )
        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
    }

    @Test
    fun breakoutReversalRiskDowngradesCandidateAndAlertsOnce() {
        val original = candidate().copy(stage = ObservationStage.BUY_CANDIDATE)
        val risk = ObservationPool.evaluate(
            original,
            ObservationSignal(
                date = "2026-10-14",
                price = 10.2,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = -2.5,
                breakoutRisk = BreakoutRiskDecision(true, "跌破5日线，突破延续转为下杀风险"),
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, risk.stage)
        assertEquals(1, risk.softRiskRounds)
        assertTrue(risk.reason.contains("下杀风险"))

        val secondRisk = ObservationPool.evaluate(
            risk,
            ObservationSignal(
                date = "2026-10-14",
                price = 10.1,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = -3.5,
                breakoutRisk = BreakoutRiskDecision(true, "跌破5日线，突破延续转为下杀风险"),
            ),
        )

        assertEquals(ObservationStage.OBSERVING, secondRisk.stage)
        assertEquals(2, secondRisk.softRiskRounds)
        assertTrue(ObservationBreakoutRiskAlert.shouldNotify(original, secondRisk))
        assertFalse(ObservationBreakoutRiskAlert.shouldNotify(secondRisk, secondRisk))
    }

    @Test
    fun hardRiskDowngradesBuyCandidateOneLevelAtATime() {
        val result = ObservationPool.evaluate(
            candidate().copy(stage = ObservationStage.BUY_CANDIDATE),
            ObservationSignal(
                date = "2026-10-14",
                price = 10.2,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = -2.5,
                trendAssessment = majorDown,
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
        assertEquals(0, result.softRiskRounds)

        val next = ObservationPool.evaluate(
            result,
            ObservationSignal(
                date = "2026-10-14",
                price = 10.1,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = -3.0,
                trendAssessment = majorDown,
            ),
        )
        assertEquals(ObservationStage.OBSERVING, next.stage)
    }

    @Test
    fun candidateRecordsHighestStageAndStatusChanges() {
        val original = candidate().copy(
            stage = ObservationStage.BUY_CANDIDATE,
            highestStage = ObservationStage.BUY_CANDIDATE,
            highestStageDate = "2026-10-13",
            highestStageAtMs = 1000L,
        )
        val downgraded = ObservationPool.evaluate(
            original,
            ObservationSignal(
                date = "2026-10-14",
                price = 10.2,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = -2.5,
                breakoutRisk = BreakoutRiskDecision(true, "跌破5日线"),
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, downgraded.highestStage)
        assertEquals("2026-10-13", downgraded.highestStageDate)
        assertEquals(1, downgraded.statusChanges.size)
        assertEquals(ObservationStage.NEAR_ENTRY, downgraded.statusChanges.single().stage)
    }

    @Test
    fun shrinkAccelerationRemainsEligibleForIntradayReevaluation() {
        val waiting = candidate().copy(
            lastEvaluatedDate = "2026-10-14",
            reason = "涨停缩量加速，暂不参与，等待放量换手或开板后确认",
        )
        val quote = Quote(waiting.code, waiting.name, 10.8, 10.0, 10.2, 11.0, 10.1,
            1_000_000.0, 10_000_000.0, "2026-10-14")
        assertTrue(ObservationEligibility.needsRefresh(waiting, "2026-10-14", quote))
    }
}
