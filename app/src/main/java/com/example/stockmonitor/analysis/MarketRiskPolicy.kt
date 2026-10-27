package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import kotlin.math.sqrt

/** 市场日内风险阈值与速度证据归一策略。 */
object MarketRiskPolicy {
    /** 旧版日线观察阈值；新版市场引擎以两指数同步和确认条件为准。 */
    const val EARLY_WARNING_PCT = 0.5

    /** 近20个已完成交易日作为主波动基线；稳健中位数避免单次暴跌把阈值抬高。 */
    const val DAILY_VOLATILITY_WINDOW = 20
    private const val DAILY_RISK_MULTIPLIER = 1.6
    private val DAILY_RISK_FLOOR_PCT = mapOf(
        "sh000001" to 0.7,
        "sz399001" to 0.9,
        "sz399006" to 1.1,
        "sh000688" to 1.2,
    )

    const val SHANGHAI_CODE = "sh000001"
    const val SHENZHEN_CODE = "sz399001"
    const val CHINEXT_CODE = "sz399006"
    const val SCI_TECH_50_CODE = "sh000688"

    /** 两个主要指数日跌幅达到该幅度后，还需市场宽度或量能确认。 */
    const val RISK_WARNING_PCT = 0.8

    /** 两个主要指数日跌幅达到该幅度且宽度极弱时，升级为系统性风险。 */
    const val SEVERE_WARNING_PCT = 1.5

    /** 科创50单独跌至该区间时发指数级风险提醒，不冒充全市场风险。 */
    const val SCI_TECH_50_SEVERE_WARNING_PCT = 1.5

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

    /** 近20日稳健日波动率：取绝对日收益率中位数，排除单日极端波动的阈值污染。 */
    fun robustDailyVolatilityPct(history: List<DayBar>): Double? {
        val closes = history.map { it.close }.filter { it.isFinite() && it > 0.0 }
        if (closes.size < DAILY_VOLATILITY_WINDOW + 1) return null
        val returns = closes.takeLast(DAILY_VOLATILITY_WINDOW + 1).zipWithNext { previous, current ->
            kotlin.math.abs((current / previous - 1.0) * 100.0)
        }.sorted()
        if (returns.isEmpty()) return null
        val middle = returns.size / 2
        return if (returns.size % 2 == 1) returns[middle] else (returns[middle - 1] + returns[middle]) / 2.0
    }

    /** 各指数按自身近20日波动给出单日异常线，同时保留最低容差避免阈值过低。 */
    fun dailyRiskThresholdPct(code: String, volatilityPct: Double?): Double {
        val floor = DAILY_RISK_FLOOR_PCT[code] ?: 1.0
        val dynamic = volatilityPct?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { it * DAILY_RISK_MULTIPLIER }
        return maxOf(floor, dynamic ?: floor)
    }

    fun dailySevereThresholdPct(code: String, volatilityPct: Double?): Double =
        dailyRiskThresholdPct(code, volatilityPct) * 1.6

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
