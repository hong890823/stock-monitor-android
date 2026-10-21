package com.example.stockmonitor.analysis

import kotlin.math.max

data class ImpulseContext(
    val upDays: Int,
    val downDays: Int,
    val gain5Pct: Double,
) {
    companion object {
        fun from(closes: List<Double>): ImpulseContext? {
            val valid = closes.filter { it.isFinite() && it > 0.0 }
            if (valid.size < 6) return null
            var upDays = 0
            var downDays = 0
            for (index in valid.lastIndex downTo 1) {
                val change = valid[index] - valid[index - 1]
                if (change > 0.0 && downDays == 0) upDays++
                else if (change < 0.0 && upDays == 0) downDays++
                else break
            }
            val gain5Pct = (valid[valid.lastIndex] / valid[valid.lastIndex - 5] - 1.0) * 100.0
            return ImpulseContext(upDays, downDays, gain5Pct)
        }
    }
}

enum class AdaptiveRhythmPhase(val label: String) {
    EARLY("延伸早期"),
    DIVERGENCE_WATCH("自适应分歧观察"),
    DIVERGENCE_RISK("分歧结构风险"),
    REBOUND_WATCH("下跌后反弹观察"),
    DOWN("下跌延续观察"),
    UNKNOWN("节奏数据不足"),
}

data class AdaptiveRhythmEvidence(
    val marketTurnoverRatio: Double?,
    val marketTurnoverSource: MarketTurnoverSource? = MarketTurnoverSource.SAME_TIME,
    val marketChangePct: Double?,
    val industryBreadth: Double?,
    val conceptBreadth: Double?,
    val marketRegime: MarketRegime,
    val shortTermTrend: ShortTermTrend?,
    val majorTrend: MajorTrend? = null,
    val impulse: ImpulseContext?,
    val atrPct: Double?,
    val volumeRatio: Double?,
    val changePct: Double?,
    val biasFromMa5Pct: Double? = null,
    val lowerHighs: Boolean? = null,
    val volumeWithoutProgress: Boolean? = null,
)

data class AdaptiveRhythmDecision(
    val phase: AdaptiveRhythmPhase,
    val pressure: Int,
    val retreatFactor: Double,
    val reason: String,
)

/**
 * 自适应节奏的冷启动阈值。历史持续时间分位数尚未采集，当前值保持显式、可替换；
 * 后续按市场活跃度用 P25/P50/P75 校准，不在没有样本时伪装成历史最优参数。
 */
data class AdaptiveRhythmThresholds(
    val watchPressure: Int = 3,
    val riskPressure: Int = 8,
    val upDayWatch: Int = 2,
    val upDayRisk: Int = 4,
    val upDayVeryRisk: Int = 6,
    val gain5ExtendedPct: Double = 8.0,
    val gain5VeryExtendedPct: Double = 15.0,
    val extendedGainAtrFactor: Double = 3.0,
    val veryExtendedGainAtrFactor: Double = 5.0,
    val highBiasFromMa5Pct: Double = 8.0,
    val strongTurnoverRatio: Double = 1.25,
    val neutralTurnoverRatio: Double = 1.0,
    val weakTurnoverRatio: Double = 0.8,
    val highBreadth: Double = 0.62,
    val neutralBreadth: Double = 0.50,
    val weakBreadth: Double = 0.35,
    val strongMarketChangePct: Double = 0.3,
    val weakMarketChangePct: Double = -1.0,
    val majorDownPressure: Int = 2,
    val bottomingPressureRelief: Int = 2,
) {
    companion object {
        val COLD_START = AdaptiveRhythmThresholds()
    }
}

