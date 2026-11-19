package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.FundFlow
import com.example.stockmonitor.core.ObservationMarketRisk
import com.example.stockmonitor.core.ObservationEvidence
import com.example.stockmonitor.core.ObservationPoolJson
import com.example.stockmonitor.core.ObservationRiskSignal
import com.example.stockmonitor.core.ObservationRiskType
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStructureQuality
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.Quote
import org.junit.Assert.*
import org.junit.Test

class ObservationEligibilityTest {
    private val candidate = ObservationCandidate(
        "sh600000", "测试", "2026-10-13", "2026-10-14",
        ObservationStage.BUY_CANDIDATE, 3, 0, observedDays = 2,
        riskPolicyVersion = ObservationPool.CURRENT_RISK_POLICY_VERSION,
    )
    private val sealed = Quote(candidate.code, candidate.name, 11.0, 10.0, 10.2,
        11.0, 10.1, 1000.0, 10000.0, "2026-10-14")

    @Test
    fun ordinaryObservingCandidateNeedsIntradayRefreshToo() {
        assertTrue(ObservationEligibility.needsRefresh(
            candidate.copy(stage = ObservationStage.OBSERVING), "2026-10-14", sealed.copy(price = 10.2),
        ))
    }

    @Test
    fun staleOrInvalidQuotesCannotAdvanceObservation() {
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-15", sealed))
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-14", sealed.copy(price = Double.NaN)))
        assertFalse(ObservationEligibility.needsRefresh(candidate, "2026-10-14", null))
        assertFalse(ObservationEligibility.needsRefresh(
            candidate.copy(stage = ObservationStage.ELIMINATED), "2026-10-14", sealed,
        ))
    }

    @Test
    fun legacyLeaderBuyCandidateMustBeReconfirmed() {
        val legacy = candidate.copy(strategy = ObservationStrategy.LEADER)
        val displayed = ObservationEligibility.forDisplay(listOf(legacy), emptyMap()).single()
        assertEquals(ObservationStage.NEAR_ENTRY, displayed.stage)
    }

    @Test
    fun legacyThreeBoardBuyCandidateNeedsThirdRound() {
        val legacy = candidate.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            sealQualityScore = 20,
            sealQualityCoveragePct = 100,
            entryConfirmationRounds = 2,
        )
        val displayed = ObservationEligibility.applyQuoteGate(legacy, sealed.copy(price = 10.5))

