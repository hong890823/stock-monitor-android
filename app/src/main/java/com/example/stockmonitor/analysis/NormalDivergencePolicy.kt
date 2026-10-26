package com.example.stockmonitor.analysis

import kotlin.math.max

enum class NormalDivergenceLevel {
    NORMAL,
    PENDING_BREAK,
    CONFIRMED_BREAK,
    MISSING,
}

enum class NormalDivergenceLine {
    MA5,
    MA20,
}

data class NormalDivergenceDecision(
    val level: NormalDivergenceLevel,
    val line: NormalDivergenceLine?,
    val linePrice: Double?,
    val reason: String,
)

data class NormalDivergenceDailyInput(
    val price: Double,
    val ma5: Double,
    val ma20: Double,
    val priceLocation: PriceLocation? = null,
    val biasFromMa5Pct: Double? = null,
    val atrPct: Double? = null,
    val volumeRatio: Double? = null,
    val priceChangePct: Double? = null,
    val topDivergence: Boolean = false,
    val trendWeak: Boolean = false,
    val supportBroken: Boolean = false,
    val failedBreakout: Boolean = false,
    val majorTrend: MajorTrend? = null,
    val weeklyTrend: SwingTrend = SwingTrend.MISSING,
    val monthlyTrend: SwingTrend = SwingTrend.MISSING,
)

data class NormalDivergenceIntradayInput(
    val price: Double,
    val ma5: Double,
    val ma20: Double,
    val priceLocation: PriceLocation? = null,
    val biasFromMa5Pct: Double? = null,
    val atrPct: Double? = null,
    val volumeRatio: Double? = null,
    val priceChangePct: Double? = null,
    val lastBarRejected: Boolean = false,
    val riskLineThresholdPct: Double = 0.0,
    val riskLineBelow: Boolean = false,
    val riskLineConfirmations: Int = 0,
    val riskLineM30Below: Boolean = false,
    val hasCompleted15m: Boolean = false,
    val hasCompleted30m: Boolean = false,
    /** 60 分钟/中期趋势只作背景，不单独把一次回落判成破位。 */
    val backgroundTrendWeak: Boolean = false,
)

/**
 * 个股正常分歧与破位的统一判断。
 *
 * 高位股看 MA5，中低位股看 MA20；盘中用 15 分钟发现、30 分钟确认，
 * 不把单根盘中回落直接当成破位。
 */
object NormalDivergencePolicy {
    const val HIGH_BIAS_PCT = 8.0
    const val EXPANDED_VOLUME_RATIO = 1.3
    const val CONTRACTED_VOLUME_RATIO = 0.8
    const val MIN_LINE_TOLERANCE_PCT = 0.5
    const val MAX_LINE_TOLERANCE_PCT = 1.5

    /** 统一给日K和盘中选择风险参考线，避免调用方各自猜高位口径。 */
    fun riskLine(
        price: Double,
        ma5: Double,
        ma20: Double,
        priceLocation: PriceLocation? = null,
        biasFromMa5Pct: Double? = null,
    ): Double? {
        if (!price.isFinite() || price <= 0.0 ||
            !ma5.isFinite() || ma5 <= 0.0 ||
            !ma20.isFinite() || ma20 <= 0.0
        ) {
            return null
        }
        val high = priceLocation == PriceLocation.HIGH ||
            (biasFromMa5Pct ?: ((price / ma5 - 1.0) * 100.0)) >= HIGH_BIAS_PCT
        return if (high) ma5 else ma20
    }

