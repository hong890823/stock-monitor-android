package com.example.stockmonitor.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradayBarsTest {
    private fun bar(minute: Int, close: Double) = IntradayBar(
        timestamp = "2026-10-20 10:%02d:00".format(minute),
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 100.0,
    )

    @Test
    fun completeWindowNeedsOrderedBarsAndFinitePrices() {
        assertTrue(IntradayBarWindow(listOf(bar(0, 10.0), bar(15, 10.2))).complete)
        assertFalse(IntradayBarWindow(listOf(bar(15, 10.2), bar(0, 10.0))).complete)
        assertFalse(
            IntradayBarWindow(
                listOf(
                    bar(0, 10.0),
                    IntradayBar("2026-10-20 10:15:00", Double.NaN, 1.0, 1.0, 1.0, 1.0),
                ),
            ).complete,
        )
    }

    @Test
    fun trendUsesRecentSlopeAndBecomesDownOnLowerCloses() {
        assertEquals(MinuteTrend.UP, IntradayBarWindow(listOf(bar(0, 10.0), bar(15, 10.2), bar(30, 10.4))).trend)
        assertEquals(MinuteTrend.DOWN, IntradayBarWindow(listOf(bar(0, 10.4), bar(15, 10.2), bar(30, 10.0))).trend)
        assertEquals(MinuteTrend.NEUTRAL, IntradayBarWindow(listOf(bar(0, 10.0), bar(15, 10.2), bar(30, 10.1))).trend)
    }

    @Test
    fun riskSignalsBypassDualTimeframeButOrdinaryActionsDoNot() {
        val down = IntradayBarWindow(listOf(bar(0, 10.4), bar(15, 10.0)))
        val up = IntradayBarWindow(listOf(bar(0, 10.0), bar(15, 10.4)))

        assertTrue(DualTimeframePolicy.decide(riskSignal = true, up, down).allowed)
        assertFalse(DualTimeframePolicy.decide(riskSignal = false, up, down).allowed)
        assertTrue(DualTimeframePolicy.decide(riskSignal = false, up, up).allowed)
    }

    @Test
    fun incompleteMinuteDataBlocksOrdinaryActions() {
        val incomplete = IntradayBarWindow(listOf(bar(0, 10.0)))

        val decision = DualTimeframePolicy.decide(
            riskSignal = false,
            execution = incomplete,
            direction = incomplete,
        )

        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("数据不完整"))
    }

    @Test
    fun intradayKlineParserAcceptsValidRowsAndRejectsMalformedRows() {
        val payload = JSONArray(
            """[
                {"day":"2026-10-20 10:15:00","open":"10.1","high":"10.4","low":"10.0","close":"10.3","volume":"1234"},
                {"day":"2026-10-20 10:00:00","open":"10.0","high":"10.2","low":"9.9","close":"10.1","volume":"1000"},
                {"day":"bad","open":"1","high":"2","low":"0.5","close":"1","volume":"1"},
                {"day":"2026-10-20 10:30:00","open":"NaN","high":"1","low":"1","close":"1","volume":"1"}
            ]""",
        ).toString()

        val bars = parseIntradayBars(payload, "sh600000", minutes = 15)

        assertEquals(
            listOf("2026-10-20 10:00:00", "2026-10-20 10:15:00"),
            bars.map { it.timestamp },
        )
        assertTrue(IntradayBarWindow(bars).complete)
    }

    @Test
    fun liveBarUpsertReplacesCurrentBarWithoutBreakingChronology() {
        val initial = IntradayBarWindow(
            listOf(
                bar(0, 10.0),
                bar(15, 10.2),
                bar(30, 10.1),
            ),
        )

        val updated = initial.upsert(bar(15, 10.3))

        assertEquals(
            listOf("2026-10-20 10:00:00", "2026-10-20 10:15:00", "2026-10-20 10:30:00"),
            updated.bars.map { it.timestamp },
        )
        assertEquals(10.3, updated.bars[1].close, 0.0001)
        assertTrue(updated.complete)
    }
}
