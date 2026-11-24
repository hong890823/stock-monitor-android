package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回踩锚位分档与可达性护栏。
 * 对应 docs/2026-11-24-divergence-execution-strategy.md 模块 D。
 */
class PullbackAnchorPolicyTest {

    /** 单边上涨、贴着 MA5 走的收盘序列：滚动 MA5 全部站稳。 */
    private val steadyUptrendCloses = listOf(9.9, 10.0, 10.05, 10.1, 10.15, 10.2, 10.25, 10.28, 10.3)

    // ---------- 强度分档 ----------

    @Test
    fun alignedUptrendNearMa5IsStrong() {
        val tier = PullbackAnchorPolicy.tierOf(
            price = 10.3, ma5 = 10.24, ma10 = 10.16, ma20 = 10.0,
            recentCloses = steadyUptrendCloses,
        )
        assertEquals(StrengthTier.STRONG, tier)
    }

    @Test
    fun priceNearMa20IsNeutral() {
        val tier = PullbackAnchorPolicy.tierOf(
            price = 10.1, ma5 = 10.4, ma10 = 10.3, ma20 = 10.0,
            recentCloses = steadyUptrendCloses,
        )
        assertEquals(StrengthTier.NEUTRAL, tier)
    }

    @Test
    fun brokenMaAlignmentIsNeutralEvenWhenPriceAboveMa5() {
        // 价格在 MA5 上方但均线没排好（ma5 < ma10），不算强势档。
        val tier = PullbackAnchorPolicy.tierOf(
            price = 10.3, ma5 = 10.1, ma10 = 10.2, ma20 = 10.0,
            recentCloses = steadyUptrendCloses,
        )
        assertEquals(StrengthTier.NEUTRAL, tier)
    }

    @Test
    fun missingMaDataFallsBackToNeutralNotInventedStrong() {
        val tier = PullbackAnchorPolicy.tierOf(
            price = 10.3, ma5 = null, ma10 = null, ma20 = 10.0,
            recentCloses = emptyList(),
        )
        assertEquals(StrengthTier.NEUTRAL, tier)
    }

    @Test
    fun belowMa20IsWeak() {
        val tier = PullbackAnchorPolicy.tierOf(
            price = 9.8, ma5 = 9.9, ma10 = 9.95, ma20 = 10.0,
            recentCloses = steadyUptrendCloses,
        )
        assertEquals(StrengthTier.WEAK, tier)
    }

    @Test
    fun trendWeakOrMajorDownIsWeakRegardlessOfPrice() {
        listOf(true to false, false to true).forEach { (trendWeak, majorDown) ->
            val tier = PullbackAnchorPolicy.tierOf(
                price = 10.3, ma5 = 10.24, ma10 = 10.16, ma20 = 10.0,
                recentCloses = steadyUptrendCloses,
                trendWeak = trendWeak, majorTrendDown = majorDown,
            )
            assertEquals(StrengthTier.WEAK, tier)
        }
    }

    @Test
    fun repeatedMa5BreakDaysBlockStrongTier() {
        // 近 5 日有两天收在滚动 MA5 下方超过容差：不算强势贴着走。
        val closes = listOf(10.5, 10.6, 10.55, 10.6, 10.0, 10.05, 9.6, 10.0, 10.1)
        val tier = PullbackAnchorPolicy.tierOf(
            price = 10.3, ma5 = 10.24, ma10 = 10.16, ma20 = 10.0,
            recentCloses = closes,
        )
        assertEquals(StrengthTier.NEUTRAL, tier)
        assertTrue(PullbackAnchorPolicy.ma5BreakDays(closes)!! > PullbackAnchorPolicy.MAX_MA5_BREAK_DAYS)
    }

    @Test
    fun ma5BreakDaysNeedsNineClosesElseNull() {
        assertNull(PullbackAnchorPolicy.ma5BreakDays(steadyUptrendCloses.drop(1)))
        assertEquals(0, PullbackAnchorPolicy.ma5BreakDays(steadyUptrendCloses))
    }

