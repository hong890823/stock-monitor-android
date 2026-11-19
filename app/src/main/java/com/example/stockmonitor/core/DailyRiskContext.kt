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
        if (criticalReasons(price).isNotEmpty()) return DailyRiskLevel.CRITICAL
        val elevated = priceLocation.equals("LOW", ignoreCase = true) ||
            majorTrend.equals("DOWN", ignoreCase = true) ||
            closesBelowMa20 >= 5 ||
            positionRisk
        return if (elevated) DailyRiskLevel.ELEVATED else DailyRiskLevel.NORMAL
    }

    fun criticalReasons(price: Double): List<String> = buildList {
        val invalidation = invalidationPrice
        val low = rangeLow
        if (highExitRisk) add("高位转弱标记（技术风险）")
        if (systemicRisk) add("市场或组合普跌标记（环境风险，非个股价值结论）")
        if (portfolioRisk) add("组合风险标记（非个股清仓依据）")
        if (eventRisk) add("基本面红灯标记（须核实来源、日期与实质影响）")
        if (liquidityRisk) add("流动性风险标记（须核实）")
        if (invalidation != null && invalidation.isFinite() && invalidation > 0.0 && price <= invalidation) {
            add("跌破自动失效参考价$invalidation（非用户授权止损）")
        }
        if (low != null && low.isFinite() && low > 0.0 && price < low) add("跌破区间低点$low（技术风险）")
        if (majorTrend.equals("DOWN", ignoreCase = true) && fiveDayNoNewLow == false) {
            add("大趋势向下且近期创新低（技术风险）")
        }
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