        assertEquals(ObservationStage.NEAR_ENTRY, displayed.stage)
    }

    @Test
    fun ordinaryThreeBoardBuyCandidateCanStayConfirmedAfterTwoRounds() {
        val current = candidate.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            limitStreak = 3,
            entryConfirmationRounds = 2,
        )

        assertEquals(
            ObservationStage.BUY_CANDIDATE,
            ObservationEligibility.applyQuoteGate(current, sealed.copy(price = 10.5)).stage,
        )
    }

    @Test
    fun postLimitHighHoldUsesLimitDistanceForTenTwentyAndThirtyPercentBoards() {
        val mainBoard = Quote("sh600000", "主板测试", 10.70, 10.0, 10.1, 11.0, 10.1, 1_000.0, 10_000.0, "2026-10-14")
        assertTrue(ObservationEligibility.isPostLimitHighHoldCandidate(mainBoard.code, mainBoard.name, mainBoard))
        assertFalse(ObservationEligibility.isPostLimitHighHoldCandidate(
            mainBoard.code,
            mainBoard.name,
            mainBoard.copy(price = 10.69),
        ))

        val growthBoard = Quote("sz300001", "创业板测试", 29.30, 25.0, 25.2, 30.0, 25.1, 1_000.0, 10_000.0, "2026-10-14")
        assertTrue(ObservationEligibility.isPostLimitHighHoldCandidate(growthBoard.code, growthBoard.name, growthBoard))
        assertFalse(ObservationEligibility.isPostLimitHighHoldCandidate(
            growthBoard.code,
            growthBoard.name,
            growthBoard.copy(price = 27.00),
        ))

        val northBoard = Quote("bj920001", "北交所测试", 12.80, 10.0, 10.2, 13.0, 10.1, 1_000.0, 10_000.0, "2026-10-14")
        assertTrue(ObservationEligibility.isPostLimitHighHoldCandidate(northBoard.code, northBoard.name, northBoard))
        assertFalse(ObservationEligibility.isPostLimitHighHoldCandidate(
            northBoard.code,
            northBoard.name,
            northBoard.copy(price = 12.60),
        ))
    }

    @Test
    fun freshNewDayQuoteDemotesConfirmedBuyUntilNextEvaluation() {
        ObservationStrategy.entries.forEach { strategy ->
            val old = candidate.copy(strategy = strategy, entryConfirmationRounds = 2)
            val quote = sealed.copy(price = 10.2, quoteDate = "2026-10-15")
            val display = ObservationEligibility.applyQuoteGate(old, quote)
            assertEquals(ObservationStage.NEAR_ENTRY, display.stage)
            assertEquals(0, display.entryConfirmationRounds)
            assertEquals(ObservationStage.BUY_CANDIDATE, old.stage)
        }
    }

    @Test
    fun everyStageHasIndependentPagesAndFullCounts() {
        assertEquals(8, ObservationEligibility.PAGE_SIZE)
        val buys = (1..105).map { candidate.copy(code = "sh${600000 + it}") }
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val sections = ObservationEligibility.sections(buys + near, emptyMap())
        assertEquals(105, sections.getValue(ObservationSection.BUY).size)
        assertEquals(listOf(near), sections.getValue(ObservationSection.NEAR))
        val lastPage = (buys.size - 1) / ObservationEligibility.PAGE_SIZE
        val pages = (0..lastPage).flatMap {
            ObservationEligibility.page(sections.getValue(ObservationSection.BUY), it)
        }
        assertEquals(buys, pages)
        assertEquals(listOf(near), ObservationEligibility.page(sections.getValue(ObservationSection.NEAR), 0))
    }

    @Test
    fun candidatesWithinTheSameSectionDoNotUseQualificationScoreAsDisplayRanking() {
        val lowScore = candidate.copy(
            code = "sz000011",
            stage = ObservationStage.NEAR_ENTRY,
            score = 1,
            structureQuality = ObservationStructureQuality.NONE,
        )
        val highScore = candidate.copy(
            code = "sz000012",
            stage = ObservationStage.NEAR_ENTRY,
            score = 3,
            structureQuality = ObservationStructureQuality.M20_PULLBACK_CONFIRMED,
        )
        val middleScore = candidate.copy(
            code = "sz000013",
            stage = ObservationStage.NEAR_ENTRY,
            score = 2,
            structureQuality = ObservationStructureQuality.BREAKOUT_CONFIRMED,
        )

        val near = ObservationEligibility
            .entrySections(listOf(lowScore, highScore, middleScore), emptyMap())
            .getValue(ObservationSection.NEAR)

        assertEquals(listOf("sz000012", "sz000013", "sz000011"), near.map { it.code })
    }

    @Test
    fun stateGroupsPutPriorConfirmedNearEntryAheadOfOrdinaryNearWithoutBoardPromotion() {
        val priorityReentry = candidate.copy(
            code = "sh603278",
            stage = ObservationStage.NEAR_ENTRY,
            highestStage = ObservationStage.BUY_CANDIDATE,
            structureQuality = ObservationStructureQuality.M20_PULLBACK_CONFIRMED,
            reason = "等待新的盘中确认",
        )
        val nearFirst = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val observingFirst = candidate.copy(code = "sz000002", stage = ObservationStage.OBSERVING)
        val observingSecond = candidate.copy(code = "sz000003", stage = ObservationStage.OBSERVING)
        val confirmedBuy = candidate.copy(code = "sz000004", stage = ObservationStage.BUY_CANDIDATE)
        val riskMarkedBuy = candidate.copy(
            code = "sz000005",
            stage = ObservationStage.BUY_CANDIDATE,
            marketRisk = ObservationMarketRisk.BLOCKED,
            reason = "已确认",
        )
        val tightenedReentry = priorityReentry.copy(
            code = "sz000006",
            marketRisk = ObservationMarketRisk.TIGHTENED,
            reason = "市场风险提高普通候选晋级门槛，等待更强的日线承接或二波确认",
        )
        val highQualityOnlyReentry = priorityReentry.copy(
            code = "sz000007",
            marketRisk = ObservationMarketRisk.HIGH_QUALITY_ONLY,
            reason = "市场风险提高普通候选晋级门槛，等待更强的日线承接或二波确认",
        )
        val intradayWeakReentry = priorityReentry.copy(
            code = "sz000008",
            evidence = ObservationEvidence(counter = listOf("当前趋势走弱")),
        )
        val temporarilyDeferred = candidate.copy(
            code = "sz000009",
            stage = ObservationStage.NEAR_ENTRY,
            limitUpBlocked = true,
            reason = "涨停封板，暂不介入，开板后重新确认",
        )

        val display = ObservationEligibility.prioritizedSections(
            candidates = listOf(
                priorityReentry,
                nearFirst,
                observingFirst,
                observingSecond,
                confirmedBuy,
                riskMarkedBuy,
                tightenedReentry,
                highQualityOnlyReentry,
                intradayWeakReentry,
                temporarilyDeferred,
            ),
            quotes = emptyMap(),
        )

        assertEquals(listOf(confirmedBuy), display.buy)
        assertEquals(
            listOf(priorityReentry, tightenedReentry, highQualityOnlyReentry),
            display.priorityReentry,
        )
        assertEquals(listOf(nearFirst), display.near)
        assertEquals(listOf(observingFirst, observingSecond), display.observing)
        assertEquals(
            listOf(riskMarkedBuy.code, temporarilyDeferred.code),
            display.temporarilyDeferred.map { it.code },
        )
        assertEquals(listOf(intradayWeakReentry.code), display.risk.map { it.code })
    }

    @Test
    fun bottomReversalNearEntryIsNotDeferredOnlyBecauseOrdinaryBuyIsBlocked() {
        val bottomReversal = candidate.copy(
            code = "sh603160",
            name = "汇顶科技",
            stage = ObservationStage.NEAR_ENTRY,
            structureQuality = ObservationStructureQuality.NONE,
            riskSignals = emptyList(),
            reason = "底部反转观察，最多按初始仓位25%轻仓试错，不晋级正常买点",
        )

        val display = ObservationEligibility.prioritizedSections(listOf(bottomReversal), emptyMap())

        assertEquals(listOf(bottomReversal.code), display.near.map { it.code })
        assertTrue(display.temporarilyDeferred.isEmpty())
        assertTrue(display.risk.isEmpty())
    }

    @Test
    fun strongBottomReversalSetupWithoutHistoryCanEnterPriorityReview() {
        val bottomReversal = candidate.copy(
            code = "sh603160",
            name = "汇顶科技",
            stage = ObservationStage.NEAR_ENTRY,
            highestStage = ObservationStage.NEAR_ENTRY,
            structureQuality = ObservationStructureQuality.SETUP_WATCH,
            technicalScore = 66,
            riskSignals = emptyList(),
            reason = "底部反转观察，最多按初始仓位25%轻仓试错，不晋级正常买点",
        )

        val display = ObservationEligibility.prioritizedSections(listOf(bottomReversal), emptyMap())

        assertEquals(listOf(bottomReversal.code), display.priorityReentry.map { it.code })
        assertTrue(display.temporarilyDeferred.isEmpty())
        assertTrue(display.risk.isEmpty())
    }

    @Test
    fun duplicateCodeCandidatesAreShownOnceWithBestStateAndMergedHistory() {
        val leaderBuy = candidate.copy(
            code = "sz003021",
            strategy = ObservationStrategy.LEADER,
            source = "涨停强势",
            sources = setOf("涨停强势"),
            stage = ObservationStage.BUY_CANDIDATE,
            boards = listOf("通用设备"),
            highestStage = ObservationStage.BUY_CANDIDATE,
            highestStageDate = "2026-10-20",
        )
        val limitWatch = leaderBuy.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            source = "涨停持续观察",
            sources = setOf("涨停持续观察"),
            stage = ObservationStage.OBSERVING,
            continuousObservation = true,
            limitUpBlocked = true,
            limitStreak = 2,
            boards = listOf("机器人"),
            highestStage = ObservationStage.NEAR_ENTRY,
            highestStageDate = "2026-10-19",
        )

        val result = ObservationEligibility.deduplicateCandidates(
            listOf(leaderBuy, limitWatch),
        )

        assertEquals(1, result.size)
        assertEquals(ObservationStage.BUY_CANDIDATE, result.single().stage)
        assertEquals(true, result.single().continuousObservation)
        assertEquals(2, result.single().limitStreak)
        assertEquals(listOf("通用设备", "机器人"), result.single().boards)
        assertEquals(ObservationStage.BUY_CANDIDATE, result.single().highestStage)
        assertEquals(setOf("涨停强势", "涨停持续观察"), result.single().allSources)

        val displayed = ObservationEligibility
            .sections(listOf(leaderBuy, limitWatch), emptyMap())
            .values
            .flatten()
        assertEquals(listOf(ObservationStrategy.LEADER), displayed.map { it.strategy })
    }

    @Test
    fun duplicateCodeDisplayUsesLatestEvaluationStateInsteadOfOlderHigherStage() {
        val staleBuy = candidate.copy(
            code = "sz003021",
            strategy = ObservationStrategy.LEADER,
            stage = ObservationStage.BUY_CANDIDATE,
            lastEvaluatedDate = "2026-10-20",
        )
        val freshRisk = staleBuy.copy(
            strategy = ObservationStrategy.LIMIT_UP_CONTINUATION,
            stage = ObservationStage.OBSERVING,
            lastEvaluatedDate = "2026-10-21",
            reason = "连续2轮风险确认，降级观察",
        )

        val result = ObservationEligibility.deduplicateCandidates(listOf(staleBuy, freshRisk)).single()

        assertEquals(ObservationStage.OBSERVING, result.stage)
        assertEquals("2026-10-21", result.lastEvaluatedDate)
    }

    @Test
    fun quickScanKeepsUnscannedTrendAndPatternSourcesWhileDeepScanReplacesThem() {
        val previous = listOf("指标严格", ObservationEligibility.TREND_SOURCE, ObservationEligibility.PATTERN_SOURCE)
        val current = listOf("指标宽松")

        assertEquals(
            setOf("指标宽松", ObservationEligibility.TREND_SOURCE, ObservationEligibility.PATTERN_SOURCE),
            ObservationEligibility.mergeScreenSources(current, previous, includePatterns = false),
        )
        assertEquals(
            setOf("指标宽松"),
            ObservationEligibility.mergeScreenSources(current, previous, includePatterns = true),
        )
    }

    @Test
    fun entrySectionsOnlyShowTopTwoStagesWithoutDeferredStocksOrMutatingPool() {
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val observing = candidate.copy(code = "sz000002", stage = ObservationStage.OBSERVING)
        val deferred = candidate.copy(code = "sz000003", stage = ObservationStage.NEAR_ENTRY, limitUpBlocked = true)
        val shrinking = candidate.copy(code = "sz000004", limitUpShrinkAcceleration = true)
        val eliminated = candidate.copy(code = "sz000005", stage = ObservationStage.ELIMINATED)
        val atLimit = candidate.copy(code = "sh600001")
        val pool = listOf(candidate, near, observing, deferred, shrinking, eliminated, atLimit)
        val original = pool.toList()
        val sections = ObservationEligibility.entrySections(pool,
            mapOf(atLimit.code to sealed.copy(code = atLimit.code)))

        assertEquals(listOf(ObservationSection.BUY, ObservationSection.NEAR), sections.keys.toList())
        assertEquals(listOf(candidate), sections.getValue(ObservationSection.BUY))
        assertEquals(listOf(near), sections.getValue(ObservationSection.NEAR))
        assertEquals(original, pool)
        val empty = ObservationEligibility.entrySections(listOf(observing, deferred, eliminated), emptyMap())
        assertTrue(empty.values.all { it.isEmpty() })
    }

    @Test
    fun sealedCandidateIsDowngradedWithoutHistoryAndNotAutoRestoredOnOpening() {
        val blocked = ObservationEligibility.applyQuoteGate(candidate, sealed)
        assertEquals(ObservationStage.NEAR_ENTRY, blocked.stage)
        assertTrue(blocked.limitUpBlocked)
        assertEquals(2, blocked.observedDays)
        val reopened = ObservationEligibility.applyQuoteGate(blocked, sealed.copy(price = 10.7))
        assertEquals(ObservationStage.NEAR_ENTRY, reopened.stage)
        assertFalse(ObservationBuyAlert.shouldNotify(blocked, reopened))
    }

    @Test
    fun freshOpenQuoteClearsStaleLimitDefer() {
        val stale = candidate.copy(limitUpBlocked = true)
        val openQuote = sealed.copy(price = 10.7)

        assertFalse(ObservationEligibility.isLimitUpDeferred(stale, openQuote))
        assertFalse(ObservationEligibility.applyQuoteGate(stale, openQuote).limitUpBlocked)
    }

    @Test
    fun shrinkRiskClearsAfterOpeningAndFreshEvidence() {
        assertTrue(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, 0.8))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, 1.1))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(true, 10.0, -1.0))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(false, -0.2, 0.6))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(false, 8.0, 0.6, true))
        assertFalse(ObservationEligibility.isLimitUpShrinkAcceleration(false, 8.0, null, true))
    }

    @Test
    fun displayedCountsAndOrderingExcludeSealedCandidatesBeforeTakingFirstHundred() {
        val sealedCandidates = (1..101).map { candidate.copy(code = "sh${600000 + it}") }
        val near = candidate.copy(code = "sz000001", stage = ObservationStage.NEAR_ENTRY)
        val buy = candidate.copy(code = "sz000002")
        val quotes = sealedCandidates.associate { it.code to sealed.copy(code = it.code) }
        val display = ObservationEligibility.forDisplay(sealedCandidates + near + buy, quotes)
        assertEquals(1, display.count { it.stage == ObservationStage.BUY_CANDIDATE })
        assertEquals(buy.code, display[0].code)
        assertEquals(near.code, display[1].code)
        assertTrue(display.take(100).contains(near))
        assertEquals(101, display.count { ObservationEligibility.isLimitUpDeferred(it, quotes[it.code]) })
    }

    @Test
    fun bothStrategiesStayObservingThroughOpeningUntilRiskClearsThenNotifyOnce() {
        ObservationStrategy.entries.forEach { strategy ->
            val original = candidate.copy(strategy = strategy)
            val blockedSignal = ObservationSignal("2026-10-14", 11.0, true, true, true,
                false, 9.6, 10.0, setupScore = 3, strongScore = 3,
                limitUpBlocked = true, limitUpShrinkAcceleration = true)
            val blocked = ObservationPool.evaluate(original, blockedSignal)
            // 2026-10-22 规格：封板期间不进 NEAR_ENTRY（NEAR_ENTRY 需要非涨停价条件）。
            assertEquals(ObservationStage.OBSERVING, blocked.stage)
            assertFalse(ObservationBuyAlert.shouldNotify(original, blocked))
            val restored = ObservationPoolJson.decode(ObservationPoolJson.encode(listOf(blocked))).single()
            assertTrue(restored.limitUpShrinkAcceleration)
            assertTrue(ObservationEligibility.needsRefresh(restored, "2026-10-14", sealed))
            val stillShrinking = ObservationPool.evaluate(restored,
                blockedSignal.copy(price = 10.8, limitUpBlocked = false))
            assertEquals(ObservationStage.OBSERVING, stillShrinking.stage)
            val entry = blockedSignal.copy(price = 10.6, limitUpBlocked = false,
                limitUpShrinkAcceleration = false, evaluatedAtMs = 1_000_000L, quoteVolume = 1000.0)
            val first = ObservationPool.evaluate(stillShrinking, entry)
            val confirmed = if (strategy == ObservationStrategy.PRE_BREAKOUT) {
                first
            } else {
                assertFalse(ObservationBuyAlert.shouldNotify(stillShrinking, first))
                ObservationPool.evaluate(first, entry.copy(evaluatedAtMs = 1_900_000L))
            }
            assertTrue(ObservationBuyAlert.shouldNotify(stillShrinking, confirmed))
            assertFalse(ObservationBuyAlert.shouldNotify(confirmed, confirmed))
            assertEquals(2, confirmed.observedDays)
        }
    }

    @Test
    fun structuredRiskSummaryIncludesConcretePriceAndVolumeEvidence() {
        val risk = candidate.copy(
            code = "sz000901",
            stage = ObservationStage.NEAR_ENTRY,
            riskSignals = listOf(
                ObservationRiskSignal(
                    ObservationRiskType.BREAKOUT_FAILURE,
                    "突破参考位 12.40，现价 11.90（-4.0%），量比1.65，当日涨跌-3.2%",
                    keyLevel = 12.4,
                    currentValue = 11.9,
                ),
                ObservationRiskSignal(
                    ObservationRiskType.TREND_WEAK,
                    "现价11.90，当日-3.2%，跌破趋势失效线",
                ),
            ),
        )

        assertEquals(
            "突破失效：突破参考位 12.40，现价 11.90（-4.0%），量比1.65，当日涨跌-3.2%；" +
                "趋势走弱：现价11.90，当日-3.2%，跌破趋势失效线",
            ObservationEligibility.riskSummary(risk),
        )
        assertEquals(listOf(risk), ObservationEligibility.prioritizedSections(
            listOf(risk),
            emptyMap(),
        ).risk)
    }

    @Test
    fun legacyUnresolvedRiskReasonWithoutConcreteEvidenceDoesNotEnterRiskGroup() {
        val legacy = candidate.copy(
            code = "sz000902",
            stage = ObservationStage.OBSERVING,
            reason = "条件尚未齐备｜风险未解除，仅保持观察",
        )

        assertNull(ObservationEligibility.riskSummary(legacy))
        val sections = ObservationEligibility.prioritizedSections(listOf(legacy), emptyMap())
        assertTrue(sections.risk.isEmpty())
        assertEquals(listOf(legacy), sections.observing)
    }

    @Test
    fun fundFlowRiskSummaryStatesTodayAndThreeDayNetOutflowAmounts() {
        val flows = (1..3).map { day ->
            FundFlow(
                code = "sz000903",
                name = "资金流测试",
                asOfMs = day.toLong(),
                amountYuan = 500_000_000.0,
                mainNetYuan = -10_000_000.0,
                mainNetPct = -4.0,
                tradingDate = "2026-10-${20 + day}",
            )
        }
        val risk = candidate.copy(
            code = "sz000903",
            stage = ObservationStage.NEAR_ENTRY,
            riskSignals = listOf(
                ObservationRiskSignal(
                    ObservationRiskType.FUND_FLOW_OUTFLOW,
                    "持续流出，暂缓晋级",
                ),
            ),
        )

        val summary = ObservationEligibility.riskSummary(risk, flows.last(), flows).orEmpty()

        assertTrue(summary.contains("资金流出：今日主力资金净流出 1000.0万"))
        assertTrue(summary.contains("占成交额 -4.00%"))
        assertTrue(summary.contains("近3日3/3天净流出"))
        assertTrue(summary.contains("合计 3000.0万"))
        assertFalse(summary.contains("风险未解除"))
    }

    @Test
    fun patternSourceCandidateExpiresWithoutWaitingForFullObservationPeriod() {
        val expiring = candidate.copy(
            stage = ObservationStage.OBSERVING,
            highestStage = ObservationStage.OBSERVING,
            source = "513形态",
            sourceExpiresAtMs = 2_000L,
        )

        assertEquals(ObservationStage.OBSERVING, ObservationEligibility.expireStaleSource(expiring, 1_999L).stage)
        val expired = ObservationEligibility.expireStaleSource(expiring, 2_000L)
        assertEquals(ObservationStage.ELIMINATED, expired.stage)
        assertTrue(expired.reason.contains("形态来源已过期"))
    }

    @Test
    fun patternSourceUsesObservedTradingDaysInsteadOfCalendarDays() {
        val friday = candidate.copy(
            stage = ObservationStage.OBSERVING,
            highestStage = ObservationStage.OBSERVING,
            source = "513形态",
            sources = setOf("513形态"),
            lastEvaluatedDate = "2026-10-16",
            sourceValidTradingDays = 3,
            sourceObservedDays = 2,
        )

        assertFalse(ObservationEligibility.isSourceExpired(friday, 2_000L, "2026-10-18"))

        val monday = ObservationPool.evaluate(
            friday,
            ObservationSignal(
                date = "2026-10-19",
                price = 10.2,
                hasScreenSignal = false,
                confirmedEntry = false,
                canBuy = false,
                trendWeak = false,
                stopPrice = null,
                changePct = 0.0,
                evaluatedAtMs = 3_000L,
            ),
        )
        assertEquals(3, monday.sourceObservedDays)
        assertFalse(ObservationEligibility.isSourceExpired(monday, 3_000L, "2026-10-19"))
        assertTrue(ObservationEligibility.isSourceExpired(monday, 4_000L, "2026-10-20"))
    }

    @Test
    fun expiredPatternSourceOnlyRemovesItsOwnSourceWhenOtherSourcesRemain() {
        val multiSource = candidate.copy(
            stage = ObservationStage.OBSERVING,
            highestStage = ObservationStage.OBSERVING,
            source = "513形态",
            sources = linkedSetOf("513形态", "指标严格"),
            lastEvaluatedDate = "2026-10-16",
            sourceValidTradingDays = 3,
            sourceObservedDays = 3,
        )

        val expired = ObservationEligibility.expireStaleSource(multiSource, 2_000L, "2026-10-19")

        assertEquals(ObservationStage.OBSERVING, expired.stage)
        assertEquals("指标严格", expired.source)
        assertEquals(setOf("指标严格"), expired.sources)
        assertEquals(0, expired.sourceValidTradingDays)
        assertEquals(0L, expired.sourceExpiresAtMs)
        assertTrue(expired.reason.contains("保留指标严格来源"))
    }

    @Test
    fun legacyPatternCacheMigratesTimestampToThreeObservedTradingDays() {
        val raw = """[{"code":"sh600000","name":"测试","added_date":"2026-10-14","last_evaluated_date":"2026-10-16","stage":"OBSERVING","score":0,"weak_days":0,"source":"513形态","source_expires_at_ms":123456,"reason":""}]"""

        val migrated = ObservationPoolJson.decode(raw).single()

        assertEquals(3, migrated.sourceValidTradingDays)
        assertEquals(0L, migrated.sourceExpiresAtMs)
    }

    @Test
    fun patternSourceExpiryCannotDiscardAnAlreadyConfirmedEntry() {
        val confirmed = candidate.copy(
            source = "513形态",
            sourceExpiresAtMs = 2_000L,
            highestStage = ObservationStage.BUY_CANDIDATE,
        )

        assertEquals(ObservationStage.BUY_CANDIDATE, ObservationEligibility.expireStaleSource(confirmed, 9_999L).stage)
    }

    @Test
    fun legacyTrendRiskDoesNotKeepCandidateInRiskGroupAfterPolicyUpgrade() {
        val legacy = candidate.copy(
            stage = ObservationStage.OBSERVING,
            riskPolicyVersion = 0,
            riskSignals = listOf(
                ObservationRiskSignal(
                    ObservationRiskType.TREND_WEAK,
                    "按已完成周K/月K：周K收12.38低于20周均线12.50",
                ),
            ),
            reason = "大趋势向下，不晋级正常买点，只保留风险处置",
        )

        val display = ObservationEligibility.prioritizedSections(listOf(legacy), emptyMap())

        assertTrue(display.risk.isEmpty())
        assertEquals(listOf(legacy.code), display.observing.map { it.code })
        assertNull(ObservationEligibility.riskSummary(legacy))
        assertEquals("风险规则已更新，等待当日复评", ObservationEligibility.displayReason(legacy))
    }

    @Test
    fun currentTrendRiskStillEntersRiskGroup() {
        val current = candidate.copy(
            stage = ObservationStage.OBSERVING,
            riskPolicyVersion = ObservationPool.CURRENT_RISK_POLICY_VERSION,
            riskSignals = listOf(
                ObservationRiskSignal(
                    ObservationRiskType.TREND_WEAK,
                    "按已完成周K/月K：周K收10低于20周均线12",
                ),
            ),
            reason = "大趋势向下，不晋级正常买点，只保留风险处置",
        )

        val display = ObservationEligibility.prioritizedSections(listOf(current), emptyMap())

        assertEquals(listOf(current.code), display.risk.map { it.code })
        assertEquals(current.reason, ObservationEligibility.displayReason(current))
        assertTrue(ObservationEligibility.riskSummary(current)!!.contains("趋势走弱"))
    }
}