    // ---------- 分档锚位 ----------

    @Test
    fun strongBandAnchorsAtMa5WithShallowTolerance() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.STRONG, price = 10.3, ma5 = 10.24, ma20 = 10.0,
            priorHigh = null, stop = 9.5, target = 12.0, minRewardRisk = 2.0,
        )
        assertEquals(10.24, band.anchorPrice!!, 1e-9)
        assertTrue(band.anchorLabel.contains("MA5"))
        // 浅锚下沿 10.24×0.99=10.138，远高于 M20 深口径下沿 9.95——强势股不再被建议等深回踩。
        assertEquals(10.24 * 0.99, band.low!!, 1e-9)
        assertTrue(band.low!! > 10.05)
        assertTrue(band.onBandNote().contains("不硬等"))
    }

    @Test
    fun strongBandPrefersBreakoutLevelWhenPriceHugsIt() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.STRONG, price = 15.2, ma5 = 14.0, ma20 = 13.0,
            priorHigh = 15.0, stop = 13.5, target = 18.0, minRewardRisk = 2.0,
        )
        assertEquals(15.0, band.anchorPrice!!, 1e-9)
        assertTrue(band.anchorLabel.contains("突破位"))
    }

    @Test
    fun strongBandFallsBackToMa5WhenPriceHasBrokenBelowPriorHigh() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.STRONG, price = 14.5, ma5 = 14.0, ma20 = 13.0,
            priorHigh = 15.0, stop = 13.5, target = 18.0, minRewardRisk = 2.0,
        )
        assertEquals(14.0, band.anchorPrice!!, 1e-9)
        assertTrue(band.anchorLabel.contains("MA5"))
    }

    @Test
    fun neutralBandKeepsMa20AnchorAndTolerance() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.NEUTRAL, price = 10.1, ma5 = 10.4, ma20 = 10.0,
            priorHigh = null, stop = 9.5, target = 12.0, minRewardRisk = 2.0,
        )
        assertEquals(10.0, band.anchorPrice!!, 1e-9)
        assertEquals(10.0 * 0.995, band.low!!, 1e-9)
        assertTrue(band.onBandNote().isEmpty())
    }

    @Test
    fun weakTierGivesNoBand() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.WEAK, price = 9.8, ma5 = 9.9, ma20 = 10.0,
            priorHigh = null, stop = 9.5, target = 12.0, minRewardRisk = 2.0,
        )
        assertNull(band.low)
        assertNull(band.high)
        assertNull(band.anchorPrice)
    }

    @Test
    fun bandAppliesStopFloorAndRewardRiskCap() {
        // 止损抬高下沿、盈亏比 2 压低上沿，两档共用同一约束。
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.STRONG, price = 10.3, ma5 = 10.24, ma20 = 10.0,
            priorHigh = null, stop = 10.2, target = 10.45, minRewardRisk = 2.0,
        )
        assertEquals(10.21, band.low!!, 1e-9)
        // cap = (10.45 + 2×10.2)/3 = 10.2833
        assertEquals((10.45 + 2 * 10.2) / 3, band.high!!, 1e-9)
    }

    // ---------- 话术锚位片段（操作建议「回踩」用） ----------

    @Test
    fun adviceRefGivesShallowAnchorForStrongMa20ForNeutralAndNothingForWeak() {
        val strong = PullbackAnchorPolicy.anchorOf(
            tier = StrengthTier.STRONG, price = 15.07, ma5 = 13.14, ma20 = 12.17, priorHigh = null,
        )
        assertEquals("MA5（13.14）", strong.adviceRef())
        val breakout = PullbackAnchorPolicy.anchorOf(
            tier = StrengthTier.STRONG, price = 15.2, ma5 = 14.0, ma20 = 13.0, priorHigh = 15.0,
        )
        assertEquals("突破位（15.00）", breakout.adviceRef())
        val neutral = PullbackAnchorPolicy.anchorOf(
            tier = StrengthTier.NEUTRAL, price = 12.0, ma5 = 11.7, ma20 = 10.9, priorHigh = null,
        )
        assertEquals("MA20（10.90）", neutral.adviceRef())
        val weak = PullbackAnchorPolicy.anchorOf(
            tier = StrengthTier.WEAK, price = 9.8, ma5 = 9.9, ma20 = 10.0, priorHigh = null,
        )
        assertNull(weak.adviceRef())
    }

    @Test
    fun anchorForWithoutRecentClosesNeverClaimsStrong() {
        // 缺近期收盘证据不猜强势档：落中性维持 MA20 口径，绝不给浅锚。
        val anchor = PullbackAnchorPolicy.anchorFor(
            price = 15.07, ma5 = 13.14, ma10 = 12.55, ma20 = 12.17, recentCloses = emptyList(),
        )
        assertEquals(StrengthTier.NEUTRAL, anchor.tier)
        assertEquals("MA20（12.17）", anchor.adviceRef())
    }

    @Test
    fun anchorForReplaysHailiStrongStageAsShallowMa5Anchor() {
        // 海立股份 2026-11-19：现价 15.07、20 日低点 11.04。「回踩支撑」若指 11.04
        // 等于说趋势已死；分档锚应给 MA5 浅锚。收盘序列取自 entry-timing-2026-11-19/600619.json。
        val closes = listOf(11.95, 12.01, 12.07, 11.90, 11.90, 11.91, 13.10, 13.70, 15.07)
        val anchor = PullbackAnchorPolicy.anchorFor(
            price = 15.07, ma5 = 13.14, ma10 = 12.55, ma20 = 12.17, recentCloses = closes,
        )
        assertEquals(StrengthTier.STRONG, anchor.tier)
        assertEquals("MA5（13.14）", anchor.adviceRef())
    }

    // ---------- 可达性护栏 ----------

    @Test
    fun deepGapBeyondAtrBandIsUnreachable() {
        val note = PullbackAnchorPolicy.unreachableNote(suggestedPrice = 9.0, currentPrice = 12.0, atrPct = 2.0)
        assertNotNull(note)
        assertTrue(note!!.contains("大概率到不了"))
    }

    @Test
    fun gapWithinVolatilityIsReachable() {
        assertNull(PullbackAnchorPolicy.unreachableNote(suggestedPrice = 11.6, currentPrice = 12.0, atrPct = 2.0))
    }

    @Test
    fun missingAtrMakesNoClaimAndSuggestedPriceAtOrAboveCurrentIsReachable() {
        assertNull(PullbackAnchorPolicy.unreachableNote(suggestedPrice = 9.0, currentPrice = 12.0, atrPct = null))
        assertNull(PullbackAnchorPolicy.unreachableNote(suggestedPrice = 12.5, currentPrice = 12.0, atrPct = 2.0))
    }

    @Test
    fun offBandReasonCarriesReachabilityNoteForDeepMa20Band() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.NEUTRAL, price = 12.0, ma5 = 11.8, ma20 = 10.0,
            priorHigh = null, stop = 9.5, target = 15.0, minRewardRisk = 2.0,
        )
        val reason = band.offBandReason(price = 12.0, atrPct = 2.0)
        assertTrue(reason.contains("不追价"))
        assertTrue(reason.contains("大概率到不了"))
    }

    @Test
    fun offBandReasonForStrongSaysShallowAnchorNotMa20() {
        val band = PullbackAnchorPolicy.bandOf(
            tier = StrengthTier.STRONG, price = 10.8, ma5 = 10.24, ma20 = 10.0,
            priorHigh = null, stop = 9.5, target = 12.0, minRewardRisk = 2.0,
        )
        val reason = band.offBandReason(price = 10.8, atrPct = 2.0)
        assertTrue(reason.contains("浅回踩"))
        assertTrue(reason.contains("不硬等 M20"))
        assertFalse(reason.contains("等回踩 M20"))
    }
}
