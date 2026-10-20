package com.example.stockmonitor

import com.example.stockmonitor.analysis.FundamentalHealth
import com.example.stockmonitor.analysis.FundamentalHealthPolicy
import com.example.stockmonitor.analysis.FundamentalHealthSnapshot
import com.example.stockmonitor.analysis.HealthLevel
import com.example.stockmonitor.analysis.QuarterPoint
import com.example.stockmonitor.analysis.RiskItem
import com.example.stockmonitor.analysis.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基本面健康分级：财务/公告可见面的恶化信号 → 亮红灯，评分尚可且无大风险 → 未见恶化。
 * 这里锁住的是「什么算恶化」和「当前优先动作怎么表述」，
 * 因为行业景气与订单是程序看不到的。
 */
class FundamentalHealthPolicyTest {

    private fun quarter(label: String, revenueYoy: Double?, netProfitYoy: Double?) = QuarterPoint(
        label = label,
        revenue = 1.0,
        netProfit = 1.0,
        grossMargin = null,
        revenueYoy = revenueYoy,
        netProfitYoy = netProfitYoy,
    )

    @Test
    fun weakScoreIsRed() {
        val health = FundamentalHealthPolicy.grade(
            score = 45, verdict = "较差", risks = emptyList(), quarters = emptyList(),
        )
        assertEquals(HealthLevel.ALERT, health.level)
    }

    @Test
    fun alertRiskIsRedEvenWithGoodScore() {
        val health = FundamentalHealthPolicy.grade(
            score = 80,
            verdict = "良好",
            risks = listOf(RiskItem(RiskLevel.ALERT, "公告提示：立案调查")),
            quarters = emptyList(),
        )
        assertEquals(HealthLevel.ALERT, health.level)
        assertTrue(health.reasons.any { it.contains("立案") })
    }

    @Test
    fun twoQuartersDoubleDeclineIsRed() {
        val health = FundamentalHealthPolicy.grade(
            score = 75,
            verdict = "良好",
            risks = emptyList(),
            quarters = listOf(
                quarter("2025Q4", revenueYoy = -5.0, netProfitYoy = -3.0),
                quarter("2026Q1", revenueYoy = -25.0, netProfitYoy = -30.0),
                quarter("2026Q2", revenueYoy = -22.0, netProfitYoy = -40.0),
            ),
        )
        assertEquals(HealthLevel.ALERT, health.level)
    }

    @Test
    fun singleWeakQuarterIsNeutralNotSound() {
        // 只有最新一季双降、上一季还好：够不上业绩变脸，但也不好意思说「未见恶化」。
        val health = FundamentalHealthPolicy.grade(
            score = 75,
            verdict = "良好",
            risks = emptyList(),
            quarters = listOf(
                quarter("2026Q1", revenueYoy = 10.0, netProfitYoy = 8.0),
                quarter("2026Q2", revenueYoy = -25.0, netProfitYoy = -30.0),
            ),
        )
        assertEquals(HealthLevel.NEUTRAL, health.level)
    }

    @Test
    fun missingYoyDoesNotCountAsDownturn() {
        // 拿不到同比数据不能当成「业绩变脸」，缺数据不等于坏数据。
        val health = FundamentalHealthPolicy.grade(
            score = 75,
            verdict = "良好",
            risks = emptyList(),
            quarters = listOf(
                quarter("2026Q1", revenueYoy = null, netProfitYoy = null),
                quarter("2026Q2", revenueYoy = null, netProfitYoy = null),
            ),
        )
        assertEquals(HealthLevel.SOUND, health.level)
    }

    @Test
    fun soundWhenScoreGoodAndNoAlerts() {
        val health = FundamentalHealthPolicy.grade(
            score = 76, verdict = "良好", risks = emptyList(), quarters = emptyList(),
        )
        assertEquals(HealthLevel.SOUND, health.level)
    }

    @Test
    fun midScoreStaysNeutral() {
        val health = FundamentalHealthPolicy.grade(
            score = 60, verdict = "中性", risks = emptyList(), quarters = emptyList(),
        )
        assertEquals(HealthLevel.NEUTRAL, health.level)
    }

    @Test
    fun tendencyDeepGivesCurrentPriorityAction() {
        val red = FundamentalHealthPolicy.grade(
            score = 40, verdict = "较差", risks = emptyList(), quarters = emptyList(),
        )
        val text = FundamentalHealthPolicy.tendency(red, deep = true)!!
        assertTrue(text.contains("亮红灯"))
        assertTrue(text.contains("当前优先"))
        assertTrue(text.contains("分批离场"))

        val sound = FundamentalHealthPolicy.grade(
            score = 76, verdict = "良好", risks = emptyList(), quarters = emptyList(),
        )
        val soundText = FundamentalHealthPolicy.tendency(sound, deep = true)!!
        assertTrue(soundText.contains("未见恶化"))
        assertTrue(soundText.contains("小仓处理"))
    }

