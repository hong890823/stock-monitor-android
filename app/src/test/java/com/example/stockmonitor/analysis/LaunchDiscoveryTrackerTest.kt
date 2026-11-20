package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDiscoveryTrackerTest {
    private fun sample(
        price: Double,
        timestamp: Long,
        platform: Double = 10.0,
        previousClose: Double = 9.6,
        volumeRatio: Double? = 2.0,
        bias: Double? = 5.0,
        limitUp: Boolean = false,
        day: String = "2026-11-19",
        code: String = "sh600619",
    ) = LaunchDiscoveryTracker.Sample(
        code = code,
        name = "测试",
        tradingDay = day,
        price = price,
        previousClose = previousClose,
        platform = platform,
        volumeRatio = volumeRatio,
        biasFromMa5Pct = bias,
        timestamp = timestamp,
        limitUp = limitUp,
    )

    @Test
    fun requiresFifteenMinutesAbovePlatform() {
        val tracker = LaunchDiscoveryTracker()
        assertNull(tracker.update(sample(10.2, 1_000)))
        assertNull(tracker.update(sample(10.3, 1_000 + 14 * 60_000L)))
        val alert = tracker.update(sample(10.4, 1_000 + 15 * 60_000L))
        assertEquals(10.4, alert!!.price, 0.0001)
        assertTrue(alert.body.contains("站上20日平台 10.00"))
    }

    @Test
    fun alertsOncePerDayAndRearmRetries() {
        val tracker = LaunchDiscoveryTracker()
        tracker.update(sample(10.2, 1_000))
        assertEquals(1, tracker.update(sample(10.3, 1_000 + 15 * 60_000L))?.let { 1 } ?: 0)
        assertNull(tracker.update(sample(10.4, 1_000 + 20 * 60_000L)))
        tracker.rearm("sh600619")
        assertTrue(tracker.update(sample(10.5, 1_000 + 21 * 60_000L)) != null)
    }

    @Test
    fun dropBelowPlatformResetsHoldTimer() {
        val tracker = LaunchDiscoveryTracker()
        tracker.update(sample(10.2, 1_000))
        assertNull(tracker.update(sample(9.9, 1_000 + 10 * 60_000L)))
        assertNull(tracker.update(sample(10.3, 1_000 + 20 * 60_000L)))
        assertNull(tracker.update(sample(10.3, 1_000 + 30 * 60_000L)))
        assertTrue(tracker.update(sample(10.3, 1_000 + 35 * 60_000L)) != null)
    }

    @Test
    fun limitUpDoesNotCountTowardHoldTime() {
        val tracker = LaunchDiscoveryTracker()
        assertNull(tracker.update(sample(10.5, 1_000, limitUp = true)))
        assertNull(tracker.update(sample(10.3, 1_000 + 5 * 60_000L)))
        assertNull(tracker.update(sample(10.3, 1_000 + 19 * 60_000L)))
        assertTrue(tracker.update(sample(10.3, 1_000 + 20 * 60_000L)) != null)
    }

    @Test
    fun weakVolumeOrExtendedBiasBlocksAlert() {
        val tracker = LaunchDiscoveryTracker()
        tracker.update(sample(10.2, 1_000, volumeRatio = 1.1))
        assertNull(tracker.update(sample(10.2, 1_000 + 20 * 60_000L, volumeRatio = 1.1)))

        val extended = LaunchDiscoveryTracker()
        extended.update(sample(10.2, 2_000, bias = 9.5))
        assertNull(extended.update(sample(10.2, 2_000 + 20 * 60_000L, bias = 9.5)))
    }

    @Test
    fun replaysHuashengAndHuadongAndHaili() {
        assertEquals(listOf("2026-11-12 09:50"), replay("600410", "2026-11-12").map { it.first })
        assertEquals(12.92, replay("600410", "2026-11-12").single().second, 0.0001)

        assertEquals(listOf("2026-11-12 14:10"), replay("002685", "2026-11-12").map { it.first })
        assertEquals(5.11, replay("002685", "2026-11-12").single().second, 0.0001)

        // 11-13 是冲高回落的假启动：站稳 15 分钟后仍会提示，需靠"跌破平台失效"约束。
        assertEquals(listOf("2026-11-13 09:55"), replay("600619", "2026-11-13").map { it.first })
        assertEquals(listOf("2026-11-17 09:50"), replay("600619", "2026-11-17").map { it.first })
        assertTrue(replay("600619", "2026-11-18").isEmpty())
        assertTrue(replay("600619", "2026-11-19").isEmpty())
    }

    private fun replay(code: String, day: String): List<Pair<String, Double>> {
        val fixture = LaunchDiscoveryPrototype.loadFixture(code)
        val dayIndex = fixture.dates.indexOf(day)
        val previousClose = fixture.closes[dayIndex - 1]
        val platform = fixture.closes.subList(maxOf(0, dayIndex - 20), dayIndex).max()
        val priorCloses = fixture.closes.subList(dayIndex - 4, dayIndex)
        // 日线样本是"手"，分钟线是"股"，统一乘 100 后与实时成交量同口径。
        val averageVolume = dailyAverageVolume(code, dayIndex)
        val limit = LaunchDiscoveryPrototype.limitPrice(previousClose)
        val tracker = LaunchDiscoveryTracker()
        val alerts = mutableListOf<Pair<String, Double>>()
        var cumulative = 0.0
        fixture.bars.filter { it.time.startsWith(day) }.forEach { bar ->
            cumulative += bar.volume
            val minuteOfDay = bar.time.substring(11, 13).toInt() * 60 + bar.time.substring(14, 16).toInt()
            val elapsed = when {
                minuteOfDay <= 570 -> 0
                minuteOfDay <= 690 -> minuteOfDay - 570
                minuteOfDay < 780 -> 120
                else -> 120 + (minuteOfDay - 780)
            }
            val volumeRatio =
                if (elapsed > 0 && averageVolume > 0) cumulative / (averageVolume * elapsed / 240.0) else null
            val ma5 = (priorCloses.sum() + bar.close) / 5.0
            val alert = tracker.update(
                LaunchDiscoveryTracker.Sample(
                    code = "sh$code",
                    name = code,
                    tradingDay = day,
                    price = bar.close,
                    previousClose = previousClose,
                    platform = platform,
                    volumeRatio = volumeRatio,
                    biasFromMa5Pct = (bar.close / ma5 - 1.0) * 100.0,
                    timestamp = minuteOfDay.toLong() * 60_000L,
                    limitUp = bar.close >= limit - 0.005,
                ),
            )
            if (alert != null) alerts += bar.time to bar.close
        }
        return alerts
    }

    private fun dailyAverageVolume(code: String, dayIndex: Int): Double {
        val stream = requireNotNull(javaClass.getResourceAsStream("/entry-timing-2026-11-19/$code.json"))
        val json = org.json.JSONObject(stream.bufferedReader().use { it.readText() })
        val daily = json.getJSONArray("daily")
        val volumes = (dayIndex - 5 until dayIndex).map { daily.getJSONArray(it).getDouble(5) * 100.0 }
        return volumes.average()
    }
}
