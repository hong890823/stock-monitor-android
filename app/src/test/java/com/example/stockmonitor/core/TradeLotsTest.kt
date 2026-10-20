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
}
