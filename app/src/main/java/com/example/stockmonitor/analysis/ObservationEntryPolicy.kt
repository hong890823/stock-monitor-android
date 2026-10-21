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
        trendAssessment: TrendAssessment? = null,
        stopPrice: Double? = null,
        priceLocation: PriceLocation? = null,
        ma5: Double? = null,
        ma20: Double? = null,
        biasFromMa5Pct: Double? = null,
    ): Boolean {
        if (recentLimitUpDays < 1 || relativeStrengthPct == null ||
            !relativeStrengthPct.isFinite() || relativeStrengthPct < RELATIVE_STRENGTH_PCT
        ) {
            return false
        }
        if (volumeRatio == null || !volumeRatio.isFinite() || volumeRatio <= 0.0 ||
            !price.isFinite() || price <= 0.0
        ) {
            return false
        }
        val effectiveStop = stopPrice ?: trend?.stopPrice
        if (effectiveStop?.let { it.isFinite() && it > 0.0 && it < price } != true) return false
        val riskEvidence = BreakoutRiskEvidence(
            price = price,
            dailyHighPrice = null,
            breakoutReference = null,
            changePct = 0.0,
            volumeRatio = volumeRatio,
            atrPct = null,
            continuation = true,
            priceLocation = priceLocation,
            ma5 = ma5,
            ma20 = ma20,
            biasFromMa5Pct = biasFromMa5Pct,
        )
        if (BreakoutRiskPolicy.riskLineBroken(riskEvidence)) return false
        val pullbackPath = volumeRatio <= TrendEntryStrategy.PULLBACK_VOLUME_RATIO &&
            trend != null && trend.type == EntrySetupType.M20_PULLBACK &&
            trend.confirmed && trend.canBuy && trend.volumeConfirmed && !trend.trendWeak
        val strongHoldPath = trend?.trendWeak != true &&
            trendAssessment?.riskOnly == false &&
            trendAssessment?.shortTerm in setOf(ShortTermTrend.UP, ShortTermTrend.EXTENDED, ShortTermTrend.REBOUND)
        return pullbackPath || strongHoldPath
    }

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
