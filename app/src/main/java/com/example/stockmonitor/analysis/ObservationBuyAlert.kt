package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import java.util.Locale

object ObservationBuyAlert {
    fun alertKey(candidate: ObservationCandidate): String = candidate.code

    /** 非持仓股的盘中提醒只允许这里的真实“买点已确认”状态。 */
    fun canNotify(candidate: ObservationCandidate): Boolean =
        candidate.stage == ObservationStage.BUY_CANDIDATE &&
            !ObservationEligibility.isLimitUpDeferred(candidate, null)

    fun shouldNotify(
        previous: ObservationCandidate,
        current: ObservationCandidate,
    ): Boolean = previous.stage != ObservationStage.BUY_CANDIDATE &&
        canNotify(current) &&
        (current.strategy == ObservationStrategy.PRE_BREAKOUT ||
            current.entryConfirmationRounds >= ObservationPool.requiredConfirmationRounds(current))

    fun title(candidate: ObservationCandidate): String =
        "🟢 ${ObservationStage.BUY_CANDIDATE.label}｜${candidate.name}"

    fun body(candidate: ObservationCandidate): String = buildString {
        append(candidate.code)
        append("｜")
        append(
            if (candidate.source == WatchlistBuyCandidatePool.SOURCE) {
                "自选股"
            } else {
                candidate.strategy.label
            },
        )
        append("｜现价")
        append(String.format(Locale.CHINA, "%.2f", candidate.lastPrice))
        append("｜")
        append(candidate.reason.ifBlank { "${ObservationStage.BUY_CANDIDATE.label}，请核对执行价格与仓位" })
        candidate.stopPrice?.let {
            append("｜止损参考")
            append(String.format(Locale.CHINA, "%.2f", it))
        }
    }

    fun body(candidate: ObservationCandidate, guardedText: String): String = buildString {
        append(candidate.code)
        append("｜")
        append(
            if (candidate.source == WatchlistBuyCandidatePool.SOURCE) {
                "自选股"
            } else {
                candidate.strategy.label
            },
        )
        append("｜现价")
        append(String.format(Locale.CHINA, "%.2f", candidate.lastPrice))
        append("｜")
        append(guardedText)
        candidate.stopPrice?.let {
            append("｜失效参考")
            append(String.format(Locale.CHINA, "%.2f", it))
        }
    }
}
