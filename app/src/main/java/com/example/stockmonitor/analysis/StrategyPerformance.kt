package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationStrategy

data class PaperTradeOutcome(
    val strategy: ObservationStrategy,
    val code: String,
    val entryDate: String,
    val exitDate: String?,
    val pnl: Double,
    val pnlPct: Double,
)

data class StrategyPerformanceSummary(
    val closedTrades: Int,
    val openTrades: Int,
    val wins: Int,
    val winRatePct: Double,
    val totalPnl: Double,
    val averagePnl: Double,
    val totalReturnPct: Double,
    val maxDrawdownPct: Double,
)

object StrategyPerformance {
    fun summarize(
        outcomes: List<PaperTradeOutcome>,
        initialCapital: Double,
    ): Map<ObservationStrategy, StrategyPerformanceSummary> {
        require(initialCapital > 0.0) { "初始资金必须为正数" }
        return outcomes.groupBy { it.strategy }.mapValues { (_, items) -> summarizeOne(items, initialCapital) }
    }

    private fun summarizeOne(
        outcomes: List<PaperTradeOutcome>,
        initialCapital: Double,
    ): StrategyPerformanceSummary {
        val closed = outcomes.filter { it.exitDate != null }
            .sortedWith(compareBy<PaperTradeOutcome> { it.exitDate }.thenBy { it.code })
        val wins = closed.count { it.pnl > 0.0 }
        val totalPnl = closed.sumOf { it.pnl }
        var equity = initialCapital
        var peak = initialCapital
        var maxDrawdown = 0.0
        closed.forEach { outcome ->
            equity += outcome.pnl
            peak = maxOf(peak, equity)
            if (peak > 0.0) maxDrawdown = maxOf(maxDrawdown, (peak - equity) / peak * 100.0)
        }
        return StrategyPerformanceSummary(
            closedTrades = closed.size,
            openTrades = outcomes.count { it.exitDate == null },
            wins = wins,
            winRatePct = if (closed.isEmpty()) 0.0 else wins.toDouble() / closed.size * 100.0,
            totalPnl = totalPnl,
            averagePnl = if (closed.isEmpty()) 0.0 else totalPnl / closed.size,
            totalReturnPct = totalPnl / initialCapital * 100.0,
            maxDrawdownPct = maxDrawdown,
        )
    }
}
