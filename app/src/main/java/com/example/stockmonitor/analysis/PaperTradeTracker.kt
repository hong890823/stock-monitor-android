package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationSnapshot
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy

data class PaperTradeRecord(
    val code: String,
    val name: String,
    val strategy: ObservationStrategy,
    val entryDate: String,
    val entryPrice: Double,
    val quantity: Int,
    val lastDate: String,
    val lastPrice: Double,
    val exitDate: String? = null,
    val exitPrice: Double? = null,
    val exitReason: String? = null,
    val entryFees: Double = 0.0,
    val exitFees: Double? = null,
    val netPnl: Double? = null,
    val pnlPct: Double? = null,
) {
    val isOpen: Boolean get() = exitDate == null

    fun outcome(): PaperTradeOutcome? = if (exitDate != null && netPnl != null && pnlPct != null) {
        PaperTradeOutcome(
            strategy = strategy,
            code = code,
            entryDate = entryDate,
            exitDate = exitDate,
            pnl = netPnl,
            pnlPct = pnlPct,
        )
    } else {
        null
    }
}

/**
 * 把观察池证据转换成可复盘的纸上交易记录。
 * 每笔固定模拟 1 手，便于比较两类策略，不代表实际仓位建议。
 */
object PaperTradeTracker {
    const val DEFAULT_QUANTITY = 100

    fun reconcile(
        existing: List<PaperTradeRecord>,
        history: List<ObservationSnapshot>,
        config: PaperTradingConfig = PaperTradingConfig(),
    ): List<PaperTradeRecord> {
        val result = existing.toMutableList()
        history.groupBy { it.code to it.strategy }.forEach { (key, snapshots) ->
            val ordered = snapshots.sortedBy { it.date }
            val knownEntryDates = result
                .filter { it.code == key.first && it.strategy == key.second }
                .map { it.entryDate }
                .toMutableSet()

            var searchFrom = 0
            val existingOpen = result.firstOrNull {
                it.code == key.first && it.strategy == key.second && it.isOpen
            }
            if (existingOpen != null) {
                val exit = findExit(ordered.filter { it.date > existingOpen.entryDate })
                val latest = ordered.lastOrNull { it.date >= existingOpen.entryDate }
                val index = result.indexOfFirst { it == existingOpen }
                if (index >= 0) {
                    result[index] = if (exit != null) {
                        searchFrom = ordered.indexOf(exit) + 1
                        close(existingOpen, exit, config)
                    } else {
                        if (latest != null) {
                            existingOpen.copy(lastDate = latest.date, lastPrice = latest.price)
                        } else {
                            existingOpen
                        }
                    }
                }
                if (exit == null) return@forEach
            }

            while (searchFrom < ordered.size) {
                val entryIndex = ordered.indexOfFirst {
                    ordered.indexOf(it) >= searchFrom &&
                        it.stage == ObservationStage.BUY_CANDIDATE &&
                        it.date !in knownEntryDates
                }
                if (entryIndex < 0) break
                val entry = ordered[entryIndex]
                val open = PaperTradeRecord(
                    code = entry.code,
                    name = entry.name,
                    strategy = entry.strategy,
                    entryDate = entry.date,
                    entryPrice = entry.price,
                    quantity = DEFAULT_QUANTITY,
                    lastDate = entry.date,
                    lastPrice = entry.price,
                    entryFees = buyFees(entry.price * DEFAULT_QUANTITY, config),
                )
                val exit = findExit(ordered.drop(entryIndex + 1))
                result += if (exit == null) {
                    open.copy(
                        lastDate = ordered.lastOrNull()?.date ?: entry.date,
                        lastPrice = ordered.lastOrNull()?.price ?: entry.price,
                    )
                } else {
                    close(open, exit, config)
                }
                knownEntryDates += entry.date
                if (exit == null) break
                searchFrom = ordered.indexOf(exit) + 1
            }
        }
        return result.sortedWith(compareBy<PaperTradeRecord> { it.entryDate }.thenBy { it.code }.thenBy { it.strategy.name })
    }

    private fun findExit(snapshots: List<ObservationSnapshot>): ObservationSnapshot? {
        return snapshots.firstOrNull { snapshot ->
            val stopHit = snapshot.stopPrice != null && snapshot.price <= snapshot.stopPrice
            stopHit || snapshot.stage == ObservationStage.ELIMINATED
        }
    }

    private fun close(
        open: PaperTradeRecord,
        exit: ObservationSnapshot,
        config: PaperTradingConfig,
    ): PaperTradeRecord {
        val gross = exit.price * open.quantity
        val fees = sellFees(gross, config)
        val net = gross - fees - open.entryPrice * open.quantity - open.entryFees
        return open.copy(
            lastDate = exit.date,
            lastPrice = exit.price,
            exitDate = exit.date,
            exitPrice = exit.price,
            exitReason = if (exit.stopPrice != null && exit.price <= exit.stopPrice) "止损" else "淘汰",
            exitFees = fees,
            netPnl = net,
            pnlPct = net / (open.entryPrice * open.quantity + open.entryFees) * 100.0,
        )
    }

    private fun commission(gross: Double, config: PaperTradingConfig): Double =
        maxOf(config.minimumCommission, gross * config.commissionRate)

    private fun buyFees(gross: Double, config: PaperTradingConfig): Double =
        commission(gross, config) + gross * config.transferFeeRate

    private fun sellFees(gross: Double, config: PaperTradingConfig): Double =
        commission(gross, config) + gross * config.stampDutyRate + gross * config.transferFeeRate
}
