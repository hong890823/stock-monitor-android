package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

enum class FiveOneThreeStage {
    OBSERVING,
    WAITING_CONFIRMATION,
    CONFIRMED,
}

data class FiveOneThreeHit(
    val code: String,
    val name: String,
    val price: Double,
    val stage: FiveOneThreeStage,
    val progress: String,
    val detail: String,
    val gain60Pct: Double,
)

data class FiveOneThreeInspection(
    val historyAvailable: Boolean,
    val historicalFiveBullish: Boolean,
    val recentFiveBullish: Boolean,
    val hit: FiveOneThreeHit?,
)

object FiveOneThreeScreener {

    fun evaluate(
        code: String,
        name: String,
        bars: List<DayBar>,
        today: String? = null,
        currentPrice: Double = Double.NaN,
    ): FiveOneThreeHit? = inspect(code, name, bars, today, currentPrice).hit

    fun inspect(
        code: String,
        name: String,
        bars: List<DayBar>,
        today: String? = null,
        currentPrice: Double = Double.NaN,
    ): FiveOneThreeInspection {
        val completed = bars.filter { it.date != today && it.close > 0.0 && it.open > 0.0 && it.volume > 0.0 }
        if (completed.size < MIN_HISTORY_DAYS) {
            return FiveOneThreeInspection(false, false, false, null)
        }
        if (name.contains("ST", ignoreCase = true)) {
            return FiveOneThreeInspection(true, false, false, null)
        }

        val historicalFiveBullish = (FIVE_DAYS - 1..completed.lastIndex).any { end ->
            isFiveBullish(completed, end)
        }
        val recentFiveBullish = isFiveBullish(completed, completed.lastIndex)
        val hit = evaluateCompleted(code, name, completed, currentPrice)
        return FiveOneThreeInspection(true, historicalFiveBullish, recentFiveBullish, hit)
    }

    private fun evaluateCompleted(
        code: String,
        name: String,
        completed: List<DayBar>,
        currentPrice: Double,
    ): FiveOneThreeHit? {

        val window = completed.takeLast(60)
        val baseClose = window.first().close
        val latestClose = window.last().close
        val gain60Pct = (latestClose / baseClose - 1.0) * 100.0
        val range = window.maxOf { it.high } - window.minOf { it.low }
        val position = if (range > 0.0) {
            (latestClose - window.minOf { it.low }) / range
        } else {
            1.0
        }
        if (gain60Pct > MAX_GAIN_60D_PCT || position >= MAX_RANGE_POSITION) return null

        val candidates = (FIVE_DAYS - 1..completed.lastIndex).mapNotNull { end ->
            if (!isFiveBullish(completed, end)) return@mapNotNull null
            if (end == completed.lastIndex) {
                return@mapNotNull candidate(
                    code = code,
                    name = name,
                    price = currentPrice.takeIf { it > 0.0 } ?: latestClose,
                    stage = FiveOneThreeStage.OBSERVING,
                    confirmations = 0,
                    gain60Pct = gain60Pct,
                )
            }

            val signalIndex = end + 1
            if (!isDoubleVolumeBullish(completed, signalIndex)) return@mapNotNull null
            val availableConfirmations = (signalIndex + 1..minOf(signalIndex + CONFIRMATION_DAYS, completed.lastIndex))
            var confirmations = 0
            for (index in availableConfirmations) {
                if (!isShrinkingConfirmation(completed, signalIndex, index)) break
                confirmations++
            }
            val stage = when {
                confirmations == CONFIRMATION_DAYS -> FiveOneThreeStage.CONFIRMED
                confirmations == availableConfirmations.count() && confirmations > 0 -> FiveOneThreeStage.WAITING_CONFIRMATION
                availableConfirmations.isEmpty() -> FiveOneThreeStage.WAITING_CONFIRMATION
                else -> return@mapNotNull null
            }
            // 513 是短线形态：倍量阳信号日（含缩量确认）距最新完整交易日太远的不再列出，
            // 否则十天前的「形态已确认」会一直挂在列表里冒充今天的买点。
            val decisiveIndex = signalIndex + confirmations
            if (completed.lastIndex - decisiveIndex > MAX_PATTERN_AGE_DAYS) return@mapNotNull null
            candidate(
                code = code,
                name = name,
                price = currentPrice.takeIf { it > 0.0 } ?: latestClose,
                stage = stage,
                confirmations = confirmations,
                gain60Pct = gain60Pct,
            )
        }
        return candidates.lastOrNull()
    }

