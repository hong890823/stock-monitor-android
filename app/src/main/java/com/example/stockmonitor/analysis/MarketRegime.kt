package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale

/**
 * 市场状态判定：**大盘与板块共用同一套口径**。
 *
 * 「熊市塌陷」和「板块塌陷」是同一件事在不同尺度上——指数跌破均线后支撑位会被系统性击穿、
 * 个股独善其身的概率很低。判定口径必须统一，否则大盘说塌了、板块说没塌，建议会互相打架。
 *
 * **判定要有后果**，否则白判。塌陷期的「回踩支撑企稳」是接飞刀，不该照常给买入 / 补仓 / 接回建议；
 * 具体改写表见 [PortfolioAnalysis] 与 [IntradayTactics]。
 */
enum class MarketRegime {
    /** 多头：站上 MA20 且 MA20 在抬升。 */
    BULL,

    /** 中性：方向不明。 */
    NEUTRAL,

    /** 转弱：跌破 MA20 且 MA20 在走低（等价于旧的 [MarketFilter.HEADWIND]）。 */
    WEAK,

    /**
     * 塌陷：MA20 / MA60 双破，且近期已经跌出一段幅度。
     * 这一档要**改写建议**——不补仓、不接回、反弹减，而不是继续「观察」。
     */
    COLLAPSE;

    val label: String
        get() = when (this) {
            BULL -> "多头"
            NEUTRAL -> "中性"
            WEAK -> "转弱"
            COLLAPSE -> "塌陷"
        }

    /**
     * 入场闸门口径。塌陷比转弱更不该开新仓，所以两档都映射到 [MarketFilter.HEADWIND]；
     * 保持 [MarketFilter] 枚举名不变，旧的选股结果缓存（`ScreenResultJson` 的 `market_filter`）才读得回来。
     */
    fun toMarketFilter(): MarketFilter = when (this) {
        BULL -> MarketFilter.UP
        NEUTRAL -> MarketFilter.NEUTRAL
        WEAK, COLLAPSE -> MarketFilter.HEADWIND
    }

