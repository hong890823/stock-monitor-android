package com.example.stockmonitor.analysis

enum class MarketTurnoverSource {
    SAME_TIME,
    FULL_DAY_FALLBACK,
}

data class MarketTurnoverReading(
    val ratio: Double,
    val source: MarketTurnoverSource,
)

object MarketTurnoverFallbackPolicy {
    private const val MIN_RECENT_SAMPLES = 3
    private const val MIN_VALID_SAMPLES = 2

    fun evaluate(
        predictedTurnoverYi: Double?,
        recentTurnoversYi: List<Double>,
    ): MarketTurnoverReading? {
        val predicted = predictedTurnoverYi?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (recentTurnoversYi.size < MIN_RECENT_SAMPLES) return null
        val recent = recentTurnoversYi.filter { it.isFinite() && it > 0.0 }.sorted()
        if (recent.size < MIN_VALID_SAMPLES) return null
        val median = if (recent.size % 2 == 0) {
            (recent[recent.size / 2 - 1] + recent[recent.size / 2]) / 2.0
        } else {
            recent[recent.size / 2]
        }
        if (!median.isFinite() || median <= 0.0) return null
        return MarketTurnoverReading(predicted / median, MarketTurnoverSource.FULL_DAY_FALLBACK)
    }
}
