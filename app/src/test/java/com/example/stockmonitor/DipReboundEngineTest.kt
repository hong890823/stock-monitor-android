package com.example.stockmonitor

import com.example.stockmonitor.analysis.DipReboundEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下探回升（V 型收复）提醒：跌出恐慌坑又被买回来才提醒，放量收复才震动。
 * 这里锁住的是「多深算下探、多大算回升、一段下探只提醒一次、每天限次」——
 * 大盘与个股阈值分开，个股 -3% 起步，否则日常波动就刷屏。
 */
class DipReboundEngineTest {

    private fun index(
        price: Double = 4000.0,
        changePct: Double,
        turnoverRatio: Double? = 1.4,
        cumulativeTurnover: Double? = null,
        timestamp: Long = 0L,
        code: String = "sh000001",
        name: String = "上证指数",
    ) = DipReboundEngine.Sample(
        code = code,
        name = name,
        price = price,
        changePct = changePct,
        volumeRatio = turnoverRatio,
        cumulativeTurnover = cumulativeTurnover,
        timestamp = timestamp,
        stock = false,
    )

    private fun stock(
        price: Double = 18.0,
        changePct: Double,
        volumeRatio: Double? = 1.5,
        cumulativeTurnover: Double? = null,
        timestamp: Long = 0L,
        atrPct: Double? = null,
        code: String = "sz002185",
        name: String = "华天科技",
    ) = DipReboundEngine.Sample(
        code = code,
        name = name,
        price = price,
        changePct = changePct,
        volumeRatio = volumeRatio,
        cumulativeTurnover = cumulativeTurnover,
        timestamp = timestamp,
        atrPct = atrPct,
        stock = true,
    )

    /** 一轮更新只看第一个样本，方便逐段喂 V 型走势。 */
    private fun DipReboundEngine.step(sample: DipReboundEngine.Sample, day: String = "2026-10-09"): DipReboundEngine.Alert? =
        update(listOf(sample), day).firstOrNull()

