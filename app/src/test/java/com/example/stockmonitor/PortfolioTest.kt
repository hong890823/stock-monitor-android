package com.example.stockmonitor

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.resolvedAvailableCapital
import com.example.stockmonitor.core.availableCapitalIsManual
import com.example.stockmonitor.core.HoldingSyncStatus
import com.example.stockmonitor.core.syncHoldingInput
import com.example.stockmonitor.core.updatePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PortfolioTest {

    @Test
    fun updatePositionAllowsZeroQuantityAndCostWithoutChangingOtherPositions() {
        val portfolio = Portfolio(
            positions = mutableListOf(
                Position("sh600000", "浦发银行", 1_000.0, 8.5),
                Position("sz000001", "平安银行", 500.0, 10.0),
            ),
        )

        val updated = portfolio.updatePosition("sh600000", 0.0, 0.0)

        val edited = updated.positions.firstOrNull { it.code == "sh600000" }
        val unchanged = updated.positions.firstOrNull { it.code == "sz000001" }
        assertNotNull(edited)
        assertEquals(0.0, edited!!.quantity, 0.0)
        assertEquals(0.0, edited.cost, 0.0)
        assertEquals(Position("sz000001", "平安银行", 500.0, 10.0), unchanged)
    }

    @Test
    fun resolvedAvailableCapitalUsesHoldingCostWhenNoCorrectionIsProvided() {
        val portfolio = Portfolio(
            totalCapital = 100_000.0,
            positions = mutableListOf(
                Position("sh600000", "浦发银行", 1_000.0, 12.0),
                Position("sz000001", "平安银行", 500.0, 20.0),
            ),
        )

        assertEquals(78_000.0, portfolio.resolvedAvailableCapital(), 0.0)
        assertEquals(false, portfolio.availableCapitalIsManual())
    }

    @Test
    fun resolvedAvailableCapitalPrefersManualCorrection() {
        val portfolio = Portfolio(
            totalCapital = 100_000.0,
            availableCapital = 30_000.0,
            availableCapitalOverride = 25_000.0,
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        )

        assertEquals(25_000.0, portfolio.resolvedAvailableCapital(), 0.0)
        assertEquals(true, portfolio.availableCapitalIsManual())
    }

    @Test
    fun zeroManualCorrectionRemainsDifferentFromAutomaticMode() {
        val portfolio = Portfolio(
            totalCapital = 100_000.0,
            availableCapitalOverride = 0.0,
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        )

        assertEquals(0.0, portfolio.resolvedAvailableCapital(), 0.0)
        assertEquals(true, portfolio.availableCapitalIsManual())
    }

    @Test
    fun resolvedAvailableCapitalKeepsLegacyNonZeroValue() {
        val portfolio = Portfolio(totalCapital = 100_000.0, availableCapital = 40_000.0)

        assertEquals(40_000.0, portfolio.resolvedAvailableCapital(), 0.0)
        assertEquals(true, portfolio.availableCapitalIsManual())
    }

    @Test
    fun syncHoldingInputMatchesCodeWithoutExchangePrefix() {
        val result = syncHoldingInput(
            listOf(Position("sh600000", "浦发银行", 1_000.0, 8.5)),
            "600000",
        )

        assertEquals(HoldingSyncStatus.MATCHED, result.status)
        assertEquals("1000,8.5", result.input)
    }

    @Test
    fun syncHoldingInputMatchesStockNameAndFormatsHolding() {
        val result = syncHoldingInput(
            listOf(Position("sz000001", "平安银行", 500.0, 10.25)),
            "平安",
        )

        assertEquals(HoldingSyncStatus.MATCHED, result.status)
        assertEquals("500,10.25", result.input)
    }

    @Test
    fun syncHoldingInputRejectsAmbiguousPartialMatches() {
        val result = syncHoldingInput(
            listOf(
                Position("sh600000", "浦发银行", 1_000.0, 8.5),
                Position("sh600001", "浦发证券", 500.0, 9.5),
            ),
            "浦发",
        )

        assertEquals(HoldingSyncStatus.AMBIGUOUS, result.status)
        assertEquals(null, result.input)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun syncHoldingInputReportsMissingHolding() {
        val result = syncHoldingInput(
            listOf(Position("sh600000", "浦发银行", 1_000.0, 8.5)),
            "平安银行",
        )

        assertEquals(HoldingSyncStatus.NOT_FOUND, result.status)
        assertEquals(null, result.input)
    }
}
