package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFadePolicyTest {
    @Test
    fun indexGapUpAndBreakOpenIsWarning() {
        val finding = GapFadePolicy.evaluate(
            openPrice = 3050.0,
            previousClose = 3030.0,
            highPrice = 3055.0,
            price = 3035.0,
            sessionMinutes = 30,
            holding = false,
        )!!
        assertEquals("index", finding.kind)
        assertEquals("warning", finding.severity)
        assertTrue(finding.brokeOpen)
        assertTrue(GapFadePolicy.indexTitle("上证指数").contains("高开低走"))
        assertTrue(GapFadePolicy.indexReason(finding).contains("跌破开盘价"))
    }

    @Test
    fun indexStillAboveOpenIsNotGapFade() {
        assertNull(
            GapFadePolicy.evaluate(
                openPrice = 3050.0,
                previousClose = 3030.0,
                highPrice = 3055.0,
                price = 3052.0,
                sessionMinutes = 30,
                holding = false,
            ),
        )
    }

    @Test
    fun openingTenMinutesAreIgnored() {
        assertNull(
            GapFadePolicy.evaluate(
                openPrice = 3050.0,
                previousClose = 3030.0,
                highPrice = 3055.0,
                price = 3035.0,
                sessionMinutes = 5,
                holding = false,
            ),
        )
    }

    @Test
    fun holdingFadeTurningNegativeIsDanger() {
        val finding = GapFadePolicy.evaluate(
            openPrice = 10.10,
            previousClose = 10.00,
            highPrice = 10.30,
            price = 9.98,
            sessionMinutes = 30,
            holding = true,
        )!!
        assertEquals("danger", finding.severity)
        assertTrue(GapFadePolicy.holdingBody(finding).contains("做T或减仓"))
    }

    @Test
    fun holdingHighFadeWithoutGapIsDetected() {
        val finding = GapFadePolicy.evaluate(
            openPrice = 10.00,
            previousClose = 10.10,
            highPrice = 10.30,
            price = 9.95,
            sessionMinutes = 60,
            holding = true,
        )!!
        assertEquals("冲高回落", finding.pattern)
    }

    @Test
    fun missingFieldsReturnNull() {
        assertNull(
            GapFadePolicy.evaluate(
                openPrice = null,
                previousClose = 3030.0,
                highPrice = 3055.0,
                price = 3035.0,
                sessionMinutes = 30,
                holding = false,
            ),
        )
    }
}