    fun evaluateDaily(input: NormalDivergenceDailyInput): NormalDivergenceDecision {
        if (!input.price.isFinite() || input.price <= 0.0 ||
            !input.ma5.isFinite() || input.ma5 <= 0.0 ||
            !input.ma20.isFinite() || input.ma20 <= 0.0
        ) {
            return missing()
        }

        val high = input.priceLocation == PriceLocation.HIGH ||
            (input.biasFromMa5Pct ?: biasFromMa5(input)) >= HIGH_BIAS_PCT
        val line = if (high) NormalDivergenceLine.MA5 else NormalDivergenceLine.MA20
        val linePrice = riskLine(
            price = input.price,
            ma5 = input.ma5,
            ma20 = input.ma20,
            priceLocation = input.priceLocation,
            biasFromMa5Pct = input.biasFromMa5Pct,
        ) ?: return missing()
        val tolerancePct = lineTolerancePct(input.atrPct)
        val lineHeld = input.price >= linePrice * (1.0 - tolerancePct / 100.0)

        // 关键线本身没破时，不把趋势偏弱或大周期背景直接升级成“确认破位”；
        // 只有关键价位/关键线已经失守，才进入确认破位，避免正常分歧被快速掉档。
        val confirmedBreak = input.supportBroken && !lineHeld
        if (confirmedBreak) {
            return NormalDivergenceDecision(
                level = NormalDivergenceLevel.CONFIRMED_BREAK,
                line = line,
                linePrice = linePrice,
                reason = "关键价位与${line.label}同时失守，已确认破位，不再按正常分歧处理",
            )
        }

        val weakContext = input.trendWeak ||
            input.supportBroken ||
            input.failedBreakout ||
            input.majorTrend == MajorTrend.DOWN ||
            (input.weeklyTrend == SwingTrend.WEAK && input.monthlyTrend == SwingTrend.WEAK)
        if (weakContext || !lineHeld) {
            return NormalDivergenceDecision(
                level = NormalDivergenceLevel.PENDING_BREAK,
                line = line,
                linePrice = linePrice,
                reason = if (lineHeld) {
                    "多周期或突破结构走弱，等待盘中确认"
                } else {
                    "价格进入${line.label}下方，等待持续性确认"
                },
            )
        }

        if (high) {
            val volumeRatio = input.volumeRatio
            val priceChangePct = input.priceChangePct
            val volumeProgress = volumeRatio != null && volumeRatio.isFinite() &&
                volumeRatio >= EXPANDED_VOLUME_RATIO &&
                priceChangePct != null && priceChangePct.isFinite() &&
                priceChangePct > 0.0
            if (!volumeProgress || input.topDivergence) {
                return NormalDivergenceDecision(
                    level = NormalDivergenceLevel.PENDING_BREAK,
                    line = line,
                    linePrice = linePrice,
                    reason = if (input.topDivergence) {
                        "高位出现顶背离，量价配合不足，等待回踩确认"
                    } else {
                        "高位虽守住${line.label}，但量价尚未形成正向配合"
                    },
                )
            }
        }

        return NormalDivergenceDecision(
            level = NormalDivergenceLevel.NORMAL,
            line = line,
            linePrice = linePrice,
            reason = if (high) {
                "高位守住${line.label}且量价仍有配合，按正常分歧观察"
            } else {
                "中低位守住${line.label}，按正常分歧观察"
            },
        )
    }

