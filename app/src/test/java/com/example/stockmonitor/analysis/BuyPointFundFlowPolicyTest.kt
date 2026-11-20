package com.example.stockmonitor.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuyPointFundFlowPolicyTest {

    private fun input(
        price: Double = 10.2,
        vwap: Double? = 10.0,
        volumeRatio: Double? = 1.4,
        dayMainNetPct: Double? = 2.0,
        recent5MainNetPct: Double? = 1.0,
        recent15MainNetPct: Double? = 1.0,
        direction: MainForceDirection? = null,
    ) = BuyPointFundFlowPolicy.Input(
        price = price,
        vwap = vwap,
        volumeRatio = volumeRatio,
        dayMainNetPct = dayMainNetPct,
        recent5MainNetPct = recent5MainNetPct,
        recent15MainNetPct = recent15MainNetPct,
        mainForceDirection = direction,
    )

    @Test
    fun distributionWithoutAbsorptionSuppressesTheAlert() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(dayMainNetPct = -6.0, recent5MainNetPct = -4.0, recent15MainNetPct = -3.0),
        )

        assertFalse("主力净流出且近端无承接时不应提醒", decision.allow)
        assertTrue(decision.reason.contains("出货"))
    }

    @Test
    fun outflowDirectionAloneAlsoSuppressesWithoutAbsorption() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(
                dayMainNetPct = 0.5,
                recent5MainNetPct = 0.5,
                recent15MainNetPct = 0.5,
                direction = MainForceDirection.TURNING_WEAK,
            ),
        )

        assertFalse(decision.allow)
    }

    @Test
    fun absorptionOverridesOutflowBecauseSomeoneIsBuyingTheDip() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(dayMainNetPct = -5.0, recent5MainNetPct = 4.0, recent15MainNetPct = 3.0),
        )

        assertTrue("近5/15分钟出现大单承接时仍可提醒", decision.allow)
        assertTrue(decision.reason.contains("承接"))
    }

    @Test
    fun weakReclaimBelowVwapOnShrinkingVolumeIsSuppressed() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(
                price = 9.95,
                vwap = 10.1,
                volumeRatio = 0.8,
                dayMainNetPct = 1.0,
                recent5MainNetPct = 1.0,
                recent15MainNetPct = 1.0,
            ),
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("成交均价"))
    }

    @Test
    fun missingFundFlowDoesNotFabricateDistribution() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(
                price = 10.3,
                vwap = 10.0,
                volumeRatio = 1.5,
                dayMainNetPct = null,
                recent5MainNetPct = null,
                recent15MainNetPct = null,
            ),
        )

        assertTrue(decision.allow)
        assertTrue(decision.evidence.contains("暂缺"))
    }

    @Test
    fun healthyReclaimWithAbsorptionIsAllowed() {
        val decision = BuyPointFundFlowPolicy.assess(
            input(dayMainNetPct = 6.0, recent5MainNetPct = 5.0, recent15MainNetPct = 4.0),
        )

        assertTrue(decision.allow)
        assertTrue(decision.evidence.contains("当日主力"))
    }
}