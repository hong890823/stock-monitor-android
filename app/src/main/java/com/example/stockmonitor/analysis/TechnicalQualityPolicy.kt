package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

data class TechnicalQualityResult(
    val score: Int,
    val dailyPoints: Int,
    val weeklyPoints: Int,
    val monthlyPoints: Int,
    val weeklyTrend: SwingTrend,
    val monthlyTrend: SwingTrend,
) {
    val trendLabel: String get() = "周K${weeklyTrend.label}｜月K${monthlyTrend.label}"
}

/**
 * 统一技术分。日线只占 40 分，完整周 K/月 K 各占 30 分，避免只凭当天形态拿高分。
 */
object TechnicalQualityPolicy {
    private const val DAILY_MAX = 40
    private const val WEEKLY_MAX = 30
    private const val MONTHLY_MAX = 30
    private const val TREND_WEAK_CAP = 45

    fun score(
        dailyEvidenceRatio: Double,
        weeklyTrend: SwingTrend,
        monthlyTrend: SwingTrend,
        trendWeak: Boolean = false,
    ): TechnicalQualityResult {
        val dailyRatio = dailyEvidenceRatio.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val dailyPoints = (dailyRatio * DAILY_MAX).roundToInt()
        val weeklyPoints = when (weeklyTrend) {
            SwingTrend.UP -> WEEKLY_MAX
            SwingTrend.REPAIR -> 18
            SwingTrend.STABILIZING -> 12
            SwingTrend.WEAK, SwingTrend.MISSING -> 0
        }
        val monthlyPoints = when (monthlyTrend) {
            SwingTrend.UP -> MONTHLY_MAX
            SwingTrend.REPAIR -> 20
            SwingTrend.STABILIZING -> 14
            SwingTrend.WEAK, SwingTrend.MISSING -> 0
        }
        val raw = dailyPoints + weeklyPoints + monthlyPoints
        return TechnicalQualityResult(
            score = if (trendWeak) minOf(raw, TREND_WEAK_CAP) else raw,
            dailyPoints = dailyPoints,
            weeklyPoints = weeklyPoints,
            monthlyPoints = monthlyPoints,
            weeklyTrend = weeklyTrend,
            monthlyTrend = monthlyTrend,
        )
    }

    fun evaluateBars(
        history: List<DayBar>,
        asOf: String,
        dailyEvidenceRatio: Double,
        trendWeak: Boolean = false,
    ): TechnicalQualityResult {
        val cycles = MultiTimeframeTrend.evaluate(history, asOf)
        return score(
            dailyEvidenceRatio = dailyEvidenceRatio,
            weeklyTrend = cycles.weekTrend,
            monthlyTrend = cycles.monthTrend,
            trendWeak = trendWeak,
        )
    }

    /** 入场结构的三项日线证据口径；选股、自选股和持仓股都必须用同一算法。 */
    fun dailyEvidenceRatio(entrySetup: EntrySetup): Double =
        listOf(
            entrySetup.type != EntrySetupType.NONE,
            entrySetup.confirmed,
            entrySetup.volumeConfirmed,
        ).count { it } / 3.0

    /**
     * 日线技术质量不等同于“是否出现买点”。即使还没有入场结构，也要看均线、近5日动量、
     * 价格位置和成交量，否则自选股/持仓股没有买点时技术分会误显示为 0。
     */
    fun dailyEvidenceRatio(
        history: List<DayBar>,
        asOf: String,
        entrySetup: EntrySetup,
    ): Double {
        val asOfDate = runCatching { LocalDate.parse(asOf) }.getOrNull()
            ?: return dailyEvidenceRatio(entrySetup)
        val completed = history
            .mapNotNull { bar ->
                val barDate = runCatching { LocalDate.parse(bar.date) }.getOrNull()
                    ?: return@mapNotNull null
                bar.takeIf { barDate <= asOfDate }
            }
            .distinctBy { it.date }
            .sortedBy { it.date }
        if (completed.size < 30) return dailyEvidenceRatio(entrySetup)
        val closes = completed.map { it.close }
        val movingAverage5 = closes.takeLast(5).average()
        val movingAverage10 = closes.takeLast(10).average()
        val movingAverage20 = closes.takeLast(20).average()
        val previousMovingAverage20 = closes.dropLast(1).takeLast(20).average()
        val trendSignals = listOf(
            closes.last() >= movingAverage5,
            movingAverage5 >= movingAverage10,
            movingAverage10 >= movingAverage20,
            movingAverage20 >= previousMovingAverage20,
        )
        val fiveDayGain = if (closes.size > 5) (closes.last() / closes[closes.lastIndex - 5] - 1.0) else 0.0
        val risingDays = closes.takeLast(6).zipWithNext().count { (previous, current) -> current > previous }
        val momentumSignals = listOf(
            fiveDayGain > 0.0,
            closes.last() > closes[closes.lastIndex - 1],
            risingDays >= 3,
        )
        val recentCloses = closes.takeLast(20)
        val recentLow = recentCloses.min()
        val recentHigh = recentCloses.max()
        val rangePosition = if (recentHigh > recentLow) {
            (closes.last() - recentLow) / (recentHigh - recentLow)
        } else {
            0.5
        }
        val positionSignals = listOf(
            closes.last() >= (recentLow + recentHigh) / 2.0,
            rangePosition >= 0.65,
        )
        val recentVolumes = completed.map { it.volume }
        val averageVolume = recentVolumes.dropLast(1).takeLast(20).average()
        val volumeSignals = listOf(
            recentVolumes.last() >= averageVolume * 1.20,
            recentVolumes.last() >= averageVolume * 0.80,
        )
        val dailyKRatio = trendSignals.count { it } / trendSignals.size * 0.45 +
            momentumSignals.count { it } / momentumSignals.size * 0.25 +
            positionSignals.count { it } / positionSignals.size * 0.15 +
            volumeSignals.count { it } / volumeSignals.size * 0.15
        return max(dailyKRatio, dailyEvidenceRatio(entrySetup))
    }

}
