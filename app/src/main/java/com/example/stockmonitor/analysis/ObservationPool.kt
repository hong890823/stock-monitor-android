package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy

/**
 * 选股后的连续观察规则。它只消费已经得到的选股/行情信号，不自行请求数据。
 */
object ObservationPool {
    const val MAX_OBSERVED_DAYS = 10
    const val MAX_WEAK_DAYS = 3
    const val ENTRY_CONFIRMATION_ROUNDS = 2
    const val ENTRY_CONFIRMATION_MIN_MS = 60_000L
    const val ENTRY_CONFIRMATION_MAX_MS = 300_000L

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
        val score = when (candidate.strategy) {
            ObservationStrategy.PRE_BREAKOUT -> if (signal.setupScore > 0) {
                signal.setupScore
            } else {
                listOf(signal.hasScreenSignal, signal.confirmedEntry, signal.canBuy).count { it }
            }
            ObservationStrategy.LEADER -> signal.strongScore
        }.let { base -> if (signal.trendWeak) base - 2 else base }.coerceIn(0, 3)
        val eligible = signal.confirmedEntry && signal.canBuy && !signal.riskFlag && !signal.trendWeak &&
            !signal.limitUpBlocked && !signal.limitUpShrinkAcceleration
        val leader = candidate.strategy == ObservationStrategy.LEADER
        val elapsed = signal.evaluatedAtMs - candidate.entryConfirmedAtMs
        val validSample = signal.evaluatedAtMs > 0 && signal.quoteVolume.isFinite() && signal.quoteVolume > 0
        val continuing = sameDay && candidate.entryConfirmationRounds > 0 &&
            elapsed in 0..ENTRY_CONFIRMATION_MAX_MS && signal.quoteVolume >= candidate.entryConfirmationVolume
        val freshRound = continuing && elapsed >= ENTRY_CONFIRMATION_MIN_MS &&
            signal.quoteVolume > candidate.entryConfirmationVolume
        val rounds = when {
            !leader || !eligible || !validSample -> 0
            !continuing -> 1
            freshRound -> (candidate.entryConfirmationRounds + 1).coerceAtMost(ENTRY_CONFIRMATION_ROUNDS)
            else -> candidate.entryConfirmationRounds
        }
        val confirmed = eligible && (!leader || rounds >= ENTRY_CONFIRMATION_ROUNDS)
        val stage = when {
            weakDays >= MAX_WEAK_DAYS -> ObservationStage.ELIMINATED
            observedDays >= MAX_OBSERVED_DAYS && !confirmed -> ObservationStage.ELIMINATED
            signal.limitUpShrinkAcceleration -> ObservationStage.OBSERVING
            confirmed -> ObservationStage.BUY_CANDIDATE
            score >= 2 || signal.confirmedEntry -> ObservationStage.NEAR_ENTRY
            else -> ObservationStage.OBSERVING
        }
        val reason = when (stage) {
            ObservationStage.BUY_CANDIDATE -> if (leader) {
                "强势资格与缩量回踩企稳条件连续${ENTRY_CONFIRMATION_ROUNDS}轮通过；仅为技术候选，不代表资金承接已获证实"
            } else signal.signalReason.ifBlank { "趋势入场已确认，等待执行纪律与仓位检查" }
            ObservationStage.NEAR_ENTRY -> when {
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                leader && eligible -> "回踩条件初步满足，等待下一轮新行情复评（$rounds/$ENTRY_CONFIRMATION_ROUNDS）"
                else -> signal.signalReason.ifBlank { "条件逐步满足，等待最后的价格与量能确认" }
            }
            ObservationStage.OBSERVING -> when {
                signal.limitUpShrinkAcceleration -> ObservationEligibility.SHRINK_WAIT_REASON
                signal.limitUpBlocked -> ObservationEligibility.LIMIT_WAIT_REASON
                signal.trendWeak -> "出现走弱信号，继续观察，连续走弱将淘汰"
                else -> "条件尚未齐备，继续观察"
            }
            ObservationStage.ELIMINATED -> if (weakDays >= MAX_WEAK_DAYS) {
                "连续${MAX_WEAK_DAYS}个交易日走弱，自动淘汰"
            } else {
                "观察期超过${MAX_OBSERVED_DAYS}个交易日仍未确认，自动淘汰"
            }
        }
        return candidate.copy(
            lastEvaluatedDate = signal.date,
            stage = stage,
            score = score,
            weakDays = weakDays,
            observedDays = observedDays,
            lastPrice = signal.price,
            stopPrice = signal.stopPrice,
            reason = reason,
            evidence = signal.evidence,
            limitUpBlocked = signal.limitUpBlocked,
            limitUpShrinkAcceleration = signal.limitUpShrinkAcceleration,
            entryConfirmationRounds = rounds,
            entryConfirmedAtMs = when {
                rounds == 0 -> 0L
                !continuing || freshRound -> signal.evaluatedAtMs
                else -> candidate.entryConfirmedAtMs
            },
            entryConfirmationVolume = when {
                rounds == 0 -> 0.0
                !continuing || freshRound -> signal.quoteVolume
                else -> candidate.entryConfirmationVolume
            },
        )
    }
}
