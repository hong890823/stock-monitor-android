package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.SinaClient

enum class ObservationSection(val label: String) {
    BUY(ObservationStage.BUY_CANDIDATE.label),
    NEAR(ObservationStage.NEAR_ENTRY.label),
    DEFERRED("暂缓参与 · 封板/缩量加速"),
    OBSERVING("其他观察对象"),
}

object ObservationEligibility {
    const val PAGE_SIZE = 20
    private const val LIMIT_TOLERANCE_PCT = 0.15
    const val LIMIT_WAIT_REASON = "涨停封板，暂不介入，开板后重新确认"
    const val SHRINK_WAIT_REASON = "缩量加速风险，已降级观察；等待换手、承接及入场条件重新确认"

    fun isAtLimitUp(code: String, name: String, quote: Quote): Boolean {
        if (!quote.prevClose.isFinite() || quote.prevClose <= 0.0 || !quote.price.isFinite()) {
            return false
        }
        val limit = SinaClient.limitPctFor(code, name)
        val reachedLimit = quote.changePct >= limit - LIMIT_TOLERANCE_PCT
        val atSessionHigh = quote.high <= 0.0 || quote.price >= quote.high * 0.9995
        return reachedLimit && atSessionHigh
    }

    fun isLimitUpShrinkAcceleration(
        limitUpBlocked: Boolean,
        changePct: Double,
        volumeRatio: Double?,
        wasDeferred: Boolean = false,
    ): Boolean {
        if (volumeRatio == null || !volumeRatio.isFinite() || volumeRatio <= 0.0) return wasDeferred
        return (limitUpBlocked || wasDeferred) &&
            changePct > VolumePrice.FLAT_PCT &&
            volumeRatio <= VolumeRatio.SHRINK_THRESHOLD
    }

    fun isLimitUpDeferred(candidate: ObservationCandidate, quote: Quote?): Boolean {
        if (candidate.stage == ObservationStage.ELIMINATED) return false
        return candidate.limitUpBlocked || candidate.limitUpShrinkAcceleration ||
            candidate.reason.contains("涨停封板") ||
            candidate.reason.contains("涨停缩量加速") ||
            (quote != null && isAtLimitUp(candidate.code, candidate.name, quote))
    }

    fun applyQuoteGate(candidate: ObservationCandidate, quote: Quote?): ObservationCandidate {
        if (candidate.stage == ObservationStage.ELIMINATED) return candidate
        val shrinkRisk = candidate.limitUpShrinkAcceleration || candidate.reason.contains("涨停缩量加速")
        if (!isLimitUpDeferred(candidate, quote)) {
            val newQuoteDay = quote?.quoteDate != null && quote.quoteDate != candidate.lastEvaluatedDate
            return if (candidate.stage == ObservationStage.BUY_CANDIDATE &&
                (newQuoteDay || (candidate.strategy == ObservationStrategy.LEADER &&
                    candidate.entryConfirmationRounds < ObservationPool.ENTRY_CONFIRMATION_ROUNDS))) {
                candidate.copy(stage = ObservationStage.NEAR_ENTRY, reason = "等待回踩企稳及连续复评确认",
                    entryConfirmationRounds = 0, entryConfirmedAtMs = 0L, entryConfirmationVolume = 0.0)
            } else candidate
        }
        return candidate.copy(
            stage = when {
                shrinkRisk -> ObservationStage.OBSERVING
                candidate.stage == ObservationStage.BUY_CANDIDATE -> ObservationStage.NEAR_ENTRY
                else -> candidate.stage
            },
            reason = if (shrinkRisk) SHRINK_WAIT_REASON else LIMIT_WAIT_REASON,
            limitUpBlocked = candidate.limitUpBlocked || quote?.let {
                isAtLimitUp(candidate.code, candidate.name, it)
            } == true,
            limitUpShrinkAcceleration = shrinkRisk,
            entryConfirmationRounds = 0,
            entryConfirmedAtMs = 0L,
            entryConfirmationVolume = 0.0,
        )
    }

    fun needsRefresh(candidate: ObservationCandidate, today: String, quote: Quote?): Boolean {
        return candidate.stage != ObservationStage.ELIMINATED && quote != null &&
            quote.quoteDate == today && quote.price.isFinite() && quote.price > 0
    }

    fun sections(candidates: List<ObservationCandidate>, quotes: Map<String, Quote>): Map<ObservationSection, List<ObservationCandidate>> {
        val active = forDisplay(candidates, quotes)
        return ObservationSection.entries.associateWith { section ->
            active.filter { item ->
                val actual = when {
                    isLimitUpDeferred(item, quotes[item.code]) -> ObservationSection.DEFERRED
                    item.stage == ObservationStage.BUY_CANDIDATE -> ObservationSection.BUY
                    item.stage == ObservationStage.NEAR_ENTRY -> ObservationSection.NEAR
                    else -> ObservationSection.OBSERVING
                }
                actual == section
            }
        }
    }

    fun page(items: List<ObservationCandidate>, index: Int): List<ObservationCandidate> =
        items.drop(index.coerceAtLeast(0) * PAGE_SIZE).take(PAGE_SIZE)

    fun entrySections(
        candidates: List<ObservationCandidate>,
        quotes: Map<String, Quote>,
    ): Map<ObservationSection, List<ObservationCandidate>> =
        sections(candidates, quotes).filterKeys { it == ObservationSection.BUY || it == ObservationSection.NEAR }

    fun forDisplay(candidates: List<ObservationCandidate>, quotes: Map<String, Quote>): List<ObservationCandidate> =
        candidates.filter { it.stage != ObservationStage.ELIMINATED }
            .map { applyQuoteGate(it, quotes[it.code]) }
            .sortedWith(
                compareBy<ObservationCandidate> {
                    when {
                        isLimitUpDeferred(it, quotes[it.code]) -> 2
                        it.stage == ObservationStage.BUY_CANDIDATE -> 0
                        it.stage == ObservationStage.NEAR_ENTRY -> 1
                        else -> 3
                    }
                }.thenByDescending { it.lastEvaluatedDate ?: it.addedDate },
            )
}
