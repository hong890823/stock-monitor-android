package com.example.stockmonitor.analysis

import kotlin.math.sqrt

/** 市场日内风险阈值与速度证据归一策略。 */
object MarketRiskPolicy {
    /** 旧版日线观察阈值；新版市场引擎以两指数同步和确认条件为准。 */
    const val EARLY_WARNING_PCT = 0.5

    /** 两个主要指数日跌幅达到该幅度后，还需市场宽度或量能确认。 */
    const val RISK_WARNING_PCT = 0.8

    /** 两个主要指数日跌幅达到该幅度且宽度极弱时，升级为系统性风险。 */
    const val SEVERE_WARNING_PCT = 1.5

    /** 主要指数均回到该幅度以上后重新武装，避免阈值附近重复通知。 */
    const val REARM_PCT = 0.4

    const val OBSERVATION_SPEED_PCT = 0.3
    const val RISK_SPEED_PCT = 0.5
    const val SEVERE_SPEED_PCT = 0.8
    const val EXPANDED_VOLUME_RATIO = 1.5
    const val ABSORPTION_LOWER_SHADOW_RATIO = 0.65
    const val WEAK_BREADTH = 0.35
    const val VERY_WEAK_BREADTH = 0.25
    const val STRONG_BREADTH = 0.60

    private val BASELINE_FIVE_MINUTE_VOLATILITY =
        mapOf(
            "sh000001" to 0.18,
            "sz399001" to 0.22,
            "sz399006" to 0.28,
        )

    fun isTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double, thresholdPct: Double): Boolean =
        shanghaiChangePct <= -thresholdPct || shenzhenChangePct <= -thresholdPct

    fun isEarlyTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        isTriggered(shanghaiChangePct, shenzhenChangePct, EARLY_WARNING_PCT)

    fun isRiskTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        isTriggered(shanghaiChangePct, shenzhenChangePct, RISK_WARNING_PCT)

    fun isRearmed(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        shanghaiChangePct > -REARM_PCT && shenzhenChangePct > -REARM_PCT

    /** 用近期 5 分钟收益率波动率（百分点）调整速度阈值；样本不足时保持 1.0。 */
    fun volatilityMultiplier(code: String, returns: List<Double>): Double {
        val samples = returns.filter { it.isFinite() }
        if (samples.size < 20) return 1.0
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        val volatility = sqrt(variance)
        val baseline = BASELINE_FIVE_MINUTE_VOLATILITY[code] ?: 0.22
        return (volatility / baseline).coerceIn(0.85, 1.6)
    }
}
