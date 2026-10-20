package com.example.stockmonitor

import com.example.stockmonitor.analysis.DivergenceState
import com.example.stockmonitor.analysis.ObvDivergence
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBV 量价背离判定。
 *
 * 口径与 macOS 版 `obv_divergence.py` 逐字一致：
 * 顶背离＝股价创阶段新高但本轮 OBV 峰值低于前期（比 OBV 自己的峰谷，不是价格高点当日读数），-1 分；
 * 底背离＝股价创阶段新低但本轮 OBV 谷值未再创新低，+1 分；
 * 同步与中性不加分；顶底同时成立＝信号打架，净 0 分。
 *
 * 对照点（前点/后点的日期、价格、段内 OBV 峰值）要能让人在行情软件里逐点核对，
 * 所以这里连对照数字一起锁住。
 */
class ObvDivergenceTest {

    /** 合成日线：前段回填同一价格，模拟「日线不足一整轮行情」的常见取数。 */
    private fun bars(closes: List<Double>, volumes: List<Double>): List<DayBar> {
        val n = closes.size
        require(volumes.size == n)
        return closes.indices.map { i ->
            val c = closes[i]
            DayBar(
                date = "2026-01-${(i % 28) + 1}",
                open = c - 0.05,
                high = c + 0.05,
                low = c - 0.10,
                close = c,
                volume = volumes[i],
            )
        }
    }

    private fun pad(series: List<Double>, target: Int = 32, fill: Double? = null): List<Double> {
        if (series.size >= target) return series
        val head = fill ?: series.first()
        return List(target - series.size) { head } + series
    }

    @Test
    fun flatDayKeepsObv() {
        // 涨加量、跌减量、平盘不变（经典定义）。
        val obv = ObvDivergence.computeObv(
            closes = listOf(10.0, 10.5, 10.5, 10.2),
            volumes = listOf(0.0, 100.0, 50.0, 80.0),
        )
        assertEquals(listOf(0.0, 100.0, 100.0, 20.0), obv)
    }

    @Test
    fun syncWhenPriceAndObvBothMakeNewHigh() {
        // 单边放量上涨：价与 OBV 同步新高，不加分。
        val n = 32
        val closes = (0 until n).map { 10 + 0.15 * it }
        val volumes = (0 until n).map { 100.0 + 5 * it }
        val result = ObvDivergence.evaluate(bars(closes, volumes))
        assertEquals(DivergenceState.SYNC, result.state)
        assertEquals(0, result.scoreDelta)
        assertTrue(result.obvConfirmedNewHigh == true)
    }

    @Test
    fun topDivergenceScoresMinusOne() {
        // 两波上涨，第二波价创新高但缩量（OBV 峰值下移）→ 顶背离。
        val closes = pad(
            listOf(
                10.0, 10.5, 11.0, 11.5, 12.0, 12.5, 13.0, 13.2, 13.0, 12.5,
                12.0, 11.5, 11.2, 11.0, 11.1, 11.3, 11.5,
                11.8, 12.2, 12.6, 13.1, 13.6, 14.1, 14.5, 14.8,
                15.0, 15.05, 15.1, 15.08, 15.12, 15.11, 15.13,
            ),
        )
        val volumes = pad(
            listOf(
                200.0, 300.0, 500.0, 800.0, 1200.0, 1500.0, 1800.0, 900.0, 1000.0, 1100.0,
                1300.0, 1600.0, 1400.0, 800.0, 500.0, 450.0, 400.0,
                350.0, 320.0, 300.0, 280.0, 260.0, 240.0, 220.0, 200.0,
                180.0, 170.0, 160.0, 150.0, 140.0, 130.0, 120.0,
            ),
            fill = 200.0,
        )
        val result = ObvDivergence.evaluate(bars(closes, volumes))
        assertEquals(DivergenceState.TOP, result.state)
        assertEquals(-1, result.scoreDelta)
        assertTrue(result.obvConfirmedNewHigh == false)
        // 对照数字必须能核对：后高 > 前高，但后段 OBV 峰值 < 前段 OBV 峰值。
        val prior = result.prior
        val current = result.current
        assertNotNull(prior)
        assertNotNull(current)
        assertTrue(current!!.price > prior!!.price)
        assertTrue(current.obv < prior.obv)
        // 「价升量能没跟上」是定义，不是分析——结论里得写出**差多少**，才判断得了这个背离值不值得当回事。
        assertTrue(result.meaning.contains("OBV 峰值"))
        assertTrue(result.meaning.contains("比前期"))
        assertTrue(result.meaning.contains("低"))
    }

    @Test
    fun bottomDivergenceScoresPlusOne() {
        // 两波下跌，第二波创新低但 OBV 谷值抬升（出逃放缓）→ 底背离。
        val closes = pad(
            listOf(
                15.0, 14.5, 14.0, 13.5, 13.0, 12.5, 12.0, 11.8, 12.0, 12.5,
                13.0, 13.5, 13.8, 14.0, 13.9, 13.7, 13.5,
                13.2, 12.8, 12.4, 11.9, 11.4, 10.9, 10.5, 10.2,
                10.0, 9.95, 9.9, 9.92, 9.88, 9.89, 9.87,
            ),
        )
        val volumes = pad(
            listOf(
                200.0, 300.0, 500.0, 800.0, 1200.0, 1500.0, 1800.0, 900.0, 1000.0, 1100.0,
                1300.0, 1600.0, 1400.0, 800.0, 500.0, 450.0, 400.0,
                350.0, 320.0, 300.0, 280.0, 260.0, 240.0, 220.0, 200.0,
                180.0, 170.0, 160.0, 150.0, 140.0, 130.0, 120.0,
            ),
            fill = 200.0,
        )
        val result = ObvDivergence.evaluate(bars(closes, volumes))
        assertEquals(DivergenceState.BOTTOM, result.state)
        assertEquals(1, result.scoreDelta)
        val prior = result.prior
        val current = result.current
        assertNotNull(prior)
        assertNotNull(current)
        assertTrue(current!!.price < prior!!.price)
        assertTrue(current.obv > prior.obv)
    }

