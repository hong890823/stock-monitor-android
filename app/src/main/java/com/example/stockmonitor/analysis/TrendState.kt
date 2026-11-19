package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.time.LocalDate
import java.util.Locale

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
    /** 已聚合的多周期数据；仅用于解释周/月线风险，不改变分档。 */
    val cycles: MultiTimeframeSummary? = null,
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
    private const val MA20_STABILIZATION_TOLERANCE_PCT = 0.5

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

    /**
     * [cycles] 允许调用方传入已算好的多周期摘要：refreshTechnicalTrendSnapshots 这类
     * 「同一份 history 要出趋势结论 + 综合分」的链路里，MultiTimeframeTrend.evaluate
     * 只跑一次，两个消费者共用，省一半重活。
     */
    fun evaluateBars(history: List<DayBar>, asOf: String, cycles: MultiTimeframeSummary? = null): TrendAssessment {
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
        val effectiveCycles = cycles ?: MultiTimeframeTrend.evaluate(history, asOf)
        return evaluate(
            TrendEvidence(
                shortTerm = shortTerm,
                weekTrend = effectiveCycles.weekTrend,
                monthTrend = effectiveCycles.monthTrend,
                price = closes.last(),
                ma20 = ma20,
                previousMa20 = previousMa20,
                ma250 = effectiveCycles.ma250,
                completedLows = completed.map { it.low },
                cycles = effectiveCycles,
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
        if (evidence.weekTrend == SwingTrend.WEAK && evidence.monthTrend == SwingTrend.WEAK) {
            return if (dailyRepairing(evidence)) MajorTrend.REPAIR else MajorTrend.DOWN
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

    /** 周/月完整周期仍弱时，只有日线真正止跌且 MA20 接近走平，才降为“修复待确认”。 */
    private fun dailyStabilizing(evidence: TrendEvidence): Boolean =
        evidence.price >= evidence.ma20 &&
            evidence.ma20 >= evidence.previousMa20 *
            (1.0 - MA20_STABILIZATION_TOLERANCE_PCT / 100.0) &&
            hasFiveDaysWithoutNewLow(evidence.completedLows)

    /** 周/月仍弱时，日线连续拉升或恢复多头排列也属于修复尝试，但不能提前授权追高。 */
    private fun strongDailyRepair(evidence: TrendEvidence): Boolean =
        evidence.shortTerm in setOf(HoldingTTrend.UP, HoldingTTrend.EXTENDED) &&
            evidence.price >= evidence.ma20

    private fun dailyRepairing(evidence: TrendEvidence): Boolean =
        dailyStabilizing(evidence) || strongDailyRepair(evidence)

    private fun hasFiveDaysWithoutNewLow(lows: List<Double>): Boolean {
        if (lows.size < 6) return false
        val previousLow = lows.dropLast(5).minOrNull() ?: return false
        return lows.takeLast(5).all { it >= previousLow }
    }

    private fun reasonOf(major: MajorTrend, evidence: TrendEvidence): String = when (major) {
        MajorTrend.UP -> "周线向上，月线不反向，价格位于250日线上方"
        MajorTrend.DOWN -> cycleWeakReason(evidence)
        MajorTrend.BOTTOMING ->
            "连续5个交易日未创新低，MA20走平或上行且价格站上MA20"
        MajorTrend.REPAIR -> if (
            evidence.weekTrend == SwingTrend.WEAK &&
            evidence.monthTrend == SwingTrend.WEAK &&
            dailyRepairing(evidence)
        ) {
            cycleRepairReason(evidence)
        } else {
            "多周期证据仍不完整，继续观察"
        }
        MajorTrend.MISSING -> "趋势历史数据不足，不授权交易"
    }

    private fun cycleWeakReason(evidence: TrendEvidence): String =
        cycleEvidence(evidence) + "；未完成周/月K不计入趋势确认，停止普通买入和做T"

    private fun cycleRepairReason(evidence: TrendEvidence): String =
        cycleEvidence(evidence) +
            when {
                strongDailyRepair(evidence) ->
                    "；日线短期强势且站上MA20，但完整周/月周期尚未转强；" +
                        "修复待确认，不追高，等待回调企稳后再评估"
                else ->
                    "；日线已站上MA20且连续5日未创新低，但完整周/月周期尚未转强；" +
                        "修复待确认，不晋级正常买点"
            }

    private fun cycleEvidence(evidence: TrendEvidence): String {
        val details = buildList {
            val weekAverage = evidence.cycles?.weeks
                ?.takeLast(MultiTimeframeTrend.WEEK_TREND_PERIOD)
                ?.map { it.close }
                ?.average()
            val weekClose = evidence.cycles?.weeks?.lastOrNull()?.close
            if (weekClose != null && weekAverage != null && weekAverage.isFinite()) {
                val completed =
                    "周K收${formatCyclePrice(weekClose)}低于" +
                        "${MultiTimeframeTrend.WEEK_TREND_PERIOD}周均线${formatCyclePrice(weekAverage)}"
                val current = currentCycleNote("周", evidence.cycles?.currentWeek, weekAverage)
                add(if (current == null) completed else "$completed（$current）")
            }
            val monthAverage = evidence.cycles?.months
                ?.takeLast(MultiTimeframeTrend.MONTH_TREND_PERIOD)
                ?.map { it.close }
                ?.average()
            val monthClose = evidence.cycles?.months?.lastOrNull()?.close
            if (monthClose != null && monthAverage != null && monthAverage.isFinite()) {
                val completed =
                    "月K收${formatCyclePrice(monthClose)}低于" +
                        "${MultiTimeframeTrend.MONTH_TREND_PERIOD}月均线${formatCyclePrice(monthAverage)}"
                val current = currentCycleNote("月", evidence.cycles?.currentMonth, monthAverage)
                add(if (current == null) completed else "$completed（$current）")
            }
        }
        val basis = details.joinToString("，").ifBlank { "已完成周K与月K均处于均线下方" }
        return "按已完成周K/月K：$basis"
    }

    private fun currentCycleNote(label: String, current: DayBar?, average: Double): String? {
        val close = current?.close?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val relation = if (close >= average) "已回到均线上方" else "仍低于均线"
        return "当前未完成${label}K收${formatCyclePrice(close)}，$relation"
    }

    private fun formatCyclePrice(value: Double): String =
        String.format(Locale.CHINA, "%.2f", value)

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
