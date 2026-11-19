package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.PositionAllocationMode

data class PositionAllocationRecommendationInput(
    val quantity: Double,
    val technicalScore: Int? = null,
    val technicalAllowsAdd: Boolean? = null,
    val trendWeak: Boolean = false,
    val majorTrend: MajorTrend? = null,
    val fundamentalLevel: HealthLevel? = null,
    val positionPct: Double? = null,
    val maxPositionPct: Double? = null,
)

data class PositionAllocationRecommendation(
    val mode: PositionAllocationMode,
    val baseRatio: Double?,
    val recommendedBaseQuantity: Double?,
    val recommendedRollingQuantity: Double?,
    val confidence: Int,
    val reasons: List<String>,
    val dataGaps: List<String>,
) {
    val summary: String
        get() = if (mode == PositionAllocationMode.RISK_OBSERVATION) {
            "风险观察模式"
        } else if (baseRatio == null || recommendedBaseQuantity == null || recommendedRollingQuantity == null) {
            "建议待确认"
        } else {
            "普通模式｜参考底仓 ${(baseRatio * 100).toInt()}%｜建议底仓 ${recommendedBaseQuantity.toLong()} 股｜滚动仓 ${recommendedRollingQuantity.toLong()} 股"
        }
}

object PositionAllocationRecommendationPolicy {
    fun recommend(input: PositionAllocationRecommendationInput): PositionAllocationRecommendation {
        val quantity = input.quantity.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val mode = PositionAllocationMode.NORMAL
        var ratio = when (input.fundamentalLevel) {
            HealthLevel.ALERT -> 0.25
            HealthLevel.NEUTRAL -> 0.40
            HealthLevel.SOUND -> 0.60
            null -> null
        }
        var confidence = 40
        val reasons = mutableListOf<String>()
        val dataGaps = mutableListOf<String>()

        when (input.fundamentalLevel) {
            HealthLevel.ALERT -> {
                confidence += 30
                reasons += "基本面亮红灯，需单独复核持有价值；不自动清零底仓计划。"
            }
            HealthLevel.NEUTRAL -> {
                confidence += 15
                reasons += "基本面为中性观察，底仓比例下调，滚动仓保持轻量。"
            }
            HealthLevel.SOUND -> {
                confidence += 20
                reasons += "基本面未见恶化，可以保留核心底仓。"
            }
            null -> dataGaps += "基本面健康等级暂缺"
        }

        val trendRisk = input.trendWeak || input.majorTrend == MajorTrend.DOWN
        if (trendRisk) {
            confidence += 25
            reasons += "技术趋势偏弱或已破位，暂缓滚动操作；技术风险不等于持有价值判断。"
        } else {
            when {
                input.technicalScore == null -> dataGaps += "技术评分暂缺"
                input.technicalScore >= 75 && input.technicalAllowsAdd == true -> {
                    confidence += 20
                    reasons += "技术评分较高且趋势允许加仓，可评估滚动仓操作。"
                }
                input.technicalScore >= 60 -> {
                    confidence += 12
                    reasons += "技术面处于可观察区间，可结合价格评估滚动操作。"
                }
                input.technicalScore < 45 -> {
                    confidence += 12
                    reasons += "技术评分偏低，暂缓新增滚动操作；分仓计划不随评分自动改变。"
                }
                else -> {
                    confidence += 8
                    reasons += "技术面尚未形成强势确认，滚动操作保持谨慎。"
                }
            }
            if (input.technicalScore != null) confidence += 5
        }

        val positionPct = input.positionPct?.takeIf { it.isFinite() && it >= 0.0 }
        val maxPositionPct = input.maxPositionPct?.takeIf { it.isFinite() && it > 0.0 }
        if (ratio != null && positionPct != null && maxPositionPct != null && positionPct >= maxPositionPct * 0.90) {
            ratio = minOf(ratio, 0.30)
            confidence += 10
            reasons += "当前持仓接近单股上限，建议避免继续集中。"
        }

        ratio = ratio?.coerceIn(0.0, 0.80)
        confidence = confidence.coerceIn(20, 95)
        val baseQuantity = ratio?.let {
            minOf(quantity, kotlin.math.floor(quantity * it / 100.0 + 0.5) * 100.0)
        }
        return PositionAllocationRecommendation(
            mode = mode,
            baseRatio = ratio,
            recommendedBaseQuantity = baseQuantity,
            recommendedRollingQuantity = baseQuantity?.let { (quantity - it).coerceAtLeast(0.0) },
            confidence = confidence,
            reasons = reasons.distinct(),
            dataGaps = dataGaps.distinct(),
        )
    }
}
