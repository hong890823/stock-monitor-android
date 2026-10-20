package com.example.stockmonitor

import com.example.stockmonitor.analysis.MarketAlertEngine
import com.example.stockmonitor.analysis.MarketAlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketAlertEngineTest {

    private fun snapshot(
        code: String,
        changePct: Double,
        minute: Long,
        price: Double = 3000.0,
        turnoverRatio: Double? = null,
    ): MarketAlertEngine.IndexSnapshot =
        MarketAlertEngine.IndexSnapshot(
            code = code,
            name = when (code) {
                "sh000001" -> "上证指数"
                "sz399001" -> "深证成指"
                else -> "创业板指"
            },
            price = price,
            changePct = changePct,
            turnover = 1_000_000.0,
            timestamp = minute * 60_000L,
            turnoverRatio = turnoverRatio,
        )

    private fun batch(shanghai: Double, shenzhen: Double, chinext: Double, minute: Long) = listOf(
        snapshot("sh000001", shanghai, minute),
        snapshot("sz399001", shenzhen, minute),
        snapshot("sz399006", chinext, minute),
    )

    private fun speedBatch(minute: Long, price: Double) = listOf(
        snapshot("sh000001", 0.0, minute, price),
        snapshot("sz399001", 0.0, minute, 3000.0),
        snapshot("sz399006", 0.0, minute, 3000.0),
    )

    @Test
    fun weaknessNeedsTwoRoundsAndReturnsOneMergedAlert() {
        val engine = MarketAlertEngine()

        assertTrue(engine.update(batch(-0.6, -0.55, -0.4, 1), "2026-09-28").isEmpty())
        val alerts = engine.update(batch(-0.6, -0.55, -0.4, 2), "2026-09-28")

        assertEquals(1, alerts.size)
        assertEquals(MarketAlertLevel.WARNING, alerts.single().level)
        assertTrue(alerts.single().body.contains("持续走弱"))
    }

    @Test
    fun earlyWarningNeedsTwoIndexesToBeWeak() {
        val engine = MarketAlertEngine()

        assertTrue(engine.update(batch(-0.6, -0.4, -0.3, 1), "2026-09-28").isEmpty())
        assertTrue(engine.update(batch(-0.6, -0.4, -0.3, 2), "2026-09-28").isEmpty())
    }

    @Test
    fun dangerTriggersImmediatelyAndSuppressesLowerWarning() {
        val engine = MarketAlertEngine()

        val alerts = engine.update(batch(-1.3, -0.8, -0.7, 1), "2026-09-28")

        assertEquals(1, alerts.size)
        assertEquals(MarketAlertLevel.DANGER, alerts.single().level)
    }

    @Test
    fun crashEscalatesAfterDangerWithoutDuplicateSameLevelAlerts() {
        val engine = MarketAlertEngine()

        assertEquals(MarketAlertLevel.DANGER, engine.update(batch(-1.3, -0.8, -0.7, 1), "2026-09-28").single().level)
        assertTrue(engine.update(batch(-1.4, -1.0, -0.9, 2), "2026-09-28").isEmpty())
        val crash = engine.update(batch(-2.6, -1.8, -1.5, 3), "2026-09-28")

        assertEquals(1, crash.size)
        assertEquals(MarketAlertLevel.CRASH, crash.single().level)
        assertTrue(engine.update(batch(-2.7, -1.9, -1.6, 4), "2026-09-28").isEmpty())
    }

    @Test
    fun recoveryRearmsTheEngineForANewWarning() {
        val engine = MarketAlertEngine()

        engine.update(batch(-1.3, -0.8, -0.7, 1), "2026-09-28")
        assertTrue(engine.update(batch(-0.39, -0.3, -0.2, 2), "2026-09-28").isEmpty())
        assertTrue(engine.update(batch(-0.6, -0.55, -0.4, 3), "2026-09-28").isEmpty())
        val warning = engine.update(batch(-0.6, -0.55, -0.4, 4), "2026-09-28")

        assertEquals(MarketAlertLevel.WARNING, warning.single().level)
    }

    @Test
    fun speedWarningNeedsTwoRoundsButSpeedDangerIsImmediate() {
        val warningEngine = MarketAlertEngine()
        warningEngine.update(speedBatch(0, 3000.0), "2026-09-28")
        warningEngine.update(speedBatch(1, 3000.0), "2026-09-28")
        warningEngine.update(speedBatch(2, 3000.0), "2026-09-28")
        assertTrue(warningEngine.update(speedBatch(6, 2991.0), "2026-09-28").isEmpty())
        val warning = warningEngine.update(speedBatch(7, 2991.0), "2026-09-28")
        assertEquals(MarketAlertLevel.WARNING, warning.single().level)
        assertTrue(warning.single().body.contains("过去 5 分钟"))

        val dangerEngine = MarketAlertEngine()
        dangerEngine.update(speedBatch(0, 3000.0), "2026-09-28")
        dangerEngine.update(speedBatch(1, 3000.0), "2026-09-28")
        val danger = dangerEngine.update(speedBatch(6, 2985.0), "2026-09-28")
        assertEquals(MarketAlertLevel.DANGER, danger.single().level)
    }

    @Test
    fun speedWindowRejectsStaleSamples() {
        val engine = MarketAlertEngine()
        engine.update(speedBatch(0, 3000.0), "2026-09-28")

        assertTrue(engine.update(speedBatch(8, 2970.0), "2026-09-28").isEmpty())
    }

    @Test
    fun fallingMarketIncludesVolumeConfirmationInMergedAlert() {
        val engine = MarketAlertEngine()
        val first = listOf(
            snapshot("sh000001", -0.6, 1, turnoverRatio = 2.2),
            snapshot("sz399001", -0.55, 1, turnoverRatio = 2.2),
        )
        val second = first.map { it.copy(timestamp = 2 * 60_000L) }

        assertTrue(engine.update(first, "2026-09-28").isEmpty())
        val alert = engine.update(second, "2026-09-28").single()

        assertTrue(alert.body.contains("放量下跌"))
        assertTrue(alert.body.contains("2.2 倍"))
    }
}
