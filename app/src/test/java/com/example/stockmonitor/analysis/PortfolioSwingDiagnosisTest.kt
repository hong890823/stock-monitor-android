package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioSwingDiagnosisTest {
    @Test
    fun fundedReadyRowReportsPassedConditions() {
        val row = SwingDecision(
            code = "sh600000",
            name = "测试持仓",
            status = SwingStatus.READY,
            score = 90,
            reason = "周月向好；日线回踩条件通过，潜在盈亏比2.5倍",
            addQuantity = 200.0,
        )

        assertEquals(SwingDiagnosticKind.ELIGIBLE, row.diagnosis.kind)
        assertTrue(row.diagnosis.passedConditions.isNotEmpty())
        assertTrue(row.diagnosis.blockers.isEmpty())
    }

    @Test
    fun missingEvidenceIsReportedAsDataGap() {
        val row = SwingDecision(
            code = "sh600000",
            name = "测试持仓",
            status = SwingStatus.BLOCKED,
            score = 0,
            reason = "基本面缺失或已过期，先更新核实",
        )

        assertEquals(SwingDiagnosticKind.DATA_GAP, row.diagnosis.kind)
        assertTrue(row.diagnosis.dataGaps.any { it.contains("基本面") })
        assertTrue(row.diagnosis.summary().contains("数据缺口"))
    }

    @Test
    fun capitalLimitIsSeparatedFromStrategyBlock() {
        val row = SwingDecision(
            code = "sh600000",
            name = "测试持仓",
            status = SwingStatus.READY,
            score = 90,
            reason = "现金、仓位或风险余额不足最低申报数量",
        )

        assertEquals(SwingDiagnosticKind.FUNDING_BLOCKED, row.diagnosis.kind)
        assertTrue(row.diagnosis.fundingIssues.isNotEmpty())
        assertTrue(row.action().contains("资金问题"))
    }

    @Test
    fun reportKeepsDiagnosisForEveryHolding() {
        val plan = SwingPlan(
            rows = listOf(
                SwingDecision("sh600000", "测试持仓", SwingStatus.BLOCKED, 0, "高位派发风险，停止新增资金并优先风险处置"),
                SwingDecision("sz000001", "第二只", SwingStatus.WAIT, 40, "长中期背景通过，日线仍需缩量回踩企稳"),
            ),
            asOf = "2026-11-16",
            capitalIssue = null,
        )

        val report = plan.reportLines().joinToString("\n")

        assertTrue(report.contains("诊断："))
        assertTrue(report.contains("高位派发风险"))
        assertTrue(report.contains("等待确认"))
    }
}
