package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class EntrySetupType(val label: String) {
    BREAKOUT("突破型机会"),
    BREAKOUT_WATCH("突破观察"),
    M20_PULLBACK("回踩 M20 观察"),
    NONE("暂无有效入场结构"),
}

enum class MarketFilter(val label: String) {
    UP("大盘不拖累"),
    NEUTRAL("大盘中性"),
    HEADWIND("大盘偏弱"),
}

/** 技术分析、持仓提醒和基本面综合结论共用的入场上下文。 */
data class EntrySetup(
    val type: EntrySetupType,
    val confirmed: Boolean,
    /** 结构成立不等于允许开仓；突破结构目前只作观察。 */
    val canBuy: Boolean = false,
    /** 已有效跌破 M20 或出现失败结构，持仓侧应停止加仓/摊平。 */
    val trendWeak: Boolean = false,
    val priorHigh: Double?,
    val ma20SlopePct: Double?,
    val volumeConfirmed: Boolean,
    val marketFilter: MarketFilter,
    val stopPrice: Double?,
    val reason: String,
) {
    val label: String get() = type.label

    fun compactText(): String {
        val structure = when (type) {
            EntrySetupType.BREAKOUT -> "突破"
            EntrySetupType.BREAKOUT_WATCH -> "突破观察"
            EntrySetupType.M20_PULLBACK -> "M20回踩"
            EntrySetupType.NONE -> "无结构"
        }
        val status = when {
            type == EntrySetupType.NONE && trendWeak -> "跌破M20"
            type == EntrySetupType.NONE -> "等待突破/回踩"
            confirmed && !canBuy -> "仅观察"
            confirmed -> "已确认"
            else -> "待确认"
        }
        return "$structure · $status"
    }

    fun conciseText(): String = when {
        type == EntrySetupType.NONE -> reason
        confirmed && !canBuy -> "$label：$reason；仅作观察，不作为买入依据"
        confirmed -> "$label：$reason"
        else -> "${label}暂不确认：$reason"
    }
}

/**
 * 趋势跟随的入场规则：突破前高看放量，回踩 M20 看缩量；大盘只做软过滤。
 * 所有方法都是纯计算，不发网络请求，便于技术分析、提醒和报告复用同一套口径。
 */
object TrendEntryStrategy {
    const val BREAKOUT_VOLUME_RATIO = 1.3
    const val PULLBACK_VOLUME_RATIO = 0.8
    const val BREAKOUT_BUFFER_PCT = 0.5
    const val MA20_BREAK_TOLERANCE_PCT = 0.5
    const val PULLBACK_ABOVE_MA20_PCT = 2.0
    const val RETEST_TOLERANCE_PCT = 0.5
    const val INVALID_BREAK_PCT = 2.0
    const val INVALID_CONFIRM_DAYS = 2
    const val ATR_STOP_MULT = 1.5
    const val BREAKOUT_CAN_BUY = false
    const val MARKET_SLOPE_THRESHOLD_PCT = 0.1
    const val RISK_PER_TRADE_PCT = 1.0
    const val LOT = 100.0

    fun evaluate(
        history: List<DayBar>,
        price: Double,
        volumeRatio: Double?,
        atr: Double,
        benchmark: List<DayBar>? = null,
        currentOpen: Double? = null,
        currentHigh: Double? = null,
        currentLow: Double? = null,
        elapsedTradingMinutes: Int? = null,
        ignoreMarketFilter: Boolean = false,
    ): EntrySetup {
        val bars = history.filter { it.close > 0 && it.high > 0 && it.low > 0 }
        if (bars.size < 30 || price <= 0) {
            return none("历史数据不足，暂时无法确认趋势入场结构。")
        }

        val hasCurrentSnapshot =
            currentOpen != null || currentHigh != null || currentLow != null ||
            price != bars.last().close
        val currentDate = TimeUtils.todayText()
        val lastBar = bars.last()
        val currentBar = if (hasCurrentSnapshot) {
            val baseBars = if (lastBar.date == currentDate) bars.dropLast(1) else bars
            val averageVolume = baseBars
                .takeLast(20)
                .map { it.volume }
                .filter { it > 0 }
                .average()
            val currentVolume = if (volumeRatio != null && averageVolume > 0) {
                averageVolume * volumeRatio
            } else {
                lastBar.volume
            }
            val updated = lastBar.copy(
                open = currentOpen?.takeIf { it > 0 } ?: lastBar.open.takeIf { it > 0 } ?: price,
                high = maxOf(lastBar.high, currentHigh?.takeIf { it > 0 } ?: price),
                low = minOf(lastBar.low, currentLow?.takeIf { it > 0 } ?: price),
                close = price,
                volume = currentVolume,
            )
            if (lastBar.date == currentDate) updated else updated.copy(date = currentDate)
        } else {
            null
        }
        val evaluationBars = when {
            currentBar == null -> bars
            lastBar.date == currentDate -> bars.dropLast(1) + currentBar
            else -> bars + currentBar
        }
        return evaluateDaily(
            evaluationBars,
            benchmark,
            atrOverride = atr,
            elapsedTradingMinutes = elapsedTradingMinutes,
            ignoreMarketFilter = ignoreMarketFilter,
        )
    }

