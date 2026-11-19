package com.example.stockmonitor.analysis

enum class AdviceDirection {
    BUY,
    SELL,
}

enum class AdviceKind {
    HOLDING_ADD,
    NORMAL_BUY,
    REDUCE,
    EXIT,
    T_BUY,
    T_SELL,
}

enum class AdvicePlanState {
    PREPARED,
    RESERVED,
    SENT,
    PARTIALLY_EXECUTED,
    COMPLETED,
    REVIEW_REQUIRED,
    BLOCKED,
    DELIVERY_UNKNOWN,
}

enum class AdviceNotificationState {
    NOT_SENT,
    RESERVED,
    SENT,
    FAILED,
    UNKNOWN,
}

data class DailyAdvicePlan(
    val date: String,
    val code: String,
    val direction: AdviceDirection,
    val kind: AdviceKind,
    val targetQuantity: Double,
    val executedQuantity: Double,
    val lastKnownQuantity: Double,
    val state: AdvicePlanState = AdvicePlanState.PREPARED,
    val notificationState: AdviceNotificationState = AdviceNotificationState.NOT_SENT,
    val reviewReason: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val riskPriority: Int = 0,
    val continuityReason: String? = null,
)

data class AdvicePreparation(
    val plan: DailyAdvicePlan,
    val reused: Boolean,
)

data class AdviceReservation(
    val accepted: Boolean,
    val plan: DailyAdvicePlan,
    val reason: String? = null,
)

object DailyAdvicePolicy {
    private const val EPSILON = 0.000001

    private val pendingBuyStates = setOf(
        AdvicePlanState.PREPARED,
        AdvicePlanState.RESERVED,
        AdvicePlanState.SENT,
        AdvicePlanState.PARTIALLY_EXECUTED,
        AdvicePlanState.DELIVERY_UNKNOWN,
        AdvicePlanState.REVIEW_REQUIRED,
    )

    fun prepare(
        date: String,
        code: String,
        direction: AdviceDirection,
        kind: AdviceKind,
        targetQuantity: Double,
        currentQuantity: Double,
        existing: DailyAdvicePlan?,
        riskPriority: Int = 0,
    ): AdvicePreparation {
        require(date.isNotBlank())
        require(code.isNotBlank())
        require(targetQuantity.isFinite() && targetQuantity > 0.0)
        require(currentQuantity.isFinite() && currentQuantity >= 0.0)
        require(riskPriority in 0..2)
        require(riskPriority == 0 || direction == AdviceDirection.SELL && kind in setOf(AdviceKind.REDUCE, AdviceKind.EXIT))

        val sameDayStock = existing != null && existing.date == date && existing.code == code
        if (sameDayStock && existing!!.reviewReason?.contains(com.example.stockmonitor.core.RiskExitReview.REASON) == true) {
            return AdvicePreparation(existing, reused = true)
        }
        if (riskPriority == 2) {
            val candidate = if (sameDayStock) existing!! else DailyAdvicePlan(
                date, code, direction, kind, targetQuantity, 0.0, currentQuantity, riskPriority = riskPriority,
            )
            return AdvicePreparation(com.example.stockmonitor.core.RiskExitReview.pause(candidate, candidate.updatedAtMs), reused = sameDayStock)
        }
        if (sameDayStock && existing!!.riskPriority > riskPriority) {
            return AdvicePreparation(existing, reused = true)
        }
        if (sameDayStock && riskPriority > existing!!.riskPriority) {
            return AdvicePreparation(
                plan = DailyAdvicePlan(
                    date, code, direction, kind, targetQuantity, 0.0, currentQuantity,
                    riskPriority = riskPriority,
                    continuityReason = "风险升级：替代原${existing.kind.label()}计划${existing.targetQuantity.prettyQuantity()}股" +
                        "（数量反馈${existing.executedQuantity.prettyQuantity()}股，未执行部分停止沿用）；" +
                        "本次按当前可卖仓位重新限量，不与旧计划叠加；风控处置不受底仓或做T盈利门槛阻挡。",
                ),
                reused = false,
            )
        }
        if (sameDayStock && kotlin.math.abs(existing!!.lastKnownQuantity - currentQuantity) > EPSILON) {
            return AdvicePreparation(
                existing.copy(
                    state = AdvicePlanState.REVIEW_REQUIRED,
                    notificationState = AdviceNotificationState.UNKNOWN,
                    reviewReason = "持仓变化尚未形成连续数量反馈，暂停原计划并核对成交；不推算成交价或接回额度。",
                    lastKnownQuantity = currentQuantity,
                ),
                reused = true,
            )
        }
        val canReuse = sameDayStock && existing!!.direction == direction && existing.kind == kind
        if (canReuse) {
            return AdvicePreparation(
                plan = existing!!.copy(lastKnownQuantity = currentQuantity),
                reused = true,
            )
        }
        if (sameDayStock) {
            return AdvicePreparation(
                plan = existing!!.copy(
                    state = AdvicePlanState.REVIEW_REQUIRED,
                    notificationState = AdviceNotificationState.UNKNOWN,
                    reviewReason = "同日出现方向或建议类型变化，暂停原计划并转人工复核，不执行新的反向操作。",
                    lastKnownQuantity = currentQuantity,
                ),
                reused = true,
            )
        }
        return AdvicePreparation(
            plan = DailyAdvicePlan(
                date = date,
                code = code,
                direction = direction,
                kind = kind,
                targetQuantity = targetQuantity,
                executedQuantity = 0.0,
                lastKnownQuantity = currentQuantity,
                riskPriority = riskPriority,
            ),
            reused = false,
        )
    }

