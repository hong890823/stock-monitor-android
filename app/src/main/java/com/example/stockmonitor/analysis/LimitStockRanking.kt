package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitStock

object LimitStockRanking {

    fun sortUp(stocks: List<LimitStock>): List<LimitStock> {
        val ranked = LimitRanking.rank(stocks.map { metrics(it) })
        val scoresById = ranked.associateBy { it.id }
        val orderById = ranked.mapIndexed { index, score -> score.id to index }.toMap()
        return stocks
            .map { stock ->
                val score = scoresById[stock.code]
                stock.copy(
                    sealQualityScore = score?.score,
                    sealQualityCoveragePct = score?.coveragePct,
                )
            }
            .sortedBy { orderById[it.code] ?: Int.MAX_VALUE }
    }

    private fun metrics(stock: LimitStock) = LimitQualityMetrics(
        id = stock.code,
        sealToTurnoverPct = stock.sealToTurnoverPct,
        sealToFloatPct = stock.sealToFloatPct,
        openCount = stock.openCount?.toDouble(),
        firstSealSeconds = secondsOfDay(stock.firstSealTime),
        lastSealSeconds = secondsOfDay(stock.lastSealTime),
        limitStreak = stock.limitStreak?.toDouble(),
        mainNetPct = stock.fundFlow?.mainNetPct,
    )

    private fun secondsOfDay(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = parts[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return hour * 3600 + minute * 60 + second
    }
}
