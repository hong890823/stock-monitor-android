package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertLedgerCodesTest {
    @Test
    fun collectsCodesFromBuyAndEventLedgersForTodayOnly() {
        val codes = AlertLedgerCodes.codes(
            today = "2026-11-20",
            codeKeys = setOf("2026-11-20:sh600186", "2026-11-19:sz000001"),
            eventKeys = setOf(
                "2026-11-20:firstBoardReclaim:sh600156",
                "2026-11-20:observationBreakoutRisk:sh600156",
                "2026-11-20:reboundTrack:PRICE:sz002594",
                "2026-11-19:firstBoardReclaim:sh600000",
            ),
        )

        assertEquals(setOf("sh600186", "sh600156", "sz002594"), codes)
    }

    @Test
    fun ignoresBlankAndMalformedKeys() {
        val codes = AlertLedgerCodes.codes(
            today = "2026-11-20",
            codeKeys = setOf("2026-11-20:"),
            eventKeys = setOf("2026-11-20:type:"),
        )

        assertEquals(emptySet<String>(), codes)
    }
}