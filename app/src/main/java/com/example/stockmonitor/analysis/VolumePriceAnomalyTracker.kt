package com.example.stockmonitor.analysis

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * 持仓盘中量价异动：短窗量能突增并有效突破爆量前整理平台上沿。
 *
 * 这里只描述盘面事实，不生成买卖建议。普通异动使用中性通知；只有更强的
 * 量能、突破、成交额和日内新高条件同时满足时，才升级为盘中点火强提醒。
 */
class VolumePriceAnomalyTracker(
    private val shortWindowSeconds: Long = SHORT_WINDOW_SECONDS,
    private val baselineWindowSeconds: Long = BASELINE_WINDOW_SECONDS,
    private val volumeRatioThreshold: Double = VOLUME_BURST_RATIO,
    private val minBreakoutPct: Double = MIN_BREAKOUT_PCT,
    private val minBreakoutTicks: Double = MIN_BREAKOUT_TICKS,
    private val minShortTurnover: Double = MIN_SHORT_TURNOVER,
    private val strongVolumeRatio: Double = STRONG_VOLUME_RATIO,
    private val strongBreakoutPct: Double = STRONG_BREAKOUT_PCT,
    private val strongShortTurnover: Double = STRONG_SHORT_TURNOVER,
) {
    enum class Level {
        ANOMALY,
        IGNITION,
    }

    data class Sample(
        val code: String,
        val name: String,
        val price: Double,
        val cumulativeVolume: Double,
        val cumulativeTurnover: Double,
        val sessionHigh: Double,
        val timestamp: LocalDateTime,
    )

    data class Alert(
        val code: String,
        val name: String,
        val price: Double,
        val volumeRatio: Double,
        val platformTop: Double,
        val breakoutPct: Double,
        val shortTurnover: Double,
        val level: Level,
        val tail: Boolean,
        val fundFlowTone: IntradayFundFlowTone,
        val fundFlowSummary: String,
    ) {
        val strong: Boolean get() = level == Level.IGNITION

        val title: String
            get() = when {
                strong && tail -> "【尾盘强势异动】 $displayName"
                strong -> "【盘中点火】 $displayName"
                tail -> "【尾盘量价异动】 $displayName"
                else -> "【量价异动】 $displayName"
            }

        val body: String
            get() {
                val facts = String.format(
                    Locale.CHINA,
                    "5 分钟量能约为前 30 分钟均量的 %.1f 倍，5 分钟成交额 %.0f 万元；现价 %.2f，较整理平台上沿 %.2f 突破 %.2f%%。",
                    volumeRatio,
                    shortTurnover / 10_000.0,
                    price,
                    platformTop,
                    breakoutPct * 100.0,
                )
                return if (strong) {
                    facts + fundFlowSummary +
                        "强提醒仅代表盘面强度，不代表趋势启动或可以追涨；非买入指令，是否参与请自行判断。"
                } else {
                    facts + fundFlowSummary +
                        "仅为量价异动事实，不代表趋势启动，不构成买卖建议；是否参与请自行判断。"
                }
            }

        private val displayName: String get() = name.ifBlank { code }
    }

    private data class Observation(
        val sessionSeconds: Long,
        val cumulativeVolume: Double,
        val price: Double,
        val cumulativeTurnover: Double,
    )

    private data class State(
        val samples: MutableList<Observation> = mutableListOf(),
        var fired: Boolean = false,
        var lastTimestamp: LocalDateTime? = null,
        var lastCumulativeVolume: Double = Double.NEGATIVE_INFINITY,
        var lastCumulativeTurnover: Double = Double.NEGATIVE_INFINITY,
    )

    private val states = mutableMapOf<String, State>()
    private var tradeDay: String? = null

    fun update(
        sample: Sample,
        day: String,
        fundFlowContext: IntradayFundFlowContext? = null,
    ): Alert? {
        if (day.isBlank() || !isDetectionTime(sample.timestamp)) return null
        if (tradeDay != day) {
            states.clear()
            tradeDay = day
        }

        val values = listOf(
            sample.price,
            sample.cumulativeVolume,
            sample.cumulativeTurnover,
            sample.sessionHigh,
        )
        if (values.any { !it.isFinite() }) return null
        if (sample.price <= 0.0 ||
            sample.cumulativeVolume < 0.0 ||
            sample.cumulativeTurnover < 0.0 ||
            sample.sessionHigh < sample.price
        ) {
            return null
        }

        val state = states.getOrPut(sample.code) { State() }
        val previousTimestamp = state.lastTimestamp
        if (previousTimestamp != null && sample.timestamp <= previousTimestamp) return null
        if (sample.cumulativeVolume < state.lastCumulativeVolume ||
            sample.cumulativeTurnover < state.lastCumulativeTurnover
        ) {
            return null
        }

        val sessionSeconds = sessionElapsedSeconds(sample.timestamp)
        state.samples += Observation(
            sessionSeconds = sessionSeconds,
            cumulativeVolume = sample.cumulativeVolume,
            price = sample.price,
            cumulativeTurnover = sample.cumulativeTurnover,
        )
        state.lastTimestamp = sample.timestamp
        state.lastCumulativeVolume = sample.cumulativeVolume
        state.lastCumulativeTurnover = sample.cumulativeTurnover

        val horizonSeconds = shortWindowSeconds + baselineWindowSeconds
        state.samples.removeAll {
            it.sessionSeconds < sessionSeconds - horizonSeconds - shortWindowSeconds
        }
        if (state.fired) return null

        val oldest = state.samples.firstOrNull() ?: return null
        if (oldest.sessionSeconds > sessionSeconds - horizonSeconds) return null

        val shortCutoff = sessionSeconds - shortWindowSeconds
        val baselineCutoff = sessionSeconds - horizonSeconds
        val cumulativeAtShort = valueAtOrBefore(state.samples, shortCutoff)?.cumulativeVolume ?: return null
        val cumulativeAtBaseline =
            valueAtOrBefore(state.samples, baselineCutoff)?.cumulativeVolume ?: return null
        val turnoverAtShort =
            valueAtOrBefore(state.samples, shortCutoff)?.cumulativeTurnover ?: return null

        val shortVolume = sample.cumulativeVolume - cumulativeAtShort
        val baselineVolume = cumulativeAtShort - cumulativeAtBaseline
        val shortTurnover = sample.cumulativeTurnover - turnoverAtShort
        val comparableBaselineVolume =
            baselineVolume * shortWindowSeconds.toDouble() / baselineWindowSeconds.toDouble()
        if (comparableBaselineVolume <= 0.0 ||
            shortVolume <= 0.0 ||
            shortTurnover < minShortTurnover
        ) {
            return null
        }

        val ratio = shortVolume / comparableBaselineVolume
        if (ratio < volumeRatioThreshold) return null

        val platformTop = state.samples
            .filter { it.sessionSeconds in (baselineCutoff + 1)..shortCutoff }
            .maxOfOrNull { it.price }
            ?: return null
        val breakout = sample.price - platformTop
        val requiredBreakout = maxOf(
            platformTop * minBreakoutPct,
            PRICE_TICK * minBreakoutTicks,
        )
        if (breakout < requiredBreakout) return null

        val dayVwap = if (sample.cumulativeVolume > 0.0 && sample.cumulativeTurnover > 0.0) {
            sample.cumulativeTurnover / sample.cumulativeVolume
        } else {
            sample.price
        }
        if (sample.price < dayVwap) return null

        state.fired = true
        val strongRequiredBreakout = maxOf(
            platformTop * strongBreakoutPct,
            PRICE_TICK * minBreakoutTicks,
        )
        val fundFlowTone = IntradayFundFlowPolicy.assess(
            fundFlowContext ?: IntradayFundFlowContext(
                dayMainNetYuan = null,
                dayMainNetPct = null,
                recent5MainNetYuan = null,
                recent5MainNetPct = null,
                recent15MainNetYuan = null,
                recent15MainNetPct = null,
            ),
        )
        val flowConfirmed = fundFlowTone == IntradayFundFlowTone.SUPPORTIVE ||
            fundFlowTone == IntradayFundFlowTone.TURNING_STRONG
        val strong = ratio >= strongVolumeRatio &&
            breakout >= strongRequiredBreakout &&
            shortTurnover >= strongShortTurnover &&
            sample.price >= sample.sessionHigh &&
            flowConfirmed
        return Alert(
            code = sample.code,
            name = sample.name,
            price = sample.price,
            volumeRatio = ratio,
            platformTop = platformTop,
            breakoutPct = if (platformTop > 0.0) breakout / platformTop else 0.0,
            shortTurnover = shortTurnover,
            level = if (strong) Level.IGNITION else Level.ANOMALY,
            tail = isTailTime(sample.timestamp),
            fundFlowTone = fundFlowTone,
            fundFlowSummary = IntradayFundFlowPolicy.summary(
                fundFlowContext ?: IntradayFundFlowContext(
                    dayMainNetYuan = null,
                    dayMainNetPct = null,
                    recent5MainNetYuan = null,
                    recent5MainNetPct = null,
                    recent15MainNetYuan = null,
                    recent15MainNetPct = null,
                ),
            ),
        )
    }

    private fun valueAtOrBefore(
        samples: List<Observation>,
        cutoff: Long,
    ): Observation? = samples.lastOrNull { it.sessionSeconds <= cutoff }

    companion object {
        const val SHORT_WINDOW_SECONDS = 300L
        const val BASELINE_WINDOW_SECONDS = 1_800L
        const val VOLUME_BURST_RATIO = 3.0
        const val MIN_BREAKOUT_PCT = 0.003
        const val MIN_BREAKOUT_TICKS = 3.0
        const val PRICE_TICK = 0.01
        const val MIN_SHORT_TURNOVER = 2_000_000.0
        const val STRONG_VOLUME_RATIO = 4.0
        const val STRONG_BREAKOUT_PCT = 0.005
        const val STRONG_SHORT_TURNOVER = 5_000_000.0

        private val MORNING_START: LocalTime = LocalTime.of(9, 30)
        private val MORNING_END: LocalTime = LocalTime.of(11, 30)
        private val AFTERNOON_START: LocalTime = LocalTime.of(13, 0)
        private val TAIL_START: LocalTime = LocalTime.of(14, 50)
        private val CLOSING_AUCTION_START: LocalTime = LocalTime.of(14, 57)
        private val MORNING_SECONDS = Duration.between(MORNING_START, MORNING_END).seconds

        fun isDetectionTime(timestamp: LocalDateTime): Boolean {
            val time = timestamp.toLocalTime()
            return (time >= MORNING_START && time <= MORNING_END) ||
                (time >= AFTERNOON_START && time < CLOSING_AUCTION_START)
        }

        fun isTailTime(timestamp: LocalDateTime): Boolean =
            timestamp.toLocalTime() >= TAIL_START

        /** 午休不计入滚动量能窗口，11:30 与 13:00 都映射为同一个连续交易时点。 */
        fun sessionElapsedSeconds(timestamp: LocalDateTime): Long {
            val time = timestamp.toLocalTime()
            return when {
                time <= MORNING_START -> 0L
                time <= MORNING_END -> Duration.between(MORNING_START, time).seconds
                time < AFTERNOON_START -> MORNING_SECONDS
                else -> MORNING_SECONDS + Duration.between(AFTERNOON_START, time).seconds
            }
        }

        fun detectionDate(timestamp: LocalDateTime): LocalDate = timestamp.toLocalDate()
    }
}
