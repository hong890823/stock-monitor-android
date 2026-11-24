package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import org.junit.Assert.*
import org.junit.Test

class IntradayFundamentalTest {
    private fun health(vararg evidence: FundamentalEvidence) = FundamentalHealth(
        HealthLevel.ALERT, 40, "偏弱", listOf("历史风险"), "2026-10-16", evidence.toList())

    @Test fun refreshDateCannotRenewOldOrUndatedEvents() {
        listOf("2026-04-18", "", "bad-date", "2026-10-17").forEach { date ->
            val result = FundamentalHealthPolicy.forIntraday(health(FundamentalEvidence(date, "业绩预亏")), "2026-10-16")
            assertEquals(date, HealthLevel.NEUTRAL, result!!.level)
        }
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(health(), "2026-10-16")!!.level)
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(
            health(FundamentalEvidence("2026-10-15", "最新业绩预亏")), "2026-10-16")!!.level)
    }

    @Test fun eventExpiresByEvidenceDateEvenWithoutCacheRefresh() {
        val original = health(FundamentalEvidence("2026-04-19", "业绩预亏"))
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(original, "2026-10-16")!!.level)
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(original, "2026-10-17")!!.level)
        assertEquals(HealthLevel.ALERT, original.level)
    }

    @Test fun financialPeriodUsesReportingDeadlineNotFetchDateOrSixMonthEventWindow() {
        val annual = health(FundamentalEvidence("2025-12-31", "财务风险", financial = true))
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(annual, "2026-04-29")!!.level)
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(annual, "2026-05-01")!!.level)
        val half = health(FundamentalEvidence("2026-06-30", "现金流风险", financial = true))
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(half, "2026-10-16")!!.level)
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(half, "2026-11-01")!!.level)
    }

    @Test fun majorComplianceRedLightUses730DayWindowLikeRiskWarningBacktrack() {
        // 重大合规（退市风险警示/立案/处罚级，RiskWarningPolicy 入列）与报告「风险预警」同口径
        // 730 天回溯：209 天前的未结案立案仍亮红灯（普通公告 180 天已过期），731 天才熄灭。
        val major = FundamentalEvidence("2025-06-15", "公告提示：立案调查——《立案告知书》（2025-06-15）", majorCompliance = true)
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(health(major), "2026-01-10")!!.level)
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(health(major), "2027-06-14")!!.level)
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(health(major), "2027-06-16")!!.level)
        // 普通公告类不搭车：同样 209 天仍按 180 天口径过期。
        val ordinary = FundamentalEvidence("2025-06-15", "公告提示：业绩预亏（2025-06-15）")
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(health(ordinary), "2026-01-10")!!.level)
    }

    @Test fun riskWarningPolicyItemsCarryMajorComplianceFlag() {
        // 生产入口：RiskWarningPolicy 入列的红灯必须带 majorCompliance，730 天窗口才生效。
        val items = RiskWarningPolicy.riskItems(
            RiskWarningAssessment(
                stockName = "测试",
                warnings = listOf(RiskWarning(RiskWarningKind.INVESTIGATION, "2025-06-15", "立案告知书", "未结案")),
            ),
            fallbackDate = "2026-01-10",
        )
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.majorCompliance && it.announcement && it.level == RiskLevel.ALERT })
    }
}
