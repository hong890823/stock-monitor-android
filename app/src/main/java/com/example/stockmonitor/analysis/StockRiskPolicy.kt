package com.example.stockmonitor.analysis

/** 个股日内累计跌幅风险分级。百分比均相对昨收。 */
object StockRiskPolicy {
    const val DAILY_WARNING_PCT = 3.0
    const val DAILY_RISK_PCT = 5.0

    fun levelFor(changePct: Double): StockDropLevel = when {
        !changePct.isFinite() -> StockDropLevel.NONE
        changePct <= -DAILY_RISK_PCT -> StockDropLevel.DANGER
        changePct <= -DAILY_WARNING_PCT -> StockDropLevel.WARNING
        else -> StockDropLevel.NONE
    }
}

enum class StockDropLevel(val priority: Int) {
    NONE(0),
    WARNING(1),
    DANGER(2),
}