    private fun candidate(
        code: String,
        name: String,
        price: Double,
        stage: FiveOneThreeStage,
        confirmations: Int,
        gain60Pct: Double,
    ): FiveOneThreeHit = FiveOneThreeHit(
        code = code,
        name = name,
        price = price,
        stage = stage,
        progress = when (stage) {
            FiveOneThreeStage.OBSERVING -> "5连阳观察"
            FiveOneThreeStage.WAITING_CONFIRMATION -> "等待3天缩量"
            FiveOneThreeStage.CONFIRMED -> "形态已确认"
        },
        detail = "5连阳 5/5｜倍量阳 ${if (stage == FiveOneThreeStage.OBSERVING) 0 else 1}/1｜缩量确认 $confirmations/3",
        gain60Pct = gain60Pct,
    )

    private fun isFiveBullish(bars: List<DayBar>, end: Int): Boolean {
        if (end < FIVE_DAYS - 1) return false
        val start = end - FIVE_DAYS + 1
        val five = bars.subList(start, end + 1)
        val baselineVolumes = bars.subList(maxOf(0, start - BASELINE_DAYS), start)
            .map { it.volume }
            .filter { it.isFinite() && it > 0.0 }
        if (baselineVolumes.isEmpty()) return false
        val baseline = baselineVolumes.average()
        if (!baseline.isFinite() || baseline <= 0.0) return false
        if (five.any { bar ->
                val gain = bar.close / bar.open - 1.0
                bar.close <= bar.open || gain < MIN_SINGLE_GAIN || gain > MAX_SINGLE_GAIN ||
                    bar.volume > baseline * MAX_OBSERVATION_VOLUME_RATIO
            }) return false
        if (five.map { it.volume }.average() < baseline * MIN_OBSERVATION_VOLUME_RATIO) return false
        return five.withIndex().all { (offset, bar) ->
            val index = start + offset
            bar.close >= movingAverage(bars, index, MOVING_AVERAGE_DAYS)
        } && movingAverage(bars, end, MOVING_AVERAGE_DAYS) >=
            movingAverage(bars, start, MOVING_AVERAGE_DAYS)
    }

    private fun isDoubleVolumeBullish(bars: List<DayBar>, index: Int): Boolean {
        if (index <= 0 || index >= bars.size) return false
        val bar = bars[index]
        if (bar.close <= bar.open || bar.volume < bars[index - 1].volume * DOUBLE_VOLUME_RATIO) return false
        if (bar.close < movingAverage(bars, index, MOVING_AVERAGE_DAYS)) return false
        return (bar.high - bar.close) / bar.high.coerceAtLeast(bar.close) <= MAX_PULLBACK_RATIO
    }

    private fun isShrinkingConfirmation(bars: List<DayBar>, signalIndex: Int, index: Int): Boolean {
        if (index <= signalIndex || index >= bars.size) return false
        val bar = bars[index]
        val previous = bars[index - 1]
        val signal = bars[signalIndex]
        val amplitude = (bar.high - bar.low) / previous.close.coerceAtLeast(0.0001)
        return bar.volume < previous.volume &&
            bar.volume < signal.volume &&
            bar.close >= signal.open &&
            amplitude <= MAX_CONFIRMATION_AMPLITUDE
    }

    private fun movingAverage(bars: List<DayBar>, end: Int, days: Int): Double {
        val start = maxOf(0, end - days + 1)
        return bars.subList(start, end + 1).map { it.close }.average()
    }

    private const val MIN_HISTORY_DAYS = 60
    private const val FIVE_DAYS = 5
    private const val CONFIRMATION_DAYS = 3
    private const val BASELINE_DAYS = 20
    private const val MOVING_AVERAGE_DAYS = 5
    private const val MIN_SINGLE_GAIN = 0.01
    private const val MAX_SINGLE_GAIN = 0.03
    private const val MAX_GAIN_60D_PCT = 30.0
    private const val MAX_RANGE_POSITION = 0.85
    private const val MAX_OBSERVATION_VOLUME_RATIO = 1.8
    private const val MIN_OBSERVATION_VOLUME_RATIO = 0.65
    private const val DOUBLE_VOLUME_RATIO = 2.0
    private const val MAX_PULLBACK_RATIO = 0.06
    private const val MAX_CONFIRMATION_AMPLITUDE = 0.05

    /** 信号日（含确认）距最新完整交易日超过这个天数，形态不再列出。 */
    private const val MAX_PATTERN_AGE_DAYS = 3
}
