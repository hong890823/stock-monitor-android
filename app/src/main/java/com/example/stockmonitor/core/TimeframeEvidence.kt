package com.example.stockmonitor.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class TimeframeEvidence(
    val m15Confirmations: Int = 0,
    val m30Aligned: Boolean = false,
    val complete: Boolean = false,
    val riskComplete: Boolean = false,
    val riskConfirmed: Boolean = false,
    val lastCompleted15m: String? = null,
    val lastCompleted30m: String? = null,
    val m15DistancePct: Double = 0.0,
    val m30DistancePct: Double = 0.0,
    val lastBarRejected: Boolean = false,
    val clearanceConfirmed: Boolean = false,
    val riskConfirmations: Int = 0,
    val riskThresholdPct: Double = 0.005,
    val riskBreachPct: Double = 0.0,
    val riskLevel: DailyRiskLevel = DailyRiskLevel.NORMAL,
    val riskLine: Double? = null,
    val riskLineThresholdPct: Double = 0.0,
    val riskLineBelow: Boolean = false,
    val riskLineConfirmations: Int = 0,
    val riskLineM30Below: Boolean = false,
) {
    companion object {
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun latestCompletedSlot(now: LocalDateTime, minutes: Int, quoteDate: String?): String? {
            require(minutes == 5 || minutes == 15 || minutes == 30) { "分钟K线仅支持5、15或30分钟" }
            val date = runCatching { LocalDate.parse(quoteDate?.take(10).orEmpty()) }.getOrNull() ?: return null
            if (date != now.toLocalDate()) return null
            val slots = sessionSlots(date, minutes)
            return slots.lastOrNull { !it.isAfter(now) }?.format(TIMESTAMP_FORMAT)
        }

        fun build(
            bars15: List<IntradayBar>,
            bars30: List<IntradayBar>,
            price: Double,
            support: Double,
            resistance: Double,
            now: LocalDateTime,
            quoteDate: String?,
            dailyContext: DailyRiskContext? = null,
            riskLine: Double? = null,
        ): TimeframeEvidence {
            val latest15 = latestCompletedSlot(now, 15, quoteDate)
            val latest30 = latestCompletedSlot(now, 30, quoteDate)
            val completed15 = bars15.filter {
                it.valid && it.completed && it.timestamp <= (latest15 ?: "")
            }
            val completed30 = bars30.filter {
                it.valid && it.completed && it.timestamp <= (latest30 ?: "")
            }
            val fresh15 = latest15 != null && completed15.lastOrNull()?.timestamp == latest15
            val fresh30 = latest30 != null && completed30.lastOrNull()?.timestamp == latest30
            val breakout = resistance > 0.0 && price >= resistance
            val threshold = if (breakout) resistance else support
            val confirmations = if (threshold > 0.0) trailingConfirmations(completed15, threshold, breakout) else 0
            val m30Aligned = completed30.size >= 2 && support > 0.0 &&
                completed30.last().close >= completed30[completed30.lastIndex - 1].close &&
                completed30.last().close >= support
            val riskThresholdPct = riskThresholdPct(completed15, support)
            val riskPriceThreshold = support * (1.0 - riskThresholdPct)
            val riskConfirmed = support > 0.0 && fresh15 && completed15.isNotEmpty() &&
                price < riskPriceThreshold && completed15.last().close < riskPriceThreshold
            val riskConfirmations = if (riskConfirmed) riskConfirmations(completed15, price, riskPriceThreshold) else 0
            val activeRiskLine = riskLine?.takeIf { it.isFinite() && it > 0.0 }
            val riskLineThresholdPct = activeRiskLine?.let { riskThresholdPct(completed15, it) } ?: 0.0
            val riskLinePriceThreshold = activeRiskLine?.let { it * (1.0 - riskLineThresholdPct) }
            val riskLineConfirmations = if (riskLinePriceThreshold != null && fresh15) {
                riskConfirmations(completed15, price, riskLinePriceThreshold)
            } else {
                0
            }
            val riskLineM30Below = riskLinePriceThreshold != null &&
                fresh30 &&
                completed30.lastOrNull()?.close?.let { it < riskLinePriceThreshold } == true
            val riskLineBelow = riskLinePriceThreshold != null &&
                price < riskLinePriceThreshold
            val confirmedBars = completed15.takeLast(minOf(2, confirmations))
            val m15DistancePct = if (threshold > 0.0 && confirmedBars.isNotEmpty()) {
                confirmedBars.minOf { (it.close - threshold) / threshold }
            } else {
                0.0
            }
            val m30DistancePct = if (threshold > 0.0 && completed30.isNotEmpty()) {
                (completed30.last().close - threshold) / threshold
            } else {
                0.0
            }
            val lastBar = completed15.lastOrNull()
            val lastBarRejected = lastBar?.let {
                val range = it.high - it.low
                val upperShadow = it.high - maxOf(it.open, it.close)
                val midpoint = (it.high + it.low) / 2.0
                range > 0.0 && upperShadow / range >= 0.5 && it.close < midpoint
            } == true
            val context = dailyContext?.takeIf {
                it.sourceDate.take(10) == quoteDate?.take(10)
            }
            return TimeframeEvidence(
                m15Confirmations = confirmations,
                m30Aligned = m30Aligned,
                complete = completed15.size >= 2 && completed30.size >= 2 && fresh15 && fresh30,
                riskComplete = support > 0.0 && fresh15 && completed15.isNotEmpty(),
                riskConfirmed = riskConfirmed,
                lastCompleted15m = completed15.lastOrNull()?.timestamp,
                lastCompleted30m = completed30.lastOrNull()?.timestamp,
                m15DistancePct = m15DistancePct,
                m30DistancePct = m30DistancePct,
                lastBarRejected = lastBarRejected,
                clearanceConfirmed = confirmations >= 2 && m30Aligned &&
                    m15DistancePct >= 0.003 && m30DistancePct >= -0.003,
                riskConfirmations = riskConfirmations,
                riskThresholdPct = riskThresholdPct,
                riskBreachPct = if (support > 0.0) maxOf(0.0, (support - price) / support) else 0.0,
                riskLevel = context?.riskLevel(price) ?: DailyRiskLevel.NORMAL,
                riskLine = activeRiskLine,
                riskLineThresholdPct = riskLineThresholdPct,
                riskLineBelow = riskLineBelow,
                riskLineConfirmations = riskLineConfirmations,
                riskLineM30Below = riskLineM30Below,
            )
        }

        private fun sessionSlots(date: LocalDate, minutes: Int): List<LocalDateTime> {
            val result = ArrayList<LocalDateTime>()
            listOf(
                LocalDateTime.of(date, java.time.LocalTime.of(9, 30)) to LocalDateTime.of(date, java.time.LocalTime.of(11, 30)),
                LocalDateTime.of(date, java.time.LocalTime.of(13, 0)) to LocalDateTime.of(date, java.time.LocalTime.of(15, 0)),
            ).forEach { (start, end) ->
                var cursor = start.plusMinutes(minutes.toLong())
                while (!cursor.isAfter(end)) {
                    result += cursor
                    cursor = cursor.plusMinutes(minutes.toLong())
                }
            }
            return result
        }

        private fun trailingConfirmations(bars: List<IntradayBar>, threshold: Double, breakout: Boolean): Int {
            var count = 0
            for (bar in bars.asReversed()) {
                val confirmed = if (breakout) bar.close > threshold else bar.close >= threshold
                if (!confirmed) break
                count += 1
                if (count == 3) break
            }
            return count
        }

        private fun riskThresholdPct(bars: List<IntradayBar>, support: Double): Double {
            if (support <= 0.0 || bars.isEmpty()) return 0.005
            val recent = bars.takeLast(14)
            var previousClose: Double? = null
            val trueRanges = recent.map { bar ->
                val candidates = mutableListOf(bar.high - bar.low)
                previousClose?.let {
                    candidates += abs(bar.high - it)
                    candidates += abs(bar.low - it)
                }
                previousClose = bar.close
                candidates.max()
            }
            val atr = trueRanges.average()
            return (0.5 * atr / support).coerceIn(0.005, 0.015)
        }

        private fun riskConfirmations(bars: List<IntradayBar>, price: Double, threshold: Double): Int {
            if (bars.isEmpty() || price >= threshold) return 0
            var count = 0
            for (bar in bars.asReversed()) {
                if (bar.close >= threshold) break
                count += 1
                if (count == 3) break
            }
            return count
        }
    }
}
