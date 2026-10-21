package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

enum class BoxRangeState(val label: String) {
    BOX_RANGE("箱体震荡"),
    BREAKOUT_CANDIDATE("箱体突破候选"),
    BREAKOUT_CONFIRMED("箱体突破确认"),
    BREAKDOWN_RISK("箱体跌破风险"),
    BREAKDOWN_CONFIRMED("箱体跌破确认"),
    NOT_BOX("非箱体结构"),
    MISSING("箱体数据不足"),
}

data class BoxRangeAssessment(
    val state: BoxRangeState,
    val upper: Double?,
    val lower: Double?,
    val widthPct: Double?,
    val reason: String,
) {
    val summary: String
        get() = if (upper != null && lower != null) {
            "${state.label}｜区间 ${format(lower)}～${format(upper)}｜$reason"
        } else {
            "${state.label}｜$reason"
        }

    private fun format(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f", value)
}

object BoxRangePolicy {
    private const val MIN_BARS = 30
    private const val LOOKBACK = 30
    private const val MAX_WIDTH_PCT = 18.0
    private const val TOUCH_TOLERANCE_PCT = 2.5
    private const val BREAKOUT_BUFFER_PCT = 0.5
    private const val BREAKDOWN_BUFFER_PCT = 0.5
    private const val CONFIRM_VOLUME_RATIO = 1.3

    fun evaluate(
        history: List<DayBar>,
        asOf: String,
        currentPrice: Double? = null,
        currentVolumeRatio: Double? = null,
    ): BoxRangeAssessment {
        val date = runCatching { LocalDate.parse(asOf) }.getOrNull()
            ?: return missing("行情日期无效")
        val completed = history.filter {
            runCatching { LocalDate.parse(it.date) }.getOrNull()?.isBefore(date) == true
        }.sortedBy { it.date }.takeLast(LOOKBACK)
        if (completed.size < MIN_BARS || completed.distinctBy { it.date }.size != completed.size ||
            completed.any { bar ->
                listOf(bar.open, bar.high, bar.low, bar.close).any { !it.isFinite() || it <= 0.0 } ||
                    bar.volume.isFinite().not() || bar.volume < 0.0 ||
                    bar.high < maxOf(bar.open, bar.close) || bar.low > minOf(bar.open, bar.close)
            }
        ) {
            return missing("已完成日线不足30根或OHLCV异常")
        }
        val upper = completed.maxOf { it.high }
        val lower = completed.minOf { it.low }
        val widthPct = if (lower > 0) (upper - lower) / lower * 100.0 else Double.NaN
        if (!widthPct.isFinite() || widthPct > MAX_WIDTH_PCT) {
            return BoxRangeAssessment(
                state = BoxRangeState.NOT_BOX,
                upper = upper,
                lower = lower,
                widthPct = widthPct,
                reason = "30日区间宽度过大，不能当作稳定箱体",
            )
        }
        val upperTolerance = upper * TOUCH_TOLERANCE_PCT / 100.0
        val lowerTolerance = lower * TOUCH_TOLERANCE_PCT / 100.0
        val upperTouches = completed.count { abs(it.high - upper) <= upperTolerance }
        val lowerTouches = completed.count { abs(it.low - lower) <= lowerTolerance }
        if (upperTouches < 2 || lowerTouches < 2) {
            return BoxRangeAssessment(
                state = BoxRangeState.NOT_BOX,
                upper = upper,
                lower = lower,
                widthPct = widthPct,
                reason = "上下沿触碰不足，暂不能确认箱体",
            )
        }
        val price = currentPrice ?: completed.last().close
        val volumeRatio = currentVolumeRatio?.takeIf { it.isFinite() && it > 0 }
        val above = price > upper * (1 + BREAKOUT_BUFFER_PCT / 100.0)
        val below = price < lower * (1 - BREAKDOWN_BUFFER_PCT / 100.0)
        val confirmedByVolume = volumeRatio != null && volumeRatio >= CONFIRM_VOLUME_RATIO
        val state = when {
            above && confirmedByVolume -> BoxRangeState.BREAKOUT_CONFIRMED
            above -> BoxRangeState.BREAKOUT_CANDIDATE
            below && confirmedByVolume -> BoxRangeState.BREAKDOWN_CONFIRMED
            below -> BoxRangeState.BREAKDOWN_RISK
            else -> BoxRangeState.BOX_RANGE
        }
        val reason = when (state) {
            BoxRangeState.BOX_RANGE -> "价格仍在30日箱体内，按区间对待，不把箱内波动当趋势"
            BoxRangeState.BREAKOUT_CANDIDATE -> "价格越过箱体上沿但量能未确认，等待突破确认"
            BoxRangeState.BREAKOUT_CONFIRMED -> "价格越过箱体上沿且量能达到确认阈值"
            BoxRangeState.BREAKDOWN_RISK -> "价格跌破箱体下沿，先按风险观察"
            BoxRangeState.BREAKDOWN_CONFIRMED -> "价格跌破箱体下沿且量能达到确认阈值"
            else -> ""
        }
        return BoxRangeAssessment(state, upper, lower, widthPct, reason)
    }

    private fun missing(reason: String) = BoxRangeAssessment(
        state = BoxRangeState.MISSING,
        upper = null,
        lower = null,
        widthPct = null,
        reason = reason,
    )
}
