package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationPolicyTest {
    private fun highPosition(phase: HighPositionPhase) = HighPositionDecision(
        phase = phase,
        reason = phase.label,
        allowBuy = false,
        allowSecondWaveEntry = false,
        allowHoldBase = false,
        riskPriority = phase == HighPositionPhase.DISTRIBUTION_RISK,
    )

    @Test
    fun acceleratingNeedsNearHighRunUpVolumeAndTodayUp() {
        val result = AccelerationPolicy.assess(
            price = 10.0,
            run5Pct = 12.0,
            volumeRatio = 1.5,
            high60 = 10.2,
            todayPct = 2.0,
        )

        assertEquals(AccelerationState.ACCELERATING, result.state)
        assertTrue(result.basis.contains("放量延伸"))
    }

    @Test
    fun flatTodayBecomesDivergenceInsteadOfContinuation() {
        val result = AccelerationPolicy.assess(10.0, 12.0, 1.5, 10.2, 0.8)

        assertEquals(AccelerationState.DIVERGING, result.state)
    }

    @Test
    fun notNearHighIsClearEvenWhenRunUpAndVolumeAreHigh() {
        val result = AccelerationPolicy.assess(9.0, 15.0, 2.0, 10.2, 2.0)

        assertEquals(AccelerationState.CLEAR, result.state)
    }

    @Test
    fun missingVolumeIsInsufficientInsteadOfGuessing() {
        val result = AccelerationPolicy.assess(10.0, 12.0, null, 10.2, 2.0)

        assertEquals(AccelerationState.INSUFFICIENT, result.state)
        assertTrue(result.basis.contains("缺"))
    }

    @Test
    fun entryBlockerExplainsEvidenceAndOnlyBlocksAccelerating() {
        val accelerating = AccelerationPolicy.assess(10.0, 15.0, 1.6, 10.2, 2.0)
        val blocker = AccelerationPolicy.entryBlocker(accelerating)

        assertNotNull(blocker)
        assertTrue(blocker!!.contains("近5日涨15.0%"))
        assertTrue(blocker.contains("量比1.6"))
        assertNull(AccelerationPolicy.entryBlocker(accelerating.copy(state = AccelerationState.DIVERGING)))
    }

    @Test
    fun entryBlockerExemptsLeaderAndConfirmedPullback() {
        val accelerating = AccelerationPolicy.assess(10.0, 15.0, 1.6, 10.2, 2.0)

        assertNull(AccelerationPolicy.entryBlocker(accelerating, leader = true))
        assertNull(AccelerationPolicy.entryBlocker(accelerating, confirmedPullback = true))
    }

    @Test
    fun entryBlockerCanBeDisabledForResearchRollback() {
        val accelerating = AccelerationPolicy.assess(10.0, 15.0, 1.6, 10.2, 2.0)
        val previous = AccelerationPolicy.entryBlockEnabled
        try {
            AccelerationPolicy.entryBlockEnabled = false
            assertNull(AccelerationPolicy.entryBlocker(accelerating))
        } finally {
            AccelerationPolicy.entryBlockEnabled = previous
        }
    }

    @Test
    fun labelsAndNotesStayCopyOnly() {
        val accelerating = AccelerationPolicy.assess(10.0, 15.0, 1.6, 10.2, 2.0)
        val diverging = AccelerationPolicy.assess(10.0, 15.0, 1.6, 10.2, 0.5)

        assertTrue(AccelerationPolicy.watchLabel(accelerating)!!.contains("不追高"))
        assertTrue(AccelerationPolicy.watchLabel(diverging)!!.contains("高位放量分歧"))
        assertNull(AccelerationPolicy.watchLabel(null))
        assertTrue(AccelerationPolicy.breakoutNote(accelerating)!!.contains("仅观察不追"))
        assertTrue(AccelerationPolicy.tSellNote(accelerating)!!.contains("优先了结"))
        assertNull(AccelerationPolicy.breakoutNote(diverging))
    }

    @Test
    fun intradayFallsBackToHighPositionPhaseWhenHigh60Missing() {
        val result = AccelerationPolicy.assessIntraday(
            price = 10.0,
            todayPct = 2.0,
            run5Pct = 12.0,
            volumeRatio = 1.5,
            high60 = null,
            highPosition = highPosition(HighPositionPhase.EXTENDED_WATCH),
        )

        assertEquals(AccelerationState.ACCELERATING, result.state)
        assertTrue(result.basis.contains("高位状态"))
    }

    @Test
    fun fromBarsComputesRun5AndVolumeRatio() {
        val bars = listOf(
            DayBar("2026-11-16", 10.0, 10.1, 9.9, 10.0, 100.0),
            DayBar("2026-11-17", 10.0, 10.2, 10.0, 10.1, 100.0),
            DayBar("2026-11-18", 10.1, 10.3, 10.1, 10.2, 100.0),
            DayBar("2026-11-19", 10.2, 10.5, 10.2, 10.4, 100.0),
            DayBar("2026-11-20", 10.4, 10.8, 10.4, 10.7, 100.0),
            DayBar("2026-11-23", 10.8, 11.0, 10.7, 11.0, 200.0),
        )

        val result = AccelerationPolicy.assessFromBars(bars)

        assertEquals(AccelerationState.ACCELERATING, result.state)
        assertEquals(10.0, result.run5Pct!!, 0.001)
        assertEquals(2.0, result.volumeRatio!!, 0.001)
    }
}