    companion object {
        /** 日线少于这个根数就不判状态——数据不够就写「暂缺」，不猜方向。 */
        const val MIN_BARS = 30

        /** MA20 斜率门槛，与 [TrendEntryStrategy.MARKET_SLOPE_THRESHOLD_PCT] 同值，避免两处判出不同档。 */
        const val SLOPE_PCT = TrendEntryStrategy.MARKET_SLOPE_THRESHOLD_PCT

        /** 塌陷的跌幅门槛：近 20 日累计跌到这个幅度。 */
        const val COLLAPSE_20D_PCT = -8.0

        /** 或者更急：近 5 日累计跌到这个幅度（急跌也算塌陷，不等 20 日走满）。 */
        const val COLLAPSE_5D_PCT = -5.0

        /**
         * 判定市场状态。
         *
         * [bars] 是日线序列。**带不带当日随数据源变动**（腾讯盘中起就带、新浪收盘后才补），
         * 所以当日涨跌优先用 [todayChangePct] 显式给；没给就用最后一根对前一根算，
         * 调用方要清楚那时的「最后一根」可能是今天也可能是昨天。
         */
        fun evaluate(bars: List<DayBar>, todayChangePct: Double? = null): MarketState {
            val closes = bars.map { it.close }.filter { it > 0 }
            if (closes.size < MIN_BARS) {
                return MarketState(
                    regime = MarketRegime.NEUTRAL,
                    reason = "日线不足 $MIN_BARS 根（实际 ${closes.size} 根），市场状态暂缺",
                    changePct = todayChangePct,
                    ma20Pct = null,
                    ma60Pct = null,
                    change5d = null,
                    change20d = null,
                )
            }

            val price = closes.last()
            val ma20 = closes.takeLast(20).average()
            val ma60 = if (closes.size >= 60) closes.takeLast(60).average() else null
            val ma20Pct = (price / ma20 - 1) * 100
            val ma60Pct = ma60?.let { (price / it - 1) * 100 }

            // 斜率口径与 TrendEntryStrategy.marketFilter 一致：今天的 MA20 对比 5 根之前的 MA20。
            val latest = closes.lastIndex
            val ma20FiveDaysAgo = closes.subList(latest - 24, latest - 4).average()
            val slope = (ma20 / ma20FiveDaysAgo - 1) * 100

            val change5d = if (closes.size >= 6) (price / closes[closes.size - 6] - 1) * 100 else null
            val change20d = if (closes.size >= 21) (price / closes[closes.size - 21] - 1) * 100 else null

            val changePct = todayChangePct
                ?: if (closes.size >= 2) (price / closes[closes.size - 2] - 1) * 100 else null

            val collapsedByDrop = (change20d != null && change20d <= COLLAPSE_20D_PCT) ||
                (change5d != null && change5d <= COLLAPSE_5D_PCT)

            val regime = when {
                ma60Pct != null && ma20Pct < 0 && ma60Pct < 0 && collapsedByDrop -> MarketRegime.COLLAPSE
                ma20Pct < 0 && slope <= -SLOPE_PCT -> MarketRegime.WEAK
                ma20Pct > 0 && slope >= SLOPE_PCT -> MarketRegime.BULL
                else -> MarketRegime.NEUTRAL
            }

            return MarketState(
                regime = regime,
                reason = reasonOf(regime, ma20Pct, ma60Pct, change5d, change20d),
                changePct = changePct,
                ma20Pct = ma20Pct,
                ma60Pct = ma60Pct,
                change5d = change5d,
                change20d = change20d,
            )
        }

        /**
         * 判定依据，直接进报告——用户要能自己核对为什么判成这档，而不是只给一个标签。
         */
        private fun reasonOf(
            regime: MarketRegime,
            ma20Pct: Double,
            ma60Pct: Double?,
            change5d: Double?,
            change20d: Double?,
        ): String {
            val ma60Text = ma60Pct?.let { String.format(Locale.CHINA, "、MA60 %+.1f%%", it) } ?: ""
            val dropText = buildString {
                change20d?.let { append(String.format(Locale.CHINA, "近20日 %+.1f%%", it)) }
                change5d?.let {
                    if (isNotEmpty()) append("、")
                    append(String.format(Locale.CHINA, "近5日 %+.1f%%", it))
                }
            }
            return when (regime) {
                MarketRegime.COLLAPSE ->
                    "跌破 MA20 ${pct(ma20Pct)}$ma60Text，且 $dropText——系统性下跌，支撑位多半会一起破"
                MarketRegime.WEAK ->
                    "跌破 MA20 ${pct(ma20Pct)} 且 MA20 在走低${if (dropText.isEmpty()) "" else "（$dropText）"}"
                MarketRegime.BULL ->
                    "站上 MA20 ${pct(ma20Pct)} 且 MA20 在抬升${if (dropText.isEmpty()) "" else "（$dropText）"}"
                MarketRegime.NEUTRAL ->
                    "在 MA20 附近 ${pct(ma20Pct)}，方向不明${if (dropText.isEmpty()) "" else "（$dropText）"}"
            }
        }

        private fun pct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)
    }
}

/** 市场（或板块）状态快照。[reason] 可直接进报告。 */
data class MarketState(
    val regime: MarketRegime,
    val reason: String,
    /** 当日涨跌（%）。 */
    val changePct: Double?,
    /** 现价相对 MA20（%），负数表示在 MA20 下方。 */
    val ma20Pct: Double?,
    /** 现价相对 MA60（%）；日线不足 60 根时为 null。 */
    val ma60Pct: Double?,
    val change5d: Double?,
    val change20d: Double?,
) {
    /** 现价是否已跌破 MA20。 */
    val belowMa20: Boolean get() = ma20Pct != null && ma20Pct < 0

    /** 是否已进入塌陷档。 */
    val isCollapse: Boolean get() = regime == MarketRegime.COLLAPSE
}