    fun applyQuantityFeedback(
        plan: DailyAdvicePlan,
        previousQuantity: Double,
        currentQuantity: Double,
        feedbackAtMs: Long,
    ): DailyAdvicePlan {
        require(previousQuantity.isFinite() && previousQuantity >= 0.0)
        require(currentQuantity.isFinite() && currentQuantity >= 0.0)

        if (plan.state == AdvicePlanState.COMPLETED) {
            return plan.copy(lastKnownQuantity = currentQuantity, updatedAtMs = feedbackAtMs)
        }
        if (plan.state == AdvicePlanState.REVIEW_REQUIRED) {
            return plan.copy(lastKnownQuantity = currentQuantity, updatedAtMs = feedbackAtMs)
        }
        if (kotlin.math.abs(previousQuantity - plan.lastKnownQuantity) > EPSILON) {
            return plan.copy(
                lastKnownQuantity = currentQuantity,
                state = AdvicePlanState.REVIEW_REQUIRED,
                notificationState = AdviceNotificationState.UNKNOWN,
                reviewReason = "数量反馈不连续或重复，暂停原计划并核对成交。",
                updatedAtMs = feedbackAtMs,
            )
        }

        val signedDelta = currentQuantity - previousQuantity
        val expectedDelta = if (plan.direction == AdviceDirection.BUY) signedDelta else -signedDelta
        if (expectedDelta < -EPSILON) {
            return plan.copy(
                lastKnownQuantity = currentQuantity,
                state = AdvicePlanState.REVIEW_REQUIRED,
                notificationState = AdviceNotificationState.UNKNOWN,
                reviewReason = "持仓数量沿建议反方向变化，需人工复核中间交易。",
                updatedAtMs = feedbackAtMs,
            )
        }
        if (expectedDelta <= EPSILON) {
            return plan.copy(lastKnownQuantity = currentQuantity, updatedAtMs = feedbackAtMs)
        }

        val executed = (plan.executedQuantity + expectedDelta).coerceAtMost(plan.targetQuantity)
        val completed = executed + EPSILON >= plan.targetQuantity
        return plan.copy(
            executedQuantity = executed,
            lastKnownQuantity = currentQuantity,
            state = if (completed) AdvicePlanState.COMPLETED else AdvicePlanState.PARTIALLY_EXECUTED,
            notificationState = if (completed) {
                AdviceNotificationState.SENT
            } else {
                plan.notificationState
            },
            reviewReason = null,
            updatedAtMs = feedbackAtMs,
        )
    }

    fun canReserveBuy(
        plans: List<DailyAdvicePlan>,
        date: String,
        code: String,
    ): Boolean = plans.none { plan ->
        plan.date == date &&
            plan.code != code &&
            plan.direction == AdviceDirection.BUY &&
            plan.state in pendingBuyStates
    }

    fun reserve(
        plan: DailyAdvicePlan,
        plans: List<DailyAdvicePlan>,
        date: String,
        code: String,
    ): AdviceReservation {
        if (plan.date != date || plan.code != code) {
            return AdviceReservation(false, plan, "建议日期或股票代码已变化，不能保留原计划。")
        }
        if (!canNotify(plan)) {
            return AdviceReservation(false, plan, "原计划已通知、已预留、已完成或待复核，不重复生成操作指令。")
        }
        if (plan.direction == AdviceDirection.BUY && !canReserveBuy(plans, date, code)) {
            return AdviceReservation(false, plan.copy(state = AdvicePlanState.BLOCKED), "同日已有其它股票的买入计划未完成。")
        }
        return AdviceReservation(
            accepted = true,
            plan = plan.copy(
                state = AdvicePlanState.RESERVED,
                notificationState = AdviceNotificationState.RESERVED,
                reviewReason = null,
            ),
        )
    }

