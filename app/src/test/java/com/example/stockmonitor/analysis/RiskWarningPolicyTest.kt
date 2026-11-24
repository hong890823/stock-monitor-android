package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskWarningPolicyTest {

    @Test
    fun stNameIsAlertEvenWithoutAnnouncements() {
        val assessment = RiskWarningPolicy.assess("ST欢瑞", emptyList(), "2026-11-24")
        assertTrue(assessment.hasWarning)
        assertEquals(RiskWarningKind.ST_OTHER, assessment.warnings.first().kind)
        assertEquals("其他风险警示（ST）", assessment.warnings.first().kind.label)
    }

    @Test
    fun starStIsDelistingRisk() {
        val assessment = RiskWarningPolicy.assess("*ST欢瑞", emptyList(), "2026-11-24")
        assertEquals(RiskWarningKind.ST_DELISTING, assessment.warnings.first().kind)
        assertTrue(assessment.warnings.first().note.contains("终止上市"))
    }

    @Test
    fun riskWarningAnnouncementIsCaptured() {
        val assessment = RiskWarningPolicy.assess(
            "欢瑞世纪",
            listOf(Announcement("2026-11-22", "关于公司股票被实施其他风险警示暨停牌的公告")),
            "2026-11-24",
        )
        assertTrue(assessment.hasWarning)
        assertEquals(RiskWarningKind.ST_OTHER, assessment.warnings.first().kind)
    }

    @Test
    fun investigationOlderThan180DaysIsStillVisible() {
        val assessment = RiskWarningPolicy.assess(
            "ST欢瑞",
            listOf(
                Announcement("2026-04-30", "关于收到中国证监会立案告知书的公告"),
                Announcement("2026-11-22", "关于公司股票被实施其他风险警示暨停牌的公告"),
            ),
            "2026-11-24",
        )
        val kinds = assessment.warnings.map { it.kind }
        assertTrue(kinds.contains(RiskWarningKind.INVESTIGATION))
        assertTrue(assessment.unresolvedInvestigation)
        assertTrue(assessment.investigationAndStLinked)
        val investigation = assessment.warnings.first { it.kind == RiskWarningKind.INVESTIGATION }
        assertTrue(investigation.note.contains("未看到结案/撤销"))
    }

    @Test
    fun closedInvestigationIsNotKeptAsOpenRisk() {
        val assessment = RiskWarningPolicy.assess(
            "测试股",
            listOf(
                Announcement("2026-04-30", "关于收到中国证监会立案告知书的公告"),
                Announcement("2026-09-20", "关于收到中国证监会结案通知书的公告"),
            ),
            "2026-11-24",
        )
        assertFalse(assessment.warnings.any { it.kind == RiskWarningKind.INVESTIGATION })
        assertFalse(assessment.unresolvedInvestigation)
    }

    @Test
    fun revokedRiskWarningIsNotKept() {
        val assessment = RiskWarningPolicy.assess(
            "测试股",
            listOf(
                Announcement("2026-08-20", "关于公司股票被实施其他风险警示的公告"),
                Announcement("2026-10-20", "关于撤销其他风险警示暨停牌的公告"),
            ),
            "2026-11-24",
        )
        assertFalse(assessment.warnings.any { it.kind == RiskWarningKind.ST_OTHER })
    }

    @Test
    fun namePrefixOnlyAvoidsFalsePositive() {
        assertNull(RiskWarningPolicy.stKind("华ST科技"))
        assertEquals(RiskWarningKind.ST_OTHER, RiskWarningPolicy.stKind("ST华瑞"))
    }

    @Test
    fun fundOccupationRoutineSummaryIsNotRisk() {
        assertNull(RiskWarningPolicy.matchKind("非经营性资金占用及其他关联资金往来情况汇总表"))
        assertEquals(
            RiskWarningKind.FUND_OCCUPATION,
            RiskWarningPolicy.matchKind("关于控股股东非经营性资金占用及整改情况的公告"),
        )
    }

    @Test
    fun bannerPutsStAndHistoryTogether() {
        val assessment = RiskWarningPolicy.assess(
            "ST欢瑞",
            listOf(
                Announcement("2026-04-30", "关于收到中国证监会立案告知书的公告"),
                Announcement("2026-11-22", "关于公司股票被实施其他风险警示暨停牌的公告"),
            ),
            "2026-11-24",
        )
        val text = RiskWarningPolicy.bannerLines(assessment).joinToString("\n")
        assertTrue(text.contains("风险预警（优先看）"))
        assertTrue(text.contains("ST欢瑞"))
        assertTrue(text.contains("立案调查"))
        assertTrue(text.contains("不用低估值或超跌作为买入理由"))
    }

    @Test
    fun noWarningForNormalStock() {
        val assessment = RiskWarningPolicy.assess(
            "贵州茅台",
            listOf(Announcement("2026-10-30", "2026年第三季度报告")),
            "2026-11-24",
        )
        assertFalse(assessment.hasWarning)
        assertEquals(emptyList<String>(), RiskWarningPolicy.bannerLines(assessment))
    }
}

class RiskWarningReportTest {

    private fun period() = FinancialPeriod(
        date = "2026-06-30",
        revenue = 100.0,
        operateCost = 80.0,
        netProfit = 10.0,
        deductNetProfit = 9.0,
        operatingCashFlow = 10.0,
        salesCash = 100.0,
        capex = 1.0,
    )

    private fun input(
        name: String,
        quoteName: String?,
        announcements: List<Announcement>,
    ) = Fundamentals.Input(
        periods = listOf(period()),
        balance = null,
        valuations = emptyList(),
        totalShares = 100.0,
        price = 4.2,
        announcements = announcements,
        today = "2026-11-24",
        name = name,
        quoteName = quoteName,
    )

    @Test
    fun reportShowsBannerAndRiskItemWithoutDuplicate() {
        val result = Fundamentals.analyze(
            input(
                name = "欢瑞世纪",
                quoteName = "ST欢瑞",
                announcements = listOf(
                    Announcement("2026-04-30", "关于收到中国证监会立案告知书的公告"),
                    Announcement("2026-11-22", "关于公司股票被实施其他风险警示暨停牌的公告"),
                ),
            ),
        )
        val report = FundamentalsReport.format("欢瑞世纪", "sz000892", 4.2, "2026-11-24", result)
        assertTrue(report.indexOf("风险预警（优先看）") >= 0)
        assertTrue(report.indexOf("风险预警（优先看）") < report.indexOf("一、快速概览"))
        assertTrue(report.contains("ST欢瑞"))
        val alarmTexts = result.risks.filter { it.level == RiskLevel.ALERT }.map { it.text }
        assertEquals(1, alarmTexts.count { it.contains("立案") })
        assertTrue(alarmTexts.any { it.contains("风险警示") })
    }

    @Test
    fun normalReportHasNoWarningBanner() {
        val result = Fundamentals.analyze(
            input(
                name = "贵州茅台",
                quoteName = "贵州茅台",
                announcements = listOf(Announcement("2026-10-30", "2026年第三季度报告")),
            ),
        )
        val report = FundamentalsReport.format("贵州茅台", "sh600519", 1500.0, "2026-11-24", result)
        assertFalse(report.contains("风险预警（优先看）"))
    }
}
