package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.withStageResult

/**
 * 选股后的连续观察规则。它只消费已经得到的选股/行情信号，不自行请求数据。
 */
object ObservationPool {
    const val MAX_OBSERVED_DAYS = 10
    const val MAX_WEAK_DAYS = 3
    const val ENTRY_CONFIRMATION_ROUNDS = 2
    const val ENTRY_CONFIRMATION_MIN_MS = 15 * 60 * 1000L
    const val ENTRY_CONFIRMATION_MAX_MS = 45 * 60 * 1000L

    fun requiredConfirmationRounds(candidate: ObservationCandidate): Int =
        if (candidate.entryConfirmationRequiredRounds > 0) {
            candidate.entryConfirmationRequiredRounds
        } else {
            requiredConfirmationRounds(candidate, candidate.limitStreak ?: 0)
        }

    fun requiredConfirmationRounds(
        candidate: ObservationCandidate,
        signal: ObservationSignal,
    ): Int = requiredConfirmationRounds(candidate, signal.recentLimitUpDays)

    private fun requiredConfirmationRounds(
        candidate: ObservationCandidate,
        currentLimitIdentity: Int,
    ): Int =
        if (currentLimitIdentity >= 3) {
            ENTRY_CONFIRMATION_ROUNDS + 1
        } else {
            ENTRY_CONFIRMATION_ROUNDS
        }