    /** 用完整日线判断最新一根是否只是候选，或已经完成次日确认。 */
    fun evaluateDaily(
        history: List<DayBar>,
        benchmark: List<DayBar>? = null,
        atrOverride: Double? = null,
        elapsedTradingMinutes: Int? = null,
        ignoreMarketFilter: Boolean = false,
    ): EntrySetup {
        val bars = history.filter { it.close > 0 && it.high > 0 && it.low > 0 && it.volume >= 0 }
        if (bars.size < 30) {
            return none("历史数据不足，暂时无法确认趋势入场结构。")
        }

        val closes = bars.map { it.close }
        val latest = closes.lastIndex
        val ma20 = closes.takeLast(20).average()
        val ma20FiveDaysAgo = closes.subList(latest - 24, latest - 4).average()
        val ma20TenDaysAgo = closes.subList(latest - 29, latest - 9).average()
        val ma20SlopePct = percentChange(ma20FiveDaysAgo, ma20)
        val ma20Rising = ma20 > ma20FiveDaysAgo && ma20FiveDaysAgo >= ma20TenDaysAgo
        val priorHigh = bars.dropLast(1).takeLast(20).maxOfOrNull { it.high }
        val marketFilter = marketFilter(benchmark)
        val currentVolumeRatio = volumeRatioAt(bars, latest, elapsedTradingMinutes)
        val currentPrice = bars[latest].close
        val previousCandidate = breakoutCandidateAt(bars, latest - 1)
        val currentCandidate = breakoutCandidateAt(bars, latest, elapsedTradingMinutes)
        val recentCandidate = (latest - 1 downTo maxOf(29, latest - 5))
            .asSequence()
            .mapNotNull { breakoutCandidateAt(bars, it) }
            .firstOrNull()
        val consecutiveClosesBelowMa20 = consecutiveClosesBelowMa20(bars, latest)
        val trendWeak = currentPrice < ma20 * (1 - INVALID_BREAK_PCT / 100.0) ||
            consecutiveClosesBelowMa20 >= INVALID_CONFIRM_DAYS
        val failedBreakout = recentCandidate != null && currentPrice < recentCandidate.priorHigh
        val currentMa5 = closes.takeLast(5).average()
        val previousBar = bars[latest - 1]
        val stabilizedAboveMa5 = currentPrice >= currentMa5
        val brokePreviousHigh = currentPrice >= previousBar.high
        val bullishCandle = bars[latest].close > bars[latest].open
        val nearMa20 = currentPrice >= ma20 * (1 - MA20_BREAK_TOLERANCE_PCT / 100.0) &&
            currentPrice <= ma20 * (1 + PULLBACK_ABOVE_MA20_PCT / 100.0)
        val contracted = currentVolumeRatio != null && currentVolumeRatio <= PULLBACK_VOLUME_RATIO
        val pullback = !trendWeak && !failedBreakout && ma20Rising && nearMa20 && contracted
        val pullbackConfirmed = pullback && (stabilizedAboveMa5 || brokePreviousHigh || bullishCandle)
        val breakoutConfirmed = previousCandidate != null &&
            currentPrice >= previousCandidate.priorHigh &&
            bars[latest].low >= previousCandidate.priorHigh * (1 - RETEST_TOLERANCE_PCT / 100.0)
        val breakoutWatch = currentCandidate != null && !breakoutConfirmed
        val type = when {
            trendWeak || failedBreakout -> EntrySetupType.NONE
            breakoutConfirmed -> EntrySetupType.BREAKOUT
            breakoutWatch -> EntrySetupType.BREAKOUT_WATCH
            pullback -> EntrySetupType.M20_PULLBACK
            else -> EntrySetupType.NONE
        }
        val marketAllows = ignoreMarketFilter || marketFilter != MarketFilter.HEADWIND
        val confirmed = when (type) {
            EntrySetupType.BREAKOUT -> marketAllows
            EntrySetupType.M20_PULLBACK -> pullbackConfirmed && marketAllows
            else -> false
        }
        val canBuy = when (type) {
            EntrySetupType.M20_PULLBACK -> confirmed
            EntrySetupType.BREAKOUT -> confirmed && BREAKOUT_CAN_BUY
            else -> false
        }
        val setupPriorHigh = when {
            breakoutConfirmed -> previousCandidate?.priorHigh
            breakoutWatch -> currentCandidate?.priorHigh
            else -> priorHigh
        }
        val setupMa20 = ma20
        val rawReason = when (type) {
            EntrySetupType.BREAKOUT ->
                "突破前高${priceText(previousCandidate!!.priorHigh)}后次日守住，M20向上"
            EntrySetupType.BREAKOUT_WATCH ->
                "已放量突破前高${priceText(currentCandidate!!.priorHigh)}，等待次日守住或回踩不破"
            EntrySetupType.M20_PULLBACK ->
                if (pullbackConfirmed) {
                    "价格接近M20 ${priceText(ma20)}，缩量至${ratioText(currentVolumeRatio)}，出现止跌确认"
                } else {
                    "价格接近M20 ${priceText(ma20)}，缩量但尚未出现止跌确认"
                }
            EntrySetupType.NONE -> when {
                failedBreakout -> "突破前高后快速回落，突破失败，等待重新形成结构"
                trendWeak -> "已有效跌破 M20，趋势转弱，等待重新站回并企稳"
                else -> noSetupReason(
                price = currentPrice,
                ma20 = ma20,
                ma20Rising = ma20Rising,
                priorHigh = priorHigh,
                volumeRatio = currentVolumeRatio,
                )
            }
        }
        val reason = if (type != EntrySetupType.NONE && marketFilter == MarketFilter.HEADWIND) {
            "$rawReason；但${marketFilter.label}，暂不确认新开仓"
        } else {
            rawReason
        }
        val stopAtr = atrOverride ?: (atrSeries(bars).lastOrNull() ?: 0.0)
        val stopPrice = when (type) {
            EntrySetupType.BREAKOUT, EntrySetupType.BREAKOUT_WATCH -> atrStop(currentPrice, stopAtr)
                ?: setupPriorHigh?.let { it * (1 - INVALID_BREAK_PCT / 100.0) }
            EntrySetupType.M20_PULLBACK -> atrStop(currentPrice, stopAtr)
                ?: setupMa20 * (1 - INVALID_BREAK_PCT / 100.0)
            EntrySetupType.NONE -> null
        }
        return EntrySetup(
            type = type,
            confirmed = confirmed,
            canBuy = canBuy,
            trendWeak = trendWeak,
            priorHigh = setupPriorHigh,
            ma20SlopePct = ma20SlopePct,
            volumeConfirmed = when (type) {
                EntrySetupType.BREAKOUT, EntrySetupType.BREAKOUT_WATCH -> true
                EntrySetupType.M20_PULLBACK -> contracted
                EntrySetupType.NONE -> false
            },
            marketFilter = marketFilter,
            stopPrice = stopPrice,
            reason = reason,
        )
    }

