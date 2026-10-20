package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Quote
import java.time.LocalDate
import java.util.Locale

enum class HoldingTTrend(val label: String) {
    UP("近期趋势向上"), EXTENDED("近期连续拉升"), REBOUND("下跌后修复"),
    WEAK("近期趋势偏弱"), RANGE("近期震荡"),
}

data class HoldingTContext(
    val date: String,
    val generatedAtMs: Long,
    val trend: HoldingTTrend,
    val gain5Pct: Double,
    val atrPct: Double,
    val weekTrend: SwingTrend,
    val monthTrend: SwingTrend,
) {
    fun isFresh(today: String, nowMs: Long): Boolean =
        date == today && nowMs - generatedAtMs in 0..45 * 60_000L

    val strongDirection: Boolean get() = trend == HoldingTTrend.UP &&
        weekTrend == SwingTrend.UP && monthTrend in setOf(SwingTrend.UP, SwingTrend.REPAIR)

    val cautiousBuy: Boolean get() = trend in setOf(HoldingTTrend.WEAK, HoldingTTrend.REBOUND) ||
        weekTrend != SwingTrend.UP || monthTrend !in setOf(SwingTrend.UP, SwingTrend.REPAIR)

    fun summary(): String = String.format(Locale.CHINA, "%s，近5日%+.1f%%；周K%s、月K%s",
        trend.label, gain5Pct, weekTrend.label, monthTrend.label)

    companion object {
        fun from(
            history: List<DayBar>,
            cycles: MultiTimeframeSummary,
            today: String,
            nowMs: Long,
            tradingDates: List<String>,
        ): HoldingTContext? {
            if (runCatching { LocalDate.parse(today) }.isFailure) return null
            val bars = history.filter { it.date < today }.sortedBy { it.date }
            if (bars.size < 30 || bars.distinctBy { it.date }.size != bars.size || bars.any {
                    runCatching { LocalDate.parse(it.date) }.isFailure ||
                        listOf(it.open, it.high, it.low, it.close).any { price -> !price.isFinite() || price <= 0 } ||
                        !it.volume.isFinite() || it.volume < 0 ||
                        it.high < maxOf(it.open, it.close) || it.low > minOf(it.open, it.close)
                }) return null
            val expected = tradingDates.filter { it < today }.distinct().sorted().takeLast(30)
            if (expected.size < 30 || bars.takeLast(30).map { it.date } != expected) return null
            val closes = bars.map { it.close }
            val ma5 = closes.takeLast(5).average()
            val ma10 = closes.takeLast(10).average()
            val ma20 = closes.takeLast(20).average()
            val previousMa20 = closes.dropLast(1).takeLast(20).average()
            val atr = Indicators.atr(bars.map { it.high }, bars.map { it.low }, closes).lastOrNull()
                ?.takeIf { it.isFinite() && it > 0 } ?: return null
            val atrPct = atr / closes.last() * 100
            val gain5 = (closes.last() / closes[closes.lastIndex - 5] - 1) * 100
            val risingDays = closes.takeLast(6).zipWithNext().count { (previous, current) -> current > previous }
            val trend = when {
                closes.last() < ma20 * 0.98 ||
                    (closes.last() < ma20 * 0.995 && closes[closes.lastIndex - 1] < previousMa20 * 0.995) -> HoldingTTrend.WEAK
                gain5 >= maxOf(6.0, atrPct * 3) && risingDays >= 3 -> HoldingTTrend.EXTENDED
                closes.last() >= ma5 && ma5 > ma10 && ma10 > ma20 && ma20 > previousMa20 -> HoldingTTrend.UP
                closes.last() > closes[closes.lastIndex - 1] && ma5 > closes.dropLast(1).takeLast(5).average() -> HoldingTTrend.REBOUND
                else -> HoldingTTrend.RANGE
            }
            val validCycles = cycles.takeIf { it.asOf == today && it.issue == null }
            return HoldingTContext(today, nowMs, trend, gain5, atrPct,
                validCycles?.weekTrend ?: SwingTrend.MISSING, validCycles?.monthTrend ?: SwingTrend.MISSING)
        }
    }
}

data class HoldingTObservation(
    val low: Double,
    val high: Double,
    val reboundPct: Double,
    val retreatPct: Double,
    val rising: Boolean,
    val falling: Boolean,
)

class HoldingTTracker {
    private data class Sample(val atMs: Long, val price: Double, val volume: Double, val date: String)
    private val samples = mutableMapOf<String, MutableList<Sample>>()

    @Synchronized fun clear() = samples.clear()

    @Synchronized fun retainCodes(codes: Set<String>) { samples.keys.retainAll(codes) }

    @Synchronized fun observe(quote: Quote, today: String, nowMs: Long): HoldingTObservation? {
        if (quote.quoteDate != today || !quote.price.isFinite() || quote.price <= 0 ||
            !quote.volume.isFinite() || quote.volume <= 0) {
            samples.remove(quote.code)
            return null
        }
        val history = samples.getOrPut(quote.code) { mutableListOf() }
        val last = history.lastOrNull()
        if (last != null) {
            if (last.date != today || nowMs - last.atMs !in 1..90_000 || quote.volume < last.volume) history.clear()
            else if (quote.volume == last.volume) return null
        }
        history.add(Sample(nowMs, quote.price, quote.volume, today))
        history.removeAll { nowMs - it.atMs > 180_000 }
        return observation(history, nowMs)
    }

    @Synchronized fun snapshot(quote: Quote, today: String, nowMs: Long): HoldingTObservation? {
        val history = samples[quote.code] ?: return null
        val last = history.lastOrNull() ?: return null
        if (quote.quoteDate != today || last.date != today || nowMs - last.atMs !in 0..90_000 ||
            quote.price != last.price || quote.volume != last.volume) return null
        return observation(history.filter { nowMs - it.atMs in 0..180_000 }, nowMs)
    }

    private fun observation(history: List<Sample>, nowMs: Long): HoldingTObservation? {
        if (history.size < 3 || history.last().atMs - history.first().atMs < 60_000) return null
        val prices = history.takeLast(3).map { it.price }
        val low = history.minOf { it.price }
        val high = history.maxOf { it.price }
        return HoldingTObservation(low, high, (prices.last() / low - 1) * 100,
            (1 - prices.last() / high) * 100,
            prices.zipWithNext().all { (previous, current) -> current >= previous } && prices.last() > prices.first(),
            prices.zipWithNext().all { (previous, current) -> current <= previous } && prices.last() < prices.first())
    }
}
