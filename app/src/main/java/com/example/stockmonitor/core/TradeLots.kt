package com.example.stockmonitor.core

enum class TradeSide {
    BUY,
    SELL,
}

enum class TradeSource {
    MANUAL,
    IMPORTED,
    AUTOMATED,
}

data class TradeRecord(
    val id: String,
    val code: String,
    val name: String,
    val side: TradeSide,
    val date: String,
    val quantity: Double,
    val price: Double,
    val source: TradeSource,
)

data class TradeLot(
    val code: String,
    val name: String,
    val quantity: Double,
    val unitCost: Double,
    val buyDate: String,
)

data class TradeBook(
    val entries: List<TradeRecord> = emptyList(),
    val lots: List<TradeLot> = emptyList(),
)

data class TradeAvailability(
    val code: String,
    val totalQuantity: Double,
    val sellableToday: Double,
    val lockedToday: Double,
    val nextTradingDaySellable: Double,
)

data class TradeApplicationResult(
    val book: TradeBook,
    val portfolio: Portfolio,
)

object TradeLotLedger {
    const val LEGACY_ENTRY_DATE = "legacy"
    const val UNTRACKED_ENTRY_DATE = "untracked"

    fun migrate(positions: List<Position>): TradeBook = TradeBook(
        lots = positions
            .filter { it.quantity.isFinite() && it.quantity > 0 }
            .map {
                TradeLot(
                    code = it.code,
                    name = it.name,
                    quantity = it.quantity,
                    unitCost = it.cost,
                    buyDate = LEGACY_ENTRY_DATE,
                )
            },
    )

    fun buy(book: TradeBook, record: TradeRecord): TradeBook {
        validate(record, TradeSide.BUY)
        val lot = TradeLot(
            code = record.code,
            name = record.name,
            quantity = record.quantity,
            unitCost = record.price,
            buyDate = record.date,
        )
        return TradeBook(book.entries + record, book.lots + lot)
    }

    fun sell(book: TradeBook, record: TradeRecord): TradeBook {
        validate(record, TradeSide.SELL)
        val sellable = book.lots.filter { it.code == record.code && isSellableOn(it, record.date) }
        check(sellable.sumOf { it.quantity } + EPSILON >= record.quantity) {
            "A 股买入当日不可卖出或可卖数量不足"
        }

        var remaining = record.quantity
        val lots = book.lots.map { lot ->
            if (remaining <= EPSILON || lot.code != record.code || !isSellableOn(lot, record.date)) {
                lot
            } else {
                val used = minOf(remaining, lot.quantity)
                remaining -= used
                lot.copy(quantity = lot.quantity - used)
            }
        }
        return TradeBook(book.entries + record, lots.filter { it.quantity > EPSILON })
    }

    fun buyToPortfolio(
        book: TradeBook,
        portfolio: Portfolio,
        record: TradeRecord,
    ): TradeApplicationResult {
        val updatedBook = buy(book, record)
        val position = portfolio.positions.firstOrNull { it.code == record.code }
            ?: throw IllegalStateException("交易股票不在持仓列表中")
        val newQuantity = position.quantity + record.quantity
        val newCost = (position.quantity * position.cost + record.quantity * record.price) / newQuantity
        return TradeApplicationResult(
            book = updatedBook,
            portfolio = portfolio.updatePosition(record.code, newQuantity, newCost),
        )
    }

    fun sellToPortfolio(
        book: TradeBook,
        portfolio: Portfolio,
        record: TradeRecord,
    ): TradeApplicationResult {
        val updatedBook = sell(book, record)
        val position = portfolio.positions.firstOrNull { it.code == record.code }
            ?: throw IllegalStateException("交易股票不在持仓列表中")
        check(position.quantity + EPSILON >= record.quantity) { "卖出数量超过当前持仓" }
        return TradeApplicationResult(
            book = updatedBook,
            portfolio = portfolio.updatePosition(
                record.code,
                (position.quantity - record.quantity).coerceAtLeast(0.0),
                position.cost,
            ),
        )
    }

    fun availability(
        book: TradeBook,
        positions: List<Position>,
        date: String,
    ): List<TradeAvailability> {
        val currentByCode = positions
            .filter { it.quantity.isFinite() && it.quantity > 0 }
            .associateBy { it.code }
        val lotByCode = book.lots.filter { it.quantity > 0 }.groupBy { it.code }
        return currentByCode.values.map { position ->
            val lots = lotByCode[position.code].orEmpty()
            val trackedSellable = lots.filter { isSellableOn(it, date) }.sumOf { it.quantity }
            val trackedLocked = lots.filter { it.buyDate == date }.sumOf { it.quantity }
            val untracked = maxOf(0.0, position.quantity - lots.sumOf { it.quantity })
            val sellable = minOf(position.quantity, trackedSellable)
            val nextDay = minOf(position.quantity, trackedSellable + trackedLocked)
            TradeAvailability(
                code = position.code,
                totalQuantity = position.quantity,
                sellableToday = sellable,
                lockedToday = position.quantity - sellable,
                nextTradingDaySellable = nextDay,
            ).also {
                check(untracked >= -EPSILON)
            }
        }.sortedBy { it.code }
    }

    fun availability(
        book: TradeBook,
        positions: List<Position>,
        date: String,
        code: String,
    ): TradeAvailability = availability(book, positions, date).firstOrNull { it.code == code }
        ?: TradeAvailability(code, 0.0, 0.0, 0.0, 0.0)

    private fun isSellableOn(lot: TradeLot, date: String): Boolean =
        lot.buyDate == LEGACY_ENTRY_DATE || (lot.buyDate != UNTRACKED_ENTRY_DATE && lot.buyDate < date)

    private fun validate(record: TradeRecord, expected: TradeSide) {
        check(record.side == expected)
        check(record.id.isNotBlank() && record.code.isNotBlank() && record.name.isNotBlank())
        check(record.date.matches(DATE_PATTERN))
        check(record.quantity.isFinite() && record.quantity > 0.0)
        check(record.price.isFinite() && record.price > 0.0)
    }

    private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    private const val EPSILON = 0.000001
}
