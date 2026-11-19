package com.example.stockmonitor.core

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 只由当前已完成的15分钟K线形成的回踩阶段量能快照。
 *
 * 基线固定在局部峰值之前，回踩窗口只覆盖峰后至局部低点的K线；后续实时行情、未完成K线和
 * 全天累计量比都不会改变这个对象。任一窗口、连续性或结构条件缺失时保持未确认，禁止降级推断。
 */
data class PullbackPhaseVolumeEvidence(
    val peakTimestamp: String? = null,
    val troughTimestamp: String? = null,
    val confirmationTimestamp: String? = null,
    val baselineMedianVolume: Double? = null,
    val pullbackAverageVolume: Double? = null,
    val pullbackToBaselineRatio: Double? = null,
    val confirmationToBaselineRatio: Double? = null,
    val troughWithinSupportBand: Boolean = false,
    val confirmationReclaimedKeyLines: Boolean = false,
) {
    val pullbackShrunk: Boolean
        get() = pullbackToBaselineRatio?.let { it > 0.0 && it <= PULLBACK_SHRINK_RATIO } == true

    val confirmationExpanded: Boolean
        get() = confirmationToBaselineRatio?.let { it >= CONFIRMATION_EXPAND_RATIO } == true

    /** 回踩缩量、支撑带低点和最后一根完成确认K缺一不可。 */
    val confirmed: Boolean
        get() = peakTimestamp != null && troughTimestamp != null && confirmationTimestamp != null &&
            pullbackShrunk && troughWithinSupportBand &&
            confirmationExpanded && confirmationReclaimedKeyLines

    companion object {
        const val PULLBACK_SHRINK_RATIO = 0.8
        const val CONFIRMATION_EXPAND_RATIO = 1.3
        private const val BASELINE_BARS = 6
        private const val MIN_PULLBACK_BARS = 2
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun build(
            bars15: List<IntradayBar>,
            support: Double,
            ma5: Double,
            ma20: Double,
            atrPct: Double?,
            now: LocalDateTime,
            quoteDate: String?,
        ): PullbackPhaseVolumeEvidence {
            if (!support.isPositiveFinite() || !ma5.isPositiveFinite() || !ma20.isPositiveFinite()) {
                return PullbackPhaseVolumeEvidence()
            }
            val completed = IntradayBarWindow(bars15)
                .completedAt(now, quoteDate, 15)
                .bars
                .sortedBy { it.timestamp }
            if (completed.size < BASELINE_BARS + MIN_PULLBACK_BARS + 2) {
                return PullbackPhaseVolumeEvidence()
            }
            val peakIndex = findLatestLocalPeak(completed) ?: return PullbackPhaseVolumeEvidence()
            val baselineStart = peakIndex - BASELINE_BARS
            if (baselineStart < 0 || peakIndex + MIN_PULLBACK_BARS >= completed.lastIndex) {
                return PullbackPhaseVolumeEvidence()
            }
            // 只要证据依赖的任意已完成槽位缺失，就不把零散数据拼成阶段证据。
            val evidenceBars = completed.subList(baselineStart, completed.lastIndex + 1)
            if (!evidenceBars.isContinuous15MinuteSeries()) return PullbackPhaseVolumeEvidence()

            val troughIndex = findLocalTrough(completed, peakIndex) ?: return PullbackPhaseVolumeEvidence()
            val baseline = completed.subList(baselineStart, peakIndex)
            val pullback = completed.subList(peakIndex + 1, troughIndex + 1)
            val confirmation = completed.last()
            if (baseline.any { !it.volume.isPositiveFinite() } ||
                pullback.size < MIN_PULLBACK_BARS ||
                pullback.any { !it.volume.isPositiveFinite() } ||
                !confirmation.volume.isPositiveFinite()
            ) {
                return PullbackPhaseVolumeEvidence()
            }
            val baselineMedian = baseline.map { it.volume }.sorted().medianOrNull() ?: return PullbackPhaseVolumeEvidence()
            val pullbackAverage = pullback.map { it.volume }.average().takeIf { it.isPositiveFinite() }
                ?: return PullbackPhaseVolumeEvidence()
            val pullbackRatio = pullbackAverage / baselineMedian
            val confirmationRatio = confirmation.volume / baselineMedian
            val bandPct = supportBandPct(atrPct)
            val lower = support * (1.0 - bandPct / 100.0)
            val upper = support * (1.0 + bandPct / 100.0)
            val trough = completed[troughIndex]
            val troughNearSupport = trough.low >= lower && trough.low <= upper
            val confirmationReclaimed = confirmation.close >= support &&
                confirmation.close >= ma5 &&
                confirmation.close >= ma20 * (1.0 - bandPct / 100.0)
            return PullbackPhaseVolumeEvidence(
                peakTimestamp = completed[peakIndex].timestamp,
                troughTimestamp = trough.timestamp,
                confirmationTimestamp = confirmation.timestamp,
                baselineMedianVolume = baselineMedian,
                pullbackAverageVolume = pullbackAverage,
                pullbackToBaselineRatio = pullbackRatio,
                confirmationToBaselineRatio = confirmationRatio,
                troughWithinSupportBand = troughNearSupport,
                confirmationReclaimedKeyLines = confirmationReclaimed,
            )
        }

        private fun findLatestLocalPeak(bars: List<IntradayBar>): Int? {
            for (index in bars.lastIndex - 1 downTo BASELINE_BARS) {
                if (bars[index].high >= bars[index - 1].high && bars[index].high > bars[index + 1].high) {
                    return index
                }
            }
            return null
        }

        private fun findLocalTrough(bars: List<IntradayBar>, peakIndex: Int): Int? {
            val candidates = (peakIndex + MIN_PULLBACK_BARS..bars.lastIndex - 1)
                .filter { index ->
                    bars[index].low <= bars[index - 1].low && bars[index].low < bars[index + 1].low
                }
            return candidates.minByOrNull { bars[it].low }
        }

        private fun supportBandPct(atrPct: Double?): Double {
            val validAtr = atrPct?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            return maxOf(0.5, validAtr * 0.3).coerceAtMost(2.0)
        }

        private fun List<IntradayBar>.isContinuous15MinuteSeries(): Boolean {
            return zipWithNext().all { (previous, current) ->
                val previousAt = parseTimestamp(previous.timestamp)
                val currentAt = parseTimestamp(current.timestamp)
                previousAt != null && currentAt != null &&
                    (currentAt == previousAt.plusMinutes(15) ||
                        (previousAt.toLocalTime() == LocalTime.of(11, 30) &&
                            currentAt == previousAt.toLocalDate().atTime(13, 15)))
            }
        }

        private fun parseTimestamp(value: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(value, TIMESTAMP_FORMAT) }.getOrNull()

        private fun List<Double>.medianOrNull(): Double? {
            if (isEmpty()) return null
            val middle = size / 2
            return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2.0
        }
    }
}

