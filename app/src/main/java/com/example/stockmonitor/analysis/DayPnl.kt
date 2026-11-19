package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.TradeBook
import com.example.stockmonitor.core.TradeSide
import java.util.Locale

/**
 * 持仓与账户当日盈亏。
 *
 * - 当前持仓今日盈亏统一为 `(现价 − 昨收) × 当前数量`，当日买入也不再混入买入成本；
 * - 账户今日盈亏在真实交易完整时额外纳入当日成交和费用；
 * - 手工数量调整没有成交价和费用，必须明确标记账户口径数据不足，不能猜成交。
 */
object DayPnl {

    /** 保留用于旧调用与批次展示；不再参与当前持仓今日盈亏的公式。 */
    data class Acquisition(
        val quantity: Double,
        val unitCost: Double,
    )

    data class Row(
        val code: String,
        val name: String,
        val quantity: Double,
        val price: Double,
        val prevClose: Double,
        val pnl: Double?,
        val changePct: Double?,
        val prevMarketValue: Double,
    )

    data class Summary(
        val rows: List<Row>,
        val totalPnl: Double,
        val totalPct: Double?,
        val prevMarketValue: Double,
        val marketValue: Double,
        val missing: Int,
    ) {
        val counted: Int get() = rows.count { it.pnl != null }
    }

    /**
     * 账户口径 = 昨日持有部分的价差 + 当日买入剩余部分的成交后价差 +
     * 当日卖出相对昨收的已实现价差 − 当日实际费用。
     */
    data class AccountSummary(
        val holding: Summary,
        val complete: Boolean,
        val totalPnl: Double?,
        val totalPct: Double?,
        val holdingsPnl: Double?,
        val realizedPnl: Double?,
        val fees: Double?,
        val prevMarketValue: Double,
        val reasons: List<String> = emptyList(),
    )

    fun acquisitions(book: TradeBook, date: String): Map<String, Acquisition> =
        book.lots
            .filter {
                it.buyDate == date &&
                    it.quantity.isFinite() && it.quantity > 0 &&
                    it.unitCost.isFinite() && it.unitCost > 0
            }
            .groupBy { it.code }
            .mapNotNull { (code, lots) ->
                val quantity = lots.sumOf { it.quantity }
                if (quantity <= 0) return@mapNotNull null
                code to Acquisition(
                    quantity = quantity,
                    unitCost = lots.sumOf { it.quantity * it.unitCost } / quantity,
                )
            }
            .toMap()

    /** 当前持仓今日盈亏，统一按现价相对昨收计算。 */
    fun summarize(
        positions: List<Position>,
        quotes: Map<String, Quote>,
        @Suppress("UNUSED_PARAMETER") todayAcquisitions: Map<String, Acquisition> = emptyMap(),
    ): Summary {
        val rows = mutableListOf<Row>()
        var totalPnl = 0.0
        var prevMarketValue = 0.0
        var marketValue = 0.0
        var missing = 0
        for (position in positions) {
            if (position.quantity <= 0) continue
            val quote = quotes[position.code]
            val price = quote?.price ?: Double.NaN
            val prevClose = quote?.prevClose ?: Double.NaN
            if (quote == null || !price.isFinite() || !prevClose.isFinite() || price <= 0 || prevClose <= 0) {
                missing++
                rows.add(Row(position.code, position.name, position.quantity, price, prevClose, null, null, 0.0))
                continue
            }
            val pnl = (price - prevClose) * position.quantity
            val prevValue = prevClose * position.quantity
            totalPnl += pnl
            prevMarketValue += prevValue
            marketValue += price * position.quantity
            rows.add(
                Row(
                    code = position.code,
                    name = position.name,
                    quantity = position.quantity,
                    price = price,
                    prevClose = prevClose,
                    pnl = pnl,
                    changePct = quote.changePct,
                    prevMarketValue = prevValue,
                ),
            )
        }
        return Summary(
            rows = rows,
            totalPnl = totalPnl,
            totalPct = if (prevMarketValue > 0) totalPnl / prevMarketValue * 100 else null,
            prevMarketValue = prevMarketValue,
            marketValue = marketValue,
            missing = missing,
        )
    }

