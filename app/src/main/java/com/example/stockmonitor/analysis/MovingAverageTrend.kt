package com.example.stockmonitor.analysis

/**
 * MA5、MA20、MA60 共同定义的日线趋势层级。
 *
 * MA5 描述短线，MA20 描述波段，MA60 描述中期背景。该分类只描述均线位置，
 * 不替代周/月趋势、量能、支撑压力或风险控制。
 */
enum class MovingAverageTrend(val label: String) {
    STRONG_BULLISH("短中期均线共振向上"),
    SHORT_BULLISH_MID_WEAK("短期转强、中期仍弱"),
    MID_BULLISH_SHORT_PULLBACK("中期向上、短期回踩"),
    SHORT_REBOUND_BELOW_MA20("短期反弹、尚未站上MA20"),
    STRONG_BEARISH("短中期均线共振向下"),
    MID_WEAK_SHORT_WEAK("中期偏弱、短期走弱"),
    MIXED("均线交错整理"),
}

object MovingAverageTrendPolicy {
    fun evaluate(
        price: Double,
        ma5: Double,
        ma20: Double,
        ma60: Double,
    ): MovingAverageTrend {
        if (listOf(price, ma5, ma20, ma60).any { !it.isFinite() || it <= 0.0 }) {
            return MovingAverageTrend.MIXED
        }
        return when {
            price >= ma5 && ma5 > ma20 && ma20 > ma60 ->
                MovingAverageTrend.STRONG_BULLISH
            price < ma5 && ma5 < ma20 && ma20 < ma60 ->
                MovingAverageTrend.STRONG_BEARISH
            ma20 > ma60 && (price < ma5 || ma5 <= ma20) ->
                MovingAverageTrend.MID_BULLISH_SHORT_PULLBACK
            price >= ma5 && ma5 > ma20 && ma20 <= ma60 ->
                MovingAverageTrend.SHORT_BULLISH_MID_WEAK
            price >= ma5 && price < ma20 && ma20 <= ma60 ->
                MovingAverageTrend.SHORT_REBOUND_BELOW_MA20
            ma20 <= ma60 && price < ma5 ->
                MovingAverageTrend.MID_WEAK_SHORT_WEAK
            else -> MovingAverageTrend.MIXED
        }
    }
}
