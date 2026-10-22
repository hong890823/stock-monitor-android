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
    }

    @Test
    fun fewerThanThreeValidDaysRemainAccumulatingAndNeutral() {
        val result = FundFlowWindowPolicy.assess(
            listOf(flow(day = 1, mainNetPct = 8.0), flow(day = 2, mainNetPct = 9.0)),
        )

        assertEquals(FundFlowWindowTone.ACCUMULATING, result.tone)
        assertEquals(0, FundFlowWindowPolicy.scoreDelta(result))
        assertTrue(result.reason.contains("样本积累"))
    }

    @Test
    fun persistentOutflowCanDelayButCannotProduceTradeSignal() {
        val result = FundFlowWindowPolicy.assess(
            (1..5).map { flow(day = it, mainNetPct = -5.0) },
        )

        assertEquals(FundFlowWindowTone.PERSISTENT_OUTFLOW, result.tone)
        assertEquals(-1, FundFlowWindowPolicy.scoreDelta(result))
        assertTrue(result.reason.contains("仅影响候选池晋级"))
    }

    private fun flow(day: Int, mainNetPct: Double) = FundFlow(
        code = "sh600000",
        name = "浦发银行",
        asOfMs = day.toLong(),
        amountYuan = 10_000_000.0,
        mainNetPct = mainNetPct,
    )
}
