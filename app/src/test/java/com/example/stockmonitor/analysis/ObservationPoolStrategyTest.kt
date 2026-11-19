package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStructureQuality
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.example.stockmonitor.core.ObservationPoolJson
import com.example.stockmonitor.core.ObservationRiskSignal
import com.example.stockmonitor.core.ObservationRiskType
import org.junit.Test

class ObservationPoolStrategyTest {
    private val entry = ObservationSignal(
        "2026-10-15", 10.2, true, true, true, false, 9.5, 1.0,
        strategy = ObservationStrategy.LEADER, strongScore = 3,
        evaluatedAtMs = 1_000_000L, quoteVolume = 1000.0,
        technicalScore = 78, weeklyTrendLabel = "向上", monthlyTrendLabel = "修复中",
    )

    private val repairTrend = TrendAssessment(
        shortTerm = ShortTermTrend.EXTENDED,
        major = MajorTrend.REPAIR,
        reason = "日线短期强势且站上MA20，但完整周/月周期尚未转强；修复待确认，不追高，等待回调企稳后再评估",
        allowsOrdinaryBuy = false,
        allowsAddPosition = false,
        allowsTBuy = false,
        allowsTSell = false,
        allowsBottomReversal = false,
        riskOnly = false,
    )

    @Test
    fun leaderNeedsTwoSpacedFreshEvaluationsAndNotifiesOnlyOnTransition() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        assertEquals(ObservationStage.NEAR_ENTRY, first.stage)
        val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(first))).single()
        assertEquals(first, restored)
        assertEquals(78, restored.technicalScore)
        assertEquals("向上", restored.weeklyTrendLabel)
        assertEquals("修复中", restored.monthlyTrendLabel)
        val second = ObservationPool.evaluate(restored, entry.copy(evaluatedAtMs = 1_900_000L))
        assertEquals(ObservationStage.BUY_CANDIDATE, second.stage)
        assertTrue(ObservationBuyAlert.shouldNotify(first, second))
        assertFalse(ObservationBuyAlert.shouldNotify(second, second))
    }

    @Test
    fun cumulativeVolumeAloneDoesNotCompleteConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val repeated = ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_060_000L, quoteVolume = 1100.0))
        assertEquals(ObservationStage.NEAR_ENTRY, repeated.stage)
        assertEquals(1, repeated.entryConfirmationRounds)

        val freshWithoutVolumeChange = ObservationPool.evaluate(
            first,
            entry.copy(evaluatedAtMs = 1_900_000L, quoteVolume = 1000.0),
        )
        assertEquals(ObservationStage.BUY_CANDIDATE, freshWithoutVolumeChange.stage)
    }

    @Test
    fun newDayDoesNotCompleteConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val nextDay = ObservationPool.evaluate(
            first,
            entry.copy(date = "2026-10-16", evaluatedAtMs = 1_900_000L),
        )
        assertEquals(ObservationStage.NEAR_ENTRY, nextDay.stage)
        assertEquals(1, nextDay.entryConfirmationRounds)
    }

    @Test
    fun afterHoursReevaluationKeepsSameDayConfirmedBuyWithoutResettingRounds() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val confirmed = ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_900_000L))
        assertEquals(ObservationStage.BUY_CANDIDATE, confirmed.stage)

        val afterHours = ObservationPool.evaluate(
            confirmed,
            entry.copy(evaluatedAtMs = 50_000_000L, inTradingSession = false),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, afterHours.stage)
        assertEquals(confirmed.entryConfirmationRounds, afterHours.entryConfirmationRounds)
        assertEquals(confirmed.entryConfirmedAtMs, afterHours.entryConfirmedAtMs)
    }

    @Test
    fun inSessionExpiredConfirmationStillResetsForFreshEntry() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val confirmed = ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_900_000L))

        val expired = ObservationPool.evaluate(
            confirmed,
            entry.copy(evaluatedAtMs = 50_000_000L, inTradingSession = true),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, expired.stage)
        assertEquals(1, expired.entryConfirmationRounds)
    }

    @Test
    fun ordinaryThreeBoardCandidateUsesTwoSpacedRounds() {
        val highBoard = candidate(ObservationStrategy.LIMIT_UP_CONTINUATION).copy(
            continuousObservation = true,
            limitUpBlocked = true,
            limitStreak = 3,
        )
        val opened = entry.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            recentLimitUpDays = 3,
            evaluatedAtMs = 1_000_000L,
        )
        val first = ObservationPool.evaluate(highBoard, opened)
        val second = ObservationPool.evaluate(first, opened.copy(evaluatedAtMs = 1_900_000L))
        val third = ObservationPool.evaluate(second, opened.copy(evaluatedAtMs = 2_800_000L))

        assertEquals(1, first.entryConfirmationRounds)
        assertEquals(2, second.entryConfirmationRounds)
        assertEquals(ObservationStage.BUY_CANDIDATE, second.stage)
    }

    @Test
    fun riskAndLimitGateResetPendingConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val blocked = ObservationEligibility.applyQuoteGate(first.copy(limitUpBlocked = true), null)
        assertEquals(0, blocked.entryConfirmationRounds)
        val risk = ObservationPool.evaluate(first, entry.copy(riskFlag = true))
        assertEquals(0, risk.entryConfirmationRounds)
        val restarted = ObservationPool.evaluate(risk, entry.copy(evaluatedAtMs = 1_900_000L))
        assertEquals(ObservationStage.NEAR_ENTRY, restarted.stage)
    }

    @Test
    fun riskFlagCannotPromoteObservingCandidate() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            entry.copy(
                riskFlag = true,
                signalReason = "高位放量分歧",
            ),
        )

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertEquals(ObservationRiskType.MOMENTUM_DIVERGENCE, result.riskSignals.single().type)
        assertEquals("高位放量分歧", result.riskSignals.single().detail)
    }

    @Test
    fun fundFlowRiskKeepsTodayAndThreeDayEvidenceInOneSignal() {
        val flows = (1..3).map { day ->
            FundFlow(
                code = "sh600000",
                name = "测试",
                asOfMs = day.toLong(),
                amountYuan = 500_000_000.0,
                mainNetYuan = -10_000_000.0,
                mainNetPct = -4.0,
                tradingDate = "2026-10-${14 + day}",
            )
        }
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            entry.copy(
                changePct = -1.2,
                fundFlow = FundFlowPolicy.assess(-1.2, flows.last()),
                fundFlowWindow = FundFlowWindowPolicy.assess(flows),
            ),
        )

        val signal = result.riskSignals.single { it.type == ObservationRiskType.FUND_FLOW_OUTFLOW }
        assertTrue(signal.detail.contains("今日主力资金净流出"))
        assertTrue(signal.detail.contains("近3日主力资金净流出合计"))
    }

    @Test
    fun transientSoftRiskPausesButDoesNotResetEntryConfirmation() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val paused = ObservationPool.evaluate(
            first,
            entry.copy(
                evaluatedAtMs = 1_060_000L,
                breakoutRisk = BreakoutRiskDecision(true, "跌破5日线，突破延续转为下杀风险"),
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, paused.stage)
        assertEquals(1, paused.entryConfirmationRounds)

        val confirmed = ObservationPool.evaluate(
            paused,
            entry.copy(evaluatedAtMs = 1_900_000L),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, confirmed.stage)
        assertEquals(2, confirmed.entryConfirmationRounds)
    }

    @Test
    fun tightenedEnvironmentOnlyMovesConfirmedCandidateOneLevelDownWhenTrendRemainsIntact() {
        val confirmed = candidate(ObservationStrategy.LEADER).copy(
            stage = ObservationStage.BUY_CANDIDATE,
            highestStage = ObservationStage.BUY_CANDIDATE,
            structureQuality = ObservationStructureQuality.M20_PULLBACK_CONFIRMED,
        )

        val result = ObservationPool.evaluate(
            confirmed,
            entry.copy(environmentTightened = true, evaluatedAtMs = 2_000_000L),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
    }

    @Test
    fun confirmationFailsWhenPriceChasesTooFarAboveTrigger() {
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.LEADER), entry)
        val chased = ObservationPool.evaluate(
            first,
            entry.copy(price = 10.6, evaluatedAtMs = 1_900_000L),
        )

        assertEquals(ObservationStage.OBSERVING, chased.stage)
        assertEquals(1, chased.entryConfirmationRounds)
        assertTrue(chased.reason.contains("不追高"))
        assertTrue(chased.riskSignals.any { it.type.name == "ENTRY_CHASE" })
    }

    @Test
    fun dailyStrongButCyclesWeakIsRepairWatchWithoutHardTrendRisk() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            entry.copy(trendAssessment = repairTrend),
        )

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertTrue(result.reason.contains("修复观察"))
        assertTrue(result.reason.contains("回调企稳"))
        assertFalse(result.riskSignals.any { it.type == ObservationRiskType.TREND_WEAK })
        assertEquals(ObservationPool.CURRENT_RISK_POLICY_VERSION, result.riskPolicyVersion)
    }

    @Test
    fun confirmedPostLimitMa20BaseCanStillEnterAfterCyclesTurnWeak() {
        val highPositionWatch = HighPositionDecision(
            phase = HighPositionPhase.EXTENDED_WATCH,
            reason = "处于高位区间，但整理质量不足，不视为二波结构",
            allowBuy = false,
            allowSecondWaveEntry = false,
            allowHoldBase = false,
            riskPriority = false,
        )
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER).copy(limitStreak = 2),
            entry.copy(
                trendAssessment = repairTrend,
                highPosition = highPositionWatch,
                postLimitDailyBaseEntry = true,
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, result.stage)
        assertTrue(result.reason.contains("回踩 MA20"))
        assertTrue(result.reason.contains("轻仓"))
        assertFalse(result.riskSignals.any { it.type == ObservationRiskType.TREND_WEAK })
    }

    @Test
    fun majorDownStillBlocksConfirmedPostLimitMa20Base() {
        val majorDown = repairTrend.copy(
            major = MajorTrend.DOWN,
            reason = "按已完成周K/月K仍弱",
            riskOnly = true,
        )
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER).copy(limitStreak = 2),
            entry.copy(
                trendAssessment = majorDown,
                postLimitDailyBaseEntry = true,
            ),
        )

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertTrue(result.reason.contains("大趋势向下"))
        assertTrue(result.riskSignals.any { it.type == ObservationRiskType.TREND_WEAK })
    }

    @Test
    fun structuredRiskAndEntryTimingSurviveJsonRoundTrip() {
        val original = candidate(ObservationStrategy.LEADER).copy(
            entryConfirmationRounds = 1,
            entryConfirmedAtMs = 1_000_000L,
            entryConfirmationVolume = 1_000.0,
            entryTriggerPrice = 10.2,
            entryTriggerAtMs = 1_000_000L,
            softRiskRounds = 1,
            softRiskLastConfirmedAtMs = 1_100_000L,
            sourceExpiresAtMs = 9_000_000L,
            postConfirmationWatchDays = 4,
            alertState = com.example.stockmonitor.core.ObservationAlertState.SENT_CAUTION,
            alertDetail = "个股量能证据不足",
            alertUpdatedAtMs = 1_200_000L,
            riskSignals = listOf(
                ObservationRiskSignal(
                    type = ObservationRiskType.BREAKOUT_FAILURE,
                    detail = "突破参考位 10.50，现价 10.10（-3.8%），量比1.60",
                    keyLevel = 10.5,
                    currentValue = 10.1,
                    threshold = 10.4475,
                ),
            ),
        )

        assertEquals(original, ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(original))).single())
    }

    @Test
    fun threeBoardIdentityKeepsThreeFreshConfirmationRoundsAfterOpening() {
        val formerThreeBoard = candidate(ObservationStrategy.LIMIT_UP_CONTINUATION).copy(
            limitStreak = 3,
            sealQualityScore = 20,
            sealQualityCoveragePct = 100,
        )
        val first = ObservationPool.evaluate(
            formerThreeBoard,
            entry.copy(
                strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
                recentLimitUpDays = 0,
            ),
        )
        val second = ObservationPool.evaluate(
            first,
            entry.copy(
                strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
                recentLimitUpDays = 0,
                evaluatedAtMs = 1_900_000L,
            ),
        )
        val third = ObservationPool.evaluate(
            second,
            entry.copy(
                strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
                recentLimitUpDays = 0,
                evaluatedAtMs = 2_800_000L,
            ),
        )

        assertEquals(3, first.entryConfirmationRequiredRounds)
        assertEquals(ObservationStage.NEAR_ENTRY, second.stage)
        assertEquals(ObservationStage.BUY_CANDIDATE, third.stage)
    }

    @Test
    fun postLimitHighHoldNeedsFiveMinutesOfPlatformSupport() {
        val signal = entry.copy(
            postLimitHighHoldCandidate = true,
            trendAssessment = repairTrend,
        )
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.PRE_BREAKOUT), signal)
        val fourMinutes = ObservationPool.evaluate(
            first,
            signal.copy(evaluatedAtMs = signal.evaluatedAtMs + 4 * 60_000L, price = 10.88),
        )
        val fiveMinutes = ObservationPool.evaluate(
            fourMinutes,
            signal.copy(evaluatedAtMs = signal.evaluatedAtMs + 5 * 60_000L, price = 10.86),
        )

        assertEquals(ObservationStage.OBSERVING, first.stage)
        assertTrue(first.reason.contains("等待平台承接满5分钟"))
        assertEquals(ObservationStage.OBSERVING, fourMinutes.stage)
        assertEquals(ObservationStage.BUY_CANDIDATE, fiveMinutes.stage)
        assertTrue(fiveMinutes.reason.contains("炸板后守住涨停高位线"))
    }

    @Test
    fun postLimitHighHoldRestartsTheFiveMinuteClockAfterPlatformBreak() {
        val signal = entry.copy(
            postLimitHighHoldCandidate = true,
            trendAssessment = repairTrend,
            price = 10.90,
        )
        val first = ObservationPool.evaluate(candidate(ObservationStrategy.PRE_BREAKOUT), signal)
        val broken = ObservationPool.evaluate(
            first,
            signal.copy(evaluatedAtMs = signal.evaluatedAtMs + 3 * 60_000L, price = 10.80),
        )
        val notYetFiveMinutesAfterRestart = ObservationPool.evaluate(
            broken,
            signal.copy(evaluatedAtMs = signal.evaluatedAtMs + 6 * 60_000L, price = 10.82),
        )
        val fiveMinutesAfterRestart = ObservationPool.evaluate(
            notYetFiveMinutesAfterRestart,
            signal.copy(evaluatedAtMs = signal.evaluatedAtMs + 8 * 60_000L, price = 10.83),
        )

        assertEquals(ObservationStage.OBSERVING, broken.stage)
        assertEquals(ObservationStage.OBSERVING, notYetFiveMinutesAfterRestart.stage)
        assertEquals(ObservationStage.BUY_CANDIDATE, fiveMinutesAfterRestart.stage)
    }

    @Test
    fun multipleScreeningSourcesSurviveJsonRoundTripWithoutChangingPrimarySource() {
        val multiSource = candidate(ObservationStrategy.PRE_BREAKOUT).copy(
            source = "趋势深扫",
            sources = linkedSetOf("指标严格", "趋势深扫", "513形态"),
            sourceValidTradingDays = 3,
            sourceObservedDays = 1,
        )

        val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(multiSource))).single()

        assertEquals(multiSource, restored)
        assertEquals("趋势深扫", restored.source)
        assertEquals(setOf("指标严格", "趋势深扫", "513形态"), restored.sources)
    }

    @Test
    fun continuousOpeningGraceLastsOnlyOneEvaluation() {
        val sealed = candidate(ObservationStrategy.LIMIT_UP_CONTINUATION).copy(
            stage = ObservationStage.NEAR_ENTRY,
            continuousObservation = true,
            limitUpBlocked = true,
            entryConfirmationRounds = 1,
            observedDays = 9,
        )
        val opened = entry.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitUpBlocked = false,
        )

        val firstOpenedEvaluation = ObservationPool.evaluate(sealed, opened)
        val nextTradingDay = ObservationPool.evaluate(
            firstOpenedEvaluation,
            opened.copy(date = "2026-10-16", evaluatedAtMs = 1_900_000L),
        )

        assertNotEquals(ObservationStage.ELIMINATED, firstOpenedEvaluation.stage)
        assertEquals(ObservationStage.ELIMINATED, nextTradingDay.stage)
    }

    private fun candidate(strategy: ObservationStrategy) = ObservationCandidate(
        code = "sh600000",
        name = "浦发银行",
        addedDate = "2026-10-14",
        lastEvaluatedDate = null,
        stage = ObservationStage.OBSERVING,
        score = 0,
        weakDays = 0,
        strategy = strategy,
    )

    @Test
    fun preBreakoutUsesTrendConfirmation() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.PRE_BREAKOUT),
            ObservationSignal(
                date = "2026-10-14",
                price = 10.3,
                hasScreenSignal = true,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 9.6,
                changePct = 2.0,
                setupScore = 3,
                signalReason = "趋势突破后守住",
            ),
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, result.stage)
        assertEquals("启动前潜力", result.strategy.label)
    }

    @Test
    fun leaderNeedsStrengthAndNoRisk() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            ObservationSignal(
                date = "2026-10-14",
                price = 12.0,
                hasScreenSignal = false,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = 11.2,
                changePct = 4.0,
                strongScore = 3,
                recentLimitUpDays = 2,
                relativeStrengthPct = 5.5,
                volumeRatio = 1.6,
                signalReason = "连板后强势承接",
            ),
        )

        assertEquals(ObservationStage.NEAR_ENTRY, result.stage)
        assertEquals(3, result.score)
    }

    @Test
    fun leaderRiskCannotBecomeBuyCandidate() {
        val result = ObservationPool.evaluate(
            candidate(ObservationStrategy.LEADER),
            ObservationSignal(
                date = "2026-10-14",
                price = 11.0,
                hasScreenSignal = false,
                confirmedEntry = true,
                canBuy = true,
                trendWeak = false,
                stopPrice = null,
                changePct = 8.8,
                strongScore = 3,
                recentLimitUpDays = 3,
                relativeStrengthPct = 9.0,
                volumeRatio = 2.8,
                riskFlag = true,
                signalReason = "高位放量分歧",
            ),
        )

        assertEquals(ObservationStage.OBSERVING, result.stage)
    }
}
