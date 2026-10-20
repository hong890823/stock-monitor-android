package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

/**
 * 量比口径的唯一来源：个股报告里的「量比」、自选股列表的「放量」标识都走这里，
 * 避免同一只股票在两个地方给出两个数字。
 */
object VolumeRatio {

    /** 一个完整交易日的分钟数（不含午休）。 */
    const val TRADING_MINUTES = 240

    /** 盘中折算的时间下限，避免开盘头几分钟把量比放大成离谱数字。 */
    const val MIN_SCALE_MINUTES = 5

    /** 放量阈值，与报告里「上涨伴随放量」的判据保持一致。 */
    const val EXPAND_THRESHOLD = 1.3

    /** 缩量阈值。 */
    const val SHRINK_THRESHOLD = 0.8

    /** 计算 20 日均量所需的最少交易日数量。 */
    private const val MIN_BARS = 5

    /**
     * 今日累计成交量 ÷ 20 日均量。
     *
     * 盘中先按已过交易时间折算到全天：否则上午 10 点只走了 1/8 的交易时间，
     * 正常节奏的股票也会算出 0.1x，结论直接反了。收盘后（elapsed = 240）不折算。
     * 返回 null 表示数据不足，不做判断。
     *
     * [elapsedTradingMinutes] ≤ 0（隔夜到集合竞价结束，含 09:30 那一分钟）时同样返回 null：
     * 此时实时行情里的成交量要么还是上一交易日的全天量、要么只是当日集合竞价撮合的一小部分，
     * 两种含义都拿不到「今日累计」这个分子，硬算出来的极小量比会把所有股票标成「缩量」。
     * 需要上一交易日的口径时，调用方用日线自己算（见 StockAnalysis 的日线回退）。
     */
    fun of(todayVolume: Double?, avgVolume20: Double, elapsedTradingMinutes: Int): Double? {
        if (elapsedTradingMinutes <= 0) return null
        if (todayVolume == null || todayVolume <= 0 || avgVolume20 <= 0) return null
        val intraday = elapsedTradingMinutes in 1 until TRADING_MINUTES
        val scaled = if (intraday) {
            todayVolume / (maxOf(elapsedTradingMinutes, MIN_SCALE_MINUTES) / TRADING_MINUTES.toDouble())
        } else {
            todayVolume
        }
        return scaled / avgVolume20
    }

    /** 量比是否为「盘中按已交易时间折算」的口径；false 表示不折算，或该时段本就不计算（见 [of]）。 */
    fun isIntraday(elapsedTradingMinutes: Int): Boolean =
        elapsedTradingMinutes in 1 until TRADING_MINUTES

    /**
     * 20 日均量，且排除当日。
     *
     * 日线序列带不带当日随时段和数据源变动（新浪盘中不含、收盘后补上，腾讯盘中起就带），
     * 所以「分母」显式剔掉今天，否则今天的成交量会同时出现在分子和分母里，量比被系统性低估。
     */
    fun averageVolumeExcludingToday(bars: List<DayBar>, today: String = TimeUtils.todayText()): Double? {
        val history = if (bars.lastOrNull()?.date == today) bars.dropLast(1) else bars
        return averageVolume(history)
    }

    /** 20 日均量；交易日太少（新股）时返回 null，不参与判断。 */
    fun averageVolume(bars: List<DayBar>): Double? {
        val volumes = bars.takeLast(20).map { it.volume }.filter { it > 0 }
        if (volumes.size < MIN_BARS) return null
        return Indicators.mean(volumes)
    }
}
