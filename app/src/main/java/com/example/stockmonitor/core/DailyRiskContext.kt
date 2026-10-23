package com.example.stockmonitor.core

import java.time.LocalDate

data class DailyRiskMetrics(
    val fiveDayNoNewLow: Boolean?,
    val closesBelowMa20: Int,
) {
    companion object {
        private const val MA20_BREAK_TOLERANCE = 0.005

        fun fromBars(bars: List<DayBar>, sourceDate: String): DailyRiskMetrics {
            val normalizedSourceDate = normalizedDate(sourceDate) ?: return DailyRiskMetrics(null, 0)
            val validBars = bars
                .mapNotNull { bar ->
                    normalizedDate(bar.date)?.let { date ->
                        bar.copy(date = date)
                    }
                }
                .filter {
                    it.low.isFinite() && it.low > 0.0 &&
                        it.close.isFinite() && it.close > 0.0
                }
                .sortedBy { it.date }
            if (validBars.lastOrNull()?.date != normalizedSourceDate) return DailyRiskMetrics(null, 0)

            val fiveDayNoNewLow = if (validBars.size >= 25) {
                val priorLow = validBars.subList(validBars.size - 25, validBars.size - 5).minOf { it.low }
                validBars.takeLast(5).all { it.low >= priorLow }
            } else {
                null
            }

            var closesBelowMa20 = 0
            for (index in validBars.lastIndex downTo 19) {
                val ma20 = validBars.subList(index - 19, index + 1).map { it.close }.average()
                if (validBars[index].close < ma20 * (1.0 - MA20_BREAK_TOLERANCE)) {
                    closesBelowMa20 += 1
                } else {
                    break
                }
            }
            return DailyRiskMetrics(fiveDayNoNewLow, closesBelowMa20)
        }

        private fun normalizedDate(value: String): String? =
            runCatching { LocalDate.parse(value.take(10)) }.getOrNull()?.toString()
    }
}

enum class DailyRiskLevel {
    NORMAL,
    ELEVATED,
    CRITICAL,
}

data class DailyRiskContext(
    val sourceDate: String,
    val priceLocation: String? = null,
    val rangeLow: Double? = null,
    val invalidationPrice: Double? = null,
    val majorTrend: String? = null,
    val fiveDayNoNewLow: Boolean? = null,
    val closesBelowMa20: Int = 0,
    val highExitRisk: Boolean = false,
    val systemicRisk: Boolean = false,
    val portfolioRisk: Boolean = false,
    val eventRisk: Boolean = false,
    val positionRisk: Boolean = false,
    val liquidityRisk: Boolean = false,
) {
    fun withCurrentInput(input: DailyRiskContextInput): DailyRiskContext = copy(
        priceLocation = input.priceLocation,
        rangeLow = input.rangeLow,
        invalidationPrice = input.invalidationPrice,
        majorTrend = input.majorTrend,
        highExitRisk = input.highExitRisk,
        systemicRisk = input.systemicRisk,
        portfolioRisk = input.portfolioRisk,
        eventRisk = input.eventRisk,
        positionRisk = input.positionRisk,
        liquidityRisk = input.liquidityRisk,
    )

    fun riskLevel(price: Double): DailyRiskLevel {
        val invalidation = invalidationPrice
        val low = rangeLow
        val critical = highExitRisk || systemicRisk || portfolioRisk || eventRisk || liquidityRisk ||
            (invalidation != null && invalidation.isFinite() && invalidation > 0.0 && price <= invalidation) ||
            (low != null && low.isFinite() && low > 0.0 && price < low) ||
            (majorTrend.equals("DOWN", ignoreCase = true) && fiveDayNoNewLow == false)
        if (critical) return DailyRiskLevel.CRITICAL
        val elevated = priceLocation.equals("LOW", ignoreCase = true) ||
            majorTrend.equals("DOWN", ignoreCase = true) ||
            closesBelowMa20 >= 5 ||
            positionRisk
        return if (elevated) DailyRiskLevel.ELEVATED else DailyRiskLevel.NORMAL
    }
}

data class DailyRiskContextInput(
    val quoteDate: String = "",
    val priceLocation: String? = null,
    val rangeLow: Double? = null,
    val invalidationPrice: Double? = null,
    val majorTrend: String? = null,
    val fiveDayNoNewLow: Boolean? = null,
    val closesBelowMa20: Int = 0,
    val highExitRisk: Boolean = false,
    val systemicRisk: Boolean = false,
    val portfolioRisk: Boolean = false,
    val eventRisk: Boolean = false,
    val positionRisk: Boolean = false,
    val liquidityRisk: Boolean = false,
) {
    fun toContext(): DailyRiskContext = DailyRiskContext(
        sourceDate = quoteDate,
        priceLocation = priceLocation,
        rangeLow = rangeLow,
        invalidationPrice = invalidationPrice,
        majorTrend = majorTrend,
        fiveDayNoNewLow = fiveDayNoNewLow,
        closesBelowMa20 = closesBelowMa20.coerceAtLeast(0),
        highExitRisk = highExitRisk,
        systemicRisk = systemicRisk,
        portfolioRisk = portfolioRisk,
        eventRisk = eventRisk,
        positionRisk = positionRisk,
        liquidityRisk = liquidityRisk,
    )
}

class DailyRiskContextCache {
    private val values = HashMap<Pair<String, String>, DailyRiskContext>()

    fun getOrBuild(
        code: String,
        quoteDate: String?,
        builder: () -> DailyRiskContextInput,
    ): DailyRiskContext? {
        val normalizedDate = normalizedDate(quoteDate) ?: return null
        val key = code.trim().lowercase() to normalizedDate
        values[key]?.let { return it }
        val input = builder()
        if (normalizedDate(input.quoteDate) != normalizedDate) return null
        return input.toContext().also { values[key] = it }
    }

    fun clear() = values.clear()

    private fun normalizedDate(value: String?): String? {
        val text = value?.take(10).orEmpty()
        return runCatching { LocalDate.parse(text) }.getOrNull()?.toString()
    }
}

