package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import java.util.Locale

object ObservationBuyAlert {
    fun shouldNotify(
        previous: ObservationCandidate,
        current: ObservationCandidate,
    ): Boolean = previous.stage != ObservationStage.BUY_CANDIDATE &&
        current.stage == ObservationStage.BUY_CANDIDATE &&
        (current.strategy != ObservationStrategy.LEADER ||
            current.entryConfirmationRounds >= ObservationPool.ENTRY_CONFIRMATION_ROUNDS) &&
        !ObservationEligibility.isLimitUpDeferred(current, null)

    fun title(candidate: ObservationCandidate): String =
        "🟢 ${ObservationStage.BUY_CANDIDATE.label}｜${candidate.name}"

    fun body(candidate: ObservationCandidate): String = buildString {
        append(candidate.code)
        append("｜")
        append(candidate.strategy.label)
        append("｜现价")
        append(String.format(Locale.CHINA, "%.2f", candidate.lastPrice))
        append("｜")
        append(candidate.reason.ifBlank { "${ObservationStage.BUY_CANDIDATE.label}，请核对执行价格与仓位" })
        candidate.stopPrice?.let {
            append("｜止损参考")
            append(String.format(Locale.CHINA, "%.2f", it))
        }
    }
}
