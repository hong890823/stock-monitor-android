package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动初期发现原型回放（规则见 [LaunchDiscoveryPrototype]）。
 *
 * 该规则只用于验证“能提前到哪天”，未接入生产链路。
 */
class LaunchDiscoveryReplayTest {
    @Test
    fun huadongAlertsOnNovemberTwelfthAfterBoardBreak() {
        val triggers = LaunchDiscoveryPrototype.replay("002685")
        assertEquals(1, triggers.size)
        val trigger = triggers.single()
        assertEquals("2026-11-12 13:55", trigger.time)
        assertEquals(5.13, trigger.price, 0.0001)
        assertEquals(6.52, trigger.biasPct, 0.01)
    }

    @Test
    fun huashengAlertsOnNovemberTwelfthBeforeFirstBoard() {
        val triggers = LaunchDiscoveryPrototype.replay("600410")
        assertEquals(1, triggers.size)
        val trigger = triggers.single()
        assertEquals("2026-11-12 13:20", trigger.time)
        assertEquals(13.18, trigger.price, 0.0001)
        assertEquals(4.03, trigger.biasPct, 0.01)
    }

    @Test
    fun dailyGateHasNoQualifyingDayBeforeNovemberTwelfth() {
        // 华胜天成 11-06 冲高 13.24 但收盘 12.68，未站上平台收盘新高 12.70。
        assertTrue(LaunchDiscoveryPrototype.qualifyingDailyDates("600410").none { it < "2026-11-12" })
        assertEquals("2026-11-12", LaunchDiscoveryPrototype.qualifyingDailyDates("600410").first())
        // 华东重机 11-10 最高只到 4.86，收盘 4.83，未突破 4.99。
        assertTrue(LaunchDiscoveryPrototype.qualifyingDailyDates("002685").none { it < "2026-11-12" })
        assertEquals("2026-11-12", LaunchDiscoveryPrototype.qualifyingDailyDates("002685").first())
    }

    @Test
    fun huadongMorningBarSealsAtLimitSoNoPreSealFillIsProven() {
        val fixture = LaunchDiscoveryPrototype.loadFixture("002685")
        val date = "2026-11-12"
        val limit = LaunchDiscoveryPrototype.limitPrice(fixture.closes[fixture.dates.indexOf(date) - 1])
        val sealBar = fixture.bars.single { it.time == "$date 09:50" }
        assertEquals(limit, sealBar.high, 0.0001)
        assertEquals(limit, sealBar.close, 0.0001)
    }

    @Test
    fun hailiLaunchRuleFiresTwiceIncludingAFalseStart() {
        val triggers = LaunchDiscoveryPrototype.replay("600619")
        assertEquals(2, triggers.size)
        // 11-13 盘中冲高 +3.6% 后回落，当日收平，止损位 11-16 低点 11.66 会先被打到。
        assertEquals("2026-11-13 09:55", triggers[0].time)
        assertEquals(12.33, triggers[0].price, 0.0001)
        assertEquals(2.31, triggers[0].biasPct, 0.01)
        // 首板日早盘，封板前 25 分钟。
        assertEquals("2026-11-17 09:55", triggers[1].time)
        assertEquals(12.75, triggers[1].price, 0.0001)
        assertEquals(5.32, triggers[1].biasPct, 0.01)
        // 11-18 高开、11-19 水下分歧都不满足：乖离超 8% 或当日涨幅不足 3%。
        assertTrue(triggers.none { it.time.startsWith("2026-11-18") || it.time.startsWith("2026-11-19") })
    }
}