    @Test
    fun formingHighWithObvConfirmIsSync() {
        // 进行中高点（末根创窗口新高）+ OBV 同步新高 → 量价同步，不加分。
        val n = 32
        val closes = (0 until n).map { 10 + 0.15 * it }
        val volumes = (0 until n).map { 100.0 + 5 * it }
        val result = ObvDivergence.evaluate(bars(closes, volumes), currentPrice = closes.last() + 0.2)
        assertEquals(DivergenceState.SYNC, result.state)
        assertTrue(result.forming)
        assertEquals(0, result.scoreDelta)
    }

    @Test
    fun formingHighWithoutObvConfirmIsTopDivergence() {
        // 实时场景：股价创本轮新高但 OBV 未跟上 → 顶背离（forming）。
        val closes = pad(
            listOf(
                10.0, 10.5, 11.0, 11.5, 12.0, 12.5, 13.0, 13.2, 13.0, 12.5,
                12.0, 11.5, 11.2, 11.0, 11.1, 11.3, 11.5,
                11.8, 12.2, 12.6, 13.1, 13.6, 14.1, 14.5, 14.8,
                15.0, 15.05, 15.1, 15.08, 15.12, 15.11, 15.13,
            ),
        )
        val volumes = pad(
            listOf(
                200.0, 300.0, 500.0, 800.0, 1200.0, 1500.0, 1800.0, 900.0, 1000.0, 1100.0,
                1300.0, 1600.0, 1400.0, 800.0, 500.0, 450.0, 400.0,
                350.0, 320.0, 300.0, 280.0, 260.0, 240.0, 220.0, 200.0,
                180.0, 170.0, 160.0, 150.0, 140.0, 130.0, 120.0,
            ),
            fill = 200.0,
        )
        val result = ObvDivergence.evaluate(bars(closes, volumes), currentPrice = 15.4)
        assertEquals(DivergenceState.TOP, result.state)
        assertTrue(result.forming)
        assertEquals(-1, result.scoreDelta)
        assertTrue(result.reason.contains("高点进行中"))
    }

    @Test
    fun noNewHighIsNeutral() {
        // 收敛三角：高点渐低、低点渐高，未创阶段新高/新低 → 不构成背离前提。
        val closes = listOf(
            10.0, 10.3, 10.7, 11.1, 11.5, 11.8, 12.0, 11.7, 11.3, 10.9,
            10.6, 10.3, 10.1, 10.0, 10.2, 10.5, 10.8, 11.0, 11.2, 11.4,
            11.5, 11.3, 11.1, 10.9, 10.8, 10.7, 10.6, 10.5, 10.6, 10.7,
            10.8, 10.75,
        )
        val result = ObvDivergence.evaluate(bars(closes, List(32) { 100.0 }))
        assertEquals(DivergenceState.NEUTRAL, result.state)
        assertEquals(0, result.scoreDelta)
        assertTrue(result.reason.contains("未创阶段新高/新低"))
    }

    @Test
    fun conflictWhenTopAndBottomBothDiverge() {
        // 宽幅震荡：高点抬升 OBV 走低 + 低点下移 OBV 抬升 → 信号打架，净 0 分。
        val closes = pad(
            listOf(
                10.0, 11.0, 12.0, 13.0, 12.0, 11.0, 10.0, 9.0, 8.0, 9.0, 10.0,
                11.0, 12.0, 13.0, 14.0, 13.0, 12.0, 11.0, 10.0, 9.0, 8.0, 7.0,
                8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 14.0, 13.0,
            ),
            target = 32,
            fill = 10.0,
        )
        // 高位放量下跌、低位缩量上涨 → OBV 与价背向。
        val volumes = pad(
            listOf(
                200.0, 400.0, 600.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 150.0,
                100.0, 80.0, 60.0, 40.0, 30.0, 25.0, 20.0, 15.0, 12.0, 10.0, 8.0,
                6.0, 5.0, 4.0, 3.0, 3.0, 2.0, 2.0, 1.0, 1.0, 1.0,
            ),
            target = 32,
            fill = 50.0,
        )
        val result = ObvDivergence.evaluate(bars(closes, volumes))
        assertEquals(DivergenceState.CONFLICT, result.state)
        assertEquals(0, result.scoreDelta)
    }

    @Test
    fun insufficientDataDoesNotJudge() {
        val closes = List(10) { 10.0 }
        val result = ObvDivergence.evaluate(bars(closes, List(10) { 1.0 }))
        assertEquals(DivergenceState.INSUFFICIENT, result.state)
        assertEquals(0, result.scoreDelta)
    }
}
