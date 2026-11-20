package com.example.stockmonitor.analysis

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 首板承接观察的资格边界。
 *
 * 观察窗口按首板后的交易日计算。涨停池入池当日即首板日，因此用候选的 `addedDate`
 * 代替 `observedDays`（复评天数）作为时钟；缺失时才回退旧口径。
 *
 * 价格边界：收盘价相对动态 MA5 的乖离超过 10%，视为分歧转强发生在已经明显拉升之后，
 * 不再发观察。乖离缺失（null）不假定为 0%，保持“缺数据不等于合格”。
 */
object FirstBoardReclaimPolicy {
    const val MAX_MA5_BIAS_PCT = 10.0
    const val MIN_TRADING_DAYS_AFTER_BOARD = 1
    const val MAX_TRADING_DAYS_AFTER_BOARD = 3

    /**
     * 首板日与今日之间的交易日数。
     *
     * 优先用指数日线给出的真实交易日序列：法定节假日（如国庆）会被正确跳过，
     * 不再把假期的工作日算成交易日导致节后窗口整体过期。
     * [tradingDates] 为空（离线/接口失败）时才退回只跳周末的估算。
     */
    fun tradingDaysAfter(
        boardDate: String,
        today: String,
        tradingDates: List<String> = emptyList(),
    ): Int? {
        val start = runCatching { LocalDate.parse(boardDate) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(today) }.getOrNull() ?: return null
        if (!end.isAfter(start)) return 0
        if (tradingDates.isNotEmpty()) {
            // 盘中新浪日线可能只到昨天；此时今天既然是交易时段在运行，就补上今天。
            val includesToday = tradingDates.any { it == today }
            return tradingDates.count { it > boardDate && it <= today } +
                if (includesToday) 0 else 1
        }
        return weekdayDaysAfter(start, end)
    }

    private fun weekdayDaysAfter(start: LocalDate, end: LocalDate): Int {
        var days = 0
        var cursor = start.plusDays(1)
        while (!cursor.isAfter(end)) {
            if (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY) {
                days++
            }
            cursor = cursor.plusDays(1)
        }
        return days
    }

    fun windowEligible(
        firstBoardDate: String?,
        observedDays: Int,
        today: String,
        tradingDates: List<String> = emptyList(),
    ): Boolean {
        val range = MIN_TRADING_DAYS_AFTER_BOARD..MAX_TRADING_DAYS_AFTER_BOARD
        val days = firstBoardDate?.let { tradingDaysAfter(it, today, tradingDates) }
            ?: return observedDays in range
        return days in range
    }

    fun withinPriceBound(biasFromMa5Pct: Double?): Boolean =
        biasFromMa5Pct == null || !biasFromMa5Pct.isFinite() || biasFromMa5Pct <= MAX_MA5_BIAS_PCT

    /** 门控日志用：优先给出首板日钟，缺失时回退复评天数。 */
    fun describeWindow(
        firstBoardDate: String?,
        observedDays: Int,
        today: String,
        tradingDates: List<String> = emptyList(),
    ): String {
        val days = firstBoardDate?.let { tradingDaysAfter(it, today, tradingDates) }
        return if (days != null) "tradingDays=$days" else "observedDays=$observedDays"
    }
}
