package com.example.stockmonitor.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-26 权限解耦：能力快照按动作意图取能力位，做T卖出不被买侧条件连带封死。 */
class TradeActionGuardTest {

    private val permissiveEvidence = TimeframeEvidence(
        m15Confirmations = 2,
        m30Aligned = true,
        complete = true,
        clearanceConfirmed = true,
        riskComplete = true,
        riskConfirmed = true,
        riskConfirmations = 2,
    )

    private val confirmingVolume = IntradayVolumeContext(
        marketExpanding = true,
        stockVolumeConfirming = true,
        barVolumeConfirming = true,
    )

    @Test
    fun addIntentConsumesAllowAdd() {
        // "add" 意图走 allowAdd：false 时被拦（此前 allowAdd 是死字段，无人消费）。
        val blocked = TradeActionGuard.evaluate(
            intent = "add",
            evidence = permissiveEvidence,
            volume = confirmingVolume,
            eligibility = TradeActionEligibility(
                totalQuantity = 2000.0,
                actionableSellableQuantity = 2000.0,
                buyCapacityQuantity = 10_000.0,
            ),
            tradeDate = "2026-11-26",
            invalidationPrice = 9.5,
            permissions = TradeActionPermissions(allowAdd = false, blockers = listOf("大趋势向下")),
        )
        assertTrue(blocked.text.contains("不执行买入"))
        assertFalse(blocked.text.contains("可小仓买入"))

        val allowed = TradeActionGuard.evaluate(
            intent = "add",
            evidence = permissiveEvidence,
            volume = confirmingVolume,
            eligibility = TradeActionEligibility(
                totalQuantity = 2000.0,
                actionableSellableQuantity = 2000.0,
                buyCapacityQuantity = 10_000.0,
            ),
            tradeDate = "2026-11-26",
            invalidationPrice = 9.5,
            permissions = TradeActionPermissions(allowAdd = true),
        )
        assertTrue(allowed.text.contains("可小仓买入"))
    }

    @Test
    fun tSellNotBlockedByBuySidePermissions() {
        // 做T卖出是减风险动作：allowT=true 时不再被买侧 blockers（大趋势向下/高位）拦。
        val action = TradeActionGuard.evaluate(
            intent = "t_sell",
            evidence = permissiveEvidence.copy(m15Confirmations = 1),
            eligibility = TradeActionEligibility(
                totalQuantity = 2000.0,
                actionableSellableQuantity = 2000.0,
                plannedTSellQuantity = 100.0,
                tSellNetProfitApproved = true,
            ),
            tradeDate = "2026-11-26",
            invalidationPrice = 9.5,
            permissions = TradeActionPermissions(
                allowT = true,
                blockers = listOf("大趋势向下", "高位结构不允许新增买入"),
            ),
        )
        assertFalse(action.text.contains("不做T"))
        assertTrue(action.level == GuardedActionLevel.ACTIONABLE)
    }
}
