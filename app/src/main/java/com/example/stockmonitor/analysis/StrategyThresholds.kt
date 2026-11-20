package com.example.stockmonitor.analysis

/**
 * 跨策略共享的阈值。
 *
 * 同一个数字出现在不同策略里时，语义必须写清楚：追高上限（新买与加仓共用 8% MA5 乖离）
 * 与均线止盈线选择（[BreakoutRiskPolicy.HIGH_BIAS_PCT] 的 8%）数值相同但没有因果关系，
 * 集中在这里只统一维护，不强行合并判断函数。
 */
object StrategyThresholds {
    /** 新买 / 加仓共用的追高边界：现价相对 MA5 的乖离上限。 */
    const val MAX_CHASE_MA5_BIAS_PCT = 8.0

    /** 首板承接观察要求日内至少下探开盘价以下的比例。 */
    const val FIRST_BOARD_OPEN_DIP_PCT = 0.5

    /** 结构止损线（MA5/M20）的有效跌破容忍度。 */
    const val LINE_BREAK_TOLERANCE_PCT = 0.5

    /** 持仓分析给出的支撑 / 压力位允许的最大使用年龄。 */
    const val LEVEL_MAX_AGE_MS = 35 * 60 * 1000L
}
