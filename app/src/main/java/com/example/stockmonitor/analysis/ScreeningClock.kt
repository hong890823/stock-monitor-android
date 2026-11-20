package com.example.stockmonitor.analysis

/**
 * 选股扫描的时间口径。
 *
 * 新浪活跃股榜单没有行情日期字段；周末/盘前/午夜返回的是**上一交易日**的快照。
 * 旧实现只要“日线最后一根不是今天”就补一根“今日”K 线，并用墙钟折算量比：
 * 周六 10:00 会把周五的量重复成今天，量比 ≈ 8 倍；盘前/午夜 `elapsed = 0` 又会把
 * 趋势回踩直接过滤掉。这里用指数行情日期作为“今天是否交易日/是否有今日行情”的事实源。
 */
object ScreeningClock {
    /**
     * 是否给日线补一根当日实时 K 线。
     *
     * - 行情日期必须是今天（周末/节假日返回的是上一交易日快照，不能补）；
     * - 必须已有交易分钟（集合竞价前/盘前不补，避免把 0 成交量写成一根 K 线）。
     */
    fun shouldAppendLiveBar(
        indexQuoteDate: String?,
        today: String,
        elapsedTradingMinutes: Int,
    ): Boolean {
        val date = indexQuoteDate?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: return false
        return date == today && elapsedTradingMinutes > 0
    }

    /**
     * 量比分母的折算分钟数：
     * - 盘中补了当日 bar：按已交易分钟折算（下限由 [VolumeRatio.of] 处理）；
     * - 非盘中 / 已收盘 / 未补当日 bar：按全天口径 240，不拿墙钟把隔夜量放大。
     */
    fun ratioElapsedMinutes(elapsedTradingMinutes: Int, appendLiveBar: Boolean): Int =
        if (appendLiveBar && elapsedTradingMinutes in 1 until VolumeRatio.TRADING_MINUTES) {
            elapsedTradingMinutes
        } else {
            VolumeRatio.TRADING_MINUTES
        }
}