    @Test
    fun indexVRecoveryWithVolumeVibrates() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9))
        engine.step(index(changePct = -0.8))
        val alert = engine.step(index(changePct = -0.2))!!
        assertTrue(alert.vibrate)
        assertFalse(alert.stock)
        assertTrue(alert.title.contains("下探回升"))
        assertTrue(alert.body.contains("放量收复"))
        assertTrue(alert.body.contains("补仓"))
    }

    @Test
    fun indexRecoveryWithoutVolumeIsSoft() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9, turnoverRatio = 0.9))
        val alert = engine.step(index(changePct = -0.2, turnoverRatio = 0.9))!!
        assertFalse(alert.vibrate)
        assertTrue(alert.body.contains("超跌反弹"))
    }

    @Test
    fun shallowDipStaysSilent() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.5))
        assertEquals(null, engine.step(index(changePct = 0.1)))
    }

    @Test
    fun dipWithoutReboundStaysSilent() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9))
        assertEquals(null, engine.step(index(changePct = -1.0)))
        assertEquals(null, engine.step(index(changePct = -0.7)))
    }

    @Test
    fun tinyReboundOfDeepDipStaysSilent() {
        // 跌 2% 只弹 0.6%：收复不到一半，不算反转。
        val engine = DipReboundEngine()
        engine.step(index(changePct = -2.0))
        assertEquals(null, engine.step(index(changePct = -1.4)))
    }

    @Test
    fun firesOncePerDipEpisode() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9))
        val first = engine.step(index(changePct = -0.2))!!
        assertTrue(first.title.contains("下探回升"))
        // 同一段下探继续回升，不再重复提醒。
        assertEquals(null, engine.step(index(changePct = 0.2)))
        assertEquals(null, engine.step(index(changePct = 0.5)))
    }

    @Test
    fun rearmsAfterDeeperLow() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9))
        engine.step(index(changePct = -0.2))
        // 没创新低的回落（-0.85 > -0.9）不重新武装。
        engine.step(index(changePct = -0.85))
        assertEquals(null, engine.step(index(changePct = -0.3)))
        // 创出更深新低后再次收复，才算新一段下探。
        engine.step(index(changePct = -1.5))
        val second = engine.step(index(changePct = -0.4))!!
        assertTrue(second.title.contains("下探回升"))
    }

    @Test
    fun chinextNeedsDeeperDip() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9, code = "sz399006", name = "创业板指"))
        assertEquals(null, engine.step(index(changePct = -0.1, code = "sz399006", name = "创业板指")))

        engine.step(index(changePct = -1.3, code = "sz399006", name = "创业板指"))
        val alert = engine.step(index(changePct = -0.5, code = "sz399006", name = "创业板指"))!!
        assertTrue(alert.title.contains("创业板指"))
    }

    @Test
    fun stockNeedsDeeperDipAndBiggerReboundThanIndex() {
        val engine = DipReboundEngine()
        // -2.9% 还不够「被砸」，指数口径这里不适用。
        engine.step(stock(changePct = -2.9))
        assertEquals(null, engine.step(stock(changePct = -0.5)))

        engine.step(stock(changePct = -3.5))
        // 回升 1.5% 未到个股 2% 门槛。
        assertEquals(null, engine.step(stock(changePct = -2.0)))
        val alert = engine.step(stock(changePct = -1.2))!!
        assertTrue(alert.stock)
        assertTrue(alert.title.contains("深跌回升"))
        assertTrue(alert.body.contains("做T"))
    }

    @Test
    fun stockWithoutVolumeSaysWaitForConfirmation() {
        val engine = DipReboundEngine()
        engine.step(stock(changePct = -3.5, volumeRatio = 0.8))
        val alert = engine.step(stock(changePct = -1.2, volumeRatio = 0.8))!!
        assertFalse(alert.vibrate)
        assertTrue(alert.body.contains("企稳"))
    }

    @Test
    fun dailyCapStopsSpam() {
        val engine = DipReboundEngine()
        var fired = 0
        var low = -0.9
        repeat(6) {
            engine.step(index(changePct = low))
            // 回升 60% 的跌幅：既够回升门槛，也始终收复过半。
            if (engine.step(index(changePct = low * 0.4)) != null) fired++
            low -= 1.0 // 每段都创更深新低
        }
        assertEquals(3, fired)
    }

    @Test
    fun dayChangeResetsState() {
        val engine = DipReboundEngine()
        engine.step(index(changePct = -0.9), day = "2026-10-09")
        engine.step(index(changePct = -0.2), day = "2026-10-09")
        // 次日新的一段下探照常提醒。
        engine.step(index(changePct = -0.9), day = "2026-10-10")
        val alert = engine.step(index(changePct = -0.2), day = "2026-10-10")!!
        assertTrue(alert.title.contains("下探回升"))
    }

    @Test
    fun reboundLegPaceDecidesNotCumulativeRatio() {
        val engine = DipReboundEngine()
        val t0 = 1_000_000L
        // 上午缓跌：60 分钟成交 60（节奏 1.0/分钟）。
        engine.step(index(changePct = -0.2, turnoverRatio = 1.6, cumulativeTurnover = 20.0, timestamp = t0))
        engine.step(index(changePct = -1.0, turnoverRatio = 1.6, cumulativeTurnover = 80.0, timestamp = t0 + 60 * 60_000))
        // 回升段 30 分钟只成交 3：缩量反弹。虽然全天累计倍数 1.6 很高，也不该判「放量」。
        val alert = engine.step(
            index(changePct = -0.3, turnoverRatio = 1.6, cumulativeTurnover = 83.0, timestamp = t0 + 90 * 60_000)
        )!!
        assertFalse(alert.vibrate)
        assertTrue(alert.body.contains("量能缩了"))
    }

    @Test
    fun heavyReboundLegVibratesEvenWhenCumulativeRatioLooksWeak() {
        val engine = DipReboundEngine()
        val t0 = 1_000_000L
        engine.step(index(changePct = -0.2, turnoverRatio = 1.2, cumulativeTurnover = 20.0, timestamp = t0))
        engine.step(index(changePct = -1.0, turnoverRatio = 1.2, cumulativeTurnover = 80.0, timestamp = t0 + 60 * 60_000))
        // 回升段 30 分钟成交 40（1.33/分钟 ≥ 下跌段的 1.1 倍）：资金承接，给强提醒。
        val alert = engine.step(
            index(changePct = -0.3, turnoverRatio = 1.2, cumulativeTurnover = 120.0, timestamp = t0 + 90 * 60_000)
        )!!
        assertTrue(alert.vibrate)
        assertTrue(alert.body.contains("量能不缩"))
    }

    @Test
    fun stockDipThresholdScalesWithAtr() {
        val engine = DipReboundEngine()
        // ATR 3% 的题材股：下探阈值 -3.6%，-3% 只是日常波动。
        engine.step(stock(changePct = -3.0, atrPct = 3.0))
        assertEquals(null, engine.step(stock(changePct = -1.0, atrPct = 3.0)))
        engine.step(stock(changePct = -3.8, atrPct = 3.0))
        val alert = engine.step(stock(changePct = -1.2, atrPct = 3.0))!!
        assertTrue(alert.stock)
    }
}
