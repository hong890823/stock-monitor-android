package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainForceDirectionPolicyTest {
    @Test
    fun evidenceShowsThreeAndFiveDayAmountsWithoutSamplingWording() {
        val dates = listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23")
        val mainNets = listOf(2_000_000.0, -1_000_000.0, 20_000_000.0, 15_000_000.0, 5_000_000.0)
        val mainPcts = listOf(2.0, -1.0, 20.0, 15.0, 5.0)
        val samples = dates.mapIndexed { index, date ->
            FundFlow(
                code = "sh600000",
                name = "测试股份",
                asOfMs = index.toLong(),
                amountYuan = 100_000_000.0,
                mainNetYuan = mainNets[index],
                mainNetPct = mainPcts[index],
                superLargeNetYuan = 5_000_000.0,
                largeNetYuan = 5_000_000.0,
                tradingDate = date,
            )
        }

        val assessment = MainForceDirectionPolicy.assess(2.0, dates.last(), samples)

        assertEquals(MainForceDirection.INFLOW_CONFIRMED, assessment.direction)
        assertEquals("主力进场｜证据较完整", assessment.summary)
        assertTrue(assessment.evidence.contains("近3日：累计净流入 4000万，平均占成交额 +13.33%（3/3天流入）"))
        assertTrue(assessment.evidence.contains("近5日：累计净流入 4100万，平均占成交额 +8.20%（4/5天流入）"))
        assertFalse(assessment.evidence.contains("已采样日"))
    }
}
