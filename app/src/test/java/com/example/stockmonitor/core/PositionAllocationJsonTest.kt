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
    fun invalidEntryIsSkippedInsteadOfWipingEveryOtherConfig() {
        val decoded = PositionAllocationJson.decode(
            "{\"sh600000\":{\"mode\":\"NORMAL\",\"base_quantity_target\":600}," +
                "\"sz000001\":{\"mode\":\"NOT_A_MODE\",\"base_quantity_target\":100}," +
                "\"sz000002\":{\"mode\":\"NORMAL\",\"base_quantity_target\":-1}}",
        )

        assertEquals(setOf("sh600000"), decoded.keys)
        assertEquals(PositionAllocationConfig.normal(600.0), decoded["sh600000"])
    }
}