    fun evaluate(
        candidate: ObservationCandidate,
        signal: ObservationSignal,
    ): ObservationCandidate {
        if (candidate.stage == ObservationStage.ELIMINATED) return candidate
        val sameDay = candidate.lastEvaluatedDate == signal.date
        val observedDays = if (sameDay) candidate.observedDays else candidate.observedDays + 1
        val weakDays = when {
            sameDay -> candidate.weakDays
            signal.trendWeak -> candidate.weakDays + 1
            else -> 0
        }
        val baseScore = when (candidate.strategy) {
            ObservationStrategy.PRE_BREAKOUT -> if (signal.setupScore > 0) {
                signal.setupScore
            } else {
                listOf(signal.hasScreenSignal, signal.confirmedEntry, signal.canBuy).count { it }
            }
            ObservationStrategy.LEADER -> signal.strongScore
            ObservationStrategy.LIMIT_UP_CONTINUATION -> signal.strongScore
        }.let { base ->
            val fundFlowDelta = listOfNotNull(
                signal.fundFlow?.let(FundFlowPolicy::scoreDelta),
                signal.fundFlowWindow?.let(FundFlowWindowPolicy::scoreDelta),
            ).let { deltas ->
                when {
                    deltas.any { it < 0 } -> -1
                    deltas.any { it > 0 } -> 1
                    else -> 0
                }
            }
            if (signal.trendWeak) base - 2 else base + fundFlowDelta
        }
        val usesSealQuality = candidate.limitUpBlocked || signal.limitUpBlocked
        val qualityScore = candidate.sealQualityScore.takeIf { usesSealQuality }
        val qualityCoverage = candidate.sealQualityCoveragePct.takeIf { usesSealQuality }
        val score = baseScore.coerceIn(0, 3)
        val highBoard = usesSealQuality && candidate.limitStreak?.let { it >= 3 } == true
        val weakHighBoardSeal = highBoard &&
            qualityCoverage != null &&
            qualityCoverage >= LimitRanking.MIN_RELIABLE_COVERAGE_PCT &&
            qualityScore != null &&
            qualityScore < 55
        val boxRange = signal.boxRange
        val breakoutRisk = signal.breakoutRisk
        val trendGate = when {
            signal.environmentGate != null -> signal.environmentGate
            weakHighBoardSeal -> "三板以上且封板质量偏弱，暂缓晋级"
            breakoutRisk?.risk == true -> breakoutRisk.reason
            boxRange != null && boxRange.state in setOf(
                BoxRangeState.BOX_RANGE,
                BoxRangeState.BREAKOUT_CANDIDATE,
                BoxRangeState.BREAKDOWN_RISK,
                BoxRangeState.BREAKDOWN_CONFIRMED,
                BoxRangeState.MISSING,
            ) -> boxRange.summary
            signal.trendAssessment?.major == MajorTrend.DOWN ->
                "大趋势向下，不晋级正常买点，只保留风险处置"
            signal.trendAssessment?.major == MajorTrend.BOTTOMING ->
                "底部反转观察，最多按初始仓位25%轻仓试错，不晋级正常买点"
            signal.highPosition?.riskPriority == true ->
                "高位派发风险，停止正常买入并优先风险处置"
            signal.highPosition?.phase == HighPositionPhase.SECOND_WAVE_CANDIDATE ->
                "二波候选，等待突破确认，不晋级正常买点"
            signal.highPosition?.phase == HighPositionPhase.CONSOLIDATION ->
                "高位整理蓄势，未突破确认前不晋级正常买点"
            signal.highPosition?.phase == HighPositionPhase.SECOND_WAVE_CONFIRMED &&
                signal.highPosition.allowBuy -> null
            signal.highPosition != null &&
                signal.highPosition.phase != HighPositionPhase.NONE -> signal.highPosition.reason
            signal.trendAssessment?.allowsOrdinaryBuy == false ->
                "${signal.trendAssessment.summary}，不晋级正常买点"
            else -> null
        }
        val fundFlowDelay =
            signal.fundFlow?.tone == FundFlowTone.DIVERGENT ||
                signal.fundFlow?.tone == FundFlowTone.RISK ||
                signal.fundFlow?.tone == FundFlowTone.ABSORPTION_WATCH ||
                signal.fundFlowWindow?.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW
        val softRisk = breakoutRisk?.risk == true
        val hardRisk = signal.limitUpShrinkAcceleration ||
            signal.trendAssessment?.major == MajorTrend.DOWN ||
            signal.highPosition?.riskPriority == true
        val softRiskRounds = when {
            hardRisk -> 0
            softRisk -> (candidate.softRiskRounds + 1).coerceAtMost(2)
            else -> 0
        }
        val eligible = breakoutRisk?.risk != true && trendGate == null && signal.confirmedEntry && signal.canBuy &&
            !signal.riskFlag && !signal.trendWeak &&
            !signal.limitUpBlocked && !signal.limitUpShrinkAcceleration && !fundFlowDelay
        val leader = candidate.strategy == ObservationStrategy.LEADER ||
            candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION
        val sealedContinuous = candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION &&
            candidate.continuousObservation && signal.limitUpBlocked
        val requiredConfirmationRounds = requiredConfirmationRounds(candidate, signal)
        val elapsed = signal.evaluatedAtMs - candidate.entryConfirmedAtMs
        val validSample = signal.evaluatedAtMs > 0
        val continuing = sameDay && candidate.entryConfirmationRounds > 0 &&
            elapsed in 0..ENTRY_CONFIRMATION_MAX_MS
        val freshRound = continuing && elapsed >= ENTRY_CONFIRMATION_MIN_MS
        val rounds = when {
            !leader || !eligible || !validSample -> 0
            !continuing -> 1
            freshRound -> (candidate.entryConfirmationRounds + 1).coerceAtMost(requiredConfirmationRounds)
            else -> candidate.entryConfirmationRounds
        }
        val confirmed = eligible && (!leader || rounds >= requiredConfirmationRounds)
        val continuousOpeningGrace = candidate.continuousObservation &&
            candidate.limitUpBlocked && !signal.limitUpBlocked
        val stage = when {
            sealedContinuous -> ObservationStage.OBSERVING
            !sealedContinuous && !continuousOpeningGrace && weakDays >= MAX_WEAK_DAYS ->
                ObservationStage.ELIMINATED
            !sealedContinuous && !continuousOpeningGrace &&
                observedDays >= MAX_OBSERVED_DAYS && !confirmed -> ObservationStage.ELIMINATED
            hardRisk && candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            hardRisk -> ObservationStage.OBSERVING
            signal.riskFlag && candidate.stage == ObservationStage.BUY_CANDIDATE ->
                ObservationStage.NEAR_ENTRY
            signal.riskFlag -> ObservationStage.OBSERVING
            softRisk && candidate.stage == ObservationStage.BUY_CANDIDATE && softRiskRounds < 2 ->
                ObservationStage.NEAR_ENTRY
            softRisk -> ObservationStage.OBSERVING
            signal.trendAssessment?.major == MajorTrend.BOTTOMING -> ObservationStage.NEAR_ENTRY
            signal.highPosition?.phase == HighPositionPhase.SECOND_WAVE_CANDIDATE -> ObservationStage.NEAR_ENTRY
            signal.highPosition?.phase == HighPositionPhase.CONSOLIDATION -> ObservationStage.NEAR_ENTRY
            signal.boxRange?.state in setOf(
                BoxRangeState.BOX_RANGE,
                BoxRangeState.BREAKOUT_CANDIDATE,
            ) -> ObservationStage.NEAR_ENTRY
            fundFlowDelay && candidate.stage == ObservationStage.BUY_CANDIDATE ->
                ObservationStage.NEAR_ENTRY
            fundFlowDelay -> ObservationStage.OBSERVING
            confirmed -> ObservationStage.BUY_CANDIDATE
            score >= 2 || signal.confirmedEntry -> ObservationStage.NEAR_ENTRY
            else -> ObservationStage.OBSERVING
        }
        val stageReason = when (stage) {
            ObservationStage.BUY_CANDIDATE -> if (candidate.strategy != ObservationStrategy.PRE_BREAKOUT) {
                "强势资格与缩量回踩企稳条件连续${requiredConfirmationRounds}轮通过；仅为技术候选，不代表资金承接已获证实"
            } else signal.signalReason.ifBlank { "趋势入场已确认，等待执行纪律与仓位检查" }
            ObservationStage.NEAR_ENTRY -> when {
                sealedContinuous -> ObservationEligibility.CONTINUOUS_LIMIT_WAIT_REASON
                softRisk && candidate.stage == ObservationStage.BUY_CANDIDATE && softRiskRounds < 2 ->
                    "${breakoutRisk.reason}｜风险缓冲1/2，暂降等待确认"
                signal.riskFlag -> "${signal.signalReason.ifBlank { "风险信号" }}｜风险未解除，不晋级"
                trendGate != null -> trendGate
                fundFlowDelay -> fundFlowDelayReason(signal)
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                leader && eligible -> "回踩条件初步满足，等待下一轮新行情复评（$rounds/$requiredConfirmationRounds）"
                else -> signal.signalReason.ifBlank { "条件逐步满足，等待最后的价格与量能确认" }
            }
            ObservationStage.OBSERVING -> when {
                sealedContinuous -> ObservationEligibility.CONTINUOUS_LIMIT_WAIT_REASON
                softRisk && softRiskRounds >= 2 ->
                    "${breakoutRisk.reason}｜连续2轮风险确认，降级观察"
                signal.riskFlag -> "${signal.signalReason.ifBlank { "风险信号" }}｜风险未解除，仅保持观察"
                trendGate != null -> trendGate
                breakoutRisk?.risk == true -> breakoutRisk.reason
                fundFlowDelay -> fundFlowDelayReason(signal)
                signal.limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                signal.trendWeak -> "出现走弱信号，继续观察，连续走弱将淘汰"
                else -> "条件尚未齐备，继续观察"
            }
            ObservationStage.ELIMINATED -> if (!sealedContinuous && weakDays >= MAX_WEAK_DAYS) {
                "连续${MAX_WEAK_DAYS}个交易日走弱，自动淘汰"
            } else {
                "观察期超过${MAX_OBSERVED_DAYS}个交易日仍未确认，自动淘汰"
            }
        }
        val reason = if (fundFlowDelay && !stageReason.contains("资金流")) {
            "${fundFlowDelayReason(signal)}｜$stageReason"
        } else {
            stageReason
        }
        val evaluated = candidate.copy(
            lastEvaluatedDate = signal.date,
            score = score,
            technicalScore = signal.technicalScore,
            weeklyTrendLabel = signal.weeklyTrendLabel,
            monthlyTrendLabel = signal.monthlyTrendLabel,
            weakDays = weakDays,
            observedDays = observedDays,
            lastPrice = signal.price,
            stopPrice = signal.stopPrice,
            evidence = signal.evidence,
            limitUpBlocked = signal.limitUpBlocked,
            limitUpShrinkAcceleration = signal.limitUpShrinkAcceleration,
            continuousObservation = candidate.continuousObservation || sealedContinuous,
            entryConfirmationRounds = if (sealedContinuous) 0 else rounds,
            entryConfirmationRequiredRounds = if (sealedContinuous) 0 else requiredConfirmationRounds,
            entryConfirmedAtMs = when {
                sealedContinuous || rounds == 0 -> 0L
                !continuing || freshRound -> signal.evaluatedAtMs
                else -> candidate.entryConfirmedAtMs
            },
            entryConfirmationVolume = when {
                sealedContinuous || rounds == 0 -> 0.0
                !continuing || freshRound -> signal.quoteVolume
                else -> candidate.entryConfirmationVolume
            },
            breakoutRisk = breakoutRisk,
        )
        return evaluated.withStageResult(
            stage = stage,
            date = signal.date,
            atMs = signal.evaluatedAtMs,
            reason = reason,
            softRiskRounds = softRiskRounds,
        )
    }

    private fun fundFlowDelayReason(signal: ObservationSignal): String {
        val evidence = signal.fundFlowWindow?.reason?.takeIf { it.isNotBlank() }
            ?: signal.fundFlow?.reason?.takeIf { it.isNotBlank() }
            ?: "资金流证据偏弱"
        return "资金流证据暂缓晋级：$evidence｜资金流只影响晋级节奏，不影响盘中提醒（包括买卖点提醒）。"
    }
}
