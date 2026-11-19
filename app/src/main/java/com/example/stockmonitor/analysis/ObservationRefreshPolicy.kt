package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Quote

object ObservationRefreshPolicy {
    /** 买点晋级/确认轮次的评估价与落盘时行情允许的最大漂移（百分点）。 */
    const val ENTRY_MAX_DRIFT_PCT = 2.0

    /**
     * 盘中复评结果能否落盘。
     *
     * Quote 含累计成交量/成交额，活跃股每个 tick 都在变；批量评估（首批要拉 K 线）经常
     * 跨 tick，要求「评估时行情与当前行情严格相等」会把越异动的股票的复评结果系统性
     * 丢弃，晋级确认轮次（15–45 分钟两轮）被拖慢甚至重置。因此：
     * - 普通状态更新：有评估时行情即可落盘（applyQuoteGate 另做日切/封板门控）；
     * - 买点晋级与确认中的轮次：附加「价格未大幅漂移」检查，避免用明显过期的价格
     *   确认买点（漂移超限则丢弃，下一轮按新价重评）。
     * - 当前行情缺失时按评估结果落盘：评估时刻的行情本身有效，没有更新的信息可对比。
     */
    fun shouldPersist(
        evaluatedQuote: Quote?,
        currentQuote: Quote?,
        protectsEntry: Boolean = false,
    ): Boolean {
        if (evaluatedQuote == null) return false
        if (currentQuote == null) return true
        if (!protectsEntry) return true
        val evaluatedPrice = evaluatedQuote.price
        val currentPrice = currentQuote.price
        if (!evaluatedPrice.isFinite() || evaluatedPrice <= 0.0 ||
            !currentPrice.isFinite() || currentPrice <= 0.0
        ) {
            return true
        }
        val driftPct = kotlin.math.abs(currentPrice - evaluatedPrice) / evaluatedPrice * 100.0
        return driftPct <= ENTRY_MAX_DRIFT_PCT
    }
}
