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

    /** 点火分级双段（2026-11-26）：OBSERVATION 点火观察（不弹窗）、CONFIRM 点火确认（弹窗）、OUTCOME 点火结果（只留痕）。 */
    enum class Phase { OBSERVATION, CONFIRM, OUTCOME }

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
        val phase: Phase = Phase.OBSERVATION,
        val quality: IgnitionQualityPolicy.IgnitionQuality? = null,
        val outcomeReason: String? = null,
    ) {
        val strong: Boolean get() = level == Level.IGNITION

        val title: String
            get() = when {
                strong && phase == Phase.CONFIRM -> "【${if (tail) "尾盘" else "盘中"}点火确认】 $displayName"
                strong && phase == Phase.OUTCOME -> "【点火结果】 $displayName"
                strong -> "【${if (tail) "尾盘" else "盘中"}点火观察】 $displayName"
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
                return when {
                    strong && phase == Phase.OUTCOME ->
                        facts + "｜点火结果：${outcomeReason ?: "确认窗内未延续"}；只留痕不打扰，不构成买卖建议。"
                    strong && phase == Phase.CONFIRM ->
                        facts + fundFlowSummary +
                            "确认窗内现价守住整理平台上沿、量能延续，点火确认；仍不代表趋势启动，勿追高。"
                    strong ->
                        facts + fundFlowSummary +
                            quality?.let { "四维诱多评级：${it.summary}。" }.orEmpty() +
                            "点火瞬间先观察不弹窗：确认窗（2.5 分钟）守住平台上沿且量能延续才升级「点火确认」；不代表趋势启动，非买入指令，是否参与请自行判断。"
                    else ->
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
        /** 点火分级双段：确认窗状态（点火观察后等待升级/衰竭）。 */
        var pendingAlert: Alert? = null,
        var pendingSinceSeconds: Long = 0L,
        var pendingPlatformTop: Double = 0.0,
    )

    private val states = mutableMapOf<String, State>()
    private var tradeDay: String? = null

    fun update(
        sample: Sample,
        day: String,
        fundFlowContext: IntradayFundFlowContext? = null,
        /** 四维诱多评级的环境/位置输入（缺省记中性，见 [IgnitionQualityPolicy]）。 */
        marketChangePct: Double? = null,
        sectorChangePct: Double? = null,
        resistance: Double? = null,
        recentChange5dPct: Double? = null,
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
        // 点火分级双段（2026-11-26）：点火观察后进入确认窗——现价守住整理平台上沿且
        // 量能延续（近 1 分钟速率 ≥ 基线的 CONFIRM_VOLUME_RATIO 倍）才升级「点火确认」；
        // 跌回平台上沿、量能衰竭或超时出「点火结果」。结果只留痕不打扰。
        state.pendingAlert?.let { pending ->
            val platformHold = sample.price >= state.pendingPlatformTop
            val recentVolume = volumeBetween(state.samples, sessionSeconds - CONFIRM_RATE_WINDOW, sessionSeconds)
            val baselineVolume = volumeBetween(
                state.samples,
                sessionSeconds - CONFIRM_RATE_BASELINE_END,
                sessionSeconds - CONFIRM_RATE_WINDOW,
            )
            val baselinePerSecond =
                baselineVolume / (CONFIRM_RATE_BASELINE_END - CONFIRM_RATE_WINDOW).toDouble()
            val volumeOk = baselinePerSecond > 0.0 &&
                recentVolume / CONFIRM_RATE_WINDOW.toDouble() >= baselinePerSecond * CONFIRM_VOLUME_RATIO
            val expired = sessionSeconds > state.pendingSinceSeconds + CONFIRM_WINDOW_SECONDS
            return when {
                !platformHold -> {
                    state.pendingAlert = null
                    pending.copy(
                        phase = Phase.OUTCOME,
                        price = sample.price,
                        outcomeReason = "跌回整理平台上沿下方（现价 ${sample.price} < 上沿 ${state.pendingPlatformTop}）",
                    )
                }
                volumeOk && !expired -> {
                    state.pendingAlert = null
                    pending.copy(phase = Phase.CONFIRM, price = sample.price)
                }
                expired -> {
                    state.pendingAlert = null
                    pending.copy(
                        phase = Phase.OUTCOME,
                        price = sample.price,
                        outcomeReason = "确认窗（${CONFIRM_WINDOW_SECONDS / 60}.5 分钟）内量能未延续到基线的 ${CONFIRM_VOLUME_RATIO} 倍",
                    )
                }
                else -> null
            }
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
        // 四维诱多评级的脉冲输入：单样本占短窗爆量比例、冲高回落幅度。
        val previousCumulative = state.samples.getOrNull(state.samples.size - 2)?.cumulativeVolume
        val burstConcentration = previousCumulative?.let {
            (sample.cumulativeVolume - it).coerceAtLeast(0.0) / shortVolume
        }
        val fadeFromHighPct = if (sample.sessionHigh > 0.0) {
            (sample.sessionHigh - sample.price) / sample.sessionHigh * 100
        } else {
            null
        }
        val quality = if (strong) {
            IgnitionQualityPolicy.assess(
                marketChangePct = marketChangePct,
                sectorChangePct = sectorChangePct,
                resistance = resistance,
                price = sample.price,
                recentChange5dPct = recentChange5dPct,
                burstConcentration = burstConcentration,
                fadeFromHighPct = fadeFromHighPct,
                fundFlowTone = fundFlowTone.name.lowercase(Locale.ROOT),
            )
        } else {
            null
        }
        val alert = Alert(
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
            phase = Phase.OBSERVATION,
            quality = quality,
        )
        if (strong) {
            // 点火瞬间只出「点火观察」（含四维评级）并挂起确认窗，不即时弹窗。
            state.pendingAlert = alert
            state.pendingSinceSeconds = sessionSeconds
            state.pendingPlatformTop = platformTop
        }
        return alert
    }

    private fun valueAtOrBefore(
        samples: List<Observation>,
        cutoff: Long,
    ): Observation? = samples.lastOrNull { it.sessionSeconds <= cutoff }

    /** [fromSec, toSec] 区间内的成交量（按累计量差值）。 */
    private fun volumeBetween(samples: List<Observation>, fromSec: Long, toSec: Long): Double {
        val before = valueAtOrBefore(samples, fromSec)?.cumulativeVolume ?: return 0.0
        val at = valueAtOrBefore(samples, toSec)?.cumulativeVolume ?: return 0.0
        return (at - before).coerceAtLeast(0.0)
    }

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

        /** 点火确认窗（秒）：华天科技点火到封板约 5 分钟，取前 2.5 分钟判延续。 */
        const val CONFIRM_WINDOW_SECONDS = 150L

        /** 确认窗量能延续门槛（近 1 分钟速率 ≥ 基线分钟速率的倍数）。 */
        const val CONFIRM_VOLUME_RATIO = 1.5

        /** 确认窗量能速率：近 1 分钟对比前 4 分钟基线。 */
        private const val CONFIRM_RATE_WINDOW = 60L
        private const val CONFIRM_RATE_BASELINE_END = 300L

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