    private data class BreakoutCandidate(val priorHigh: Double)

    private fun breakoutCandidateAt(
        bars: List<DayBar>,
        index: Int,
        elapsedTradingMinutes: Int? = null,
    ): BreakoutCandidate? {
        if (index < 29 || index >= bars.size) return null
        val closes = bars.map { it.close }
        val ma20 = closes.subList(index - 19, index + 1).average()
        val ma20FiveDaysAgo = closes.subList(index - 24, index - 4).average()
        val ma20TenDaysAgo = closes.subList(index - 29, index - 9).average()
        val priorHigh = bars.subList(index - 20, index).maxOfOrNull { it.high } ?: return null
        val volumeRatio = volumeRatioAt(bars, index, elapsedTradingMinutes) ?: return null
        return if (
            bars[index].close >= priorHigh * (1 + BREAKOUT_BUFFER_PCT / 100.0) &&
            volumeRatio >= BREAKOUT_VOLUME_RATIO &&
            ma20 > ma20FiveDaysAgo && ma20FiveDaysAgo >= ma20TenDaysAgo
        ) {
            BreakoutCandidate(priorHigh)
        } else {
            null
        }
    }

    private fun volumeRatioAt(
        bars: List<DayBar>,
        index: Int,
        elapsedTradingMinutes: Int? = null,
    ): Double? {
        if (index < 20) return null
        val average = bars.subList(index - 20, index).map { it.volume }.filter { it > 0 }.average()
        if (!average.isFinite() || average <= 0 || !bars[index].volume.isFinite() || bars[index].volume <= 0) {
            return null
        }
        return if (index == bars.lastIndex && elapsedTradingMinutes != null) {
            VolumeRatio.of(bars[index].volume, average, elapsedTradingMinutes)
        } else {
            bars[index].volume / average
        }
    }