    fun markSent(plan: DailyAdvicePlan, sentAtMs: Long): DailyAdvicePlan = plan.copy(
        state = if (plan.state in setOf(AdvicePlanState.COMPLETED, AdvicePlanState.REVIEW_REQUIRED)) {
            plan.state
        } else if (plan.executedQuantity > EPSILON && plan.executedQuantity < plan.targetQuantity) {
            AdvicePlanState.PARTIALLY_EXECUTED
        } else {
            AdvicePlanState.SENT
        },
        notificationState = AdviceNotificationState.SENT,
        updatedAtMs = sentAtMs,
    )

    fun rollback(plan: DailyAdvicePlan, failedAtMs: Long, reason: String? = null): DailyAdvicePlan = plan.copy(
        state = AdvicePlanState.PREPARED,
        notificationState = AdviceNotificationState.FAILED,
        reviewReason = reason,
        updatedAtMs = failedAtMs,
    )

    fun markDeliveryUnknown(
        plan: DailyAdvicePlan,
        atMs: Long,
        reason: String,
    ): DailyAdvicePlan = plan.copy(
        state = AdvicePlanState.DELIVERY_UNKNOWN,
        notificationState = AdviceNotificationState.UNKNOWN,
        reviewReason = reason,
        updatedAtMs = atMs,
    )

    fun canNotify(plan: DailyAdvicePlan): Boolean = plan.riskPriority < 2 && remainingQuantity(plan) >= 100.0 && plan.state in setOf(
        AdvicePlanState.PREPARED,
        AdvicePlanState.PARTIALLY_EXECUTED,
    ) && plan.notificationState in setOf(AdviceNotificationState.NOT_SENT, AdviceNotificationState.FAILED)

    fun remainingQuantity(plan: DailyAdvicePlan): Double =
        (plan.targetQuantity - plan.executedQuantity).coerceAtLeast(0.0)

    fun publicationQuantity(plan: DailyAdvicePlan, currentLimit: Double): Double =
        if (!currentLimit.isFinite() || currentLimit <= 0.0) 0.0
        else kotlin.math.floor(minOf(remainingQuantity(plan), currentLimit) / 100.0) * 100.0

    fun continuityText(plan: DailyAdvicePlan, quantity: Double): String =
        listOfNotNull(plan.continuityReason, "同日计划：目标${plan.targetQuantity.prettyQuantity()}股，" +
            "数量反馈${plan.executedQuantity.prettyQuantity()}股，本次最多${quantity.prettyQuantity()}股（包含在原计划内，不是追加）；" +
            "提醒不代表成交，操作前须复核最新条件。").joinToString("｜")

    fun displayText(plan: DailyAdvicePlan): String {
        val remaining = (plan.targetQuantity - plan.executedQuantity).coerceAtLeast(0.0)
        return when (plan.state) {
            AdvicePlanState.PARTIALLY_EXECUTED ->
                "原计划${plan.targetQuantity.prettyQuantity()}，已反馈${plan.executedQuantity.prettyQuantity()}，剩余${remaining.prettyQuantity()}"
            AdvicePlanState.COMPLETED -> "原计划${plan.targetQuantity.prettyQuantity()}已完成"
            AdvicePlanState.REVIEW_REQUIRED -> "原计划待复核：${plan.reviewReason ?: "持仓变化不连续"}"
            AdvicePlanState.DELIVERY_UNKNOWN -> "通知送达未知，暂缓新的操作指令"
            AdvicePlanState.BLOCKED -> "同日买入计划被阻断：${plan.reviewReason ?: "存在未完成买入计划"}"
            else -> "原计划${plan.targetQuantity.prettyQuantity()}，待反馈执行数量"
        }
    }

    private fun Double.prettyQuantity(): String =
        if (this % 1.0 == 0.0) toInt().toString() else String.format("%.2f", this)

    private fun AdviceKind.label(): String = when (this) {
        AdviceKind.HOLDING_ADD -> "补仓"
        AdviceKind.NORMAL_BUY -> "买入"
        AdviceKind.REDUCE -> "减仓"
        AdviceKind.EXIT -> "退出"
        AdviceKind.T_BUY -> "做T接回"
        AdviceKind.T_SELL -> "做T卖出"
    }
}
