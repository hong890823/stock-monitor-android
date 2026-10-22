package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TradeLotsTest {
    private fun position(quantity: Double) =
        Position("sz000001", "测试股", quantity, 10.0)

    private fun buy(date: String, quantity: Double, price: Double = 10.0) =
        TradeRecord(
            id = "buy-$date-$quantity",
            code = "sz000001",
            name = "测试股",
            side = TradeSide.BUY,
            date = date,
            quantity = quantity,
            price = price,
            source = TradeSource.MANUAL,
        )

    @Test
    fun migratedLegacyPositionIsSellableToday() {
        val book = TradeLotLedger.migrate(listOf(position(500.0)))

        val availability = TradeLotLedger.availability(book, listOf(position(500.0)), "2026-10-20").single()

        assertEquals(500.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.lockedToday, 0.001)
        assertEquals(500.0, availability.nextTradingDaySellable, 0.001)
    }

    @Test
    fun sameDayBuyIsLockedAndPreviousPositionRemainsSellable() {
        var book = TradeLotLedger.migrate(listOf(position(500.0)))
        book = TradeLotLedger.buy(book, buy("2026-10-20", 100.0))

        val availability = TradeLotLedger.availability(book, listOf(position(600.0)), "2026-10-20").single()

        assertEquals(500.0, availability.sellableToday, 0.001)
        assertEquals(100.0, availability.lockedToday, 0.001)
        assertEquals(600.0, availability.nextTradingDaySellable, 0.001)
    }

    @Test
    fun buyBecomesSellableOnTheNextDate() {
        var book = TradeLotLedger.migrate(listOf(position(500.0)))
        book = TradeLotLedger.buy(book, buy("2026-10-20", 100.0))

        val availability = TradeLotLedger.availability(book, listOf(position(600.0)), "2026-10-21").single()

        assertEquals(600.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.lockedToday, 0.001)
    }

    @Test
    fun sellConsumesOldestSellableLotsFifo() {
        var book = TradeLotLedger.migrate(listOf(position(200.0)))
        book = TradeLotLedger.buy(book, buy("2026-10-19", 100.0, 11.0))
        book = TradeLotLedger.buy(book, buy("2026-10-20", 100.0, 12.0))
        val sell = TradeRecord(
            id = "sell-1",
            code = "sz000001",
            name = "测试股",
            side = TradeSide.SELL,
            date = "2026-10-20",
            quantity = 250.0,
            price = 13.0,
            source = TradeSource.MANUAL,
        )

        book = TradeLotLedger.sell(book, sell)
        val availability = TradeLotLedger.availability(book, listOf(position(150.0)), "2026-10-20").single()

        assertEquals(50.0, availability.sellableToday, 0.001)
        assertEquals(100.0, availability.lockedToday, 0.001)
    }

    @Test
    fun sellCannotUseSameDayOrUntrackedShares() {
        var book = TradeLotLedger.migrate(emptyList())
        book = TradeLotLedger.buy(book, buy("2026-10-20", 100.0))
        val sell = TradeRecord(
            id = "sell-1",
            code = "sz000001",
            name = "测试股",
            side = TradeSide.SELL,
            date = "2026-10-20",
            quantity = 100.0,
            price = 11.0,
            source = TradeSource.MANUAL,
        )

        assertThrows(IllegalStateException::class.java) {
            TradeLotLedger.sell(book, sell)
        }
    }

    @Test
    fun untrackedManualIncreaseStaysLockedUntilRecorded() {
        val book = TradeLotLedger.migrate(listOf(position(500.0)))

        val availability = TradeLotLedger.availability(book, listOf(position(600.0)), "2026-10-21").single()

        assertEquals(500.0, availability.sellableToday, 0.001)
        assertEquals(100.0, availability.lockedToday, 0.001)
        assertEquals(500.0, availability.nextTradingDaySellable, 0.001)
    }

    @Test
    fun recordedBuyUpdatesPortfolioWeightedCostAndLocksNewLot() {
        val portfolio = Portfolio(positions = mutableListOf(position(500.0)))
        var book = TradeLotLedger.migrate(portfolio.positions)

        val applied = TradeLotLedger.buyToPortfolio(book, portfolio, buy("2026-10-20", 100.0, 12.0))
        val availability = TradeLotLedger.availability(applied.book, applied.portfolio.positions, "2026-10-20").single()

        assertEquals(600.0, applied.portfolio.positions.single().quantity, 0.001)
        assertEquals(10.333333, applied.portfolio.positions.single().cost, 0.0001)
        assertEquals(500.0, availability.sellableToday, 0.001)
        assertEquals(100.0, availability.lockedToday, 0.001)
    }

    @Test
    fun recordedSellUpdatesPortfolioWithoutConsumingSameDayLots() {
        val portfolio = Portfolio(positions = mutableListOf(position(600.0)))
        var book = TradeLotLedger.migrate(listOf(position(500.0)))
        book = TradeLotLedger.buy(book, buy("2026-10-20", 100.0, 12.0))
        val sell = TradeRecord(
            id = "sell-portfolio",
            code = "sz000001",
            name = "测试股",
            side = TradeSide.SELL,
            date = "2026-10-20",
            quantity = 250.0,
            price = 13.0,
            source = TradeSource.MANUAL,
        )

        val applied = TradeLotLedger.sellToPortfolio(book, portfolio, sell)
        val availability = TradeLotLedger.availability(applied.book, applied.portfolio.positions, "2026-10-20").single()

        assertEquals(350.0, applied.portfolio.positions.single().quantity, 0.001)
        assertEquals(10.0, applied.portfolio.positions.single().cost, 0.001)
        assertEquals(250.0, availability.sellableToday, 0.001)
        assertEquals(100.0, availability.lockedToday, 0.001)
    }

    @Test
    fun rejectedPortfolioSellChangesNeitherBookNorPortfolio() {
        val portfolio = Portfolio(positions = mutableListOf(position(100.0)))
        val book = TradeLotLedger.migrate(emptyList()).let {
            TradeLotLedger.buy(it, buy("2026-10-20", 100.0))
        }
        val sell = TradeRecord(
            id = "sell-rejected",
            code = "sz000001",
            name = "测试股",
            side = TradeSide.SELL,
            date = "2026-10-20",
            quantity = 100.0,
            price = 11.0,
            source = TradeSource.MANUAL,
        )

        assertThrows(IllegalStateException::class.java) {
            TradeLotLedger.sellToPortfolio(book, portfolio, sell)
        }
    }

    /** 回归：手工加仓必须按买入日期记批次，否则残股被当成「今天买入」一直锁定。 */
    @Test
    fun manualAddPositionLotIsDatedAndSellableNextDay() {
        val book = TradeLotLedger.syncPositionEdit(
            TradeLotLedger.migrate(emptyList()),
            "sz000001", "测试股",
            oldQuantity = 0.0, newQuantity = 300.0, unitCost = 10.0, buyDate = "2026-10-21",
        )

        val availability = TradeLotLedger.availability(book, listOf(position(300.0)), "2026-10-22").single()

        assertEquals(300.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.lockedToday, 0.001)
        assertEquals(0.0, availability.untrackedQuantity, 0.001)
    }

    @Test
    fun manualAddSameDayLotLocksTodayButMaturesNextDate() {
        val book = TradeLotLedger.syncPositionEdit(
            TradeLotLedger.migrate(emptyList()),
            "sz000001", "测试股",
            oldQuantity = 0.0, newQuantity = 300.0, unitCost = 10.0, buyDate = "2026-10-22",
        )

        val today = TradeLotLedger.availability(book, listOf(position(300.0)), "2026-10-22").single()
        assertEquals(0.0, today.sellableToday, 0.001)
        assertEquals(300.0, today.lockedToday, 0.001)
        // 有买入日期只是今天锁定，不算「未记录日期」残股。
        assertEquals(0.0, today.untrackedQuantity, 0.001)

        val tomorrow = TradeLotLedger.availability(book, listOf(position(300.0)), "2026-10-23").single()
        assertEquals(300.0, tomorrow.sellableToday, 0.001)
        assertEquals(0.0, tomorrow.lockedToday, 0.001)
    }

    @Test
    fun editIncreaseCreatesDatedLotAndDecreaseConsumesFifo() {
        var book = TradeLotLedger.migrate(listOf(position(500.0)))
        book = TradeLotLedger.syncPositionEdit(book, "sz000001", "测试股", 500.0, 800.0, 10.0, "2026-10-21")

        var availability = TradeLotLedger.availability(book, listOf(position(800.0)), "2026-10-22").single()
        assertEquals(800.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.untrackedQuantity, 0.001)

        book = TradeLotLedger.syncPositionEdit(book, "sz000001", "测试股", 800.0, 600.0, 10.0, "2026-10-22")
        availability = TradeLotLedger.availability(book, listOf(position(600.0)), "2026-10-22").single()
        assertEquals(600.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.untrackedQuantity, 0.001)
    }

    @Test
    fun syncRejectsInvalidBuyDateOnIncrease() {
        assertThrows(IllegalStateException::class.java) {
            TradeLotLedger.syncPositionEdit(TradeBook(), "sz000001", "测试股", 0.0, 300.0, 10.0, "10-21")
        }
    }

    /** 旧数据：手工加过数量但没记批次的残股，要露出来且不会随交易日自动解锁。 */
    @Test
    fun untrackedResidualIsExposedAndDoesNotAutoMature() {
        val book = TradeLotLedger.migrate(listOf(position(500.0)))

        val today = TradeLotLedger.availability(book, listOf(position(800.0)), "2026-10-22").single()
        assertEquals(500.0, today.sellableToday, 0.001)
        assertEquals(300.0, today.lockedToday, 0.001)
        assertEquals(300.0, today.untrackedQuantity, 0.001)

        val tomorrow = TradeLotLedger.availability(book, listOf(position(800.0)), "2026-10-23").single()
        assertEquals(500.0, tomorrow.sellableToday, 0.001)
        assertEquals(300.0, tomorrow.lockedToday, 0.001)
        assertEquals(300.0, tomorrow.untrackedQuantity, 0.001)
    }

    /** 「记录交易 → 仅登记买入批次」：数量已在持仓里，只补批次日期，不重复加持仓。 */
    @Test
    fun lotOnlyBackfillUnlocksResidualWithoutDoubleCounting() {
        val portfolio = Portfolio(positions = mutableListOf(position(300.0)))
        val book = TradeLotLedger.migrate(emptyList())

        val backfilled = TradeLotLedger.buy(book, buy("2026-10-21", 300.0))

        assertEquals(300.0, portfolio.positions.single().quantity, 0.001)
        val availability = TradeLotLedger.availability(backfilled, portfolio.positions, "2026-10-22").single()
        assertEquals(300.0, availability.sellableToday, 0.001)
        assertEquals(0.0, availability.lockedToday, 0.001)
        assertEquals(0.0, availability.untrackedQuantity, 0.001)
    }
}
