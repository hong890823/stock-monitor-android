package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.IntradayAdditionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntradayAdditionJsonTest {
    @Test
    fun roundTripPreservesMaxPriceAndInvalidation() {
        val source = mapOf(
            "sh600000" to IntradayAdditionState("sh600000", "2026-11-17", 10.10, false, "portfolio-a", "eval-a"),
            "sz000001" to IntradayAdditionState("sz000001", "2026-11-17", null, true, "portfolio-b", "eval-b"),
        )

        assertEquals(source, IntradayAdditionJson.decode(IntradayAdditionJson.encode(source)))
    }

    @Test
    fun malformedStateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            IntradayAdditionJson.decode("{broken")
        }
    }
}
