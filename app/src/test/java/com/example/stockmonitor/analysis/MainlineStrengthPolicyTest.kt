package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainlineStrengthPolicyTest {

    private val robot = SectorStrengthPoint(
        kind = BoardKind.CONCEPT,
        date = "2026-10-26",
        name = "机器人概念",
        code = "BK0800",
        changePct = 2.0,
        changeRankPct = 0.10,
        turnoverRankPct = 0.10,
        mainNetRatioPct = 1.0,
    )

    @Test
    fun oneStrongDayIsNotMainline() {
        val evidence = MainlineStrengthPolicy.assess(
            history = listOf(SectorStrengthDay("2026-10-26", listOf(robot))),
            current = robot,
        )

        assertFalse(evidence.isMainline)
        assertFalse(evidence.strongWatch)
        assertEquals(1, evidence.strongDays)
    }

    @Test
    fun threeStrongOfFiveWithLatestStrongIsMainline() {
        val history = listOf(
            day("2026-10-20", robot),
            day("2026-10-21", robot.copy(changePct = -1.0, changeRankPct = 0.80, turnoverRankPct = 0.80)),
            day("2026-10-22", robot),
            day("2026-10-23", robot.copy(changePct = -1.0, changeRankPct = 0.80, turnoverRankPct = 0.80)),
            day("2026-10-26", robot),
        )

        val evidence = MainlineStrengthPolicy.assess(history, robot)

        assertTrue(evidence.isMainline)
        assertTrue(evidence.strongWatch)
        assertEquals(3, evidence.strongDays)
        assertEquals(5, evidence.windowDays)
        assertTrue(evidence.latestStrong)
    }

    @Test
    fun firstNormalWeakDayAfterMainlineIsRetainedAsDivergence() {
        val latest = robot.copy(changePct = -0.5, changeRankPct = 0.70, turnoverRankPct = 0.70)
        val history = listOf(
            day("2026-10-20", robot),
            day("2026-10-21", robot),
            day("2026-10-22", robot),
            day("2026-10-23", robot),
            day("2026-10-26", latest),
        )

        val evidence = MainlineStrengthPolicy.assess(history, latest)

        assertTrue(evidence.isMainline)
        assertTrue(evidence.strongWatch)
        assertFalse(evidence.latestStrong)
    }

    @Test
    fun secondConsecutiveWeakDayEndsMainline() {
        val firstWeak = robot.copy(changePct = -0.5, changeRankPct = 0.70, turnoverRankPct = 0.70)
        val secondWeak = firstWeak.copy(changePct = -0.7)
        val history = listOf(
            day("2026-10-20", robot),
            day("2026-10-21", robot),
            day("2026-10-22", robot),
            day("2026-10-23", robot),
            day("2026-10-24", firstWeak),
        )

        val evidence = MainlineStrengthPolicy.assess(history, secondWeak)

        assertFalse(evidence.isMainline)
        assertFalse(evidence.strongWatch)
        assertFalse(evidence.latestStrong)
    }

    @Test
    fun severeWeaknessEndsMainlineImmediately() {
        val broken = robot.copy(changePct = -3.0, changeRankPct = 0.90, turnoverRankPct = 0.90)
        val history = listOf(
            day("2026-10-20", robot),
            day("2026-10-21", robot),
            day("2026-10-22", robot),
            day("2026-10-23", robot),
        )

        val evidence = MainlineStrengthPolicy.assess(history, broken)

        assertFalse(evidence.isMainline)
        assertFalse(evidence.strongWatch)
    }

    @Test
    fun singleDayChangeWithoutTurnoverOrFlowIsWeak() {
        val weak = robot.copy(
            changePct = 1.0,
            changeRankPct = 0.25,
            turnoverRankPct = 0.50,
            mainNetRatioPct = -1.0,
        )

        assertFalse(MainlineStrengthPolicy.isStrongDay(weak))
    }

    @Test
    fun upsertReplacesSameTradingDateAndKeepsTenDays() {
        var history = emptyList<SectorStrengthDay>()
        (1..11).forEach { dayNumber ->
            history = MainlineStrengthPolicy.upsert(
                history,
                SectorStrengthDay("2026-10-%02d".format(dayNumber), listOf(robot)),
            )
        }
        history = MainlineStrengthPolicy.upsert(
            history,
            SectorStrengthDay("2026-10-11", listOf(robot.copy(name = "替换后"))),
        )

        assertEquals(10, history.size)
        assertEquals("2026-10-02", history.first().date)
        assertEquals("替换后", history.last().points.single().name)
    }

    private fun day(date: String, point: SectorStrengthPoint) = SectorStrengthDay(date, listOf(point))
}
