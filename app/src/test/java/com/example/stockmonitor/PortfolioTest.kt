package com.example.stockmonitor

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.resolvedAvailableCapital
import com.example.stockmonitor.core.availableCapitalIsManual
import com.example.stockmonitor.core.autoAvailableCapital
import com.example.stockmonitor.core.cashPosition
import com.example.stockmonitor.core.materializeCapitalCorrection
import com.example.stockmonitor.core.withCapitalCorrection
import com.example.stockmonitor.core.HoldingSyncStatus
import com.example.stockmonitor.core.syncHoldingInput
import com.example.stockmonitor.core.updatePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun capitalCorrectionFloatsWithHoldingChangesInsteadOfStayingPinned() {
        val seeded = Portfolio(
            totalCapital = 100_000.0,
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        ).withCapitalCorrection(30_000.0)

        assertEquals(30_000.0, seeded.resolvedAvailableCapital(), 0.0)
        assertTrue(seeded.availableCapitalIsManual())

        // 持仓成本从 12,000 降到 6,000，可用现金应回流 6,000；本金不动。
        val after = seeded.updatePosition("sh600000", 500.0, 12.0)
        assertEquals(36_000.0, after.resolvedAvailableCapital(), 0.0)
        assertEquals(100_000.0, after.totalCapital, 0.0)
    }

    @Test
    fun materializeCapitalCorrectionTurnsLegacyPinIntoFloatingCorrection() {
        val legacy = Portfolio(
            totalCapital = 100_000.0,
            availableCapital = 30_000.0,
            availableCapitalOverride = 25_000.0,
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        )

        val materialized = legacy.materializeCapitalCorrection()
        assertEquals(25_000.0, materialized.resolvedAvailableCapital(), 0.0)
        assertEquals(0.0, materialized.availableCapital, 0.0)
        assertNull(materialized.availableCapitalOverride)

        // 折算之后改持仓就该跟着变，不再钉死在 25,000。
        val after = materialized.updatePosition("sh600000", 500.0, 12.0)
        assertEquals(31_000.0, after.resolvedAvailableCapital(), 0.0)
    }

    @Test
    fun clearingCapitalCorrectionReturnsToPureAuto() {
        val corrected = Portfolio(
            totalCapital = 100_000.0,
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        ).withCapitalCorrection(30_000.0)

        val cleared = corrected.withCapitalCorrection(null)

        assertEquals(88_000.0, cleared.resolvedAvailableCapital(), 0.0)
        assertFalse(cleared.availableCapitalIsManual())
    }

    @Test
    fun correctionCalibratedBeforeTotalCapitalIsSetDoesNotDoubleCount() {
        // 总资金还没设就先按真实现金校准。
        val calibrated = Portfolio(
            positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)),
        ).withCapitalCorrection(30_000.0)
        assertEquals(30_000.0, calibrated.resolvedAvailableCapital(), 0.0)

        // 之后补上总资金：仍是校准值，不能在自动值上再叠一遍。
        val withTotal = calibrated.copy(totalCapital = 100_000.0).materializeCapitalCorrection()
        assertEquals(30_000.0, withTotal.resolvedAvailableCapital(), 0.0)

        // 再改持仓就跟着浮动。
        val after = withTotal.updatePosition("sh600000", 500.0, 12.0)
        assertEquals(36_000.0, after.resolvedAvailableCapital(), 0.0)
    }

    @Test
    fun cashPositionExplainsWhyAutoValueIsUnavailable() {
        val noTotal = Portfolio(positions = mutableListOf(Position("sh600000", "浦发银行", 1_000.0, 12.0)))
        assertNull(noTotal.autoAvailableCapital())
        assertNotNull(noTotal.cashPosition().blockedReason)

        val missingCost = Portfolio(
            totalCapital = 100_000.0,
            positions = mutableListOf(
                Position("sh600000", "浦发银行", 1_000.0, 12.0),
                Position("sz000001", "平安银行", 500.0, 0.0),
            ),
        )
        assertNull(missingCost.autoAvailableCapital())
        assertTrue(missingCost.cashPosition().blockedReason!!.contains("未填成本"))
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
