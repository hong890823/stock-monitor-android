package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedMomentumPolicyTest {

    @Test
    fun macdBarStateLabelsGrowthAndShrink() {
        assertEquals("红柱 3 连放大", EnhancedMomentumPolicy.macdBarState(listOf(0.1, 0.2, 0.3, 0.5)))
        assertEquals("红柱缩短", EnhancedMomentumPolicy.macdBarState(listOf(0.3, 0.5, 0.2)))
        assertEquals("绿柱 2 连缩短", EnhancedMomentumPolicy.macdBarState(listOf(-0.5, -0.3, -0.1)))
        assertEquals("绿柱放大", EnhancedMomentumPolicy.macdBarState(listOf(-0.1, -0.4)))
    }

    @Test
    fun squeezeDetectsCompressionAndUpwardRelease() {
        // 交替窄幅波动：BOLL 收进 Keltner = 压缩。
        val flat = (0 until 30).map { if (it % 2 == 0) 10.0 else 10.01 }
        val high = flat.map { it + 0.005 }
        val low = flat.map { it - 0.005 }
        val compressed = EnhancedMomentumPolicy.squeezeState(high, low, flat)!!
        assertTrue("got: $compressed", compressed.startsWith("波动率压缩"))

        // 压缩后连续拉升：BOLL 张口超出 Keltner = 向上释放。
        val released = flat.dropLast(3) + listOf(10.3, 10.7, 11.2)
        val releasedHigh = released.map { it + 0.005 }
        val releasedLow = released.map { it - 0.005 }
        val text = EnhancedMomentumPolicy.squeezeState(releasedHigh, releasedLow, released)!!
        assertTrue("got: $text", text.contains("向上释放"))
    }

    @Test
    fun cmfReflectsCloseLocationInRange() {
        // 收在区间上沿 = 买方占优；收在下沿 = 抛压。
        val n = 25
        val strongHigh = (0 until n).map { 11.0 }
        val strongLow = (0 until n).map { 10.0 }
        val strongClose = (0 until n).map { 10.95 }
        val volume = (0 until n).map { 1000.0 }
        assertTrue(EnhancedMomentumPolicy.cmfLabel(strongHigh, strongLow, strongClose, volume)!!.contains("承接偏强"))

        val weakClose = (0 until n).map { 10.05 }
        assertTrue(EnhancedMomentumPolicy.cmfLabel(strongHigh, strongLow, weakClose, volume)!!.contains("抛压偏重"))
    }

    @Test
    fun aroonFlagsUptrendOnRisingHighs() {
        val n = 30
        val high = (0 until n).map { 10.0 + it * 0.1 }
        val low = (0 until n).map { 9.9 + it * 0.1 }
        val label = EnhancedMomentumPolicy.aroonLabel(high, low)!!
        assertTrue(label.contains("上行趋势"))
    }

    @Test
    fun evaluateProducesAllFourEvidenceLines() {
        val n = 30
        val close = (0 until n).map { 10.0 + it * 0.05 }
        val high = close.map { it + 0.02 }
        val low = close.map { it - 0.02 }
        val volume = (0 until n).map { 1000.0 }
        val hist = (0 until n).map { it * 0.01 }
        val detail = EnhancedMomentumPolicy.evaluate(hist, high, low, close, volume)

        assertNotNull(detail.macdBarLabel)
        assertNotNull(detail.squeezeLabel)
        assertNotNull(detail.cmfLabel)
        assertNotNull(detail.aroonLabel)
        assertTrue(detail.summaryLine.contains("｜"))
    }
}
