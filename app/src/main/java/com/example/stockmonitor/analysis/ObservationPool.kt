package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationRiskSignal
import com.example.stockmonitor.core.ObservationRiskType
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.withStageResult
import java.util.Locale

/**
 * 选股后的连续观察规则。它只消费已经得到的选股/行情信号，不自行请求数据。
 */
object ObservationPool {
    const val MAX_OBSERVED_DAYS = 10
    const val MAX_WEAK_DAYS = 3
    const val ENTRY_CONFIRMATION_ROUNDS = 2
    const val ENTRY_CONFIRMATION_MIN_MS = 15 * 60 * 1000L
    const val ENTRY_CONFIRMATION_MAX_MS = 45 * 60 * 1000L
    const val ENTRY_MAX_CHASE_PCT = 3.0
    const val SOFT_RISK_CONFIRMATION_MIN_MS = 15 * 60 * 1000L
    const val MAX_CONFIRMED_WATCH_DAYS = 15
    const val POST_LIMIT_HOLD_MIN_MS = 5 * 60 * 1000L
    const val POST_LIMIT_HOLD_BREAK_PCT = 0.5
    private const val WEAK_HIGH_BOARD_SEAL_SCORE = 55

    /** 风险口径版本：旧缓存中的风险结论必须先经过当日复评才能重新成为风险。 */
    const val CURRENT_RISK_POLICY_VERSION = 2

    fun requiredConfirmationRounds(candidate: ObservationCandidate): Int =
        requiredConfirmationRounds(
            currentLimitIdentity = candidate.limitStreak ?: 0,
            extraRisk = weakHighBoardSeal(candidate),
        )

    fun requiredConfirmationRounds(
        candidate: ObservationCandidate,
        signal: ObservationSignal,
    ): Int = requiredConfirmationRounds(
        currentLimitIdentity = candidate.limitStreak ?: signal.recentLimitUpDays,
        extraRisk = weakHighBoardSeal(candidate) ||
            signal.riskFlag ||
            signal.trendWeak ||
            signal.breakoutRisk?.risk == true ||
            signal.highPosition?.riskPriority == true ||
            signal.marketRisk == com.example.stockmonitor.core.ObservationMarketRisk.HIGH_QUALITY_ONLY ||
            signal.marketRisk == com.example.stockmonitor.core.ObservationMarketRisk.BLOCKED,
    )

    private fun requiredConfirmationRounds(
        currentLimitIdentity: Int,
        extraRisk: Boolean,
    ): Int =
        if (currentLimitIdentity >= 3 && extraRisk) {
            ENTRY_CONFIRMATION_ROUNDS + 1
        } else {
            ENTRY_CONFIRMATION_ROUNDS
        }

    private fun weakHighBoardSeal(candidate: ObservationCandidate): Boolean =
        (candidate.limitStreak ?: 0) >= 3 &&
            candidate.sealQualityCoveragePct?.let { it >= LimitRanking.MIN_RELIABLE_COVERAGE_PCT } == true &&
            candidate.sealQualityScore?.let { it < WEAK_HIGH_BOARD_SEAL_SCORE } == true