object AdaptiveRhythmPolicy {
    fun evaluate(
        evidence: AdaptiveRhythmEvidence,
        thresholds: AdaptiveRhythmThresholds = AdaptiveRhythmThresholds.COLD_START,
    ): AdaptiveRhythmDecision {
        if (evidence.lowerHighs == true || evidence.volumeWithoutProgress == true) {
            return decision(
                AdaptiveRhythmPhase.DIVERGENCE_RISK,
                pressure = thresholds.riskPressure,
                retreatFactor = 1.0,
                reason = "下移高点或放量滞涨等结构证据优先，即使市场量能或宽度缺失也按分歧风险处理",
            )
        }

        val turnoverRatio = evidence.marketTurnoverRatio?.takeIf { it.isFinite() && it > 0.0 }
        val industryBreadth = evidence.industryBreadth?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val conceptBreadth = evidence.conceptBreadth?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val impulse = evidence.impulse
        if (turnoverRatio == null || industryBreadth == null || conceptBreadth == null || impulse == null ||
            !impulse.gain5Pct.isFinite()
        ) {
            return decision(
                AdaptiveRhythmPhase.UNKNOWN,
                pressure = 0,
                retreatFactor = 1.0,
                reason = "成交额同期相对量、市场宽度或个股延伸数据不足，不按固定天数猜节奏",
            )
        }

        val marketStrength = marketStrength(
            turnoverRatio = turnoverRatio,
            source = evidence.marketTurnoverSource,
            marketChangePct = evidence.marketChangePct,
            industryBreadth = industryBreadth,
            conceptBreadth = conceptBreadth,
            marketRegime = evidence.marketRegime,
            thresholds = thresholds,
        )
        val extensionPressure = extensionPressure(evidence, impulse, thresholds)
        val majorTrendPressure = if (evidence.majorTrend == MajorTrend.DOWN) thresholds.majorDownPressure else 0
        val weakMarketPenalty = if (marketStrength < 2) 2 else 0
        val pressure = extensionPressure + majorTrendPressure + weakMarketPenalty

        val reboundEvidence = impulse.downDays >= 3 &&
            evidence.changePct?.let { it.isFinite() && it > 0.0 } == true &&
            evidence.volumeRatio?.let { it.isFinite() && it in 0.0..1.0 } == true
        val phase = when {
            evidence.lowerHighs == true || evidence.volumeWithoutProgress == true ->
                AdaptiveRhythmPhase.DIVERGENCE_RISK
            evidence.shortTermTrend == ShortTermTrend.DOWN || evidence.marketRegime == MarketRegime.COLLAPSE ->
                if (reboundEvidence) AdaptiveRhythmPhase.REBOUND_WATCH else AdaptiveRhythmPhase.DOWN
            pressure >= thresholds.riskPressure -> AdaptiveRhythmPhase.DIVERGENCE_RISK
            pressure >= thresholds.watchPressure -> AdaptiveRhythmPhase.DIVERGENCE_WATCH
            else -> AdaptiveRhythmPhase.EARLY
        }
        val retreatFactor = when {
            phase == AdaptiveRhythmPhase.DIVERGENCE_RISK -> 1.0
            marketStrength >= 4 -> 2.0
            marketStrength >= 2 -> 1.5
            else -> 1.0
        }
        val reason = when (phase) {
            AdaptiveRhythmPhase.DIVERGENCE_RISK ->
                "下移高点或放量滞涨等结构证据优先，无需等到固定天数；当前分歧压力$pressure"
            AdaptiveRhythmPhase.REBOUND_WATCH ->
                "连续下跌后出现缩量回升，进入反弹观察；下跌不做固定天数预测，仍需守住低点并抬高结构"
            AdaptiveRhythmPhase.DOWN ->
                "下跌或市场塌陷仍在延续，不按7～8天预测止跌，等待缩量企稳和结构抬高"
            AdaptiveRhythmPhase.DIVERGENCE_WATCH ->
                "市场承接和个股延伸共同推高分歧压力至$pressure，提高观察强度但不预设具体分歧日"
            AdaptiveRhythmPhase.EARLY if evidence.majorTrend == MajorTrend.BOTTOMING ->
                "大趋势仍在筑底，降低上涨延伸压力但保留底部反转观察；当前分歧压力$pressure"
            AdaptiveRhythmPhase.EARLY ->
                "市场相对量能、宽度和个股延伸度仍允许延续，上涨天数只增加风险权重，不按固定第4～5天卖出"
            AdaptiveRhythmPhase.UNKNOWN ->
                "成交额同期相对量、市场宽度或个股延伸数据不足，不按固定天数猜节奏"
        }
        val bottomingNote = if (evidence.majorTrend == MajorTrend.BOTTOMING) {
            "；筑底阶段降低上涨延伸压力，但保留底部反转观察"
        } else {
            ""
        }
        val turnoverNote = if (evidence.marketTurnoverSource == MarketTurnoverSource.FULL_DAY_FALLBACK) {
            "；全天预估/历史全天量能仅为降级参考，不据此推入强市场"
        } else {
            ""
        }
        return decision(
            phase,
            pressure,
            retreatFactor,
            "$reason$bottomingNote$turnoverNote；成交额按同期相对量与宽度动态调整，不使用固定2.5万亿门槛",
        )
    }

