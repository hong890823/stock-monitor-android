package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.PositionAllocationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionAllocationPolicyTest {
    @Test
    fun normalModeDerivesBaseAndRollingFromCurrentQuantity() {
        val allocation = PositionAllocationPolicy.derive(
            Position("sh600000", "浦发银行", 1_000.0, 8.5),
            PositionAllocationConfig.normal(baseQuantityTarget = 600.0),
        )

        assertEquals(600.0, allocation.baseQuantity, 0.000001)
        assertEquals(400.0, allocation.rollingQuantity, 0.000001)
        assertEquals(0.0, allocation.riskObservationQuantity, 0.000001)
        assertTrue(allocation.configured)
    }

    @Test
    fun baseTargetCannotExceedCurrentQuantity() {
        val allocation = PositionAllocationPolicy.derive(
            Position("sh600000", "浦发银行", 300.0, 8.5),
            PositionAllocationConfig.normal(baseQuantityTarget = 600.0),
        )

        assertEquals(300.0, allocation.baseQuantity, 0.000001)
        assertEquals(0.0, allocation.rollingQuantity, 0.000001)
    }

    @Test
    fun riskObservationModeDoesNotInventBuyBackQuantity() {
        val allocation = PositionAllocationPolicy.derive(
            Position("sh600000", "浦发银行", 1_000.0, 8.5),
            PositionAllocationConfig.riskObservation(),
        )

        assertEquals(0.0, allocation.baseQuantity, 0.000001)
        assertEquals(0.0, allocation.rollingQuantity, 0.000001)
        assertEquals(1_000.0, allocation.riskObservationQuantity, 0.000001)
        assertTrue(allocation.configured)
    }

    @Test
    fun unconfiguredPositionIsShownAsTemporaryObservation() {
        val allocation = PositionAllocationPolicy.derive(
            Position("sh600000", "浦发银行", 123.5, 8.5),
            null,
        )

        assertFalse(allocation.configured)
        assertEquals(0.0, allocation.baseQuantity, 0.000001)
        assertEquals(0.0, allocation.rollingQuantity, 0.000001)
        assertEquals(123.5, allocation.riskObservationQuantity, 0.000001)
        assertEquals("分仓未设置（暂列观察）", allocation.displayText)
    }

    @Test
    fun clearingPositionKeepsConfiguredTargetOutsideDerivedDisplay() {
        val allocation = PositionAllocationPolicy.derive(
            Position("sh600000", "浦发银行", 0.0, 0.0),
            PositionAllocationConfig.normal(baseQuantityTarget = 600.0),
        )

        assertEquals(600.0, allocation.baseQuantityTarget ?: Double.NaN, 0.000001)
        assertEquals(0.0, allocation.totalQuantity, 0.000001)
        assertEquals(0.0, allocation.baseQuantity, 0.000001)
        assertEquals(0.0, allocation.rollingQuantity, 0.000001)
        assertEquals(0.0, allocation.riskObservationQuantity, 0.000001)
    }
}
