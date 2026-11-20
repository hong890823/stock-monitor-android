package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAdvicePolicyTest {
    @Test
    fun unchangedQuantityDoesNotConfirmExecution() {
        val plan = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
        ).plan

        val updated = DailyAdvicePolicy.applyQuantityFeedback(
            plan = plan,
            previousQuantity = 1_200.0,
            currentQuantity = 1_200.0,
            feedbackAtMs = 1L,
        )

        assertEquals(AdvicePlanState.PREPARED, updated.state)
        assertEquals(0.0, updated.executedQuantity, 0.001)
        assertEquals(1_200.0, updated.lastKnownQuantity, 0.001)
    }

    @Test
    fun partialReductionUsesOnlyNetDecreaseAndCompletesAtTarget() {
        val plan = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
        ).plan

        val partial = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_000.0, 1L)
        val complete = DailyAdvicePolicy.applyQuantityFeedback(partial, 1_000.0, 800.0, 2L)

        assertEquals(200.0, partial.executedQuantity, 0.001)
        assertEquals(AdvicePlanState.PARTIALLY_EXECUTED, partial.state)
        assertEquals(400.0, complete.executedQuantity, 0.001)
        assertEquals(AdvicePlanState.COMPLETED, complete.state)
    }

    @Test
    fun costOnlyChangeDoesNotCreateExecutionFeedback() {
        val plan = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
        ).plan

        val updated = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_200.0, 2L)

        assertEquals(0.0, updated.executedQuantity, 0.001)
        assertEquals(AdvicePlanState.PREPARED, updated.state)
    }

    @Test
    fun reverseQuantityChangeRequiresReview() {
        val plan = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
        ).plan

        val updated = DailyAdvicePolicy.applyQuantityFeedback(plan, 1_200.0, 1_300.0, 3L)

        assertEquals(AdvicePlanState.REVIEW_REQUIRED, updated.state)
        assertTrue(updated.reviewReason.orEmpty().isNotBlank())
        assertEquals(0.0, updated.executedQuantity, 0.001)
    }

    @Test
    fun oldDatePlanCannotBeReusedForToday() {
        val oldPlan = DailyAdvicePolicy.prepare(
            date = "2026-11-15",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = null,
        ).plan

        val preparation = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = oldPlan,
        )

        assertTrue(!preparation.reused)
        assertEquals("2026-11-16", preparation.plan.date)
        assertEquals(0.0, preparation.plan.executedQuantity, 0.001)
    }

    @Test
    fun sameDayOppositeSignalKeepsOriginalPlanForReview() {
        val original = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
        ).plan

        val preparation = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.HOLDING_ADD,
            targetQuantity = 200.0,
            currentQuantity = 1_200.0,
            existing = original,
        )

        assertTrue(preparation.reused)
        assertEquals(AdviceDirection.SELL, preparation.plan.direction)
        assertEquals(AdviceKind.REDUCE, preparation.plan.kind)
        assertEquals(AdvicePlanState.REVIEW_REQUIRED, preparation.plan.state)
    }

    @Test
    fun anotherStockUnfinishedBuyBlocksNewBuy() {
        val existing = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = null,
        ).plan

        assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(existing), "2026-11-16", "sh600000"))
    }

    @Test
    fun holdingAddAndNormalBuyShareTheSameLock() {
        val existing = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.HOLDING_ADD,
            targetQuantity = 200.0,
            currentQuantity = 500.0,
            existing = null,
        ).plan

        assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(existing), "2026-11-16", "sh600000"))
    }

    @Test
    fun completedBuyDoesNotBlockAnotherStock() {
        val existing = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = null,
        ).plan.copy(state = AdvicePlanState.COMPLETED, executedQuantity = 200.0)

        assertTrue(DailyAdvicePolicy.canReserveBuy(listOf(existing), "2026-11-16", "sh600000"))
    }

    @Test
    fun failedNotificationCanRollbackReservedPlan() {
        val prepared = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = null,
        ).plan
        val reserved = DailyAdvicePolicy.reserve(prepared, listOf(prepared), "2026-11-16", "sz000001")
        val rolledBack = DailyAdvicePolicy.rollback(reserved.plan, 4L, "通知发送失败")

        assertTrue(reserved.accepted)
        assertEquals(AdvicePlanState.PREPARED, rolledBack.state)
        assertEquals(AdviceNotificationState.FAILED, rolledBack.notificationState)
    }

    @Test
    fun deliveryUnknownRemainsBlocked() {
        val prepared = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.NORMAL_BUY,
            targetQuantity = 200.0,
            currentQuantity = 0.0,
            existing = null,
        ).plan
        val unknown = DailyAdvicePolicy.markDeliveryUnknown(prepared, 5L, "系统通知状态未知")

        assertTrue(!DailyAdvicePolicy.canReserveBuy(listOf(unknown), "2026-11-16", "sh600000"))
        assertEquals(AdvicePlanState.DELIVERY_UNKNOWN, unknown.state)
    }

    @Test
    fun blockedPlanIsRevivedWhenMutualExclusionClears() {
        // 旧版本会把“同日买入互斥”持久化成 BLOCKED；互斥解除后必须能重新准备/通知。
        val blocked = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.HOLDING_ADD,
            targetQuantity = 200.0,
            currentQuantity = 500.0,
            existing = null,
        ).plan.copy(
            state = AdvicePlanState.BLOCKED,
            notificationState = AdviceNotificationState.NOT_SENT,
        )

        val revived = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.BUY,
            kind = AdviceKind.HOLDING_ADD,
            targetQuantity = 200.0,
            currentQuantity = 500.0,
            existing = blocked,
        )

        assertEquals(AdvicePlanState.PREPARED, revived.plan.state)
        assertEquals(AdviceNotificationState.NOT_SENT, revived.plan.notificationState)
        assertTrue(revived.reused)
    }

    @Test
    fun riskStageEscalationReopensSentPlanWithoutLosingQuantityFeedback() {
        val sent = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = null,
            riskPriority = 1,
            riskStage = 2,
        ).plan.copy(
            executedQuantity = 100.0,
            state = AdvicePlanState.SENT,
            notificationState = AdviceNotificationState.SENT,
        )

        val escalated = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = sent,
            riskPriority = 1,
            riskStage = 3,
        )

        assertEquals(AdvicePlanState.PREPARED, escalated.plan.state)
        assertEquals(AdviceNotificationState.NOT_SENT, escalated.plan.notificationState)
        assertEquals(3, escalated.plan.riskStage)
        assertEquals(100.0, escalated.plan.executedQuantity, 0.0)
        assertTrue(DailyAdvicePolicy.canNotify(escalated.plan))

        // 同档位不重发；30 分钟链路传 riskStage=0 也不能把档位降回去。
        val sameStage = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = escalated.plan.copy(
                state = AdvicePlanState.SENT,
                notificationState = AdviceNotificationState.SENT,
            ),
            riskPriority = 1,
            riskStage = 3,
        )
        assertEquals(AdvicePlanState.SENT, sameStage.plan.state)
        val fromSlowPath = DailyAdvicePolicy.prepare(
            date = "2026-11-16",
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 400.0,
            currentQuantity = 1_200.0,
            existing = sameStage.plan,
            riskPriority = 1,
            riskStage = 0,
        )
        assertEquals(3, fromSlowPath.plan.riskStage)
    }

    @Test
    fun onlyTodaysPlansAreActive() {
        val today = "2026-11-20"
        val current = DailyAdvicePolicy.prepare(
            date = today,
            code = "sz000001",
            direction = AdviceDirection.SELL,
            kind = AdviceKind.REDUCE,
            targetQuantity = 200.0,
            currentQuantity = 1_000.0,
            existing = null,
        ).plan
        val previousDay = current.copy(date = "2026-11-19")

        assertEquals(listOf(current), DailyAdvicePolicy.activePlans(listOf(previousDay, current), today))
    }
}
