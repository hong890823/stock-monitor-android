package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 龙头强度状态机（炸板守住高位线）。
 *
 * 2026-10-19 用户口径：**炸板不等于走弱**——炸板后能长时间稳在水上 8 个点以上，
 * 说明砸出来的筹码被买盘吃掉了、承接极强，这时候提示减机动仓很可能正好卖在回封前一秒。
 * 所以判定看「是不是还贴在涨停附近的高位」，且转弱要持续 3 分钟才确认。
 */
class LeaderStrengthTest {

    private fun member(code: String, name: String) = BoardMember(code, name, Double.NaN)

    /**
     * prevClose 固定 10.0 的主板票（涨停 10%）：[changePct] / [highChangePct] 按百分比给，
     * high 反推成绝对价——「今日曾封板」就靠这个 high 认，不靠状态记忆。
     */
    private fun quote(
        code: String,
        changePct: Double,
        highChangePct: Double = maxOf(changePct, 0.0),
        date: String = "2026-10-19",
    ) = Quote(
        code = code,
        name = "",
        price = 10.0 * (1 + changePct / 100.0),
        prevClose = 10.0,
        open = 10.0,
        high = 10.0 * (1 + highChangePct / 100.0),
        low = 10.0,
        volume = 1.0,
        turnover = 1.0,
        quoteDate = date,
    )

    @Test
    fun sealedBoardIsStrongImmediately() {
        val tracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")

        val strength = tracker.strength(hengtong, quote("sh600487", changePct = 10.0), nowMs = 1_000L)

        assertEquals(LeaderStrength.STRONG, strength)
    }

    @Test
    fun brokenBoardHoldingHighLineStaysStrong() {
        // 亨通摸过涨停（high +10%）又炸到 +8.5%：贴着高位线（10% − 2%），承接还在，算强。
        val tracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")

        val strength = tracker.strength(
            hengtong,
            quote("sh600487", changePct = 8.5, highChangePct = 10.0),
            nowMs = 1_000L,
        )

        assertEquals(LeaderStrength.STRONG, strength)
    }

    @Test
    fun dipBelowHighLineDoesNotFlipWithinThreeMinutes() {
        // 炸到 +7.9%（跌破高位线 8%）刚 2 分钟：抖动期不翻转，先按强处理。
        val tracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")
        val opened = quote("sh600487", changePct = 7.9, highChangePct = 10.0)

        assertEquals(LeaderStrength.STRONG, tracker.strength(hengtong, opened, nowMs = 1_000L))
        assertEquals(LeaderStrength.STRONG, tracker.strength(hengtong, opened, nowMs = 1_000L + 2 * 60_000L))
    }

    @Test
    fun stayingBelowHighLineForThreeMinutesConfirmsWeak() {
        val tracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")
        val opened = quote("sh600487", changePct = 7.9, highChangePct = 10.0)

        assertEquals(LeaderStrength.STRONG, tracker.strength(hengtong, opened, nowMs = 1_000L))
        val weak = tracker.strength(hengtong, opened, nowMs = 1_000L + 3 * 60_000L)

        assertEquals(LeaderStrength.WEAK, weak)
    }

    @Test
    fun neverSealedIsWeakEvenAtEightFivePercent() {
        // 从没封过板（high 只到 +8.5%）却涨到 8.5%：只认「炸板守住」，不认「从没封过」。
        val tracker = LeaderStrengthTracker()
        val follower = member("300570", "太辰光")

        val strength = tracker.strength(follower, quote("sz300570", changePct = 8.5, highChangePct = 8.5), 1_000L)

        assertEquals(LeaderStrength.WEAK, strength)
    }

    @Test
    fun highPriceStillRecognizesMorningSealAfterRestart() {
        // 服务重启（新 tracker、没有任何记忆）后靠 Quote.high 仍能认出「今日曾封过板」。
        val freshTracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")

        val strength = freshTracker.strength(
            hengtong,
            quote("sh600487", changePct = 8.5, highChangePct = 10.0),
            nowMs = 9_000L,
        )

        assertEquals(LeaderStrength.STRONG, strength)
    }

    @Test
    fun dayChangeResetsWeakTimer() {
        // 跨日（quoteDate 变了）转弱计时重置：昨天下跌破位的计时不能拿来判今天。
        val tracker = LeaderStrengthTracker()
        val hengtong = member("600487", "亨通光电")
        val yesterday = quote("sh600487", changePct = 7.0, highChangePct = 10.0, date = "2026-10-17")
        val today = quote("sh600487", changePct = 8.5, highChangePct = 10.0, date = "2026-10-19")

        tracker.strength(hengtong, yesterday, nowMs = 1_000L)
        tracker.strength(hengtong, yesterday, nowMs = 1_000L + 10 * 60_000L)
        // 新的一天第一次采样：计时从头来，仍算强（守住高位线）。
        assertEquals(LeaderStrength.STRONG, tracker.strength(hengtong, today, nowMs = 2_000_000L))
    }

    @Test
    fun missingQuoteIsNotTreatedAsStrong() {
        // 拿不到行情就不能当强——宁可照常给做T，也不凭空压掉减仓提示。
        val tracker = LeaderStrengthTracker()
        val leaders = listOf(member("600487", "亨通光电"))

        assertTrue(tracker.strongNames(leaders, emptyMap(), 1_000L).isEmpty())
        // 创业板 20%：+9% 不是封板。
        val kcb = listOf(member("688001", "中芯国际"))
        assertTrue(tracker.strongNames(kcb, mapOf("sh688001" to quote("sh688001", 9.0, 9.0)), 1_000L).isEmpty())
    }

    @Test
    fun sealedNamesAndHeldHighNamesDescribeBoardWithoutTouchingTimer() {
        val tracker = LeaderStrengthTracker()
        val sealed = member("600487", "亨通光电")
        val heldHigh = member("600522", "中天科技")
        val leaders = listOf(sealed, heldHigh)
        val quotes = mapOf(
            "sh600487" to quote("sh600487", changePct = 10.0),
            "sh600522" to quote("sh600522", changePct = 8.5, highChangePct = 10.0),
        )

        assertEquals(listOf("亨通光电"), LeaderStrengthTracker.sealedNames(leaders, quotes))
        assertEquals(
            listOf("亨通光电", "中天科技"),
            LeaderStrengthTracker.heldHighNames(leaders, quotes),
        )
        // 界面展示不该影响转弱计时：同一 tracker 再走一次判定仍是强。
        assertEquals(
            listOf("亨通光电", "中天科技"),
            tracker.strongNames(leaders, quotes, nowMs = 1_000L),
        )
    }

    @Test
    fun strongNamesDropLeaderOnceWeakConfirmed() {
        // 判定侧用 strongNames：跌破高位线且满 3 分钟后剔出强势名单，做T提醒恢复。
        val tracker = LeaderStrengthTracker()
        val leaders = listOf(member("600487", "亨通光电"), member("600522", "中天科技"))
        val quotes = mapOf(
            "sh600487" to quote("sh600487", changePct = 6.0, highChangePct = 10.0),
            "sh600522" to quote("sh600522", changePct = 10.0),
        )

        assertEquals(listOf("亨通光电", "中天科技"), tracker.strongNames(leaders, quotes, 1_000L))
        assertEquals(listOf("中天科技"), tracker.strongNames(leaders, quotes, 1_000L + 3 * 60_000L))
    }
}
