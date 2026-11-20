package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneOffset

class EntryTimingHistoricalReplayTest {
    private data class ReplayPoint(val time: String, val price: Double, val biasPct: Double)

    @Test
    fun huadongFirstBoardAndSecondBoardHaveNoReclaimAlertAtFiveMinuteEndpoints() {
        assertTrue(replay("002685", "2026-11-13").isEmpty())
        assertTrue(replay("002685", "2026-11-16").isEmpty())
    }

    @Test
    fun huashengFirstBoardMorningReclaimPrecedesFirstLimitCloseSample() {
        val hypothetical = replay("600410", "2026-11-13").single()
        assertEquals("09:45", hypothetical.time)
        assertEquals(13.60, hypothetical.price, 0.0001)
        assertEquals(6.10, hypothetical.biasPct, 0.01)
        assertTrue(replay("600410", "2026-11-13", admittedAt = "09:55").isEmpty())
    }

    @Test
    fun huashengNextTradingDayReclaimIsAnObservationAtExtendedPrice() {
        val point = replay("600410", "2026-11-16").single()
        assertEquals("09:40", point.time)
        assertEquals(14.97, point.price, 0.0001)
        assertEquals(11.00, point.biasPct, 0.01)
        val assessment = TrendAssessment(
            shortTerm = ShortTermTrend.UP,
            major = MajorTrend.UP,
            reason = "回放隔离MA5乖离门控",
            allowsOrdinaryBuy = true,
            allowsAddPosition = true,
            allowsTBuy = true,
            allowsTSell = true,
            allowsBottomReversal = false,
            riskOnly = false,
        )

        assertFalse(
            ObservationEntryPolicy.leaderCanBuy(
                recentLimitUpDays = 1,
                relativeStrengthPct = 8.0,
                volumeRatio = 1.2,
                price = point.price,
                trend = null,
                trendAssessment = assessment,
                stopPrice = 13.0,
                biasFromMa5Pct = point.biasPct,
            ),
        )
    }

    @Test
    fun huashengNovemberSeventeenHasNoOpeningPriceDip() {
        assertTrue(replay("600410", "2026-11-17").isEmpty())
    }

    @Test
    fun huashengNovemberEighteenHasShapeIfCandidateAgeFilterIsBypassed() {
        val hypothetical = replay("600410", "2026-11-18").single()
        assertEquals("09:40", hypothetical.time)
        assertEquals(16.05, hypothetical.price, 0.0001)
        assertEquals(10.63, hypothetical.biasPct, 0.01)
    }

    @Test
    fun observationAgeCountsAdmissionDayAndIsNotDaysSinceFirstBoard() {
        var candidate = ObservationCandidate(
            code = "sh600410",
            name = "华胜天成",
            addedDate = "2026-11-13",
            lastEvaluatedDate = null,
            stage = ObservationStage.OBSERVING,
            score = 0,
            weakDays = 0,
            strategy = ObservationStrategy.LEADER,
            limitStreak = 1,
        )
        val days = listOf("2026-11-13", "2026-11-16", "2026-11-17", "2026-11-18")
        days.forEachIndexed { index, date ->
            val signal = ObservationSignal(
                date = date,
                price = 14.0,
                hasScreenSignal = true,
                confirmedEntry = false,
                canBuy = false,
                trendWeak = false,
                stopPrice = 12.0,
                changePct = 0.0,
                strategy = ObservationStrategy.LEADER,
                recentLimitUpDays = 1,
            )
            candidate = ObservationPool.evaluate(candidate, signal)
            assertEquals(index + 1, candidate.observedDays)
            assertEquals(index + 1, ObservationPool.evaluate(candidate, signal).observedDays)
        }
    }

    private fun replay(code: String, date: String, admittedAt: String = "09:30"): List<ReplayPoint> {
        val resource = requireNotNull(javaClass.getResourceAsStream("/entry-timing-2026-11-19/$code.json"))
        val fixture = resource.bufferedReader().use { JSONObject(it.readText()) }
        val daily = fixture.getJSONArray("daily")
        val dayIndex = (0 until daily.length()).first { daily.getJSONArray(it).getString(0) == date }
        val open = daily.getJSONArray(dayIndex).getDouble(1)
        val previousClose = daily.getJSONArray(dayIndex - 1).getDouble(2)
        val priorCloses = (dayIndex - 4 until dayIndex).map { daily.getJSONArray(it).getDouble(2) }
        val limitPrice = BigDecimal.valueOf(previousClose).multiply(BigDecimal("1.10"))
            .setScale(2, RoundingMode.HALF_UP).toDouble()
        val minutes = fixture.getJSONArray("minutes")
        val tracker = FirstBoardReclaimTracker()
        var runningLow = Double.POSITIVE_INFINITY
        val alerts = mutableListOf<ReplayPoint>()
        for (index in 0 until minutes.length()) {
            val row = minutes.getJSONArray(index)
            val timestampText = row.getString(0)
            if (!timestampText.startsWith(date)) continue
            runningLow = minOf(runningLow, row.getDouble(3))
            val time = timestampText.substring(11, 16)
            if (time < admittedAt) continue
            val price = row.getDouble(4)
            val timestamp = LocalDateTime.parse(timestampText.replace(' ', 'T'))
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
            val sample = FirstBoardReclaimTracker.Sample(
                code = if (code == "002685") "sz$code" else "sh$code",
                name = code,
                tradingDay = date,
                previousClose = previousClose,
                open = open,
                low = runningLow,
                price = price,
                timestamp = timestamp,
                recentFirstBoard = true,
                limitUp = price >= limitPrice - 0.005,
            )
            if (tracker.update(sample) != null) {
                val ma5 = (priorCloses.sum() + price) / 5.0
                alerts.add(ReplayPoint(time, price, (price / ma5 - 1.0) * 100.0))
            }
        }
        return alerts
    }
}
