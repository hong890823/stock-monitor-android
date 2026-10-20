package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「板块龙头」的挑人；强弱判定见 [LeaderStrengthTest]。
 *
 * 两条硬要求（2026-10-19 用户口径）：
 * 1. **只认龙头，小票不算**——小票涨停多是情绪跟风，不该拿它压住做T；
 * 2. **龙头强弱要跟着实时行情走**——炸板转弱后做T提醒得恢复，不能停在上一次快照。
 */
class SectorLeadersTest {

    private fun member(code: String, name: String, changePct: Double = 0.0) =
        BoardMember(code, name, changePct)

    /** prevClose 固定 10.0 的主板票；[highChangePct] 用于反推「今日是否摸过涨停」。 */
    private fun quote(code: String, changePct: Double, highChangePct: Double = maxOf(changePct, 0.0)) = Quote(
        code = code, name = "", price = 10.0 * (1 + changePct / 100.0), prevClose = 10.0,
        open = 10.0, high = 10.0 * (1 + highChangePct / 100.0), low = 10.0, volume = 1.0, turnover = 1.0,
    )

    @Test
    fun onlyTopTurnoverMembersCountAsLeaders() {
        // 板块里 8 只，成交额最大的 5 只才是龙头；排在后面的跟风小票一律出局。
        val members = listOf(
            member("600487", "亨通光电"), member("600522", "中天科技"), member("600498", "烽火通信"),
            member("002281", "光迅科技"), member("300502", "新易盛"), member("300308", "中际旭创"),
            member("688387", "信科移动"), member("300570", "太辰光"),
        )
        val turnovers = mapOf(
            "sh600487" to 20e8, "sh600522" to 18e8, "sh600498" to 12e8,
            "sz002281" to 9e8, "sz300502" to 8e8, "sz300308" to 7e8,
            "sh688387" to 1e8, "sz300570" to 0.5e8,
        )

        val leaders = SectorLeaders.of(members, turnovers, asOfMs = 1L)

        assertEquals(SectorLeaders.SIZE, leaders.members.size)
        assertEquals(
            listOf("亨通光电", "中天科技", "烽火通信", "光迅科技", "新易盛"),
            leaders.members.map { it.name },
        )
        // 跟风小票不在龙头集合里，它们涨停也就不该影响做T。
        assertFalse(leaders.members.any { it.name == "太辰光" })
    }

    @Test
    fun manualOverridesCanPinAnUnlistedStockAndExcludeAnAutoLeader() {
        // 人工指定 / 排除压过自动排名：手动补进不在接口候选里的「中际旭创」，把排名内的「新易盛」踢出去。
        val members = listOf(
            member("600487", "亨通光电"), member("600522", "中天科技"), member("600498", "烽火通信"),
            member("002281", "光迅科技"), member("300502", "新易盛"),
            member("688387", "信科移动"), member("300570", "太辰光"),
        )
        val turnovers = mapOf(
            "sh600487" to 20e8, "sh600522" to 18e8, "sh600498" to 12e8,
            "sz002281" to 9e8, "sz300502" to 8e8, "sz300308" to 7e8,
            "sh688387" to 1e8, "sz300570" to 0.5e8,
        )
        val overrides = LeaderOverrides(
            pinned = mapOf("300308" to "中际旭创"),
            excluded = setOf("300502"),
        )

        val leaders = SectorLeaders.of(members, turnovers, overrides, asOfMs = 1L)

        assertEquals(
            listOf("亨通光电", "中天科技", "烽火通信", "光迅科技", "中际旭创"),
            leaders.members.map { it.name },
        )
        assertFalse(leaders.members.any { it.name == "新易盛" })
    }

    @Test
    fun strongNamesTrackLiveBoardSoWeakeningLeadersRestoreTSell() {
        val leaders = SectorLeaders(
            members = listOf(member("600487", "亨通光电"), member("600522", "中天科技")),
            asOfMs = 1L,
        )
        val tracker = LeaderStrengthTracker()

        // 两只都封板 → 暂缓做T。
        val bothSealed = mapOf(
            "sh600487" to quote("sh600487", 9.98),
            "sh600522" to quote("sh600522", 10.0),
        )
        assertEquals(listOf("亨通光电", "中天科技"), tracker.strongNames(leaders.members, bothSealed, 1L))

        // 亨通炸板但守住高位线（摸过涨停，现价 8.5%）→ 仍算强，继续暂缓做T。
        val hengtongOpened = mapOf(
            "sh600487" to quote("sh600487", 8.5, highChangePct = 10.0),
            "sh600522" to quote("sh600522", 10.0),
        )
        assertEquals(listOf("亨通光电", "中天科技"), tracker.strongNames(leaders.members, hengtongOpened, 1L))

        // 亨通跌破高位线（摸过涨停，现价 6.0%）满 3 分钟 → 转弱，做T恢复。
        val weakNow = mapOf(
            "sh600487" to quote("sh600487", 6.0, highChangePct = 10.0),
            "sh600522" to quote("sh600522", 10.0),
        )
        tracker.strongNames(leaders.members, weakNow, 1L)
        assertEquals(listOf("中天科技"), tracker.strongNames(leaders.members, weakNow, 1L + 3 * 60_000L))
    }

    @Test
    fun missingLeaderQuotesAreTreatedAsNotStrong() {
        // 拿不到行情就不能当强——宁可照常给做T，也不凭空压掉减仓提示。
        val leaders = SectorLeaders(listOf(member("600487", "亨通光电")), asOfMs = 1L)
        val tracker = LeaderStrengthTracker()
        assertTrue(tracker.strongNames(leaders.members, emptyMap(), 1L).isEmpty())
        // 没摸过板（high 只到 +9.0%）不算封板。
        assertTrue(
            tracker.strongNames(leaders.members, mapOf("sh600487" to quote("sh600487", 9.0)), 1L).isEmpty(),
        )
    }

    @Test
    fun quoteCodeDerivesMarketFromCodePrefix() {
        assertEquals("sh600522", BoardMember.quoteCodeOf("600522"))
        assertEquals("sz300750", BoardMember.quoteCodeOf("300750"))
        assertEquals("sz002185", BoardMember.quoteCodeOf("002185"))
        assertEquals("bj830799", BoardMember.quoteCodeOf("830799"))
    }
}
