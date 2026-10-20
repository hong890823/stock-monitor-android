package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaperTradingTest {
    private val config = PaperTradingConfig(
        commissionRate = 0.0002,
        minimumCommission = 5.0,
        stampDutyRate = 0.0005,
        transferFeeRate = 0.00001,
        lotSize = 100,
    )

    @Test
    fun buyDeductsGrossValueAndBuyFees() {
        val ledger = PaperTradingLedger(initialCash = 10_000.0, config = config)

        val receipt = ledger.buy("sh600000", "测试股", 100, 10.0, "2026-10-14")

        assertEquals(1005.01, receipt.totalCashChange, 0.0001)
        assertEquals(8994.99, ledger.cash, 0.0001)
        assertEquals(100, ledger.position("sh600000")!!.quantity)
    }

    @Test
    fun buyCannotBeSoldOnTheSameTradingDay() {
        val ledger = PaperTradingLedger(initialCash = 10_000.0, config = config)
        ledger.buy("sh600000", "测试股", 100, 10.0, "2026-10-14")

        assertThrows(IllegalStateException::class.java) {
            ledger.sell("sh600000", 100, 11.0, "2026-10-14")
        }
    }

    @Test
    fun nextDaySellAppliesStampDutyAndReportsRealizedPnl() {
        val ledger = PaperTradingLedger(initialCash = 10_000.0, config = config)
        ledger.buy("sh600000", "测试股", 100, 10.0, "2026-10-14")

        val receipt = ledger.sell("sh600000", 100, 11.0, "2026-10-15")

        assertEquals(1094.439, receipt.totalCashChange, 0.0001)
        assertEquals(89.429, receipt.realizedPnl!!, 0.0001)
        assertEquals(10_089.429, ledger.cash, 0.0001)
        assertEquals(0, ledger.position("sh600000")?.quantity ?: 0)
    }
}
