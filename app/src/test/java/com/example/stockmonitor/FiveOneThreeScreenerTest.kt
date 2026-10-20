package com.example.stockmonitor

import com.example.stockmonitor.analysis.FiveOneThreeScreener
import com.example.stockmonitor.analysis.FiveOneThreeStage
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FiveOneThreeScreenerTest {

    @Test
    fun fiveBullishDaysReturnObservingStage() {
        val result = FiveOneThreeScreener.evaluate("sh600000", "测试股份", barsWithPattern())

        assertEquals(FiveOneThreeStage.OBSERVING, result?.stage)
        assertEquals("5连阳 5/5｜倍量阳 0/1｜缩量确认 0/3", result?.detail)
    }

    @Test
    fun doubleVolumeBullishDayReturnsWaitingStage() {
        val result = FiveOneThreeScreener.evaluate(
            "sh600000",
            "测试股份",
            barsWithPattern(includeDoubleVolume = true),
        )

        assertEquals(FiveOneThreeStage.WAITING_CONFIRMATION, result?.stage)
        assertEquals("5连阳 5/5｜倍量阳 1/1｜缩量确认 0/3", result?.detail)
    }

    @Test
    fun threeShrinkingDaysReturnConfirmedStage() {
        val result = FiveOneThreeScreener.evaluate(
            "sh600000",
            "测试股份",
            barsWithPattern(includeDoubleVolume = true, confirmationVolumes = listOf(1_500.0, 1_200.0, 900.0)),
        )

        assertEquals(FiveOneThreeStage.CONFIRMED, result?.stage)
        assertEquals("5连阳 5/5｜倍量阳 1/1｜缩量确认 3/3", result?.detail)
    }

    @Test
    fun highPositionCandidateIsExcluded() {
        val bars = risingBaseBars() + bullishPattern(startClose = 15.0)

        assertNull(FiveOneThreeScreener.evaluate("sh600000", "测试股份", bars))
    }

    @Test
    fun breakingSignalOpenInvalidatesConfirmation() {
        val result = FiveOneThreeScreener.evaluate(
            "sh600000",
            "测试股份",
            barsWithPattern(
                includeDoubleVolume = true,
                confirmationVolumes = listOf(1_500.0, 1_200.0, 900.0),
                breakSignalOpenOnConfirmation = true,
            ),
        )

        assertNull(result)
    }

    @Test
    fun expandingVolumeOrRangeDoesNotConfirm() {
        val result = FiveOneThreeScreener.evaluate(
            "sh600000",
            "测试股份",
            barsWithPattern(
                includeDoubleVolume = true,
                confirmationVolumes = listOf(1_500.0, 1_800.0, 900.0),
                wideRangeOnConfirmation = true,
            ),
        )

        assertNull(result)
    }

    @Test
    fun stStockIsExcluded() {
        assertNull(FiveOneThreeScreener.evaluate("sh600000", "ST测试", barsWithPattern()))
    }

    @Test
    fun inspectionDistinguishesHistoricalAndCurrentFiveBullishPatterns() {
        val inspection = FiveOneThreeScreener.inspect("sh600000", "测试股份", barsWithPattern())

        assertTrue(inspection.historyAvailable)
        assertTrue(inspection.historicalFiveBullish)
        assertTrue(inspection.recentFiveBullish)
        assertFalse(inspection.hit == null)
    }

    @Test
    fun staleConfirmedPatternExpires() {
        // 513 是短线形态：确认完成后过了几天就不再是今天的买点，不该一直挂着「形态已确认」。
        val confirmed = barsWithPattern(
            includeDoubleVolume = true,
            confirmationVolumes = listOf(1_500.0, 1_200.0, 900.0),
        )
        assertEquals(
            FiveOneThreeStage.CONFIRMED,
            FiveOneThreeScreener.evaluate("sh600000", "测试股份", confirmed)?.stage,
        )
        // 3 个完整交易日之内仍保留。
        val within = confirmed + quietDays(3)
        assertEquals(
            FiveOneThreeStage.CONFIRMED,
            FiveOneThreeScreener.evaluate("sh600000", "测试股份", within)?.stage,
        )
        // 超过 3 天：过期，不再列出。
        val stale = confirmed + quietDays(4)
        assertNull(FiveOneThreeScreener.evaluate("sh600000", "测试股份", stale))
    }

    /** 确认之后的安静交易日：不构成新形态、也不影响旧候选的判定。 */
    private fun quietDays(count: Int): List<DayBar> = (0 until count).map { index ->
        val close = 11.5 - index * 0.02
        bar(close = close, open = close * 1.005, volume = 800.0)
    }

    private fun barsWithPattern(
        includeDoubleVolume: Boolean = false,
        confirmationVolumes: List<Double> = emptyList(),
        breakSignalOpenOnConfirmation: Boolean = false,
        wideRangeOnConfirmation: Boolean = false,
    ): List<DayBar> {
        val bars = decliningBaseBars().toMutableList()
        bars += bullishPattern(startClose = bars.last().close)
        if (includeDoubleVolume) {
            val signalOpen = bars.last().close * 1.01
            val signalClose = signalOpen * 1.02
            bars += bar(
                close = signalClose,
                open = signalOpen,
                high = signalClose * 1.01,
                low = signalOpen * 0.99,
                volume = 2_200.0,
            )
            confirmationVolumes.forEachIndexed { index, volume ->
                val open = if (breakSignalOpenOnConfirmation && index == 0) signalOpen * 0.98 else signalClose * 0.995
                val close = open * 1.002
                bars += bar(
                    close = close,
                    open = open,
                    high = if (wideRangeOnConfirmation && index == 1) close * 1.04 else close * 1.005,
                    low = if (wideRangeOnConfirmation && index == 1) close * 0.94 else open * 0.998,
                    volume = volume,
                )
            }
        }
        return bars
    }

    private fun decliningBaseBars(): List<DayBar> = (0 until 60).map { index ->
        val close = 20.0 - minOf(index, 55) * 0.16
        bar(close = close, open = close * 1.01, volume = 1_000.0)
    }

    private fun risingBaseBars(): List<DayBar> = (0 until 60).map { index ->
        val close = 10.0 + index * 0.12
        bar(close = close, open = close * 0.99, volume = 1_000.0)
    }

    private fun bullishPattern(startClose: Double): List<DayBar> = (1..5).map { index ->
        val close = startClose * (1.02.pow(index))
        val open = close / 1.02
        bar(close = close, open = open, volume = 1_000.0)
    }

    private fun bar(
        close: Double,
        open: Double,
        high: Double = close * 1.01,
        low: Double = open * 0.99,
        volume: Double,
    ) = DayBar(
        date = "2026-09-${dateCounter++}",
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
    )

    private var dateCounter = 1

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
}
