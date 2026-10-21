package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.time.LocalDate

enum class ShortTermTrend(val label: String) {
    UP("短期向上"),
    EXTENDED("短期连续拉升"),
    REBOUND("短期修复反弹"),
    DOWN("短期向下"),
    RANGE("短期震荡"),
    MISSING("短期数据不足"),
}

enum class MajorTrend(val label: String) {
    UP("大趋势向上"),
    DOWN("大趋势向下"),
    BOTTOMING("大趋势筑底"),
    REPAIR("大趋势修复"),
    MISSING("大趋势数据不足"),
}

data class TrendEvidence(
    val shortTerm: HoldingTTrend,
    val weekTrend: SwingTrend,
    val monthTrend: SwingTrend,
    val price: Double,
    val ma20: Double,
    val previousMa20: Double,
    val ma250: Double?,
    val completedLows: List<Double>,
)

data class TrendAssessment(
    val shortTerm: ShortTermTrend,
    val major: MajorTrend,
    val reason: String,
    val allowsOrdinaryBuy: Boolean,
    val allowsAddPosition: Boolean,
    val allowsTBuy: Boolean,
    val allowsTSell: Boolean,
    val allowsBottomReversal: Boolean,
    val riskOnly: Boolean,
) {
    val summary: String get() = "${major.label}｜${shortTerm.label}｜$reason"
}

object TrendAssessmentPolicy {
    private val executableShortTerm = setOf(
        ShortTermTrend.UP,
        ShortTermTrend.EXTENDED,
        ShortTermTrend.REBOUND,
    )

    fun evaluate(evidence: TrendEvidence): TrendAssessment {
        val shortTerm = shortTermOf(evidence.shortTerm)
        val major = majorOf(evidence)
        val ordinaryAllowed = major == MajorTrend.UP && shortTerm in executableShortTerm
        val addPositionAllowed = major == MajorTrend.UP &&
            shortTerm in setOf(ShortTermTrend.UP, ShortTermTrend.EXTENDED)
        val bottomReversalAllowed = major == MajorTrend.BOTTOMING && shortTerm in executableShortTerm
        return TrendAssessment(
            shortTerm = shortTerm,
            major = major,
            reason = reasonOf(major, evidence),
            allowsOrdinaryBuy = ordinaryAllowed,
            allowsAddPosition = addPositionAllowed,
            allowsTBuy = ordinaryAllowed,
            allowsTSell = ordinaryAllowed,
            allowsBottomReversal = bottomReversalAllowed,
            riskOnly = major == MajorTrend.DOWN,
        )
    }

    fun evaluateBars(history: List<DayBar>, asOf: String): TrendAssessment {
        val date = runCatching { LocalDate.parse(asOf) }.getOrNull()
            ?: return missing("行情日期无效")
        val completed = history.filter {
            runCatching { LocalDate.parse(it.date) }.getOrNull()?.isBefore(date) == true
        }.sortedBy { it.date }
        val valid = completed.size >= 30 &&
            completed.distinctBy { it.date }.size == completed.size &&
            completed.all { bar ->
                runCatching { LocalDate.parse(bar.date) }.isSuccess &&
                    listOf(bar.open, bar.high, bar.low, bar.close).all { it.isFinite() && it > 0.0 } &&
                    bar.volume.isFinite() && bar.volume >= 0.0 &&
                    bar.high >= maxOf(bar.open, bar.close) &&
                    bar.low <= minOf(bar.open, bar.close)
            }
        if (!valid) return missing("已完成日线不足30根或OHLCV异常")
        val closes = completed.map { it.close }
        val ma5 = closes.takeLast(5).average()
        val ma10 = closes.takeLast(10).average()
        val ma20 = closes.takeLast(20).average()
        val previousMa20 = closes.dropLast(1).takeLast(20).average()
        val atr = Indicators.atr(
            completed.map { it.high },
            completed.map { it.low },
            closes,
        ).lastOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: return missing("ATR数据不足")
        val atrPct = atr / closes.last() * 100.0
        val gain5 = (closes.last() / closes[closes.lastIndex - 5] - 1.0) * 100.0
        val risingDays = closes.takeLast(6).zipWithNext().count { (previous, current) -> current > previous }
        val shortTerm = when {
            closes.last() < ma20 * 0.98 ||
                (closes.last() < ma20 * 0.995 && closes[closes.lastIndex - 1] < previousMa20 * 0.995) ->
                HoldingTTrend.WEAK
            gain5 >= maxOf(6.0, atrPct * 3.0) && risingDays >= 3 -> HoldingTTrend.EXTENDED
            closes.last() >= ma5 && ma5 > ma10 && ma10 > ma20 && ma20 > previousMa20 -> HoldingTTrend.UP
            closes.last() > closes[closes.lastIndex - 1] &&
                ma5 > closes.dropLast(1).takeLast(5).average() -> HoldingTTrend.REBOUND
            else -> HoldingTTrend.RANGE
        }
        val cycles = MultiTimeframeTrend.evaluate(history, asOf)
        return evaluate(
            TrendEvidence(
                shortTerm = shortTerm,
                weekTrend = cycles.weekTrend,
                monthTrend = cycles.monthTrend,
                price = closes.last(),
                ma20 = ma20,
                previousMa20 = previousMa20,
                ma250 = cycles.ma250,
                completedLows = completed.map { it.low },
            ),
        )
    }

