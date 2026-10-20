package com.example.stockmonitor

import com.example.stockmonitor.analysis.*
import org.junit.Assert.*
import org.junit.Test

class IntradayFundamentalTest {
    private fun health(vararg evidence: FundamentalEvidence) = FundamentalHealth(
        HealthLevel.ALERT, 40, "偏弱", listOf("历史风险"), "2026-10-16", evidence.toList())

    @Test fun refreshDateCannotRenewOldOrUndatedEvents() {
        listOf("2026-04-18", "", "bad-date", "2026-10-17").forEach { date ->
            val result = FundamentalHealthPolicy.forIntraday(health(FundamentalEvidence(date, "处罚")), "2026-10-16")
            assertEquals(date, HealthLevel.NEUTRAL, result!!.level)
        }
        assertEquals(HealthLevel.NEUTRAL, FundamentalHealthPolicy.forIntraday(health(), "2026-10-16")!!.level)
        assertEquals(HealthLevel.ALERT, FundamentalHealthPolicy.forIntraday(
            health(FundamentalEvidence("2026-10-15", "最新处罚")), "2026-10-16")!!.level)
    }

    @Test fun eventExpiresByEvidenceDateEvenWithoutCacheRefresh() {
        val original = health(FundamentalEvidence("2026-04-19", "处罚"))
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
}
