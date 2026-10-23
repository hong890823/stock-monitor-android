package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import com.example.stockmonitor.core.WatchItem

object WatchlistBuyCandidatePool {
    const val SOURCE = "自选股买点确认"

    fun sync(
        watchlist: List<WatchItem>,
        previous: List<ObservationCandidate>,
        heldCodes: Set<String>,
        observationCodes: Set<String>,
        today: String,
    ): List<ObservationCandidate> {
        val watchedCodes = watchlist
            .filterNot { it.code.startsWith("sh000") || it.code.startsWith("sz399") }
            .filter { it.code !in heldCodes && it.code !in observationCodes }
            .associateBy { it.code }
        val previousByCode = previous.associateBy { it.code }
        return watchedCodes.values.map { item ->
            val existing = previousByCode[item.code]
            when {
                existing == null -> candidate(item.code, item.name, today)
                existing.stage == ObservationStage.ELIMINATED -> candidate(item.code, item.name, today)
                else -> existing.copy(name = item.name)
            }
        }
    }

    fun candidate(code: String, name: String, today: String): ObservationCandidate =
        ObservationCandidate(
            code = code,
            name = name,
            addedDate = today,
            lastEvaluatedDate = null,
            stage = ObservationStage.OBSERVING,
            score = 0,
            weakDays = 0,
            observedDays = 0,
            lastPrice = 0.0,
            source = SOURCE,
            reason = "自选股持续评估有效买点",
            strategy = ObservationStrategy.PRE_BREAKOUT,
        )
}
