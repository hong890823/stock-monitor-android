package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class SwingTrend(val label: String) {
    UP("向上"), REPAIR("趋势修复"), STABILIZING("下跌企稳"), WEAK("偏弱"), MISSING("数据不足"),
}

data class MultiTimeframeSummary(
    val weeks: List<DayBar> = emptyList(),
    val months: List<DayBar> = emptyList(),
    val years: List<DayBar> = emptyList(),
    val weekTrend: SwingTrend = SwingTrend.MISSING,
    val monthTrend: SwingTrend = SwingTrend.MISSING,
    val ma250: Double? = null,
    val asOf: String,
    val historyStart: String? = null,
    val issue: String? = null,
) {
    fun summary(): String = "周K${weekTrend.label}｜月K${monthTrend.label}｜250日线" +
        (ma250?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "暂缺")
}

object MultiTimeframeTrend {
    fun evaluate(history: List<DayBar>, asOf: String, tradingDates: List<String>? = null): MultiTimeframeSummary {
        val date = runCatching { LocalDate.parse(asOf) }.getOrNull()
            ?: return MultiTimeframeSummary(asOf = asOf, issue = "行情日期无效")
        if (history.isEmpty()) return MultiTimeframeSummary(asOf = asOf, issue = "缺少同口径前复权长历史")
        val parsed = history.map { bar -> bar to runCatching { LocalDate.parse(bar.date) }.getOrNull() }
        if (parsed.any { (bar, day) -> day == null ||
                listOf(bar.open, bar.high, bar.low, bar.close).any { !it.isFinite() || it <= 0 } ||
                !bar.volume.isFinite() || bar.volume < 0 || bar.high < maxOf(bar.open, bar.close) ||
                bar.low > minOf(bar.open, bar.close) } || history.distinctBy { it.date }.size != history.size) {
            return MultiTimeframeSummary(asOf = asOf, issue = "历史日期或OHLCV异常，暂停多周期判断")
        }
        val closed = parsed.filter { it.second!! < date }.map { it.first }.sortedBy { it.date }
        if (closed.isEmpty()) return MultiTimeframeSummary(asOf = asOf, issue = "缺少已结束日线")
        val weekStart: (LocalDate) -> LocalDate = { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        val monthStart: (LocalDate) -> LocalDate = { it.withDayOfMonth(1) }
        val yearStart: (LocalDate) -> LocalDate = { it.withDayOfYear(1) }
        fun aggregate(start: (LocalDate) -> LocalDate): List<DayBar> = closed
            .groupBy { start(LocalDate.parse(it.date)) }
            .entries.sortedBy { it.key }.drop(1).filter { it.key < start(date) }
            .map { (_, bars) ->
                DayBar(bars.last().date, bars.first().open, bars.maxOf { it.high },
                    bars.minOf { it.low }, bars.last().close, bars.sumOf { it.volume })
            }
        val weeks = aggregate(weekStart)
        val months = aggregate(monthStart)
        val years = aggregate(yearStart)
        val ma250 = closed.takeIf { it.size >= 250 }?.takeLast(250)?.map { it.close }?.average()
        val expectedDates = tradingDates?.filter { it < asOf }?.distinct()?.sorted()?.takeLast(30)
        val actualDates = closed.map { it.date }.toSet()
        val issue = when {
            expectedDates != null && (expectedDates.size < 30 || expectedDates.any { it !in actualDates }) ->
                "近期交易日日线不齐，暂停加仓评估"
            ChronoUnit.DAYS.between(LocalDate.parse(closed.last().date), date) > 10 -> "日线长时间未更新，暂停加仓评估"
            ma250 == null || weeks.size < 24 || months.size < 13 ->
                "历史不足：需250日日线、24个完整周K及13个完整月K"
            else -> null
        }
        return MultiTimeframeSummary(weeks, months, years, classify(weeks, 20, 4), classify(months, 12, 1),
            ma250, asOf, closed.first().date, issue)
    }

    /**
     * 摆动趋势分五档。完整周期确认后才允许从「偏弱」进入「下跌企稳」：
     * 至少两个完整周期收复均线、之后三个完整周期不再创新低，且均线仍未转为上行。
     * 当前未完成周期由 [evaluate] 聚合时排除，不会改变结论。
     */
    fun classify(bars: List<DayBar>, period: Int, lag: Int): SwingTrend {
        if (bars.size < period + lag) return SwingTrend.MISSING
        val average = bars.takeLast(period).map { it.close }.average()
        val prior = bars.dropLast(lag).takeLast(period).map { it.close }.average()
        val current = bars.last()
        if (current.close < average) return SwingTrend.WEAK
        if (average > prior) return SwingTrend.UP

        val beforeConfirmation = bars.dropLast(2)
        val wasWeak = beforeConfirmation.size >= period &&
            beforeConfirmation.last().close <
            beforeConfirmation.takeLast(period).map { it.close }.average()
        val confirmed = bars.takeLast(2).mapIndexed { offset, bar ->
            val endIndex = bars.size - 2 + offset
            val startIndex = (endIndex - period).coerceAtLeast(0)
            val previousAverage = bars.subList(startIndex, endIndex).map { it.close }.average()
            bar.close >= previousAverage
        }.all { it }
        val lowsAfterBottom = bars.takeLast(3).map { it.low }
        val noLaterNewLow = lowsAfterBottom.size == 3 && lowsAfterBottom.zipWithNext().all { (previous, next) ->
            next >= previous
        }
        return if (wasWeak && confirmed && noLaterNewLow) SwingTrend.STABILIZING else SwingTrend.REPAIR
    }
}
