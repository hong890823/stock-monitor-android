package com.example.stockmonitor

import com.example.stockmonitor.analysis.AnnouncementSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementSummaryTest {

    @Test
    fun assessesPrivatePlacementBodyAsMixedWithApprovalAndDilutionRisks() {
        val body = """
            <p>本次发行股票数量不超过发行前公司总股本的30%。</p>
            <p>募集资金将用于海洋通信及智能电网项目，并补充流动资金。</p>
            <p>本次发行尚需上海证券交易所审核通过及中国证监会注册。</p>
        """.trimIndent()

        val assessment = AnnouncementSummary.assess(
            title = "关于2026年度向特定对象发行A股股票预案的公告",
            reason = "定增/再融资事项",
            content = body,
        )!!

        assertTrue(assessment.contains("影响待确认"))
        assertTrue(assessment.contains("审核/注册"))
        assertTrue(assessment.contains("摊薄"))
        assertFalse(assessment.contains("<p>"))
        assertFalse(assessment.contains("募集资金将用于海洋通信及智能电网项目"))
    }

    @Test
    fun assessesExpansionRiskWhenPrivatePlacementFundsCapacityBuildout() {
        val assessment = AnnouncementSummary.assess(
            title = "关于向特定对象发行A股股票募集资金投资项目的公告",
            reason = "定增/再融资事项",
            content = """
                <p>项目拟新增光通信产品产能，建设期24个月。</p>
                <p>公司将根据市场需求和客户订单推进产能建设。</p>
                <p>本次发行尚需交易所审核及中国证监会注册。</p>
            """.trimIndent(),
        )!!

        assertTrue(assessment.contains("扩产") || assessment.contains("产能") )
        assertTrue(assessment.contains("供需") || assessment.contains("产能利用率") || assessment.contains("订单") )
        assertTrue(assessment.contains("兑现") || assessment.contains("回报") )
    }

    @Test
    fun assessesPenaltyBodyAsNegative() {
        val assessment = AnnouncementSummary.assess(
            title = "关于收到行政处罚决定书的公告",
            reason = "行政处罚",
            content = "<p>因信息披露违法，公司被处以罚款并责令改正。</p>",
        )!!

        assertTrue(assessment.contains("偏利空"))
        assertTrue(assessment.contains("处罚"))
        assertFalse(assessment.contains("信息披露违法，公司被处以罚款"))
    }

    @Test
    fun explainsPledgeAnnouncementFromAnInvestorPerspective() {
        val assessment = AnnouncementSummary.assess(
            title = "关于股东股份质押的公告",
            reason = "股东质押",
            content = "<p>控股股东本次质押股份用于融资。</p>",
        )!!

        assertTrue(assessment.contains("平仓压力") || assessment.contains("控制权"))
        assertFalse(assessment.contains("控股股东本次质押股份用于融资"))
    }

    @Test
    fun returnsNullWhenAnnouncementAssessmentBodyIsUnavailable() {
        assertTrue(AnnouncementSummary.assess("关于股东减持股份的公告", "股东减持", null) == null)
        assertTrue(AnnouncementSummary.assess("关于股东减持股份的公告", "股东减持", "   ") == null)
    }

    @Test fun keywordAloneDoesNotProveGoodOrBadNews() {
        listOf(
            Triple("关于终止回购计划的公告", "股份回购", "公司决定终止回购计划，尚未实施回购。"),
            Triple("关于回购注销限制性股票的公告", "股份回购", "离职激励对象所获限制性股票将回购注销。"),
            Triple("关于增持计划的公告", "股东增持", "股东拟增持，尚未实施。"),
            Triple("关于分红的公告", "分红", "公司拟分红，需股东大会审议。"),
            Triple("关于股东部分股份解除质押及再质押的公告", "股东质押", "本次部分解除质押，同时新增质押。"),
            Triple("关于终止定增的公告", "定增/再融资事项", "公司终止本次发行。"),
        ).forEach { (title, reason, body) ->
            val text = AnnouncementSummary.assess(title, reason, body)!!
            assertFalse(text, text.startsWith("偏利好") || text.startsWith("偏利空"))
        }
    }

    @Test fun resolvedRiskAndUnknownForecastRevisionDoNotRemainRed() {
        val items = com.example.stockmonitor.analysis.Governance.scan(listOf(
            com.example.stockmonitor.analysis.Announcement("2026-10-01", "关于公司股份被冻结的公告"),
            com.example.stockmonitor.analysis.Announcement("2026-10-15", "关于公司股份全部解除冻结的公告"),
            com.example.stockmonitor.analysis.Announcement("2026-10-14", "业绩预告修正公告"),
            com.example.stockmonitor.analysis.Announcement("2026-10-13", "关于终止回购的公告"),
            com.example.stockmonitor.analysis.Announcement("2026-10-17", "关于公司收到行政处罚的公告"),
        ), "2026-10-16")
        assertTrue(items.isNotEmpty())
        assertFalse(items.any { it.level == com.example.stockmonitor.analysis.RiskLevel.ALERT })
        assertFalse(items.any { it.level == com.example.stockmonitor.analysis.RiskLevel.GOOD })
    }
}
