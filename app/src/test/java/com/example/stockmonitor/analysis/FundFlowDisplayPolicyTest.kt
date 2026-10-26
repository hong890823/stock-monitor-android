package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
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

    private fun flow(day: Int, pct: Double) = FundFlow(
        code = "sh600000",
        name = "测试股票",
        asOfMs = day.toLong(),
        amountYuan = 10_000_000.0,
        mainNetPct = pct,
    )
}