    fun evaluate(
        candidate: ObservationCandidate,
        signal: ObservationSignal,
    ): ObservationCandidate {
        if (candidate.stage == ObservationStage.ELIMINATED) return candidate
        val sameDay = candidate.lastEvaluatedDate == signal.date
        val observedDays = if (sameDay) candidate.observedDays else candidate.observedDays + 1
        val validSample = signal.evaluatedAtMs > 0L
        val sourceObservedDays = when {
            candidate.sourceValidTradingDays <= 0 -> candidate.sourceObservedDays
            sameDay -> candidate.sourceObservedDays
            else -> candidate.sourceObservedDays + 1
        }
        val priorHoldStart = candidate.postLimitHoldStartedAtMs.takeIf { sameDay && it > 0L } ?: 0L
        val priorHoldLow = candidate.postLimitHoldLowPrice
            .takeIf { priorHoldStart > 0L && it.isFinite() && it > 0.0 }
            ?: 0.0
        val holdLineBroken = priorHoldLow > 0.0 &&
            signal.price < priorHoldLow * (1.0 - POST_LIMIT_HOLD_BREAK_PCT / 100.0)
        val holdStartedAtMs = when {
            !signal.postLimitHighHoldCandidate -> 0L
            priorHoldStart <= 0L || holdLineBroken -> signal.evaluatedAtMs
            else -> priorHoldStart
        }
        val holdLowPrice = when {
            !signal.postLimitHighHoldCandidate || signal.price <= 0.0 -> 0.0
            holdStartedAtMs == priorHoldStart && priorHoldLow > 0.0 -> minOf(priorHoldLow, signal.price)
            else -> signal.price
        }
        val highHoldElapsedMs = when {
            !validSample || holdStartedAtMs <= 0L -> 0L
            else -> (signal.evaluatedAtMs - holdStartedAtMs).coerceAtLeast(0L)
        }
        val postLimitHighHoldEntry = signal.postLimitHighHoldCandidate &&
            validSample && holdStartedAtMs > 0L &&
            highHoldElapsedMs >= POST_LIMIT_HOLD_MIN_MS
        val settlesWeakDay = signal.settlesWeakDay && candidate.weakDaysSettledDate != signal.date
        val weakDays = when {
            settlesWeakDay && signal.trendWeak -> candidate.weakDays + 1
            settlesWeakDay -> 0
            else -> candidate.weakDays
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
        val postLimitDailyBaseEntry = signal.postLimitDailyBaseEntry
        val independentHighHoldEntry = postLimitHighHoldEntry &&
            !weakHighBoardSeal(candidate) &&
            signal.environmentGate == null &&
            signal.trendAssessment != null &&
            signal.trendAssessment.major != MajorTrend.DOWN &&
            signal.highPosition?.riskPriority != true &&
            boxRange?.state !in setOf(
                BoxRangeState.BREAKDOWN_RISK,
                BoxRangeState.BREAKDOWN_CONFIRMED,
                BoxRangeState.MISSING,
            )
        val effectiveBreakoutRisk = breakoutRisk.takeUnless { independentHighHoldEntry }
        // 两板及以上回踩 MA20 缩量止跌是独立日线结构：完整周/月仍弱时只阻止普通追高，
        // 不能把这条已经用 ATR、均线方向、量能和止跌证据确认过的承接路径一起拦掉。
        val independentDailyBaseEntry = postLimitDailyBaseEntry &&
            !weakHighBoardSeal &&
            signal.environmentGate == null &&
            breakoutRisk?.risk != true &&
            signal.trendAssessment != null &&
            signal.trendAssessment.major != MajorTrend.DOWN &&
            signal.highPosition?.riskPriority != true &&
            boxRange?.state !in setOf(
                BoxRangeState.BREAKDOWN_RISK,
                BoxRangeState.BREAKDOWN_CONFIRMED,
                BoxRangeState.MISSING,
            )
        val trendGate = when {
            independentDailyBaseEntry || independentHighHoldEntry -> null
            signal.environmentGate != null -> signal.environmentGate
            weakHighBoardSeal -> "三板以上且封板质量偏弱，暂缓晋级"
            effectiveBreakoutRisk?.risk == true -> effectiveBreakoutRisk.reason
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
            signal.trendAssessment?.major == MajorTrend.REPAIR ->
                "${signal.trendAssessment.summary}，按修复观察，等待回调企稳后的日线承接确认"
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
        val riskSignals = buildRiskSignals(signal, ignoreBreakoutRisk = independentHighHoldEntry)
        val softRisk = effectiveBreakoutRisk?.risk == true
        val hardRisk = signal.limitUpShrinkAcceleration ||
            signal.trendAssessment?.major == MajorTrend.DOWN ||
            signal.highPosition?.riskPriority == true
        val softRiskSample = softRisk && signal.evaluatedAtMs > 0L
        val confirmsSoftRisk = softRisk && (
            candidate.softRiskRounds == 0 ||
                (softRiskSample && candidate.softRiskRounds > 0 &&
                    (candidate.softRiskLastConfirmedAtMs <= 0L ||
                        signal.evaluatedAtMs - candidate.softRiskLastConfirmedAtMs >=
                        SOFT_RISK_CONFIRMATION_MIN_MS))
            )
        val softRiskRounds = when {
            hardRisk -> 0
            !softRisk -> 0
            confirmsSoftRisk -> (candidate.softRiskRounds + 1).coerceAtMost(2)
            else -> candidate.softRiskRounds
        }
        val softRiskLastConfirmedAtMs = when {
            hardRisk || !softRisk -> 0L
            confirmsSoftRisk -> signal.evaluatedAtMs
            else -> candidate.softRiskLastConfirmedAtMs
        }
        val secondWaveEntry = signal.highPosition?.phase == HighPositionPhase.SECOND_WAVE_CONFIRMED &&
            signal.highPosition.allowSecondWaveEntry && signal.highPosition.allowBuy
        // 二波确认和两板以上回踩 MA20 的日线承接均可作为独立结构；
        // 不改变普通 BREAKOUT 仅观察的全局规则。
        val entryConfirmed = signal.confirmedEntry || secondWaveEntry || independentHighHoldEntry
        val accelerationBlocked = !signal.accelerationBlocker.isNullOrBlank()
        val entryCanBuy = (signal.canBuy || secondWaveEntry || independentHighHoldEntry) && !accelerationBlocked
        val environmentTightened = signal.environmentTightened
        val leader = candidate.strategy == ObservationStrategy.LEADER ||
            candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION
        // 仅在两板及以上、回踩 MA20 缩量止跌已确认时缩短高连板的盘中轮次；
        // 单纯开板或横盘仍保留常规两/三轮确认，避免把高位整理直接当买点。
        val leaderNeedsRounds = leader && !postLimitDailyBaseEntry && !independentHighHoldEntry
        val sealedContinuous = candidate.strategy == ObservationStrategy.LIMIT_UP_CONTINUATION &&
            candidate.continuousObservation && signal.limitUpBlocked
        // 已确认过买点的候选，在趋势未破时允许回踩/环境暂缓，不因一轮短暂波动直接消失。
        val previouslyConfirmed = (candidate.highestStage ?: candidate.stage) == ObservationStage.BUY_CANDIDATE
        val trendIntact = !signal.trendWeak &&
            signal.trendAssessment?.major != MajorTrend.DOWN &&
            signal.highPosition?.riskPriority != true &&
            signal.highPosition?.phase !in setOf(
                HighPositionPhase.SECOND_WAVE_CANDIDATE,
                HighPositionPhase.CONSOLIDATION,
            ) &&
            signal.boxRange?.state !in setOf(
                BoxRangeState.BOX_RANGE,
                BoxRangeState.BREAKOUT_CANDIDATE,
            ) &&
            !signal.limitUpShrinkAcceleration
        val requiredConfirmationRounds = requiredConfirmationRounds(candidate, signal)
        val elapsed = signal.evaluatedAtMs - candidate.entryConfirmedAtMs
        val continuing = sameDay && candidate.entryConfirmationRounds > 0 &&
            (elapsed in 0..ENTRY_CONFIRMATION_MAX_MS || !signal.inTradingSession)
        // 盘后没有新的15分钟K线，确认窗口不能继续按时钟流逝而重置。否则应用在夜间
        // 重启/规则迁移复评时，会把当天已经连续确认完成的买点重新打回（1/2）。
        val freshRound = signal.inTradingSession && continuing &&
            elapsed >= ENTRY_CONFIRMATION_MIN_MS
        val triggerPrice = candidate.entryTriggerPrice.takeIf { it > 0.0 }
            ?: candidate.lastPrice.takeIf { candidate.entryConfirmationRounds > 0 && it > 0.0 }
        val entryChaseBlocked = continuing && triggerPrice != null &&
            signal.price > triggerPrice * (1.0 + ENTRY_MAX_CHASE_PCT / 100.0)
        val volumeSampleValid = candidate.entryConfirmationVolume <= 0.0 ||
            signal.quoteVolume <= 0.0 || signal.quoteVolume >= candidate.entryConfirmationVolume
        val highHoldPending = signal.postLimitHighHoldCandidate && !independentHighHoldEntry
        val eligible = effectiveBreakoutRisk?.risk != true && trendGate == null &&
            entryConfirmed && entryCanBuy && !highHoldPending &&
            (!environmentTightened || secondWaveEntry || postLimitDailyBaseEntry || independentHighHoldEntry) &&
            !signal.riskFlag && !signal.trendWeak && !entryChaseBlocked && volumeSampleValid &&
            !signal.limitUpBlocked && !signal.limitUpShrinkAcceleration && !fundFlowDelay
        val resetConfirmation = hardRisk || signal.riskFlag || signal.trendWeak ||
            signal.limitUpBlocked || signal.limitUpShrinkAcceleration
        val progressActive = continuing && !resetConfirmation
        val rounds = when {
            !leaderNeedsRounds || !validSample -> 0
            resetConfirmation -> 0
            candidate.entryConfirmationRounds == 0 -> if (eligible) 1 else 0
            !progressActive -> if (eligible) 1 else 0
            freshRound -> if (eligible) {
                (candidate.entryConfirmationRounds + 1).coerceAtMost(requiredConfirmationRounds)
            } else {
                candidate.entryConfirmationRounds
            }
            else -> candidate.entryConfirmationRounds
        }
        val startsConfirmation = rounds > 0 && (
            candidate.entryConfirmationRounds == 0 || !progressActive
            )
        val confirmed = eligible && (!leaderNeedsRounds || rounds >= requiredConfirmationRounds)
        val continuousOpeningGrace = candidate.continuousObservation &&
            candidate.limitUpBlocked && !signal.limitUpBlocked
        val observingOnlyConfirmed = signal.confirmedEntry && !entryCanBuy && !secondWaveEntry &&
            !postLimitDailyBaseEntry
        val nearStructureGate = signal.trendAssessment?.major == MajorTrend.BOTTOMING
        val promotionBlocked = trendGate != null && !nearStructureGate && !softRisk
        val allRiskSignals = if (entryChaseBlocked) {
            val chaseReference = triggerPrice ?: signal.price
            riskSignals + ObservationRiskSignal(
                type = ObservationRiskType.ENTRY_CHASE,
                detail = String.format(
                    Locale.CHINA,
                    "首次触发价 %.2f，现价 %.2f，高于允许追涨 %.0f%%",
                    chaseReference,
                    signal.price,
                    ENTRY_MAX_CHASE_PCT,
                ),
                keyLevel = chaseReference,
                currentValue = signal.price,
                threshold = chaseReference * (1.0 + ENTRY_MAX_CHASE_PCT / 100.0),
            )
        } else {
            riskSignals
        }
        val stage = when {
            sealedContinuous -> ObservationStage.OBSERVING
            // 封板期间不参与；开板首轮由 continuousOpeningGrace 保留，等待后续重新确认。
            signal.limitUpBlocked -> ObservationStage.OBSERVING
            // 三日走弱和十日未确认只淘汰从未确认过买点的候选；已确认候选走结构失效/降档路径。
            !previouslyConfirmed && !sealedContinuous && !continuousOpeningGrace && weakDays >= MAX_WEAK_DAYS ->
                ObservationStage.ELIMINATED
            !previouslyConfirmed && !sealedContinuous && !continuousOpeningGrace &&
                observedDays >= MAX_OBSERVED_DAYS && !confirmed -> ObservationStage.ELIMINATED
            previouslyConfirmed && candidate.stage != ObservationStage.BUY_CANDIDATE &&
                !sealedContinuous && !continuousOpeningGrace &&
                candidate.postConfirmationWatchDays >= MAX_CONFIRMED_WATCH_DAYS && !confirmed ->
                ObservationStage.ELIMINATED
            hardRisk && candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            hardRisk -> ObservationStage.OBSERVING
            signal.riskFlag && candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            signal.riskFlag -> ObservationStage.OBSERVING
            entryChaseBlocked -> ObservationStage.OBSERVING
            // 软风险必须间隔15分钟再次确认；首次出现只展示风险，不立刻降档。
            softRisk && softRiskRounds < 2 -> candidate.stage
            softRisk && previouslyConfirmed && trendIntact -> ObservationStage.NEAR_ENTRY
            softRisk -> ObservationStage.OBSERVING
            promotionBlocked && previouslyConfirmed && trendIntact -> ObservationStage.NEAR_ENTRY
            promotionBlocked -> ObservationStage.OBSERVING
            environmentTightened && previouslyConfirmed && trendIntact -> ObservationStage.NEAR_ENTRY
            environmentTightened && !secondWaveEntry && !postLimitDailyBaseEntry -> ObservationStage.OBSERVING
            nearStructureGate -> ObservationStage.NEAR_ENTRY
            fundFlowDelay && previouslyConfirmed && trendIntact -> ObservationStage.NEAR_ENTRY
            fundFlowDelay && candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
            fundFlowDelay -> ObservationStage.OBSERVING
            accelerationBlocked && previouslyConfirmed && trendIntact -> ObservationStage.NEAR_ENTRY
            accelerationBlocked -> ObservationStage.OBSERVING
            confirmed -> ObservationStage.BUY_CANDIDATE
            observingOnlyConfirmed -> ObservationStage.OBSERVING
            eligible && entryCanBuy -> ObservationStage.NEAR_ENTRY
            else -> ObservationStage.OBSERVING
        }
        val stageReason = when (stage) {
            ObservationStage.BUY_CANDIDATE -> when {
                softRisk && softRiskRounds < 2 -> allRiskSignals.take(2)
                    .joinToString("；") { "${it.type.label}：${it.detail}" } +
                    "｜软风险首次出现，保留买点状态，等待15分钟间隔复核"
                allRiskSignals.isNotEmpty() -> allRiskSignals.take(2)
                    .joinToString("；") { "${it.type.label}：${it.detail}" } +
                    "｜风险条件仍成立，暂不通知，等待复查具体风险"
                secondWaveEntry ->
                    "${signal.highPosition?.reason ?: "二波突破确认"}；按二波结构首次轻仓参与，回踩不破关键位再评估追加"
                postLimitDailyBaseEntry ->
                    "两板及以上强势后回踩 MA20 缩量止跌确认；按日线承接结构首次轻仓参与，跌破 MA20 或止损参考即停止追加"
                independentHighHoldEntry ->
                    "炸板后守住涨停高位线，平台低点未破，已连续观察${POST_LIMIT_HOLD_MIN_MS / 60_000}分钟；按高位承接结构轻仓参与，跌破平台低点或止损参考即停止"
                candidate.strategy != ObservationStrategy.PRE_BREAKOUT ->
                    "强势资格与缩量回踩企稳条件连续${requiredConfirmationRounds}轮通过；仅为技术候选，不代表资金承接已获证实"
                else -> signal.signalReason.ifBlank { "趋势入场已确认，等待执行纪律与仓位检查" }
            }
            ObservationStage.NEAR_ENTRY -> when {
                sealedContinuous -> ObservationEligibility.CONTINUOUS_LIMIT_WAIT_REASON
                highHoldPending -> highHoldWaitReason(highHoldElapsedMs)
                entryChaseBlocked -> "买点确认期间涨幅超过允许追涨3%，本轮确认失效；等待回落到触发价附近重新形成买点"
                softRisk && softRiskRounds < 2 ->
                    "${effectiveBreakoutRisk?.reason ?: "突破结构出现软风险"}｜软风险首次出现，保留原档位，等待15分钟后的新行情复核"
                signal.riskFlag -> "${signal.signalReason.ifBlank { "风险信号" }}｜风险条件仍成立，不晋级"
                accelerationBlocked -> signal.accelerationBlocker ?: "放量加速末端暂缓晋级"
                trendGate != null -> trendGate
                environmentTightened -> "市场风险提高普通候选晋级门槛，等待更强的日线承接或二波确认"
                fundFlowDelay -> fundFlowDelayReason(signal)
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                leader && eligible -> "回踩条件初步满足，等待下一轮新行情复评（$rounds/$requiredConfirmationRounds）"
                else -> signal.signalReason.ifBlank { "条件逐步满足，等待最后的价格与量能确认" }
            }
            ObservationStage.OBSERVING -> when {
                sealedContinuous -> ObservationEligibility.CONTINUOUS_LIMIT_WAIT_REASON
                highHoldPending -> highHoldWaitReason(highHoldElapsedMs)
                entryChaseBlocked -> "买点确认期间涨幅超过允许追涨3%，本轮买点失效；不追高，等待回踩后重新确认"
                softRisk && softRiskRounds >= 2 ->
                    "${effectiveBreakoutRisk?.reason ?: "突破结构弱点已确认"}｜软风险已间隔确认2轮，降级观察"
                softRisk -> "${effectiveBreakoutRisk?.reason ?: "突破结构出现软风险"}｜软风险等待15分钟间隔复核"
                signal.riskFlag -> "${signal.signalReason.ifBlank { "风险信号" }}｜风险条件仍成立，仅保持观察"
                accelerationBlocked -> signal.accelerationBlocker ?: "放量加速末端暂缓晋级"
                trendGate != null -> trendGate
                effectiveBreakoutRisk?.risk == true -> effectiveBreakoutRisk.reason
                fundFlowDelay -> fundFlowDelayReason(signal)
                signal.limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                signal.trendWeak -> "出现走弱信号，继续观察，连续走弱将淘汰"
                observingOnlyConfirmed -> "入场结构已确认，但策略标记为仅观察、不直接买入，因此不进入买点待确认"
                environmentTightened -> "市场风险提高普通候选晋级门槛，保持观察，等待日线承接或二波结构"
                else -> "条件尚未齐备，继续观察"
            }
            ObservationStage.ELIMINATED -> if (!sealedContinuous && weakDays >= MAX_WEAK_DAYS) {
                "连续${MAX_WEAK_DAYS}个交易日走弱，自动淘汰"
            } else if (previouslyConfirmed && candidate.stage != ObservationStage.BUY_CANDIDATE &&
                candidate.postConfirmationWatchDays >= MAX_CONFIRMED_WATCH_DAYS && !confirmed
            ) {
                "买点失效后观察超过${MAX_CONFIRMED_WATCH_DAYS}个交易日仍未重新确认，自动淘汰"
            } else {
                "观察期超过${MAX_OBSERVED_DAYS}个交易日仍未确认，自动淘汰"
            }
        }
        val reason = if (fundFlowDelay && !stageReason.contains("资金流")) {
            "${fundFlowDelayReason(signal)}｜$stageReason"
        } else {
            stageReason
        }
        val postConfirmationWatchDays = when {
            stage == ObservationStage.BUY_CANDIDATE -> 0
            previouslyConfirmed && !confirmed -> if (sameDay) {
                candidate.postConfirmationWatchDays.coerceAtLeast(1)
            } else {
                candidate.postConfirmationWatchDays + 1
            }
            !previouslyConfirmed -> 0
            else -> candidate.postConfirmationWatchDays
        }
        val evaluated = candidate.copy(
            lastEvaluatedDate = signal.date,
            score = score,
            technicalScore = signal.technicalScore,
            weeklyTrendLabel = signal.weeklyTrendLabel,
            monthlyTrendLabel = signal.monthlyTrendLabel,
            weakDays = weakDays,
            weakDaysSettledDate = if (settlesWeakDay) signal.date else candidate.weakDaysSettledDate,
            dailyBaseEntryConfirmed = postLimitDailyBaseEntry,
            structureQuality = signal.structureQuality,
            relativeStrengthPct = signal.relativeStrengthPct,
            marketRisk = signal.marketRisk,
            observedDays = observedDays,
            sourceObservedDays = sourceObservedDays,
            postConfirmationWatchDays = postConfirmationWatchDays,
            lastPrice = signal.price,
            stopPrice = signal.stopPrice,
            evidence = signal.evidence,
            limitUpBlocked = signal.limitUpBlocked,
            limitUpShrinkAcceleration = signal.limitUpShrinkAcceleration,
            continuousObservation = candidate.continuousObservation || sealedContinuous,
            entryConfirmationRounds = if (sealedContinuous) 0 else rounds,
            entryConfirmationRequiredRounds = when {
                sealedContinuous || independentHighHoldEntry -> 0
                else -> requiredConfirmationRounds
            },
            entryConfirmedAtMs = when {
                sealedContinuous || rounds == 0 -> 0L
                startsConfirmation || freshRound -> signal.evaluatedAtMs
                else -> candidate.entryConfirmedAtMs
            },
            entryConfirmationVolume = when {
                sealedContinuous || rounds == 0 -> 0.0
                startsConfirmation || freshRound -> signal.quoteVolume
                else -> candidate.entryConfirmationVolume
            },
            entryTriggerPrice = when {
                sealedContinuous || rounds == 0 -> 0.0
                startsConfirmation -> signal.price
                else -> candidate.entryTriggerPrice.takeIf { it > 0.0 } ?: signal.price
            },
            entryTriggerAtMs = when {
                sealedContinuous || rounds == 0 -> 0L
                startsConfirmation -> signal.evaluatedAtMs
                else -> candidate.entryTriggerAtMs
            },
            breakoutRisk = effectiveBreakoutRisk,
            riskSignals = allRiskSignals,
            riskPolicyVersion = CURRENT_RISK_POLICY_VERSION,
            softRiskLastConfirmedAtMs = softRiskLastConfirmedAtMs,
            postLimitHoldStartedAtMs = holdStartedAtMs,
            postLimitHoldLowPrice = holdLowPrice,
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

    private fun highHoldWaitReason(elapsedMs: Long): String {
        val minutes = (elapsedMs / 60_000L).coerceAtLeast(0L)
        val requiredMinutes = POST_LIMIT_HOLD_MIN_MS / 60_000L
        return "炸板后守住涨停高位线，等待平台承接满${requiredMinutes}分钟；当前已观察${minutes}分钟，跌破平台低点将重新计时"
    }

    private fun buildRiskSignals(
        signal: ObservationSignal,
        ignoreBreakoutRisk: Boolean = false,
    ): List<ObservationRiskSignal> = buildList {
        signal.breakoutRisk?.takeIf { it.risk && !ignoreBreakoutRisk }?.let { risk ->
            val reference = risk.breakoutReference?.takeIf { it.isFinite() && it > 0.0 }
            val details = buildList {
                add(risk.reason)
                if (reference != null && signal.price > 0.0) {
                    add(
                        String.format(
                            Locale.CHINA,
                            "突破参考位 %.2f，现价 %.2f（%+.1f%%）",
                            reference,
                            signal.price,
                            (signal.price / reference - 1.0) * 100.0,
                        ),
                    )
                }
                signal.volumeRatio?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    add(String.format(Locale.CHINA, "量比%.2f", it))
                }
                add(String.format(Locale.CHINA, "当日涨跌%+.1f%%", signal.changePct))
            }
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.BREAKOUT_FAILURE,
                    detail = details.joinToString("，"),
                    keyLevel = reference,
                    currentValue = signal.price,
                    threshold = reference?.let {
                        it * (1.0 - BreakoutRiskPolicy.REFERENCE_BREAK_PCT / 100.0)
                    },
                ),
            )
        }
        if (signal.trendAssessment?.major == MajorTrend.DOWN) {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.TREND_WEAK,
                    detail = "${signal.trendAssessment.reason}；现价${formatPrice(signal.price)}，当日${formatPct(signal.changePct)}",
                    currentValue = signal.price,
                ),
            )
        } else if (signal.trendWeak && none { it.type == ObservationRiskType.TREND_WEAK }) {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.TREND_WEAK,
                    detail = "现价${formatPrice(signal.price)}，当日${formatPct(signal.changePct)}，跌破趋势失效线",
                    currentValue = signal.price,
                ),
            )
        }
        signal.highPosition?.takeIf { it.riskPriority }?.let {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.HIGH_POSITION_DISTRIBUTION,
                    detail = "${it.reason}；现价${formatPrice(signal.price)}，当日${formatPct(signal.changePct)}",
                    currentValue = signal.price,
                ),
            )
        }
        if (signal.limitUpShrinkAcceleration) {
            val volume = signal.volumeRatio?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { String.format(Locale.CHINA, "量比%.2f", it) } ?: "量比暂缺"
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.VOLUME_SHRINK_ACCELERATION,
                    detail = "涨停封板但$volume，低于放量换手门槛${String.format(Locale.CHINA, "%.2f", VolumeRatio.SHRINK_THRESHOLD)}",
                    currentValue = signal.volumeRatio,
                    threshold = VolumeRatio.SHRINK_THRESHOLD,
                ),
            )
        }
        signal.boxRange?.takeIf {
            it.state == BoxRangeState.BREAKDOWN_RISK || it.state == BoxRangeState.BREAKDOWN_CONFIRMED
        }?.let {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.STRUCTURE_BREAKDOWN,
                    detail = it.summary,
                    keyLevel = it.lower,
                    currentValue = signal.price,
                ),
            )
        }
        val fundFlowDetails = buildList {
            signal.fundFlow
                ?.takeIf { it.tone == FundFlowTone.DIVERGENT || it.tone == FundFlowTone.RISK }
                ?.let { add(it.reason.ifBlank { "主动资金流与价格方向背离" }) }
            if (signal.fundFlowWindow?.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW) {
                add(
                    signal.fundFlowWindow.reason.ifBlank { signal.fundFlowWindow.summary },
                )
            }
        }.distinct()
        if (fundFlowDetails.isNotEmpty()) {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.FUND_FLOW_OUTFLOW,
                    detail = fundFlowDetails.joinToString("；"),
                ),
            )
        }
        if (signal.evidence.counter.any { it.contains("量价背离") } &&
            none { it.type == ObservationRiskType.PRICE_VOLUME_DIVERGENCE }
        ) {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.PRICE_VOLUME_DIVERGENCE,
                    detail = signal.evidence.counter.first { it.contains("量价背离") },
                ),
            )
        }
        if (signal.riskFlag && isEmpty()) {
            add(
                ObservationRiskSignal(
                    type = ObservationRiskType.MOMENTUM_DIVERGENCE,
                    detail = signal.signalReason.ifBlank {
                        "强势股出现高位放量分歧，当日${formatPct(signal.changePct)}"
                    },
                    currentValue = signal.price,
                ),
            )
        }
    }.distinctBy { it.type }

    private fun formatPrice(value: Double): String =
        if (value.isFinite() && value > 0.0) String.format(Locale.CHINA, "%.2f", value) else "暂缺"

    private fun formatPct(value: Double): String =
        if (value.isFinite()) String.format(Locale.CHINA, "%+.1f%%", value) else "暂缺"
}
