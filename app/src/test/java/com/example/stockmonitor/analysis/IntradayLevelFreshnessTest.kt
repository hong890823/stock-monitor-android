package com.example.stockmonitor.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradayLevelFreshnessTest {
    @Test
    fun onlyTodaysAnalysisLevelsCanDriveIntradayDecisions() {
        assertTrue(IntradayLevelFreshness.usable("2026-11-20", "2026-11-20"))
        assertFalse(IntradayLevelFreshness.usable("2026-11-19", "2026-11-20"))
        assertFalse(IntradayLevelFreshness.usable("", "2026-11-20"))
        assertFalse(IntradayLevelFreshness.usable(null, "2026-11-20"))
    }
}