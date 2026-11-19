package com.example.stockmonitor.core

/**
 * 将追加顺序的真实交易流水投影为可展示的交易历史。
 *
 * 卖出成本严格按 TradeLotLedger 当前的批次插入顺序与 T+1 规则重放，不能按日期
 * 重排历史补录。旧持仓、手工数量调整和缺少可追溯买入流水的卖出明确标为不可计算。
 */
object TradeHistoryProjector {
    data class Row(
        val entryIndex: Int,
        val record: TradeRecord,
        /** 仅卖出且买入流水可完整追溯时有值，已扣买卖费用。 */
        val realizedPnl: Double?,
        val unavailableReason: String? = null,
    )

    private data class ReplayLot(
        val code: String,
        val buyDate: String,
        val unitCostWithFee: Double,
        var remaining: Double,
    )

    fun project(book: TradeBook): List<Row> {
        val lots = mutableListOf<ReplayLot>()
        return book.entries.mapIndexed { index, record ->
            when (record.side) {
                TradeSide.BUY -> {
                    val unitCost = record.price + record.fee / record.quantity
                    lots += ReplayLot(record.code, record.date, unitCost, record.quantity)
                    Row(index, record, realizedPnl = null)
                }
                TradeSide.SELL -> projectSell(index, record, lots)
            }
        }
    }

    private fun projectSell(
        entryIndex: Int,
        record: TradeRecord,
        lots: MutableList<ReplayLot>,
    ): Row {
        var remaining = record.quantity
        var matchedCost = 0.0
        val matched = mutableListOf<Pair<ReplayLot, Double>>()
        for (lot in lots) {
            if (remaining <= EPSILON) break
            if (lot.code != record.code || lot.buyDate >= record.date || lot.remaining <= EPSILON) continue
            val used = minOf(remaining, lot.remaining)
            matched += lot to used
            matchedCost += used * lot.unitCostWithFee
            remaining -= used
        }
        if (remaining > EPSILON) {
            return Row(
                entryIndex = entryIndex,
                record = record,
                realizedPnl = null,
                unavailableReason = "缺少可追溯买入流水，无法计算已实现盈亏",
            )
        }
        matched.forEach { (lot, used) -> lot.remaining -= used }
        return Row(
            entryIndex = entryIndex,
            record = record,
            realizedPnl = record.quantity * record.price - record.fee - matchedCost,
        )
    }

    private const val EPSILON = 0.000001
}
