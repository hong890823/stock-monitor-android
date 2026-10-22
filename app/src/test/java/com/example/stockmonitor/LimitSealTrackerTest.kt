package com.example.stockmonitor

import com.example.stockmonitor.analysis.LimitSealTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封板 / 炸板提醒：触涨停价报封板（带封单强度）、回落报炸板，反复开板受日限约束。
 * 形态以 2026-10-22 华天科技（15.68 封板、封单 8500 万股）校准。
 */
class LimitSealTrackerTest {

    private val day = "2026-10-22"
    private val t0 = 1_000_000_000L

    private fun sample(
        price: Double,
        buy1Volume: Double? = null,
        minute: Double = 0.0,
        held: Boolean = true,
    ) = LimitSealTracker.Sample(
        code = "sz002185",
        name = "华天科技",
        price = price,
        prevClose = 14.25,
        limitPct = 10.0,
        buy1Volume = buy1Volume,
        timestamp = t0 + (minute * 60_000).toLong(),
        held = held,
    )

    @Test
    fun sealReportsOnceWithSealAmountThenBreakReports() {
        val tracker = LimitSealTracker()
        val events = listOf(
            sample(price = 15.20, minute = 1.0),
            sample(price = 15.68, buy1Volume = 85_000_000.0, minute = 2.0),
            sample(price = 15.68, buy1Volume = 84_000_000.0, minute = 3.0),
            sample(price = 15.20, minute = 4.0),
        ).flatMap { tracker.update(listOf(it), day) }

        assertEquals(2, events.size)
        val seal = events[0]
        assertEquals(LimitSealTracker.Kind.SEAL, seal.kind)
        assertTrue(seal.title.contains("封板"))
        assertTrue(seal.body.contains("封单"))
        assertTrue(seal.body.contains("13."))
        assertFalse(tracker.isSealed("sz002185"))
        val broken = events[1]
        assertEquals(LimitSealTracker.Kind.BREAK, broken.kind)
        assertTrue(broken.title.contains("炸板"))
        assertTrue(broken.vibrate)
    }

    @Test
    fun missingSealAmountStillReportsSealFact() {
        val tracker = LimitSealTracker()
        val events = tracker.update(listOf(sample(price = 15.68, buy1Volume = null)), day)

        val seal = events.single()
        assertEquals(LimitSealTracker.Kind.SEAL, seal.kind)
        assertFalse(seal.body.contains("封单"))
        assertTrue(tracker.isSealed("sz002185"))
    }

    @Test
    fun repeatedOpenAndResealStopsAtDailyCap() {
        val tracker = LimitSealTracker()
        val events = mutableListOf<LimitSealTracker.Event>()
        for (i in 0 until 5) {
            events += tracker.update(listOf(sample(price = 15.68, minute = i * 2.0)), day)
            events += tracker.update(listOf(sample(price = 15.30, minute = i * 2.0 + 1.0)), day)
        }

        assertEquals(3, events.count { it.kind == LimitSealTracker.Kind.SEAL })
        assertEquals(3, events.count { it.kind == LimitSealTracker.Kind.BREAK })
    }
}
