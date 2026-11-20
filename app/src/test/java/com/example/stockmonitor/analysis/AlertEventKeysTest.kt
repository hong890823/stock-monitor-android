package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlertEventKeysTest {

    @Test
    fun deeperLowIsANewEventButSameLowDedupes() {
        assertEquals(AlertEventKeys.dipRebound(-3.0), AlertEventKeys.dipRebound(-3.0))
        assertNotEquals(AlertEventKeys.dipRebound(-3.0), AlertEventKeys.dipRebound(-4.2))
    }

    @Test
    fun reboundKindsKeepIndependentDailyKeys() {
        val confirmed = AlertEventKeys.reboundTrack(ReboundTracker.Kind.CONFIRMED)
        val trap = AlertEventKeys.reboundTrack(ReboundTracker.Kind.TRAP)
        assertNotEquals(confirmed, trap)
        assertEquals(confirmed, AlertEventKeys.reboundTrack(ReboundTracker.Kind.CONFIRMED))
    }
}