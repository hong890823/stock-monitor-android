package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.TrendScreenHit

internal object TrendScreenSelection {
    const val MAX_CANDIDATES = 20

    fun select(hits: List<TrendScreenHit>): List<TrendScreenHit> = orderTrendHits(
        hits.filter { hit ->
            val setup = hit.entrySetup
            setup.confirmed && setup.volumeConfirmed && !setup.trendWeak &&
                setup.marketFilter != MarketFilter.HEADWIND &&
                setup.type in setOf(EntrySetupType.M20_PULLBACK, EntrySetupType.BREAKOUT)
        },
    ).take(MAX_CANDIDATES)

    fun merge(
        previous: List<TrendScreenHit>,
        current: List<TrendScreenHit>,
        includePatterns: Boolean,
    ): List<TrendScreenHit> = select(if (includePatterns) current else previous)
}
