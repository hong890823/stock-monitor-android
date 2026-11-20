package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 海立股份 600619：首板后水下分歧转强回放。
 *
 * 使用生产类 [FirstBoardReclaimTracker]，验证 11-18、11-19 的承接买点
 * 是否能在盘中产生“首板分歧转强观察”。入池资格不在本测试范围。
 */
class HailiReclaimReplayTest {
    private data class Alert(val time: String, val price: Double, val body: String)

    @Test
    fun novemberEighteenthReclaimFiresAfterGapUpDip() {
        val alert = singleAlert("2026-11-18")
        assertEquals("2026-11-18 09:55", alert.time)
        assertEquals(13.72, alert.price, 0.0001)
    }

    @Test
    fun novemberNineteenthUnderwaterReclaimFiresBeforeReseal() {
        val alert = singleAlert("2026-11-19")
        assertEquals("2026-11-19 09:50", alert.time)
        assertEquals(13.57, alert.price, 0.0001)
        // 动态 MA5 = (11.90 + 11.91 + 13.10 + 13.70 + 13.57) / 5，乖离 5.7%，未触发 10% 价格边界。
        assertTrue(alert.body.contains("MA5乖离5.7%"))
    }

    private fun singleAlert(day: String): Alert {
        val fixture = LaunchDiscoveryPrototype.loadFixture("600619")
        val dayIndex = fixture.dates.indexOf(day)
        val previousClose = fixture.closes[dayIndex - 1]
        val priorCloses = fixture.closes.subList(dayIndex - 4, dayIndex)
        val open = fixture.bars.first { it.time.startsWith(day) }.open
        val limit = LaunchDiscoveryPrototype.limitPrice(previousClose)
        val tracker = FirstBoardReclaimTracker()
        fixture.bars.filter { it.time.startsWith(day) }.forEach { bar ->
            val timestamp = LocalDateTime.parse(bar.time.replace(' ', 'T'))
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
            val ma5 = (priorCloses.sum() + bar.close) / 5.0
            val alert = tracker.update(
                FirstBoardReclaimTracker.Sample(
                    code = "sh600619",
                    name = "海立股份",
                    tradingDay = day,
                    previousClose = previousClose,
                    open = open,
                    low = bar.low,
                    price = bar.close,
                    timestamp = timestamp,
                    recentFirstBoard = true,
                    limitUp = bar.close >= limit - 0.005,
                    biasFromMa5Pct = (bar.close / ma5 - 1.0) * 100.0,
                ),
            )
            if (alert != null) return Alert(bar.time, bar.close, alert.body)
        }
        throw AssertionError("no reclaim alert on $day")
    }
}
