package com.example.stockmonitor.analysis

import java.util.Locale

enum class MarketEnvironmentStatus(val label: String) {
    STRONG_SUSTAINED("强势延续"),
    STRONG_SINGLE_DAY("高成交额待确认"),
    DIVERGENCE("放量分歧"),
    NORMAL("普通环境"),
    UNKNOWN("数据不足"),
}

data class MarketEnvironmentQuality(
    val status: MarketEnvironmentStatus,
    val predictedTurnoverYi: Double?,
    val persistenceDays: Int?,
    val persistenceAverageYi: Double?,
    val industryBreadth: Double?,
    val conceptBreadth: Double?,
    val marketChangePct: Double?,
    val indexNearHigh: Boolean?,
    val reasons: List<String>,
) {
    val strong: Boolean get() = status == MarketEnvironmentStatus.STRONG_SUSTAINED
    val cautious: Boolean get() = status == MarketEnvironmentStatus.STRONG_SINGLE_DAY
    val summary: String get() = reasons.joinToString("；").ifBlank { status.label }

    companion object {
        const val STRONG_TURNOVER_YI = 20_000.0
        const val SUSTAINED_TURNOVER_YI = 17_000.0
        const val MIN_SUSTAINED_AVERAGE_YI = 18_000.0
        const val MIN_SUSTAINED_DAYS = 3
        const val MIN_BREADTH = 0.60
        const val NEAR_INDEX_HIGH_PCT = 1.0

        fun evaluate(
            predictedTurnoverYi: Double?,
            marketChangePct: Double?,
            marketPrice: Double? = null,
            marketHighPrice: Double? = null,
            industryBreadth: Double? = null,
            conceptBreadth: Double? = null,
            recentTurnoversYi: List<Double> = emptyList(),
        ): MarketEnvironmentQuality {
            val turnover = predictedTurnoverYi?.takeIf { it.isFinite() && it > 0 }
            val change = marketChangePct?.takeIf { it.isFinite() }
            val industry = industryBreadth?.takeIf { it.isFinite() && it in 0.0..1.0 }
            val concept = conceptBreadth?.takeIf { it.isFinite() && it in 0.0..1.0 }
            val price = marketPrice?.takeIf { it.isFinite() && it > 0 }
            val high = marketHighPrice?.takeIf { it.isFinite() && it > 0 }
            val nearHigh = if (price != null && high != null && price <= high) {
                (high - price) / high < NEAR_INDEX_HIGH_PCT / 100.0
            } else null
            val history = recentTurnoversYi.takeLast(5)
            val persistence = if (turnover == null) null else {
                var consecutive = 0
                for (value in history.asReversed()) {
                    if (!value.isFinite() || value < SUSTAINED_TURNOVER_YI) break
                    consecutive++
                }
                consecutive
            }
            val persistenceAverage = history.takeLast(MIN_SUSTAINED_DAYS)
                .takeIf { values ->
                    values.size == MIN_SUSTAINED_DAYS && values.all {
                        it.isFinite() && it >= SUSTAINED_TURNOVER_YI
                    }
                }?.average()
            val reasons = mutableListOf<String>()
            reasons += turnover?.let { "全天预估成交额${String.format(Locale.CHINA, "%.0f", it)}亿" }
                ?: "全天预估成交额暂缺"
            if (persistence != null) {
                val averageText = persistenceAverage?.let {
                    "最近3日均值${String.format(Locale.CHINA, "%.0f", it)}亿"
                } ?: "最近3日均值待确认"
                reasons += "此前完整收盘日连续${persistence}日达到1.7万亿参考线；$averageText；今日预估单独观察"
            }
            reasons += change?.let { "沪指${String.format(Locale.CHINA, "%+.2f%%", it)}" } ?: "沪指涨跌暂缺"
            when (nearHigh) {
                true -> reasons += "指数接近日内高位"
                false -> reasons += "指数未能接近日内高位，存在滞涨或分歧"
                null -> reasons += "指数日内位置数据暂缺或异常"
            }
            reasons += industry?.let { "行业板块上涨占比${String.format(Locale.CHINA, "%.0f%%", it * 100)}" }
                ?: "行业板块宽度暂缺"
            reasons += concept?.let { "概念板块上涨占比${String.format(Locale.CHINA, "%.0f%%", it * 100)}" }
                ?: "概念板块宽度暂缺"

            if (turnover == null || change == null || industry == null || concept == null || nearHigh == null) return MarketEnvironmentQuality(
                MarketEnvironmentStatus.UNKNOWN, turnover, persistence, persistenceAverage, industry, concept, change, nearHigh, reasons,
            )
            val breadthStrong = minOf(industry, concept) >= MIN_BREADTH
            val indexStrong = change >= 0.0 && nearHigh == true
            val highTurnover = turnover >= STRONG_TURNOVER_YI
            val sustained = persistence != null && persistence >= MIN_SUSTAINED_DAYS &&
                persistenceAverage != null && persistenceAverage >= MIN_SUSTAINED_AVERAGE_YI
            val status = when {
                highTurnover && breadthStrong && indexStrong && sustained -> {
                    reasons += "量能、指数和板块扩散暂时同向"
                    MarketEnvironmentStatus.STRONG_SUSTAINED
                }
                highTurnover && breadthStrong && indexStrong -> {
                    reasons += "当前量能很高，但连续性尚未确认"
                    MarketEnvironmentStatus.STRONG_SINGLE_DAY
                }
                highTurnover -> {
                    if (!breadthStrong) reasons += "板块扩散不足，不能把高成交额等同于普涨"
                    if (!indexStrong) reasons += "指数涨幅或日内位置不支持强势延续"
                    MarketEnvironmentStatus.DIVERGENCE
                }
                else -> {
                    reasons += "成交额尚未达到高量级判断线"
                    MarketEnvironmentStatus.NORMAL
                }
            }
            return MarketEnvironmentQuality(status, turnover, persistence, persistenceAverage, industry, concept, change, nearHigh, reasons)
        }
    }
}
