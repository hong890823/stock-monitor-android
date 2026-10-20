package com.example.stockmonitor

import com.example.stockmonitor.analysis.SectorTurnoverTracker
import com.example.stockmonitor.core.SectorItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectorTurnoverTrackerTest {

    private fun item(name: String, volume: Double) = SectorItem(
        name = name,
        change = 1.0,
        count = 50,
        volume = volume,
        lead = "",
        leadPct = "",
    )

    @Test
    fun needsTwoCompletedIntervalsBeforeReportingAcceleration() {
        val tracker = SectorTurnoverTracker()

        assertNull(tracker.update(listOf(item("半导体", 100.0)), 1_000L)["半导体"])
        assertNull(tracker.update(listOf(item("半导体", 120.0)), 2_000L)["半导体"])
        assertEquals(2.0, tracker.update(listOf(item("半导体", 160.0)), 3_000L)["半导体"]!!, 1e-9)
    }

    @Test
    fun cumulativeTurnoverResetDoesNotCreateFalseFlowSignal() {
        val tracker = SectorTurnoverTracker()

        tracker.update(listOf(item("半导体", 100.0)), 1_000L)
        tracker.update(listOf(item("半导体", 120.0)), 2_000L)
        assertNull(tracker.update(listOf(item("半导体", 10.0)), 3_000L)["半导体"])
        assertNull(tracker.update(listOf(item("半导体", 30.0)), 4_000L)["半导体"])
    }
}