    private fun atrSeries(bars: List<DayBar>): DoubleArray =
        Indicators.atr(bars.map { it.high }, bars.map { it.low }, bars.map { it.close })

    private fun consecutiveClosesBelowMa20(bars: List<DayBar>, latest: Int): Int {
        var count = 0
        // 「收在 M20 下方」用和模块其它地方一致的 [MA20_BREAK_TOLERANCE_PCT] 容差：
        // 贴着 M20 下方 0.1% 震两天不该算「有效跌破」，否则回踩信号会被自己压掉。
        val breakLevel = 1 - MA20_BREAK_TOLERANCE_PCT / 100.0
        for (index in latest downTo 19) {
            val ma20 = bars.subList(index - 19, index + 1).map { it.close }.average()
            if (bars[index].close < ma20 * breakLevel) count++ else break
        }
        return count
    }

    private fun atrStop(price: Double, atr: Double): Double? =
        (price - atr * ATR_STOP_MULT).takeIf { price > 0 && atr > 0 && it > 0 }

    /** 风险预算优先，且不超过初始仓位和最大仓位；按 A 股一手向下取整。 */
    fun suggestedQuantity(
        price: Double,
        stopPrice: Double?,
        totalCapital: Double,
        initialEntryPct: Double,
        maxPositionPct: Double,
        riskPerTradePct: Double = RISK_PER_TRADE_PCT,
    ): Double {
        if (price <= 0 || stopPrice == null || stopPrice >= price || totalCapital <= 0) return 0.0
        if (initialEntryPct <= 0 || maxPositionPct <= 0 || riskPerTradePct <= 0) return 0.0
        val riskPerShare = price - stopPrice
        val riskShares = totalCapital * riskPerTradePct / 100.0 / riskPerShare
        val initialShares = totalCapital * initialEntryPct / 100.0 / price
        val maxShares = totalCapital * maxPositionPct / 100.0 / price
        return floor(min(riskShares, min(initialShares, maxShares)) / LOT) * LOT
    }

    /**
     * 大盘对新开仓的软闸门。状态判定统一交给 [MarketRegime]——大盘与板块同口径，
     * 这里只做映射：WEAK / COLLAPSE 都拦新开仓（塌陷期比转弱更不该开）。
     *
     * 保持 [MarketFilter] 枚举名与含义不变，旧的选股结果缓存
     * （[com.example.stockmonitor.core.ScreenResultJson] 的 `market_filter`）才读得回来。
     */
    fun marketFilter(benchmark: List<DayBar>?): MarketFilter =
        MarketRegime.evaluate(benchmark.orEmpty()).regime.toMarketFilter()

    private fun none(reason: String): EntrySetup = EntrySetup(
        type = EntrySetupType.NONE,
        confirmed = false,
        canBuy = false,
        trendWeak = false,
        priorHigh = null,
        ma20SlopePct = null,
        volumeConfirmed = false,
        marketFilter = MarketFilter.NEUTRAL,
        stopPrice = null,
        reason = reason,
    )

    private fun noSetupReason(
        price: Double,
        ma20: Double,
        ma20Rising: Boolean,
        priorHigh: Double?,
        volumeRatio: Double?,
    ): String = when {
        !ma20Rising -> "M20尚未持续向上，暂不追踪趋势入场"
        priorHigh != null && price >= priorHigh * (1 - 0.01) &&
            (volumeRatio ?: 0.0) < BREAKOUT_VOLUME_RATIO -> "接近前高但未放量突破"
        price < ma20 * (1 - MA20_BREAK_TOLERANCE_PCT / 100.0) -> "价格已跌破M20，等待重新站回"
        price <= ma20 * (1 + PULLBACK_ABOVE_MA20_PCT / 100.0) &&
            (volumeRatio ?: 1.0) > PULLBACK_VOLUME_RATIO -> "回踩M20但量能未收缩"
        else -> "等待前高放量突破或回踩M20缩量企稳"
    }

    private fun stopBelow(reference: Double, atr: Double, percent: Double): Double {
        val atrBuffer = if (atr > 0) atr else reference * percent
        return max(0.01, reference - max(atrBuffer, reference * percent))
    }

    private fun percentChange(previous: Double, current: Double): Double =
        if (previous == 0.0) 0.0 else (current / previous - 1) * 100

    private fun priceText(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun ratioText(value: Double?): String =
        value?.let { String.format(Locale.CHINA, "%.2f倍", it) } ?: "暂无"
}