    private fun extensionPressure(
        evidence: AdaptiveRhythmEvidence,
        impulse: ImpulseContext,
        thresholds: AdaptiveRhythmThresholds,
    ): Int {
        val upDayScore = when {
            impulse.upDays >= thresholds.upDayVeryRisk -> 3
            impulse.upDays >= thresholds.upDayRisk -> 2
            impulse.upDays >= thresholds.upDayWatch -> 1
            else -> 0
        }
        val atrPct = evidence.atrPct?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val gainScore = when {
            impulse.gain5Pct >= max(thresholds.gain5VeryExtendedPct, atrPct * thresholds.veryExtendedGainAtrFactor) -> 2
            impulse.gain5Pct >= max(thresholds.gain5ExtendedPct, atrPct * thresholds.extendedGainAtrFactor) -> 1
            else -> 0
        }
        val biasScore = if (evidence.biasFromMa5Pct?.let {
            it.isFinite() && it >= thresholds.highBiasFromMa5Pct
        } == true) 1 else 0
        val evidenceScore = (if (evidence.lowerHighs == true) 3 else 0) +
            (if (evidence.volumeWithoutProgress == true) 2 else 0)
        val pressure = upDayScore + gainScore + biasScore + evidenceScore
        return if (evidence.majorTrend == MajorTrend.BOTTOMING) {
            max(0, pressure - thresholds.bottomingPressureRelief)
        } else {
            pressure
        }
    }

    private fun marketStrength(
        turnoverRatio: Double,
        source: MarketTurnoverSource?,
        marketChangePct: Double?,
        industryBreadth: Double,
        conceptBreadth: Double,
        marketRegime: MarketRegime,
        thresholds: AdaptiveRhythmThresholds,
    ): Int {
        val turnoverScore = when {
            source == MarketTurnoverSource.FULL_DAY_FALLBACK -> 0
            turnoverRatio >= thresholds.strongTurnoverRatio -> 2
            turnoverRatio >= thresholds.neutralTurnoverRatio -> 1
            turnoverRatio <= thresholds.weakTurnoverRatio -> -1
            else -> 0
        }
        val breadth = minOf(industryBreadth, conceptBreadth)
        val breadthScore = when {
            breadth >= thresholds.highBreadth -> 2
            breadth >= thresholds.neutralBreadth -> 1
            breadth <= thresholds.weakBreadth -> -1
            else -> 0
        }
        val indexScore = marketChangePct?.takeIf { it.isFinite() }?.let {
            when {
                it >= thresholds.strongMarketChangePct -> 1
                it <= thresholds.weakMarketChangePct -> -1
                else -> 0
            }
        } ?: 0
        val regimeScore = if (marketRegime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE)) -2 else 1
        val strength = turnoverScore + breadthScore + indexScore + regimeScore
        return if (source == MarketTurnoverSource.FULL_DAY_FALLBACK) minOf(strength, 1) else strength
    }

    private fun decision(
        phase: AdaptiveRhythmPhase,
        pressure: Int,
        retreatFactor: Double,
        reason: String,
    ) = AdaptiveRhythmDecision(phase, pressure, retreatFactor, reason)
}