    fun evaluateIntraday(input: NormalDivergenceIntradayInput): NormalDivergenceDecision {
        if (!input.price.isFinite() || input.price <= 0.0 ||
            !input.ma5.isFinite() || input.ma5 <= 0.0 ||
            !input.ma20.isFinite() || input.ma20 <= 0.0 ||
            !input.hasCompleted15m || !input.hasCompleted30m
        ) {
            return missing()
        }

        val high = input.priceLocation == PriceLocation.HIGH ||
            (input.biasFromMa5Pct ?: biasFromMa5(input)) >= HIGH_BIAS_PCT
        val line = if (high) NormalDivergenceLine.MA5 else NormalDivergenceLine.MA20
        val linePrice = riskLine(
            price = input.price,
            ma5 = input.ma5,
            ma20 = input.ma20,
            priceLocation = input.priceLocation,
            biasFromMa5Pct = input.biasFromMa5Pct,
        ) ?: return missing()
        val tolerancePct = if (input.riskLineThresholdPct.isFinite() && input.riskLineThresholdPct > 0.0) {
            input.riskLineThresholdPct * 100.0
        } else {
            lineTolerancePct(input.atrPct)
        }
        val threshold = linePrice * (1.0 - tolerancePct / 100.0)
        val lineHeld = input.price >= threshold && !input.riskLineBelow
        val confirmedBreak = input.riskLineConfirmations >= 3 ||
            (input.riskLineConfirmations >= 2 && input.riskLineM30Below)

        if (confirmedBreak) {
            return NormalDivergenceDecision(
                level = NormalDivergenceLevel.CONFIRMED_BREAK,
                line = line,
                linePrice = linePrice,
                reason = if (input.riskLineConfirmations >= 3) {
                    "15分钟连续3根跌破${line.label}，按盘中确认破位处理"
                } else {
                    "15分钟连续跌破${line.label}且30分钟确认，按盘中破位处理"
                },
            )
        }

        val volumeRatio = input.volumeRatio
        val priceChangePct = input.priceChangePct
        val volumeProgress = volumeRatio != null && volumeRatio.isFinite() &&
            volumeRatio >= EXPANDED_VOLUME_RATIO &&
            priceChangePct != null && priceChangePct.isFinite() &&
            priceChangePct > 0.0
        val highNeedsVolume = high && !volumeProgress
        val pendingBreak = input.backgroundTrendWeak ||
            !lineHeld ||
            input.riskLineConfirmations > 0 ||
            input.lastBarRejected ||
            highNeedsVolume
        if (pendingBreak) {
            return NormalDivergenceDecision(
                level = NormalDivergenceLevel.PENDING_BREAK,
                line = line,
                linePrice = linePrice,
                reason = when {
                    input.backgroundTrendWeak -> "60分钟/中期趋势背景偏弱，等待盘中价格与量能重新确认"
                    !lineHeld -> "15分钟价格进入${line.label}下方，等待30分钟确认"
                    input.lastBarRejected -> "15分钟出现上影拒绝，暂按分歧待确认"
                    highNeedsVolume -> "高位守住${line.label}但量价配合不足，等待确认"
                    else -> "盘中已有跌破样本，但尚未完成30分钟确认"
                },
            )
        }

        return NormalDivergenceDecision(
            level = NormalDivergenceLevel.NORMAL,
            line = line,
            linePrice = linePrice,
            reason = if (high) {
                "高位守住${line.label}且量价配合，按盘中正常分歧观察"
            } else {
                "中低位守住${line.label}，按盘中正常分歧观察"
            },
        )
    }

    private fun biasFromMa5(input: NormalDivergenceDailyInput): Double =
        if (input.ma5 > 0.0) (input.price / input.ma5 - 1.0) * 100.0 else Double.NaN

    private fun biasFromMa5(input: NormalDivergenceIntradayInput): Double =
        if (input.ma5 > 0.0) (input.price / input.ma5 - 1.0) * 100.0 else Double.NaN

    private fun lineTolerancePct(atrPct: Double?): Double {
        val atr = atrPct?.takeIf { it.isFinite() && it > 0.0 } ?: MIN_LINE_TOLERANCE_PCT
        return max(MIN_LINE_TOLERANCE_PCT, atr * 0.5).coerceAtMost(MAX_LINE_TOLERANCE_PCT)
    }

    private fun missing() = NormalDivergenceDecision(
        level = NormalDivergenceLevel.MISSING,
        line = null,
        linePrice = null,
        reason = "关键均线或盘中完成K线不足，暂不判断正常分歧",
    )

    private val NormalDivergenceLine.label: String
        get() = when (this) {
            NormalDivergenceLine.MA5 -> "MA5"
            NormalDivergenceLine.MA20 -> "MA20"
        }
}
