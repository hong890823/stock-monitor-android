package com.example.stockmonitor

import com.example.stockmonitor.analysis.TradeCalculator
import org.junit.Test

class TradeCalculatorTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteInput() {
        TradeCalculator.calculate(
            holdingShares = Double.NaN,
            averageCost = 10.0,
            side = "买入",
            tradeShares = 100.0,
            tradePrice = 10.0,
        )
    }

}
