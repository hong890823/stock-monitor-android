package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.GuardedActionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPushPolicyTest {

    @Test
    fun actionableAndNextDayPush() {
        assertTrue(AlertPushPolicy.classify(GuardedActionLevel.ACTIONABLE).isPush)
        assertTrue(AlertPushPolicy.classify(GuardedActionLevel.NEXT_DAY).isPush)
    }

    @Test
    fun observationAndRiskFactAreRecordOnly() {
        val observation = AlertPushPolicy.classify(GuardedActionLevel.OBSERVATION)
        assertFalse(observation.isPush)
        assertEquals(AlertPushPolicy.Delivery.RECORD_ONLY, observation.delivery)

        assertFalse(AlertPushPolicy.classify(GuardedActionLevel.RISK_FACT).isPush)
    }

    @Test
    fun riskFactPushExceptionsKeepDelivering() {
        // 破位提示 / 高开低走：只报告风险不生成卖量，但要送达（不能被静默吞掉）。
        assertTrue(AlertPushPolicy.classify(GuardedActionLevel.RISK_FACT, riskFactPush = true).isPush)
    }

    @Test
    fun supportOpNeedsValidatedSupport() {
        assertTrue(AlertPushPolicy.classifySupportOp(null).isPush) // 缺数据 fail-open
        assertTrue(AlertPushPolicy.classifySupportOp(true).isPush)
        val invalid = AlertPushPolicy.classifySupportOp(false)
        assertFalse(invalid.isPush)
        assertTrue(invalid.reason.contains("支撑未验证"))
    }
}
