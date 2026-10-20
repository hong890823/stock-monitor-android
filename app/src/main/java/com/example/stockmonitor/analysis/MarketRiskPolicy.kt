package com.example.stockmonitor.analysis

/** 大盘日内风险预警策略。 */
object MarketRiskPolicy {
    /** 至少两个主要指数相对昨收跌到该幅度后，连续两轮确认转弱。 */
    const val EARLY_WARNING_PCT = 0.5

    /** 任一主要指数相对昨收跌到该幅度时立即触发风险预警。 */
    const val RISK_WARNING_PCT = 0.8

    /** 任一主要指数相对昨收跌到该幅度时立即升级为严重风险。 */
    const val SEVERE_WARNING_PCT = 1.5

    /** 两个指数都反弹到该幅度以上后重新武装，避免阈值附近重复通知。 */
    const val REARM_PCT = 0.4

    fun isTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double, thresholdPct: Double): Boolean =
        shanghaiChangePct <= -thresholdPct || shenzhenChangePct <= -thresholdPct

    fun isEarlyTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        isTriggered(shanghaiChangePct, shenzhenChangePct, EARLY_WARNING_PCT)

    fun isRiskTriggered(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        isTriggered(shanghaiChangePct, shenzhenChangePct, RISK_WARNING_PCT)

    fun isRearmed(shanghaiChangePct: Double, shenzhenChangePct: Double): Boolean =
        shanghaiChangePct > -REARM_PCT && shenzhenChangePct > -REARM_PCT
}
