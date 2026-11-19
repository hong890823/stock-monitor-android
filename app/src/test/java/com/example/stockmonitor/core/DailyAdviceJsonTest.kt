package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.AdviceDirection
import com.example.stockmonitor.analysis.AdviceKind
import com.example.stockmonitor.analysis.AdviceNotificationState
import com.example.stockmonitor.analysis.AdvicePlanState
import com.example.stockmonitor.analysis.DailyAdvicePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAdviceJsonTest {
    @Test
    fun dailyAdviceRoundTripsAllState() {
        val source = listOf(
            DailyAdvicePlan(
                date = "2026-11-16",
                code = "sz000001",
                direction = AdviceDirection.SELL,
                kind = AdviceKind.REDUCE,
                targetQuantity = 400.0,
                executedQuantity = 200.0,
                lastKnownQuantity = 1_000.0,
                state = AdvicePlanState.PARTIALLY_EXECUTED,
                notificationState = AdviceNotificationState.SENT,
                reviewReason = "等待剩余数量变化",
                createdAtMs = 10L,
                updatedAtMs = 20L,
            ),
            DailyAdvicePlan(
                date = "2026-11-16",
                code = "sh600000",
                direction = AdviceDirection.BUY,
                kind = AdviceKind.NORMAL_BUY,
                targetQuantity = 200.0,
                executedQuantity = 0.0,
                lastKnownQuantity = 0.0,
                state = AdvicePlanState.DELIVERY_UNKNOWN,
                notificationState = AdviceNotificationState.UNKNOWN,
                reviewReason = "通知系统返回未知",
                createdAtMs = 30L,
                updatedAtMs = 40L,
            ),
        )

        assertEquals(source, DailyAdviceJson.decode(DailyAdviceJson.encode(source)))
    }

    @Test
    fun malformedDailyAdviceStorageFallsBackToEmpty() {
        assertTrue(DailyAdviceJson.decode("not-json").isEmpty())
        assertTrue(DailyAdviceJson.decode(null).isEmpty())
    }

    @Test
    fun invalidEntriesAreSkippedWithoutDroppingValidPlans() {
        val valid = DailyAdvicePlan(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            executedQuantity = 0.0,
            lastKnownQuantity = 0.0,
        )
        val raw = "[${DailyAdviceJson.encode(listOf(valid)).removePrefix("[").removeSuffix("]")},{}]"

        assertEquals(listOf(valid), DailyAdviceJson.decode(raw))
    }
}