    private fun shortTermOf(trend: HoldingTTrend): ShortTermTrend = when (trend) {
        HoldingTTrend.UP -> ShortTermTrend.UP
        HoldingTTrend.EXTENDED -> ShortTermTrend.EXTENDED
        HoldingTTrend.REBOUND -> ShortTermTrend.REBOUND
        HoldingTTrend.WEAK -> ShortTermTrend.DOWN
        HoldingTTrend.RANGE -> ShortTermTrend.RANGE
    }

    private fun majorOf(evidence: TrendEvidence): MajorTrend {
        val values = listOf(
            evidence.price,
            evidence.ma20,
            evidence.previousMa20,
            evidence.ma250 ?: Double.NaN,
        )
        if (values.any { !it.isFinite() || it <= 0.0 } ||
            evidence.completedLows.any { !it.isFinite() || it <= 0.0 } ||
            evidence.weekTrend == SwingTrend.MISSING ||
            evidence.monthTrend == SwingTrend.MISSING
        ) {
            return MajorTrend.MISSING
        }
        val ma250 = requireNotNull(evidence.ma250)
        if (evidence.weekTrend == SwingTrend.UP &&
            evidence.monthTrend in setOf(SwingTrend.UP, SwingTrend.REPAIR) &&
            evidence.price >= ma250
        ) {
            return MajorTrend.UP
        }
        if (evidence.weekTrend == SwingTrend.WEAK && evidence.monthTrend == SwingTrend.WEAK ||
            evidence.price < ma250 &&
            evidence.weekTrend == SwingTrend.WEAK &&
            evidence.monthTrend == SwingTrend.WEAK
        ) {
            return MajorTrend.DOWN
        }
        val baselineWeak = evidence.weekTrend == SwingTrend.WEAK ||
            evidence.monthTrend == SwingTrend.WEAK ||
            evidence.price < ma250
        if (baselineWeak &&
            evidence.weekTrend != SwingTrend.WEAK &&
            evidence.ma20 >= evidence.previousMa20 &&
            evidence.price >= evidence.ma20 &&
            hasFiveDaysWithoutNewLow(evidence.completedLows)
        ) {
            return MajorTrend.BOTTOMING
        }
        return MajorTrend.REPAIR
    }

    private fun hasFiveDaysWithoutNewLow(lows: List<Double>): Boolean {
        if (lows.size < 6) return false
        val previousLow = lows.dropLast(5).minOrNull() ?: return false
        return lows.takeLast(5).all { it >= previousLow }
    }

    private fun reasonOf(major: MajorTrend, evidence: TrendEvidence): String = when (major) {
        MajorTrend.UP -> "周线向上，月线不反向，价格位于250日线上方"
        MajorTrend.DOWN -> "周线与月线偏弱，停止普通买入和做T"
        MajorTrend.BOTTOMING ->
            "连续5个交易日未创新低，MA20走平或上行且价格站上MA20"
        MajorTrend.REPAIR -> "多周期证据仍不完整，继续观察"
        MajorTrend.MISSING -> "趋势历史数据不足，不授权交易"
    }

    private fun missing(reason: String): TrendAssessment = TrendAssessment(
        shortTerm = ShortTermTrend.MISSING,
        major = MajorTrend.MISSING,
        reason = reason,
        allowsOrdinaryBuy = false,
        allowsAddPosition = false,
        allowsTBuy = false,
        allowsTSell = false,
        allowsBottomReversal = false,
        riskOnly = false,
    )
}
