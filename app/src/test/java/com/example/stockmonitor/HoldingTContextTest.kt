package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.Quote
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class HoldingTContextTest {
    private val today = "2026-10-15"
    private val cycles = MultiTimeframeSummary(asOf = today, weekTrend = SwingTrend.UP, monthTrend = SwingTrend.UP)
    private fun bars(closes: List<Double>) = closes.mapIndexed { index, close ->
        DayBar(LocalDate.parse(today).minusDays((closes.size - index).toLong()).toString(),
            close, close + 0.1, close - 0.1, close, 1000.0)
    }
    private fun context(history: List<DayBar>, summary: MultiTimeframeSummary = cycles) =
        HoldingTContext.from(history, summary, today, 1000L, history.map { it.date })

    @Test
    fun distinguishesSteadyRiseExtendedRallyAndWeakTrend() {
        assertEquals(HoldingTTrend.UP, context(bars((1..40).map { 10 + it * 0.02 }))!!.trend)
        assertEquals(HoldingTTrend.EXTENDED, context(bars(List(35) { 10.0 } + listOf(10.2, 10.4, 10.6, 10.8, 11.0)))!!.trend)
        assertEquals(HoldingTTrend.WEAK, context(bars((1..40).map { 12 - it * 0.05 }))!!.trend)
    }

    @Test
    fun incompleteCyclesRemainUnknownAndExpiredContextsCannotBeUsed() {
        val result = context(bars((1..40).map { 10 + it * 0.02 }), cycles.copy(issue = "insufficient"))!!
        assertEquals(SwingTrend.MISSING, result.weekTrend)
        assertFalse(result.strongDirection)
        assertTrue(result.isFresh(today, 1000L))
        assertFalse(result.isFresh(today, 1000L + 46 * 60000))
        assertFalse(result.isFresh("2026-10-16", 1000L))
        assertFalse(result.isFresh(today, 999L))
    }

    @Test
    fun missingDatesInvalidPricesAndUnfinishedTodayCannotCreateDailyEvidence() {
        val history = bars(List(40) { 10.0 })
        assertNull(HoldingTContext.from(history.dropLast(1), cycles, today, 1000L, history.map { it.date }))
        assertNull(context(history.dropLast(1) + history.last().copy(close = Double.NaN)))
        val intraday = history + history.last().copy(date = today, close = 15.0, high = 15.0)
        assertEquals(context(history), context(intraday))
    }

    private fun quote(price: Double, volume: Double, date: String = today) = Quote(
        "sh600000", "test", price, 10.0, 10.0, maxOf(10.0, price), minOf(10.0, price), volume, volume * price, date,
    )

    @Test
    fun needsSixtySecondsIncreasingVolumeAndThreeSamplesBeforeConfirmingRebound() {
        val tracker = HoldingTTracker()
        assertNull(tracker.observe(quote(10.0, 100.0), today, 1000L))
        assertNull(tracker.observe(quote(10.02, 110.0), today, 31000L))
        val observed = tracker.observe(quote(10.05, 120.0), today, 61000L)!!
        assertTrue(observed.rising)
        assertFalse(observed.falling)
        assertEquals(0.5, observed.reboundPct, 0.0001)
    }

    @Test
    fun duplicateStaleAndInterruptedQuotesCannotAccumulateConfirmation() {
        val tracker = HoldingTTracker()
        tracker.observe(quote(10.0, 100.0), today, 1000L)
        assertNull(tracker.observe(quote(10.1, 100.0), today, 31000L))
        assertNull(tracker.observe(quote(10.2, 100.0), today, 61000L))
        assertNull(tracker.observe(quote(10.2, 120.0), today, 121000L))
        assertNull(tracker.observe(quote(10.2, 130.0, "2026-10-14"), today, 151000L))
        assertNull(tracker.observe(quote(10.2, 140.0), today, 181000L))
        tracker.clear()
        assertNull(tracker.observe(quote(10.2, 150.0), today, 211000L))
    }

    @Test
    fun rolloverAndVolumeResetAreHandledSeparately() {
        val tracker = HoldingTTracker()
        tracker.observe(quote(10.2, 100.0), today, 1000L)
        tracker.observe(quote(10.1, 110.0), today, 31000L)
        val observed = tracker.observe(quote(10.0, 120.0), today, 61000L)!!
        assertTrue(observed.falling)
        assertFalse(observed.rising)
        assertTrue(observed.retreatPct > 1.9)
        assertNull(tracker.observe(quote(10.0, 80.0), today, 91000L))
        tracker.retainCodes(emptySet())
        assertNull(tracker.observe(quote(10.0, 90.0), today, 121000L))
    }

    @Test
    fun reportSnapshotReusesOnlyMatchingFreshObservedQuotesWithoutAddingSamples() {
        val tracker = HoldingTTracker()
        tracker.observe(quote(10.2, 100.0), today, 1000L)
        tracker.observe(quote(10.1, 110.0), today, 31000L)
        val current = quote(10.0, 120.0)
        val observation = tracker.observe(current, today, 61000L)!!
        assertEquals(observation, tracker.snapshot(current, today, 76000L))
        assertNull(tracker.snapshot(quote(10.05, 120.0), today, 76000L))
        assertNull(tracker.snapshot(quote(10.0, 121.0), today, 76000L))
        assertNull(tracker.snapshot(quote(10.2, 100.0), today, 76000L))
        assertNull(tracker.snapshot(current, "2026-10-16", 76000L))
        assertNull(tracker.snapshot(current, today, 151001L))
        assertNull(tracker.snapshot(current, today, 60000L))
        assertNull(tracker.observe(current, today, 76000L))
        assertEquals(observation, tracker.snapshot(current, today, 76000L))
    }

    @Test
    fun waitingWithoutNewTradesCannotTurnThirtySecondsOfSamplesIntoConfirmation() {
        val tracker = HoldingTTracker()
        tracker.observe(quote(10.2, 100.0), today, 1000L)
        tracker.observe(quote(10.1, 110.0), today, 16000L)
        val current = quote(10.0, 120.0)
        assertNull(tracker.observe(current, today, 31000L))
        assertNull(tracker.snapshot(current, today, 61000L))
        assertNull(tracker.snapshot(current, today, 91000L))
        assertTrue(tracker.observe(quote(9.99, 130.0), today, 61000L)!!.falling)
    }

    @Test
    fun historicalHighAndExtendedRallyNeedRealObservedRolloverBeforeRiskReduction() {
        val history = bars(List(35) { 10.0 } + listOf(10.5, 11.0, 11.5, 12.0, 12.5))
        val recent = context(history)!!
        val window = PricePosition.of(history, today)!!
        assertEquals(HoldingTTrend.EXTENDED, recent.trend)
        val tracker = HoldingTTracker()
        fun evaluate(price: Double, volume: Double, now: Long): HoldingExitDecision? {
            val current = quote(price, volume).copy(prevClose = 12.5)
            return HoldingExitPolicy.evaluate(HoldingExitPolicy.Input(
                price = price, support = 12.0, quantity = 1000.0, cost = 10.0,
                atrPct = recent.atrPct, volumeRatio = 1.5, priceContext = window.contextOf(price),
                changePct = current.changePct, holdingTContext = recent.takeIf { it.isFresh(today, now) },
                holdingTObservation = tracker.observe(current, today, now)))
        }
        assertNull(evaluate(12.7, 100.0, 1000L))
        assertNull(evaluate(12.6, 110.0, 31000L))
        val result = evaluate(12.45, 120.0, 61000L)!!
        assertEquals(HoldingExitStage.HIGH_REDUCE, result.stage)
        assertTrue(result.action.contains("放量滞涨"))
        assertNull(evaluate(12.45, 120.0, 76000L))
        assertNull(evaluate(12.7, 130.0, 91000L))
    }

    @Test
    fun trackerAndPolicyRequireRealReboundBeforeReturningBuyObservation() {
        val tracker = HoldingTTracker()
        val recent = HoldingTContext(today, 1000L, HoldingTTrend.RANGE, 1.0, 2.0, SwingTrend.UP, SwingTrend.UP)
        fun evaluate(price: Double, volume: Double, now: Long): IntradaySignal? {
            val snapshot = quote(price, volume)
            return IntradayTactics.evaluate(IntradayTactics.Input(
                code = snapshot.code, name = snapshot.name, price = price, changePct = -3.5,
                volumeRatio = 0.8, limitPct = 10.0, support = 10.0, resistance = 11.0,
                score = 2, quantity = 2000.0, cost = 15.0, totalCapital = 100000.0,
                availableCapital = 5000.0, initialEntryPct = 5.0, maxPositionPct = 20.0,
                holdingTContext = recent.takeIf { it.isFresh(today, now) },
                holdingTObservation = tracker.observe(snapshot, today, now),
            ))
        }
        assertNull(evaluate(10.0, 100.0, 1000L))
        assertNull(evaluate(10.02, 110.0, 31000L))
        val signal = evaluate(10.05, 120.0, 61000L)!!
        assertEquals(IntradaySignalType.T_BUY, signal.type)
        assertTrue(signal.body.contains("不推算接回股数"))
        assertNull(evaluate(10.05, 120.0, 76000L))
        assertNull(evaluate(10.06, 130.0, 1000L + 46 * 60000))
    }
}
