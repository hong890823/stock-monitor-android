package com.example.stockmonitor

import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.PortfolioSnapshotJson
import com.example.stockmonitor.core.RowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioSnapshotTest {

    @Test
    fun snapshotAdvancesOnlyWhenQuoteDateMatchesToday() {
        val quote = Quote(
            code = "sh600000",
            name = "浦发银行",
            price = 10.0,
            prevClose = 9.8,
            open = 9.9,
            high = 10.1,
            low = 9.8,
            volume = 1_000.0,
            turnover = 10_000.0,
            quoteDate = "2026-10-13",
        )

        assertTrue(PortfolioSnapshotJson.hasFreshTradingDate(listOf(quote), "2026-10-13"))
        assertFalse(PortfolioSnapshotJson.hasFreshTradingDate(listOf(quote), "2026-10-14"))
    }

    @Test
    fun mergeKeepsSameDayRowsForStocksThatFailedThisRun() {
        val previous = com.example.stockmonitor.core.PortfolioSnapshotState(
            date = "2026-10-13",
            rows = mapOf(
                "sh600000" to row("sh600000", 10.0),
                "sh600001" to row("sh600001", 20.0),
            ),
        )

        val merged = PortfolioSnapshotJson.mergeRows(
            current = previous,
            today = "2026-10-13",
            freshRows = mapOf("sh600000" to row("sh600000", 11.0)),
            activeCodes = setOf("sh600000", "sh600001"),
        )

        assertEquals(11.0, merged.getValue("sh600000").price, 1e-9)
        assertEquals(20.0, merged.getValue("sh600001").price, 1e-9)
    }

    private fun row(code: String, price: Double) = RowSnapshot(
        code = code,
        signal = "持有观察",
        score = 1,
        support = 9.0,
        resistance = 12.0,
        price = price,
        changePct = 0.0,
        volumeRatio = 1.0,
    )
}
