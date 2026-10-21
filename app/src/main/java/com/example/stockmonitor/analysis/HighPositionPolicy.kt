package com.example.stockmonitor.analysis

import kotlin.math.max

enum class HighPositionPhase(val label: String) {
    NONE("非高位区间"),
    EXTENDED_WATCH("高位待确认"),
    DISTRIBUTION_RISK("高位派发风险"),
    CONSOLIDATION("高位整理蓄势"),
    SECOND_WAVE_CANDIDATE("二波候选"),
    SECOND_WAVE_CONFIRMED("二波突破确认"),
    UNCERTAIN("高位证据冲突"),
}

data class HighPositionEvidence(
    val price: Double,
    val rangeHigh: Double,
    val rangeLow: Double,
    val atrPct: Double,
    val gain5Pct: Double,
    val volumeRatio: Double?,
    val pullbackVolumeRatio: Double?,
    val recentLows: List<Double>,
    val recentHighs: List<Double>,
    val recentCloses: List<Double>,
    val support: Double,
    val marketHealthy: Boolean,
    val sectorHealthy: Boolean,
    val dualTimeframeAllowed: Boolean,
    val trend: TrendAssessment,
)

data class HighPositionDecision(
    val phase: HighPositionPhase,
    val reason: String,
    val allowBuy: Boolean,
    val allowSecondWaveEntry: Boolean,
    val allowHoldBase: Boolean,
    val riskPriority: Boolean,
)

object HighPositionPolicy {
    private const val HIGH_RANGE_RATIO = 0.95
    private const val BREAKOUT_BUFFER_PCT = 0.5
    private const val BREAKOUT_VOLUME_RATIO = 1.3
    private const val REBOUND_VOLUME_RATIO = 1.1
    private const val PULLBACK_VOLUME_RATIO = 0.8
    private const val MAX_RANGE_WIDTH_PCT = 5.0

    fun evaluate(evidence: HighPositionEvidence): HighPositionDecision {
        if (!valid(evidence)) {
            return decision(
                phase = HighPositionPhase.UNCERTAIN,
                reason = "高位结构数据缺失或异常，继续观察",
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = false,
                riskPriority = false,
            )
        }
        val highPosition = evidence.price >= evidence.rangeHigh * HIGH_RANGE_RATIO
        if (!highPosition) {
            return decision(
                phase = HighPositionPhase.NONE,
                reason = "价格未进入高位区间",
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = false,
                riskPriority = false,
            )
        }
        val trendCanEnter = evidence.trend.allowsOrdinaryBuy || evidence.trend.allowsBottomReversal
        val rangeWidthPct = (evidence.rangeHigh - evidence.rangeLow) / evidence.rangeLow * 100.0
        val volumeConfirmed = evidence.volumeRatio ?: 0.0
        val breakout = evidence.price >= evidence.rangeHigh * (1.0 + BREAKOUT_BUFFER_PCT / 100.0) &&
            volumeConfirmed >= BREAKOUT_VOLUME_RATIO &&
            evidence.dualTimeframeAllowed &&
            trendCanEnter
        if (breakout) {
            return decision(
                phase = HighPositionPhase.SECOND_WAVE_CONFIRMED,
                reason = "放量突破高位区间上沿，15/30分钟条件允许执行",
                allowBuy = true,
                allowSecondWaveEntry = true,
                allowHoldBase = true,
                riskPriority = false,
            )
        }
        val lowerHighs = evidence.recentHighs.size >= 3 &&
            evidence.recentHighs.zipWithNext().all { (previous, current) -> current <= previous } &&
            evidence.recentHighs.last() < evidence.recentHighs.first()
        val noPriceProgress = evidence.gain5Pct <= max(1.0, evidence.atrPct * 0.5)
        val volumeWithoutProgress = volumeConfirmed >= BREAKOUT_VOLUME_RATIO && noPriceProgress
        val supportBroken = evidence.price < evidence.support
        if (lowerHighs || volumeWithoutProgress || supportBroken) {
            return decision(
                phase = HighPositionPhase.DISTRIBUTION_RISK,
                reason = when {
                    supportBroken -> "高位区间支撑已经跌破"
                    lowerHighs -> "高位高点逐步下移，出现派发风险"
                    else -> "放量但价格缺乏进展，出现滞涨派发风险"
                },
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = false,
                riskPriority = true,
            )
        }
        val stableRange = rangeWidthPct <= max(MAX_RANGE_WIDTH_PCT, evidence.atrPct * 3.0)
        val contractedPullback = evidence.pullbackVolumeRatio?.let { it <= PULLBACK_VOLUME_RATIO } == true
        val higherLows = evidence.recentLows.size >= 3 &&
            evidence.recentLows.zipWithNext().all { (previous, current) -> current >= previous } &&
            evidence.recentLows.last() > evidence.recentLows.first()
        val consolidation = stableRange && contractedPullback && higherLows
        if (!consolidation) {
            return decision(
                phase = HighPositionPhase.EXTENDED_WATCH,
                reason = "处于高位区间，但整理质量不足，不视为二波结构",
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = false,
                riskPriority = false,
            )
        }
        val closesRising = evidence.recentCloses.size >= 2 &&
            evidence.recentCloses.last() > evidence.recentCloses[evidence.recentCloses.lastIndex - 1]
        val candidate = volumeConfirmed >= REBOUND_VOLUME_RATIO &&
            closesRising &&
            evidence.marketHealthy &&
            evidence.sectorHealthy &&
            trendCanEnter
        return if (candidate) {
            decision(
                phase = HighPositionPhase.SECOND_WAVE_CANDIDATE,
                reason = "高位缩量整理后出现量能配合的抬高低点，等待突破确认",
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = true,
                riskPriority = false,
            )
        } else {
            decision(
                phase = HighPositionPhase.CONSOLIDATION,
                reason = "高位回踩缩量且低点抬高，先保留底仓等待突破",
                allowBuy = false,
                allowSecondWaveEntry = false,
                allowHoldBase = true,
                riskPriority = false,
            )
        }
    }

    private fun valid(evidence: HighPositionEvidence): Boolean {
        val finitePositive = listOf(
            evidence.price,
            evidence.rangeHigh,
            evidence.rangeLow,
            evidence.atrPct,
            evidence.support,
        )
        return finitePositive.all { it.isFinite() && it > 0.0 } &&
            evidence.rangeHigh > evidence.rangeLow &&
            evidence.gain5Pct.isFinite() &&
            (evidence.volumeRatio == null || evidence.volumeRatio.isFinite()) &&
            (evidence.pullbackVolumeRatio == null || evidence.pullbackVolumeRatio.isFinite()) &&
            evidence.recentLows.all { it.isFinite() && it > 0.0 } &&
            evidence.recentHighs.all { it.isFinite() && it > 0.0 } &&
            evidence.recentCloses.all { it.isFinite() && it > 0.0 }
    }

    private fun decision(
        phase: HighPositionPhase,
        reason: String,
        allowBuy: Boolean,
        allowSecondWaveEntry: Boolean,
        allowHoldBase: Boolean,
        riskPriority: Boolean,
    ) = HighPositionDecision(
        phase = phase,
        reason = reason,
        allowBuy = allowBuy,
        allowSecondWaveEntry = allowSecondWaveEntry,
        allowHoldBase = allowHoldBase,
        riskPriority = riskPriority,
    )
}
