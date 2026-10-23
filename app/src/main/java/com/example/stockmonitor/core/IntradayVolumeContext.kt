package com.example.stockmonitor.core

import java.time.LocalDateTime

data class IntradayVolumeContext(
    val marketTurnoverRatio: Double? = null,
    val marketExpanding: Boolean? = null,
    val stockVolumeRatio: Double? = null,
    val stockVolumeConfirming: Boolean? = null,
    val barVolumeRatio: Double? = null,
    val barVolumeConfirming: Boolean? = null,
    val absorptionConfirmed: Boolean = false,
    val blowoffRisk: Boolean = false,
) {
    companion object {
        const val MARKET_EXPANDING_RATIO = 1.2
        const val STOCK_CONFIRMING_RATIO = 1.3
        const val BAR_CONFIRMING_RATIO = 1.3
        const val ABSORPTION_LOWER_SHADOW_RATIO = 0.35
        const val BLOWOFF_UPPER_SHADOW_RATIO = 0.50

        fun build(
            marketPredictedFullDayTurnover: Double? = null,
            recentCompleteMarketTurnovers: List<Double> = emptyList(),
            stockVolumeRatio: Double? = null,
            priceChangePct: Double? = null,
            bars15: List<IntradayBar> = emptyList(),
            price: Double? = null,
            support: Double? = null,
            now: LocalDateTime,
            quoteDate: String?,
        ): IntradayVolumeContext {
            val marketBaseline = recentCompleteMarketTurnovers
                .filter { it.isPositiveFinite() }
                .takeLast(5)
                .sorted()
                .medianOrNull()
            val marketRatio = ratio(marketPredictedFullDayTurnover, marketBaseline)
            val stockRatio = stockVolumeRatio?.takeIf { it.isPositiveFinite() }
            val stockConfirming = stockRatio?.let { it >= STOCK_CONFIRMING_RATIO }
            val validBars = IntradayBarWindow(bars15)
                .completedAt(now, quoteDate, 15)
                .bars
            val current = validBars.lastOrNull()
            val previous = validBars.dropLast(1).takeLast(20)
            val previousMedian = previous.map { it.volume }.filter { it.isPositiveFinite() }.sorted().medianOrNull()
            val barRatio = if (current != null && previousMedian != null && current.volume.isPositiveFinite()) {
                current.volume / previousMedian
            } else {
                null
            }
            val barConfirming = barRatio?.let { it >= BAR_CONFIRMING_RATIO }
            var absorption = false
            var blowoff = false
            if (current != null && previous.size >= 20) {
                val currentPrice = price
                val supportPrice = support
                val range = current.high - current.low
                val midpoint = (current.high + current.low) / 2.0
                val lowerShadow = minOf(current.open, current.close) - current.low
                val upperShadow = current.high - maxOf(current.open, current.close)
                val previousClose = previous.last().close
                absorption = barConfirming == true &&
                    currentPrice != null && currentPrice.isFinite() && currentPrice > 0.0 &&
                    supportPrice != null && supportPrice.isFinite() && supportPrice > 0.0 &&
                    currentPrice < supportPrice && current.close < supportPrice &&
                    range > 0.0 && lowerShadow / range >= ABSORPTION_LOWER_SHADOW_RATIO &&
                    current.close >= midpoint &&
                    current.close >= previousClose * 0.99
                blowoff = barConfirming == true &&
                    priceChangePct != null && priceChangePct.isFinite() && priceChangePct > 0.0 &&
                    range > 0.0 && upperShadow / range >= BLOWOFF_UPPER_SHADOW_RATIO &&
                    current.close < midpoint
            }
            return IntradayVolumeContext(
                marketTurnoverRatio = marketRatio,
                marketExpanding = marketRatio?.let { it >= MARKET_EXPANDING_RATIO },
                stockVolumeRatio = stockRatio,
                stockVolumeConfirming = stockConfirming,
                barVolumeRatio = barRatio,
                barVolumeConfirming = barConfirming,
                absorptionConfirmed = absorption,
                blowoffRisk = blowoff,
            )
        }

        private fun ratio(value: Double?, baseline: Double?): Double? {
            if (value == null || baseline == null) return null
            return if (value.isPositiveFinite() && baseline.isPositiveFinite()) value / baseline else null
        }

        private fun List<Double>.medianOrNull(): Double? {
            if (isEmpty()) return null
            val middle = size / 2
            return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2.0
        }
    }
}

private fun Double?.isPositiveFinite(): Boolean =
    this != null && isFinite() && this > 0.0

