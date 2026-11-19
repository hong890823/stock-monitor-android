package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FundFlowDisplayPolicyTest {

    @Test
    fun currentFundFlowUsesPlainInflowAndOutflowWords() {
        val inflow = FundFlow(
            code = "sh600000",
            name = "测试股票",
            asOfMs = 1L,
            mainNetYuan = 12_000_000.0,
            mainNetPct = 2.5,
        )

        assertTrue(FundFlowDisplayPolicy.current(inflow).contains("今日主力资金净流入"))
        assertTrue(FundFlowDisplayPolicy.current(inflow).contains("1200.0万"))
        assertTrue(FundFlowDisplayPolicy.current(inflow.copy(mainNetYuan = -8_000_000.0, mainNetPct = -1.5)).contains("净流出"))
    }

    @Test
    fun multiDayFundFlowExplainsDirectionWithoutCodeWords() {
        val flows = listOf(
            flow(day = 1, pct = 4.0),
            flow(day = 2, pct = 3.0),
            flow(day = 3, pct = 2.0),
        )

        val summary = FundFlowDisplayPolicy.multiDay(flows)
        assertTrue(summary.contains("3天净流入"))
        assertTrue(summary.contains("整体持续流入"))
        assertTrue(summary.contains("平均占成交额"))
    }

    @Test
    fun candidateSummaryMergesLatestAndWindowsWithoutRepeatingThreeDayContext() {
        val flows = (1..5).map { day ->
            flow(day = day, pct = day.toDouble()).copy(mainNetYuan = if (day == 5) 12_000_000.0 else 10_000_000.0)
        }
        val summary = FundFlowDisplayPolicy.candidateSummary(flows.last(), FundFlowWindowPolicy.assess(flows))

        assertTrue(summary.contains("最新主力净流入 1200.0万（+5.00%）"))
        assertTrue(summary.contains("近3日 3/3天净流入（均值 +4.00%）"))
        assertTrue(summary.contains("近5日 5/5天净流入"))
        assertTrue(summary.contains("持续流入，仅作加分参考"))
        assertEquals(1, Regex("近3日").findAll(summary).count())
        assertFalse(summary.contains("多日资金流"))
    }

    @Test
    fun candidateSummaryKeepsAccumulatingAndUnavailableStatesReadable() {
        val flows = listOf(flow(day = 1, pct = 4.0), flow(day = 2, pct = 3.0))
        val accumulating = FundFlowDisplayPolicy.candidateSummary(
            flows.last(),
            FundFlowWindowPolicy.assess(flows),
        )
        val unavailable = FundFlowDisplayPolicy.candidateSummary(null, FundFlowWindowPolicy.assess(emptyList()))

        assertTrue(accumulating.contains("近3日仅2/3天有有效样本"))
        assertEquals("暂无有效资金流数据", unavailable)
    }

    private fun flow(day: Int, pct: Double) = FundFlow(
        code = "sh600000",
        name = "测试股票",
        asOfMs = day.toLong(),
        amountYuan = 10_000_000.0,
        mainNetYuan = pct * 100_000.0,
        mainNetPct = pct,
    )
}
