package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketAlertEngine
import com.example.stockmonitor.analysis.MarketAlertLevel
import com.example.stockmonitor.analysis.MarketAlertScope
import com.example.stockmonitor.analysis.MarketRiskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketAlertEngineTest {
    private fun bar(
        code: String,
        minute: Long,
        close: Double,
        volume: Double = 100.0,
    ) = MarketAlertEngine.IndexBar(
        code = code,
        name = nameOf(code),
        timestamp = minute * 60_000L,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = volume,
    )

    private fun series(
        code: String,
        closes: List<Double>,
        startMinute: Long = 0,
        volumes: List<Double> = List(closes.size) { 100.0 },
    ): List<MarketAlertEngine.IndexBar> =
        closes.mapIndexed { index, close ->
            bar(code, startMinute + index * 5, close, volumes[index])
        }

    private fun nameOf(code: String): String = when (code) {
        "sh000001" -> "上证指数"
        "sz399001" -> "深证成指"
        else -> "创业板指"
    }

    private fun snapshot(code: String, changePct: Double) =
        MarketAlertEngine.IndexSnapshot(
            code = code,
            name = nameOf(code),
            price = 3000.0,
            changePct = changePct,
            turnover = 1_000_000.0,
            timestamp = 60 * 60_000L,
            turnoverRatio = null,
        )

    private val flatSnapshots = listOf(
        snapshot("sh000001", 0.0),
        snapshot("sz399001", 0.0),
        snapshot("sz399006", 0.0),
    )

    @Test
    fun repeatedScansInsideSameCompletedBarDoNotCreateRepeatedEvidence() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", listOf(3000.0, 2991.0, 2982.0)),
            "sz399001" to series("sz399001", List(3) { 3000.0 }),
            "sz399006" to series("sz399006", List(3) { 3000.0 }),
        )
        val breadth = MarketAlertEngine.MarketBreadth(0.25, 0.25)

        assertTrue(engine.update(flatSnapshots, bars, "2026-10-23", breadth).isEmpty())
        assertTrue(engine.update(flatSnapshots, bars, "2026-10-23", breadth).isEmpty())
    }

    @Test
    fun singleIndexTwoConsecutiveFiveMinuteDropsStayObservationOnly() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2991.0, 2982.0)),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        assertTrue(
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.25, 0.25),
            ).isEmpty(),
        )
    }

    @Test
    fun singleIndexDropBelowHalfPercentDoesNotBecomeMarketRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2984.0, 2982.0)),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        assertTrue(
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.70, 0.68),
            ).isEmpty(),
        )
    }

    @Test
    fun twoIndexesFallingTogetherWithWeakBreadthRaiseMarketRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2984.0, 2982.0)),
            "sz399001" to series("sz399001", List(8) { 3000.0 } + listOf(2984.0, 2982.0)),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        val alerts =
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.32, 0.34),
            )

        assertEquals(1, alerts.size)
        assertEquals(MarketAlertLevel.DANGER, alerts.single().level)
        assertEquals(MarketAlertScope.MARKET, alerts.single().scope)
        assertTrue(alerts.single().title.contains("市场风险"))
    }

    @Test
    fun singleVolumeShockNeedsNextBarFollowThrough() {
        val engine = MarketAlertEngine()
        val shockBars = mapOf(
            "sh000001" to series(
                "sh000001",
                List(8) { 3000.0 } + listOf(2984.0),
                volumes = List(8) { 100.0 } + listOf(180.0),
            ),
            "sz399001" to series("sz399001", List(9) { 3000.0 }),
            "sz399006" to series("sz399006", List(9) { 3000.0 }),
        )
        val breadth = MarketAlertEngine.MarketBreadth(0.70, 0.68)
        assertTrue(engine.update(flatSnapshots, shockBars, "2026-10-23", breadth).isEmpty())

        val followThroughBars = mapOf(
            "sh000001" to series(
                "sh000001",
                List(8) { 3000.0 } + listOf(2984.0, 2978.0),
                volumes = List(8) { 100.0 } + listOf(180.0, 100.0),
            ),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )
        val alerts = engine.update(flatSnapshots, followThroughBars, "2026-10-23", breadth)

        assertEquals(1, alerts.size)
        assertEquals(MarketAlertLevel.DANGER, alerts.single().level)
        assertEquals(MarketAlertScope.INDEX, alerts.single().scope)
        assertTrue(alerts.single().title.contains("上证指数"))
    }

    @Test
    fun lowerShadowAbsorptionSuppressesSingleIndexVolumeRisk() {
        val engine = MarketAlertEngine()
        val absorbedShock =
            MarketAlertEngine.IndexBar(
                code = "sh000001",
                name = nameOf("sh000001"),
                timestamp = 8 * 5 * 60_000L,
                open = 2990.0,
                high = 2990.0,
                low = 2950.0,
                close = 2984.0,
                volume = 180.0,
            )
        val shockBars =
            mapOf(
                "sh000001" to series("sh000001", List(8) { 3000.0 }) + absorbedShock,
                "sz399001" to series("sz399001", List(9) { 3000.0 }),
                "sz399006" to series("sz399006", List(9) { 3000.0 }),
            )
        val breadth = MarketAlertEngine.MarketBreadth(0.70, 0.68)
        assertTrue(engine.update(flatSnapshots, shockBars, "2026-10-23", breadth).isEmpty())

        val followBars =
            mapOf(
                "sh000001" to shockBars.getValue("sh000001") +
                    bar("sh000001", minute = 45, close = 2965.0),
                "sz399001" to series("sz399001", List(10) { 3000.0 }),
                "sz399006" to series("sz399006", List(10) { 3000.0 }),
            )

        assertTrue(engine.update(flatSnapshots, followBars, "2026-10-23", breadth).isEmpty())
    }

    @Test
    fun singleIndexPointEightIsIndexRiskNotMarketRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2975.0, 2973.0)),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        val alerts =
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.70, 0.68),
            )

        assertEquals(MarketAlertScope.INDEX, alerts.single().scope)
        assertTrue(alerts.single().title.contains("上证指数"))
        assertTrue(!alerts.single().title.contains("市场风险"))
    }

    @Test
    fun twoIndexesRapidDropAndContinuationRaiseSystemicRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2975.0, 2970.0)),
            "sz399001" to series("sz399001", List(8) { 3000.0 } + listOf(2975.0, 2970.0)),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        val alerts =
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.70, 0.68),
            )

        assertEquals(MarketAlertLevel.CRASH, alerts.single().level)
        assertEquals(MarketAlertScope.SYSTEMIC, alerts.single().scope)
        assertTrue(alerts.single().title.contains("系统性风险"))
    }

    @Test
    fun dailyPointEightNeedsWeakBreadthOrExpandedTurnover() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(10) { 3000.0 }),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )
        val weakDay = listOf(
            snapshot("sh000001", -0.85),
            snapshot("sz399001", -0.90),
            snapshot("sz399006", 0.20),
        )

        assertTrue(
            engine.update(
                weakDay,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.70, 0.68),
            ).isEmpty(),
        )
        val alerts =
            engine.update(
                weakDay.map { it.copy(timestamp = it.timestamp + 60_000L) },
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.32, 0.34),
            )

        assertEquals(MarketAlertScope.MARKET, alerts.single().scope)
    }

    @Test
    fun shocksFromDifferentFiveMinuteWindowsDoNotBecomeMarketRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(8) { 3000.0 } + listOf(2984.0, 2982.0)),
            "sz399001" to series("sz399001", List(9) { 3000.0 } + listOf(2984.0)),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )

        assertTrue(
            engine.update(
                flatSnapshots,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.32, 0.34),
            ).isEmpty(),
        )
    }

    @Test
    fun recoveredIndexesRearmTheSameDailyRisk() {
        val engine = MarketAlertEngine()
        val bars = mapOf(
            "sh000001" to series("sh000001", List(10) { 3000.0 }),
            "sz399001" to series("sz399001", List(10) { 3000.0 }),
            "sz399006" to series("sz399006", List(10) { 3000.0 }),
        )
        val riskDay = listOf(
            snapshot("sh000001", -0.85),
            snapshot("sz399001", -0.90),
            snapshot("sz399006", -0.20),
        )

        assertEquals(
            MarketAlertScope.MARKET,
            engine.update(
                riskDay,
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.32, 0.34),
            ).single().scope,
        )
        assertTrue(
            engine.update(
                riskDay.map { it.copy(changePct = -0.10) },
                bars,
                "2026-10-23",
                MarketAlertEngine.MarketBreadth(0.70, 0.68),
            ).isEmpty(),
        )
        val repeated = engine.update(
            riskDay.map { it.copy(timestamp = it.timestamp + 60_000L) },
            bars,
            "2026-10-23",
            MarketAlertEngine.MarketBreadth(0.32, 0.34),
        )

        assertEquals(1, repeated.size)
        assertEquals(MarketAlertScope.MARKET, repeated.single().scope)
    }

    @Test
    fun volatilityMultiplierWidensThresholdsForHighVolatilityIndex() {
        val stable = MarketRiskPolicy.volatilityMultiplier(
            "sz399006",
            List(30) { if (it % 2 == 0) 0.10 else -0.10 },
        )
        val volatile = MarketRiskPolicy.volatilityMultiplier(
            "sz399006",
            List(30) { if (it % 2 == 0) 1.0 else -1.0 },
        )

        assertTrue(volatile > stable)
        assertEquals(1.6, volatile, 0.001)
    }
}
