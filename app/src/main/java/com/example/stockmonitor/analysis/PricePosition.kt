package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

/**
 * 价格位置：同一组量价组合在不同位置上的含义可以相反。
 *
 * 低位的「放量滞涨」多半是底部换手、吸筹初期，高位同样的形态则基本是借着量能出货；
 * 低位的「缩量上涨」是无量反弹，高位的「缩量上涨」却是控盘加速。
 * 所以位置不参与「放量 / 缩量」的计算，只决定结论往哪个方向解释（见 VolumePrice）。
 */
enum class PriceLocation(val label: String) {
    LOW("低位"),
    MID("中位"),
    HIGH("高位"),
}

/**
 * 价格位置上下文：区间位置 + 从区间低点反弹的幅度。
 *
 * 光有「低位」还不够：同样是区间下 1/3，一直贴着低点磨的票和已经从低点弹起来
 * （比如涨停反弹到压力位下面）的票，同一个「放量滞涨」含义正好相反
 * ——前者偏底部换手，后者偏冲高遇阻，所以反弹幅度要一起带上（见 VolumePrice）。
 */
data class PriceContext(val location: PriceLocation, val reboundFromLow: Double)

/**
 * 近 60 个交易日的价格区间 + 区间起点收盘价。
 *
 * 只用一个「区间位置」是不够的：慢牛股的现价天天都在区间顶部，但那是正常趋势，
 * 不是高位。所以高位还要同时满足「累计涨幅已经不小」，低位还要同时满足「没怎么涨」。
 */
data class PriceWindow(val low: Double, val high: Double, val baseClose: Double) {

    /** 现价相对区间起点的累计涨幅（小数，0.2 = +20%）。 */
    fun gainRatio(price: Double): Double =
        if (baseClose > 0) price / baseClose - 1 else 0.0

    /** 现价相对区间低点的反弹幅度（小数，0.25 = 从低点涨了 25%）。 */
    fun reboundRatio(price: Double): Double =
        if (low > 0) price / low - 1 else 0.0

    /** 位置上下文：区间位置 + 反弹幅度；位置判不出来（现价异常、区间退化）时为 null。 */
    fun contextOf(price: Double): PriceContext? =
        locationOf(price)?.let { PriceContext(it, reboundRatio(price)) }

    /**
     * 区间位置 = (现价 - 区间最低) ÷ (区间最高 - 区间最低)。
     *
     * 高位：区间上 1/3 且 60 日累计涨幅 ≥ [HIGH_GAIN]（涨得多 + 位置高，才是真的要防）。
     * 低位：区间下 1/3 且累计涨幅 ≤ 0（跌下来且还没涨起来，才是真的底部区域）。
     * 其余都算中位，不做位置判断，避免把慢牛、横盘股误判成高位。
     */
    fun locationOf(price: Double): PriceLocation? {
        if (price <= 0 || high <= low) return null
        val position = (price - low) / (high - low)
        val gain = gainRatio(price)
        return when {
            position >= HIGH_MIN && gain >= HIGH_GAIN -> PriceLocation.HIGH
            position <= LOW_MAX && gain <= LOW_GAIN -> PriceLocation.LOW
            else -> PriceLocation.MID
        }
    }

    companion object {
        /** 区间下 1/3 算低位。 */
        const val LOW_MAX = 1.0 / 3.0

        /** 区间上 1/3 算高位。 */
        const val HIGH_MIN = 2.0 / 3.0

        /** 高位要求的最小累计涨幅：两个月涨不到 20% 的，不按「涨多了」处理。 */
        const val HIGH_GAIN = 0.20

        /** 低位允许的最大累计涨幅：已经涨起来的股票不再算底部区域。 */
        const val LOW_GAIN = 0.0
    }
}

object PricePosition {

    /** 位置判定窗口：近 60 个交易日。 */
    const val WINDOW = 60

    /** 少于这么多根日线就不给位置结论（新股、长期停牌），界面退回不带位置的文案。 */
    private const val MIN_BARS = 20

    /**
     * 用日线算区间。收盘后日线已含当日，先把当日那根剔掉，
     * 这样「位置」始终是「现价相对此前 60 日」的口径，和量比分母（20 日均量）一致。
     */
    fun of(bars: List<DayBar>, today: String? = null): PriceWindow? {
        val history = if (today != null && bars.lastOrNull()?.date == today) bars.dropLast(1) else bars
        val window = history.takeLast(WINDOW)
        if (window.size < MIN_BARS) return null
        val high = window.maxOf { it.high }
        val low = window.minOf { it.low }
        val baseClose = window.first().close
        if (high <= low || low <= 0 || baseClose <= 0) return null
        return PriceWindow(low = low, high = high, baseClose = baseClose)
    }
}