    @Test
    fun tendencyShallowMentionsFundamentalsEvenWithoutData() {
        // 破位恰是最需要基本面判断的时刻，没数据也要交代一句，不能沉默。
        val hint = FundamentalHealthPolicy.tendency(null, deep = false)!!
        assertTrue(hint.contains("未分析"))
        val red = FundamentalHealthPolicy.grade(
            score = 40, verdict = "较差", risks = emptyList(), quarters = emptyList(),
        )
        assertTrue(FundamentalHealthPolicy.tendency(red, deep = false)!!.contains("亮红灯"))
    }

    @Test
    fun cyclicalNoteCapsAtNeutral() {
        // 周期股盈利高点的低估值是陷阱：再好的评分也不说「未见恶化」。
        val health = FundamentalHealthPolicy.grade(
            score = 76, verdict = "良好", risks = emptyList(), quarters = emptyList(),
            notes = listOf("周期股在盈利高点的低 PE 是陷阱而不是便宜"),
        )
        assertEquals(HealthLevel.NEUTRAL, health.level)
    }

    @Test
    fun forecastDowngradesAreRed() {
        // 业绩预亏/预减公告（Governance 归入 ALERT）直接亮红灯，比财报快。
        val health = FundamentalHealthPolicy.grade(
            score = 76, verdict = "良好",
            risks = listOf(RiskItem(RiskLevel.ALERT, "公告提示：业绩预亏（2026-10-01）")),
            quarters = emptyList(),
        )
        assertEquals(HealthLevel.ALERT, health.level)
    }

    @Test
    fun tendencyDeepWithoutDataStillExplainsMissingFundamentals() {
        val text = FundamentalHealthPolicy.tendency(null, deep = true)!!
        assertTrue(text.contains("暂无数据"))
    }

    @Test
    fun tendencyCarriesTheScoreBehindTheLabel() {
        // 「亮红灯」是标签，不是依据——带上评分，读者才核对得了这个判断。
        val red = FundamentalHealthPolicy.grade(
            score = 40, verdict = "较差", risks = emptyList(), quarters = emptyList(),
        )
        assertTrue(FundamentalHealthPolicy.tendency(red, deep = true)!!.contains("评分 40"))
    }

    @Test
    fun neutralTendencySaysWhyTheProgramCannotPick() {
        // 不把无法判断写成「自行取舍」；要说清程序为什么分不出来，用户才知道该拿什么补。
        val neutral = FundamentalHealthPolicy.grade(
            score = 60, verdict = "中性", risks = emptyList(), quarters = emptyList(),
        )
        val text = FundamentalHealthPolicy.tendency(neutral, deep = true)!!

        assertTrue(text.contains("行业景气与订单"))
        assertFalse(text.contains("自行取舍"))
    }

    @Test
    fun healthSnapshotKeepsAnalyzedAt() {
        val health = FundamentalHealth(
            level = HealthLevel.SOUND,
            score = 76,
            verdict = "良好",
            reasons = listOf("评分 76（良好）"),
            analyzedAt = "2026-10-09 10:00",
        )
        assertEquals("2026-10-09 10:00", health.analyzedAt)
    }

    @Test
    fun freshCacheIsUsedWithoutFetching() {
        // 有缓存就用缓存：基本面半个月内基本不变，提醒时不反复拉接口。
        val snapshot = FundamentalHealthSnapshot(
            health = FundamentalHealth(
                level = HealthLevel.SOUND, score = 76, verdict = "良好",
                reasons = listOf("评分 76（良好）"), analyzedAt = "2026-10-09 10:00",
            ),
            fetchedAtMs = 1_000_000L,
        )
        val dayMs = 24 * 60 * 60 * 1000L
        assertFalse(FundamentalHealthPolicy.needsFetch(snapshot, lastAttemptMs = 1_000_000L, nowMs = 1_000_000L + 13 * dayMs))
        // 过了半个月才补拉。
        assertTrue(FundamentalHealthPolicy.needsFetch(snapshot, lastAttemptMs = 1_000_000L, nowMs = 1_000_000L + 15 * dayMs))
    }

    @Test
    fun failedFetchRetriesWithThrottle() {
        // 没缓存才需要拉；失败后 4 小时内不重试，避免轮询打爆接口。
        val retry = FundamentalHealthPolicy.RETRY_AFTER_FAILURE_MS
        assertTrue(FundamentalHealthPolicy.needsFetch(null, lastAttemptMs = 0L, nowMs = retry + 1))
        assertFalse(FundamentalHealthPolicy.needsFetch(null, lastAttemptMs = 1_000_000L, nowMs = 1_000_000L + retry - 1))
        assertTrue(FundamentalHealthPolicy.needsFetch(null, lastAttemptMs = 1_000_000L, nowMs = 1_000_000L + retry))
    }
}