data class IntradayVolumeContext(
    val marketTurnoverRatio: Double? = null,
    val marketExpanding: Boolean? = null,
    val stockVolumeRatio: Double? = null,
    val stockVolumeConfirming: Boolean? = null,
    val barVolumeRatio: Double? = null,
    val barVolumeConfirming: Boolean? = null,
    val absorptionConfirmed: Boolean = false,
    val blowoffRisk: Boolean = false,
) {
    companion object {
        const val MARKET_EXPANDING_RATIO = 1.2
        const val STOCK_CONFIRMING_RATIO = 1.3
        const val BAR_CONFIRMING_RATIO = 1.3
        const val ABSORPTION_LOWER_SHADOW_RATIO = 0.35
        const val BLOWOFF_UPPER_SHADOW_RATIO = 0.50

        fun build(
            marketPredictedFullDayTurnover: Double? = null,
            recentCompleteMarketTurnovers: List<Double> = emptyList(),
            stockVolumeRatio: Double? = null,
            priceChangePct: Double? = null,
            bars15: List<IntradayBar> = emptyList(),
            price: Double? = null,
            support: Double? = null,
            now: LocalDateTime,
            quoteDate: String?,
        ): IntradayVolumeContext {
            val marketBaseline = recentCompleteMarketTurnovers
                .filter { it.isPositiveFinite() }
                .takeLast(5)
                .sorted()
                .medianOrNull()
            val marketRatio = ratio(marketPredictedFullDayTurnover, marketBaseline)
            val stockRatio = stockVolumeRatio?.takeIf { it.isPositiveFinite() }
            val stockConfirming = stockRatio?.let { it >= STOCK_CONFIRMING_RATIO }
            val validBars = IntradayBarWindow(bars15)
                .completedAt(now, quoteDate, 15)
                .bars
            val current = validBars.lastOrNull()
            val previous = validBars.dropLast(1).takeLast(20)
            val previousMedian = previous.map { it.volume }.filter { it.isPositiveFinite() }.sorted().medianOrNull()
            val barRatio = if (current != null && previousMedian != null && current.volume.isPositiveFinite()) {
                current.volume / previousMedian
            } else {
                null
            }
            val barConfirming = barRatio?.let { it >= BAR_CONFIRMING_RATIO }
            var absorption = false
            var blowoff = false
            if (current != null && previous.size >= 20) {
                val currentPrice = price
                val supportPrice = support
                val range = current.high - current.low
                val midpoint = (current.high + current.low) / 2.0
                val lowerShadow = minOf(current.open, current.close) - current.low
                val upperShadow = current.high - maxOf(current.open, current.close)
                val previousClose = previous.last().close
                absorption = barConfirming == true &&
                    currentPrice != null && currentPrice.isFinite() && currentPrice > 0.0 &&
                    supportPrice != null && supportPrice.isFinite() && supportPrice > 0.0 &&
                    currentPrice < supportPrice && current.close < supportPrice &&
                    range > 0.0 && lowerShadow / range >= ABSORPTION_LOWER_SHADOW_RATIO &&
                    current.close >= midpoint &&
                    current.close >= previousClose * 0.99
                blowoff = barConfirming == true &&
                    priceChangePct != null && priceChangePct.isFinite() && priceChangePct > 0.0 &&
                    range > 0.0 && upperShadow / range >= BLOWOFF_UPPER_SHADOW_RATIO &&
                    current.close < midpoint
            }
            return IntradayVolumeContext(
                marketTurnoverRatio = marketRatio,
                marketExpanding = marketRatio?.let { it >= MARKET_EXPANDING_RATIO },
                stockVolumeRatio = stockRatio,
                stockVolumeConfirming = stockConfirming,
                barVolumeRatio = barRatio,
                barVolumeConfirming = barConfirming,
                absorptionConfirmed = absorption,
                blowoffRisk = blowoff,
            )
        }

        private fun ratio(value: Double?, baseline: Double?): Double? {
            if (value == null || baseline == null) return null
            return if (value.isPositiveFinite() && baseline.isPositiveFinite()) value / baseline else null
        }

        private fun List<Double>.medianOrNull(): Double? {
            if (isEmpty()) return null
            val middle = size / 2
            return if (size % 2 == 1) this[middle] else (this[middle - 1] + this[middle]) / 2.0
        }
    }
}

private fun Double?.isPositiveFinite(): Boolean =
    this != null && isFinite() && this > 0.0
