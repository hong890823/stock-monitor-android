package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Quote

object ObservationRefreshPolicy {
    fun shouldPersist(
        evaluatedQuote: Quote?,
        currentQuote: Quote?,
    ): Boolean = evaluatedQuote != null && evaluatedQuote == currentQuote
}
