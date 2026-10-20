package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.ScreenHit

internal object RelaxedScreenSelection {
    const val MAX_CANDIDATES = 20

    fun select(hits: List<ScreenHit>): List<ScreenHit> = orderRelaxedHits(
        hits.filter {
            it.relaxedTrendEligible && it.macdDetail.isNotBlank() && (it.rsiOk || it.kdjOk)
        },
    ).take(MAX_CANDIDATES)

    fun trendEligible(bars: List<DayBar>): Boolean =
        bars.size >= 30 && bars.all {
            it.close.isFinite() && it.close > 0 &&
                it.high.isFinite() && it.high > 0 && it.low.isFinite() && it.low > 0 &&
                it.volume.isFinite() && it.volume >= 0
        } && !TrendEntryStrategy.evaluateDaily(bars).trendWeak
}
