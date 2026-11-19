package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PositionAllocationJsonTest {
    @Test
    fun roundTripPreservesIndependentConfigs() {
        val source = mapOf(
            "sh600000" to PositionAllocationConfig.normal(600.0),
            "sz000001" to PositionAllocationConfig.riskObservation(),
        )

        val decoded = PositionAllocationJson.decode(PositionAllocationJson.encode(source))

        assertEquals(source, decoded)
    }

    @Test
    fun malformedJsonIsRejectedSoStoreCanKeepOriginalRawValue() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionAllocationJson.decode("{broken")
        }
    }

    @Test
    fun invalidEntryIsRejectedInsteadOfSilentlyBecomingNormalMode() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionAllocationJson.decode(
                "{\"sh600000\":{\"mode\":\"NORMAL\",\"base_quantity_target\":-1}}",
            )
        }
    }
}
