package com.example.stockmonitor.analysis

import java.util.Locale

class FirstBoardReclaimTracker {
    data class Sample(
        val code: String,
        val name: String,
        val tradingDay: String,
        val previousClose: Double,
        val open: Double,
        val low: Double,
        val price: Double,
        val timestamp: Long,
        val recentFirstBoard: Boolean,
        val limitUp: Boolean,
        /** 收盘价相对动态 MA5 的乖离百分比；调用方负责按 [FirstBoardReclaimPolicy] 过滤。 */
        val biasFromMa5Pct: Double? = null,
    )

    data class Alert(
        val code: String,
        val title: String,
        val body: String,
    )

    private data class State(
        val tradingDay: String,
        var low: Double,
        var lowAtMs: Long,
        var emitted: Boolean = false,
    )

    private val states = mutableMapOf<String, State>()

    fun update(sample: Sample): Alert? {
        if (!sample.recentFirstBoard) {
            states.remove(sample.code)
            return null
        }
        if (sample.limitUp || sample.timestamp <= 0L ||
            listOf(sample.previousClose, sample.open, sample.low, sample.price).any {
                !it.isFinite() || it <= 0.0
            }
        ) {
            return null
        }

        val state = states[sample.code]
            ?.takeIf { it.tradingDay == sample.tradingDay }
            ?: State(sample.tradingDay, sample.low, sample.timestamp).also { states[sample.code] = it }
        if (sample.low < state.low) {
            state.low = sample.low
            state.lowAtMs = sample.timestamp
        }
        val meaningfulDip = sample.low <= sample.open - sample.previousClose * MIN_OPEN_DIP_PCT / 100.0
        val stableForMs = sample.timestamp - state.lowAtMs
        if (state.emitted || !meaningfulDip || sample.price < sample.open ||
            stableForMs < STABLE_LOW_MS
        ) {
            return null
        }

        state.emitted = true
        val biasText = sample.biasFromMa5Pct
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.CHINA, "，MA5乖离%.1f%%", it) }
            .orEmpty()
        return Alert(
            code = sample.code,
            title = "首板分歧转强观察",
            body = String.format(
                Locale.CHINA,
                "%s｜回收开盘价 %.2f，日内低点 %.2f 已稳定5分钟%s；观察量价承接与失效位，不等同买入确认，勿追涨停。",
                sample.name,
                sample.open,
                state.low,
                biasText,
            ),
        )
    }

    /** 推送预算不足时回滚“已提醒”，下一次满足条件可以重试。 */
    fun rearm(code: String) {
        states[code]?.emitted = false
    }

    companion object {
        const val MIN_OPEN_DIP_PCT = StrategyThresholds.FIRST_BOARD_OPEN_DIP_PCT
        const val STABLE_LOW_MS = 5 * 60 * 1000L
    }
}
