package com.example.stockmonitor

import com.example.stockmonitor.analysis.RelaxedScreenSelection
import com.example.stockmonitor.core.CachedScreen
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.ScreenHit
import com.example.stockmonitor.core.ScreenResult
import com.example.stockmonitor.core.ScreenResultJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaxedScreenSelectionTest {
    private fun hit(code: String = "sample") = ScreenHit(
        code = code, name = code, price = 10.0, rsi = 35.0,
        k = 30.0, d = 25.0, dif = 0.1, relaxedScore = 3,
        macdDetail = "柱体转正", rsiOk = true, kdjOk = true,
        relaxedTrendEligible = true,
    )

    private fun bars(vararg lastCloses: Double): List<DayBar> =
        (List(35) { 10.0 } + lastCloses.toList()).mapIndexed { index, close ->
            DayBar("day$index", close, close + 0.1, close - 0.1, close, 1000.0)
        }

    @Test
    fun `macd improvement is mandatory but either secondary indicator suffices`() {
        val valid = hit()
        val rsiOnly = hit("rsi").copy(kdjOk = false, relaxedScore = 2)
        val kdjOnly = hit("kdj").copy(rsiOk = false, rsi = Double.NaN, relaxedScore = 2)
        val noMacd = hit("noMacd").copy(macdDetail = "", relaxedScore = 2)
        val macdOnly = hit("macdOnly").copy(rsiOk = false, kdjOk = false, relaxedScore = 1)
        assertEquals(
            setOf("sample", "rsi", "kdj"),
            RelaxedScreenSelection.select(listOf(valid, rsiOnly, kdjOnly, noMacd, macdOnly)).map { it.code }.toSet(),
        )
    }

    @Test
    fun `broken and unverified candidates are excluded before top twenty selection`() {
        val invalid = (1..25).map { hit("broken$it").copy(relaxedTrendEligible = false) }
        val eligible = (1..30).map { hit("eligible$it") }
        val selected = RelaxedScreenSelection.select(invalid + eligible)
        assertEquals(20, selected.size)
        assertTrue(selected.all { it.code.startsWith("eligible") })
        assertTrue(RelaxedScreenSelection.select(invalid).isEmpty())
    }

    @Test
    fun `three signals rank first then positive dif instead of deepest oversold`() {
        val complete = hit("complete").copy(dif = -0.1)
        val recovering = hit("recovering").copy(kdjOk = false, relaxedScore = 2, rsi = 40.0)
        val oversold = hit("oversold").copy(kdjOk = false, relaxedScore = 2, rsi = 10.0, dif = -0.2)
        assertEquals(
            listOf("complete", "recovering", "oversold"),
            RelaxedScreenSelection.select(listOf(oversold, recovering, complete)).map { it.code },
        )
    }

    @Test
    fun `m20 filter excludes sharp and sustained breakdown without requiring a confirmed buy point`() {
        assertTrue(RelaxedScreenSelection.trendEligible(bars(9.98)))
        assertTrue(RelaxedScreenSelection.trendEligible(bars(9.90)))
        assertFalse(RelaxedScreenSelection.trendEligible(bars(9.70)))
        assertFalse(RelaxedScreenSelection.trendEligible(bars(9.90, 9.90)))
        assertFalse(RelaxedScreenSelection.trendEligible(emptyList()))
        assertFalse(RelaxedScreenSelection.trendEligible(bars(Double.NaN)))
    }

    @Test
    fun `cache retains filter evidence and legacy cache cannot bypass new gate`() {
        val cached = CachedScreen(null, 1L, null, 1L, ScreenResult(emptyList(), listOf(hit())))
        val encoded = ScreenResultJson.encode(cached)
        val restored = ScreenResultJson.decode(encoded)!!.result.relaxed
        assertEquals(listOf("sample"), RelaxedScreenSelection.select(restored).map { it.code })
        val legacy = JSONObject(encoded)
        legacy.getJSONObject("result").getJSONArray("relaxed").getJSONObject(0).remove("relaxed_trend_eligible")
        val old = ScreenResultJson.decode(legacy.toString())!!.result.relaxed
        assertTrue(RelaxedScreenSelection.select(old).isEmpty())
    }
}
