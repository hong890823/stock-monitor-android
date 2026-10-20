package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Quote

object ObservationEntryPolicy {
    const val RELATIVE_STRENGTH_PCT = 3.0

    fun leaderCanBuy(
        recentLimitUpDays: Int,
        relativeStrengthPct: Double?,
        volumeRatio: Double?,
        price: Double,
        trend: EntrySetup?,
    ): Boolean = recentLimitUpDays >= 1 &&
        relativeStrengthPct != null && relativeStrengthPct.isFinite() && relativeStrengthPct >= RELATIVE_STRENGTH_PCT &&
        volumeRatio != null && volumeRatio.isFinite() && volumeRatio > 0 &&
        volumeRatio <= TrendEntryStrategy.PULLBACK_VOLUME_RATIO &&
        price.isFinite() && price > 0 &&
        trend != null && trend.type == EntrySetupType.M20_PULLBACK &&
        trend.confirmed && trend.canBuy && trend.volumeConfirmed && !trend.trendWeak &&
        trend.marketFilter != MarketFilter.HEADWIND &&
        trend.stopPrice?.let { it.isFinite() && it > 0 && it < price } == true

    fun withLiveBar(history: List<DayBar>, quote: Quote): List<DayBar> {
        val date = quote.quoteDate ?: return emptyList()
        if (listOf(quote.price, quote.open, quote.high, quote.low).any { !it.isFinite() || it <= 0 } ||
            !quote.volume.isFinite() || quote.volume <= 0 ||
            quote.low > minOf(quote.open, quote.price) || quote.high < maxOf(quote.open, quote.price)) {
            return emptyList()
        }
        return history.filter { it.date < date } + DayBar(
            date, quote.open, quote.high, quote.low, quote.price, quote.volume,
        )
    }
}
