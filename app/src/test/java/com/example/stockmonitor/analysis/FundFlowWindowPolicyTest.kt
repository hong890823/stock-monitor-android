package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FundFlowWindowPolicyTest {

    @Test
    fun summarizesAtMostFiveMostRecentSamplesAcrossThreeAndFiveDayWindows() {
        val flows = (1..6).map { day ->
            FundFlow(
                code = "sh600000",
                name = "浦发银行",
                asOfMs = day.toLong(),
                amountYuan = 10_000_000.0,
                mainNetPct = if (day == 1) -20.0 else 5.0,
            )
        }

        val result = FundFlowWindowPolicy.assess(flows)

        assertEquals(5, result.sampleCount)
        assertEquals(3, result.recent3PositiveDays)
        assertEquals(5, result.recent5PositiveDays)
        assertEquals(FundFlowWindowTone.PERSISTENT_INFLOW, result.tone)
        assertTrue(result.summary.contains("近3个交易日3/3天净流入"))
        assertTrue(result.summary.contains("平均主力净占比 +5.00%"))
    }

    @Test
    fun fewerThanThreeValidDaysRemainAccumulatingAndNeutral() {
        val result = FundFlowWindowPolicy.assess(
            listOf(flow(day = 1, mainNetPct = 8.0), flow(day = 2, mainNetPct = 9.0)),
        )

        assertEquals(FundFlowWindowTone.ACCUMULATING, result.tone)
        assertEquals(0, FundFlowWindowPolicy.scoreDelta(result))
        assertTrue(result.reason.contains("已有2/3个交易日样本"))
        assertTrue(result.reason.contains("暂不判断持续流入或流出方向"))
    }

    @Test
    fun persistentOutflowCanDelayButCannotProduceTradeSignal() {
        val result = FundFlowWindowPolicy.assess(
            (1..5).map { flow(day = it, mainNetPct = -5.0) },
        )

        assertEquals(FundFlowWindowTone.PERSISTENT_OUTFLOW, result.tone)
        assertEquals(-1, FundFlowWindowPolicy.scoreDelta(result))
        assertTrue(result.summary.contains("近3个交易日3/3天净流出"))
        assertTrue(result.summary.contains("平均主力净占比 -5.00%"))
        assertTrue(result.reason.contains("近3个交易日0天净流入、3天净流出"))
        assertTrue(result.reason.contains("持续流出，暂缓晋级"))
        assertTrue(result.reason.contains("不单独触发买卖提醒"))
    }

    @Test
    fun mixedFlowExplainsDailyDirectionAndAverage() {
        val result = FundFlowWindowPolicy.assess(
            listOf(
                flow(day = 1, mainNetPct = 5.0),
                flow(day = 2, mainNetPct = -1.0),
                flow(day = 3, mainNetPct = -1.0),
            ),
        )

        assertEquals(FundFlowWindowTone.MIXED, result.tone)
        assertTrue(result.reason.contains("近3个交易日1天净流入、2天净流出"))
        assertTrue(result.reason.contains("方向不一致，保持中性"))
        assertTrue(result.summary.contains("近3个交易日1天净流入、2天净流出"))
        assertTrue(result.summary.contains("平均主力净占比 +1.00%"))
    }

    private fun flow(day: Int, mainNetPct: Double) = FundFlow(
        code = "sh600000",
        name = "浦发银行",
        asOfMs = day.toLong(),
        amountYuan = 10_000_000.0,
        mainNetPct = mainNetPct,
    )
}
