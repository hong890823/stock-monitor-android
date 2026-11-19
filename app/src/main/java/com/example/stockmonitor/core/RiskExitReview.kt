package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.AdvicePlanState
import com.example.stockmonitor.analysis.DailyAdvicePlan

object RiskExitReview {
    const val REASON = "重大风险待复核：缺少独立、可核验的清仓授权，原计划未执行部分暂停；不自动清仓或替换为减仓指令。"

    fun action(evidence: TimeframeEvidence?, tradeDate: String?, ledgerConsistent: Boolean): GuardedTradeAction =
        GuardedTradeAction(
            level = GuardedActionLevel.RISK_FACT,
            action = GuardedAction.HOLD,
            text = "$REASON 技术破位不等于持有逻辑失效，不因深套隐藏风险，也不凭风险标签认定公司价值失效。" +
                "｜行情日${tradeDate ?: "缺失"}｜账本${if (ledgerConsistent) "数量一致" else "不一致，须核对"}" +
                "｜风险标签${evidence?.riskLevel ?: "缺失"}｜15分钟破位计数${evidence?.riskConfirmations ?: 0}" +
                "｜最近完成15分钟K线${evidence?.lastCompleted15m ?: "缺失"}" +
                "｜${evidence?.riskAuditText?.takeIf { it.isNotBlank() } ?: "具体风险来源、价位与K线收盘依据缺失，不能授权清仓"}",
            requiresExitReview = true,
        )

    fun evidenceKey(date: String, evidence: TimeframeEvidence?): String = listOf(
        date, evidence?.riskLevel, evidence?.riskComplete, evidence?.riskConfirmed,
        evidence?.riskConfirmations, evidence?.riskReasonKey,
    ).joinToString("|")

    fun evidenceSummary(evidence: TimeframeEvidence?): String =
        "风险标签${evidence?.riskLevel ?: "缺失"}、15分钟破位计数${evidence?.riskConfirmations ?: 0}、" +
            "15分钟证据有效${evidence?.riskComplete ?: false}；" +
            (evidence?.riskReasonKey?.takeIf { it.isNotBlank() } ?: "具体风险来源缺失")

    fun pause(plan: DailyAdvicePlan, atMs: Long): DailyAdvicePlan {
        if (plan.state == AdvicePlanState.COMPLETED || plan.reviewReason?.contains(REASON) == true) return plan
        return plan.copy(
            state = AdvicePlanState.REVIEW_REQUIRED,
            reviewReason = listOfNotNull(plan.reviewReason, REASON).joinToString("；"),
            updatedAtMs = atMs,
        )
    }

    fun continuity(plan: DailyAdvicePlan?): String = if (plan == null) {
        "无同日操作计划；本次不创建卖出计划，提醒不代表成交。"
    } else {
        "原计划方向${plan.direction}、类型${plan.kind}、历史目标${plan.targetQuantity.toLong()}股、" +
            "数量反馈${plan.executedQuantity.toLong()}股；" +
            (if (plan.state == AdvicePlanState.COMPLETED) "已完成记录保留，不生成新指令。" else "未执行部分暂停，不沿用旧指令。") +
            "提醒不代表成交，不推算成交价或接回额度。"
    }
}

class ExitReviewDedup {
    private var currentDate: String? = null
    private val sent = mutableSetOf<Pair<String, String>>()

    fun shouldEmit(date: String, code: String, key: String): Boolean {
        if (date != currentDate) {
            sent.clear()
            currentDate = date
        }
        return code to key !in sent
    }

    fun markSent(date: String, code: String, key: String) {
        shouldEmit(date, code, key)
        sent.add(code to key)
    }
}
