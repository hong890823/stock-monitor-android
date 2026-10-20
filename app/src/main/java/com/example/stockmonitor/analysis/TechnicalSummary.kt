package com.example.stockmonitor.analysis

import java.util.Locale

data class TechnicalSummary(
    val score: Int,
    val scoreLabel: String,
    val trend: String,
    val volume: String,
    val momentum: String,
    val levels: String,
    val entry: String,
    val action: String,
    /** 量价背离一行式结论（如「量价背离：顶背离（股价创新高 OBV 未同步）」）。 */
    val divergence: String = "",
) {
    companion object {
        fun from(result: TechnicalResult): TechnicalSummary {
            val trend = when {
                result.price > result.ma20 && result.ma20 > result.ma60 -> "中期趋势偏强"
                result.price < result.ma20 && result.ma20 < result.ma60 -> "中期趋势偏弱"
                else -> "中期趋势还没确认"
            } + when {
                result.ma5 > result.ma10 && result.ma10 > result.ma20 -> "，短期均线多头排列"
                result.ma5 < result.ma10 && result.ma10 < result.ma20 -> "，短期均线空头排列"
                else -> "，短期均线缠绕"
            }

            val volume = when (val ratio = result.volumeRatio) {
                null -> "量比暂缺"
                else -> String.format(
                    Locale.CHINA,
                    "量比 %.2f 倍%s",
                    ratio,
                    if (result.volumeRatioIntraday) "（盘中折算）" else "",
                )
            } + "｜${VolumePrice.tag(VolumePrice.of(result.volumeRatio, result.priceChange, result.priceContext))}"

            val momentum = String.format(
                Locale.CHINA,
                "MACD %s｜RSI %s｜KDJ K %.1f / D %.1f",
                if (result.macd.dif > result.macd.dea) "多头" else "空头",
                result.rsi?.let { "%.1f".format(Locale.CHINA, it) } ?: "暂缺",
                result.kdj.k,
                result.kdj.d,
            )
            val levels = String.format(
                Locale.CHINA,
                "支撑 %.2f｜压力 %.2f｜MA20 %.2f｜ATR %.2f（%.2f%%）",
                result.support,
                result.resistance,
                result.ma20,
                result.volatility.atr,
                result.volatility.atrPct ?: 0.0,
            )
            val entry = buildString {
                append(result.entrySetup.conciseText())
                result.entrySetup.stopPrice?.let {
                    append(String.format(Locale.CHINA, "｜止损 %.2f", it))
                }
            }
            return TechnicalSummary(
                score = result.score,
                scoreLabel = when {
                    result.score >= 4 -> "偏强"
                    result.score <= -4 -> "偏弱"
                    else -> "中性"
                },
                trend = trend,
                volume = volume,
                momentum = momentum,
                levels = levels,
                entry = entry,
                action = result.action,
                divergence = result.divergence.short,
            )
        }
    }
}
