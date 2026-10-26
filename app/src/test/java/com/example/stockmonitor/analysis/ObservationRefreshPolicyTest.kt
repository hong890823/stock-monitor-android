package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationRefreshPolicyTest {
    private val quote = Quote(
        code = "sz003021",
        name = "兆威机电",
        price = 10.0,
        prevClose = 9.5,
        open = 9.6,
        high = 10.1,
        low = 9.5,
        volume = 1_000.0,
        turnover = 10_000.0,
        quoteDate = "2026-10-21",
    )

    @Test
    fun onlyEvaluationUsingCurrentQuoteCanPersist() {
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote))
        assertFalse(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(price = 10.2)))
        assertFalse(ObservationRefreshPolicy.shouldPersist(null, quote))
        assertFalse(ObservationRefreshPolicy.shouldPersist(quote, null))
    }
}
