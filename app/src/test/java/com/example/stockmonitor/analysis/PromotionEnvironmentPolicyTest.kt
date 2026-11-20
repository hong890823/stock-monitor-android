package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionEnvironmentPolicyTest {
    @Test
    fun broadMarketRiskDelaysPromotion() {
        val gate = PromotionEnvironmentPolicy.gate(
            PromotionEnvironmentInput(
                shanghaiChangePct = -0.4,
                shenzhenChangePct = -0.9,
                limitUpCount = 30,
                limitDownCount = 5,
                boardChanges = listOf(1.0),
            ),
        )

        assertEquals("主要指数进入风险区，暂缓候选晋级", gate)
    }

    @Test
    fun limitDownBreadthDelaysPromotion() {
        val gate = PromotionEnvironmentPolicy.gate(
            PromotionEnvironmentInput(
                shanghaiChangePct = -0.2,
                shenzhenChangePct = -0.3,
                limitUpCount = 12,
                limitDownCount = 20,
                boardChanges = listOf(1.0),
            ),
        )

        assertEquals("跌停数量明显偏多，市场承接转弱，暂缓候选晋级", gate)
    }

    @Test
    fun allKnownBoardsRetreatingNoLongerBlocksPromotion() {
        // 板块标签有来源与粒度误差，只作排序/辅助证据，不再单独阻断候选晋级；
        // 真正的掉档由个股趋势、关键位、量价和市场风险共振决定。
        val gate = PromotionEnvironmentPolicy.gate(
            PromotionEnvironmentInput(
                shanghaiChangePct = 0.1,
                shenzhenChangePct = 0.2,
                limitUpCount = 30,
                limitDownCount = 5,
                boardChanges = listOf(-1.0, -1.8, null),
            ),
        )

        assertNull(gate)
    }

    @Test
    fun missingOrMixedEnvironmentDataDoesNotBlock() {
        assertNull(
            PromotionEnvironmentPolicy.gate(
                PromotionEnvironmentInput(
                    shanghaiChangePct = null,
                    shenzhenChangePct = -0.2,
                    limitUpCount = null,
                    limitDownCount = null,
                    boardChanges = listOf(null),
                ),
            ),
        )
        assertNull(
            PromotionEnvironmentPolicy.gate(
                PromotionEnvironmentInput(
                    shanghaiChangePct = 0.2,
                    shenzhenChangePct = 0.1,
                    limitUpCount = 20,
                    limitDownCount = 10,
                    boardChanges = listOf(-1.5, 0.2),
                ),
            ),
        )
    }
}