    /**
     * 计算账户今日盈亏。只有真实 [TradeBook.entries] 可用于成交计算；
     * 同日的手工数量调整会返回不完整状态，保留 [holding] 供界面展示当前持仓。
     */
    fun accountSummary(
        positions: List<Position>,
        quotes: Map<String, Quote>,
        book: TradeBook,
        date: String,
    ): AccountSummary {
        val holding = summarize(positions, quotes)
        val manual = book.adjustments.filter {
            it.date == date && it.quantityDelta.isFinite() && it.quantityDelta != 0.0
        }
        if (manual.isNotEmpty()) {
            return AccountSummary(
                holding = holding,
                complete = false,
                totalPnl = null,
                totalPct = null,
                holdingsPnl = null,
                realizedPnl = null,
                fees = null,
                prevMarketValue = 0.0,
                reasons = manual.map { "${it.name}：今日手工数量调整未记录成交价/费用" },
            )
        }

        val dayEntries = book.entries.filter { it.date == date && !it.bookkeepingOnly }
        val dayTradeCodes = dayEntries.map { it.code }.toSet()
        val positionByCode = positions
            .filter { it.quantity > 0 || it.code in dayTradeCodes }
            .associateBy { it.code }
        val buyByCode = dayEntries.filter { it.side == TradeSide.BUY }.groupBy { it.code }
        val sellByCode = dayEntries.filter { it.side == TradeSide.SELL }.groupBy { it.code }
        val codes = positionByCode.keys + buyByCode.keys + sellByCode.keys
        val reasons = mutableListOf<String>()
        var holdingsPnl = 0.0
        var realizedPnl = 0.0
        var fees = 0.0
        var prevMarketValue = 0.0

        for (code in codes) {
            val quote = quotes[code]
            val price = quote?.price ?: Double.NaN
            val prevClose = quote?.prevClose ?: Double.NaN
            if (quote == null || !price.isFinite() || !prevClose.isFinite() || price <= 0 || prevClose <= 0) {
                reasons.add("$code：缺少当日行情")
                continue
            }
            val currentQuantity = positionByCode[code]?.quantity ?: 0.0
            val buys = buyByCode[code].orEmpty()
            val boughtQuantity = buys.sumOf { it.quantity }
            val openingRemaining = currentQuantity - boughtQuantity
            if (!openingRemaining.isFinite() || openingRemaining < -EPSILON) {
                reasons.add("$code：当前数量小于当日买入数量")
                continue
            }
            holdingsPnl += openingRemaining * (price - prevClose)
            prevMarketValue += openingRemaining * prevClose
            for (trade in buys) {
                if (!trade.price.isFinite() || trade.price <= 0 || !trade.fee.isFinite() || trade.fee < 0) {
                    reasons.add("$code：买入成交价或费用无效")
                    continue
                }
                holdingsPnl += trade.quantity * (price - trade.price)
                fees += trade.fee
            }
            for (trade in sellByCode[code].orEmpty()) {
                if (!trade.price.isFinite() || trade.price <= 0 || !trade.fee.isFinite() || trade.fee < 0) {
                    reasons.add("$code：卖出成交价或费用无效")
                    continue
                }
                realizedPnl += trade.quantity * (trade.price - prevClose)
                prevMarketValue += trade.quantity * prevClose
                fees += trade.fee
            }
        }
        if (reasons.isNotEmpty()) {
            return AccountSummary(
                holding = holding,
                complete = false,
                totalPnl = null,
                totalPct = null,
                holdingsPnl = null,
                realizedPnl = null,
                fees = null,
                prevMarketValue = prevMarketValue,
                reasons = reasons,
            )
        }
        val total = holdingsPnl + realizedPnl - fees
        return AccountSummary(
            holding = holding,
            complete = true,
            totalPnl = total,
            totalPct = if (prevMarketValue > 0) total / prevMarketValue * 100 else null,
            holdingsPnl = holdingsPnl,
            realizedPnl = realizedPnl,
            fees = fees,
            prevMarketValue = prevMarketValue,
        )
    }

    fun money(value: Double): String = String.format(Locale.CHINA, "%+,.2f", value)

    fun pct(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)

    fun amount(value: Double): String = String.format(Locale.CHINA, "%,.0f", value)

    fun summaryText(summary: Summary): String? {
        val totalPct = summary.totalPct ?: return null
        return "今日盈亏 ${money(summary.totalPnl)} 元（较昨日市值 ${pct(totalPct)}）" +
            "｜持仓市值 ${amount(summary.marketValue)} 元" +
            "｜昨日市值 ${amount(summary.prevMarketValue)} 元"
    }

    fun accountSummaryText(summary: AccountSummary): String? {
        if (!summary.complete || summary.totalPnl == null || summary.totalPct == null ||
            summary.holdingsPnl == null || summary.realizedPnl == null || summary.fees == null
        ) return null
        return "账户今日盈亏 ${money(summary.totalPnl)} 元（较昨日市值 ${pct(summary.totalPct)}）" +
            "｜当前持仓 ${money(summary.holdingsPnl)} 元" +
            "｜已实现 ${money(summary.realizedPnl)} 元" +
            "｜费用 ${String.format(Locale.CHINA, "%,.2f", summary.fees)} 元"
    }

    private const val EPSILON = 0.000001
}
