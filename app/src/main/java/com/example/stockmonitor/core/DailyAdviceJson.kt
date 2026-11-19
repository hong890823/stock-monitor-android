package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.AdviceDirection
import com.example.stockmonitor.analysis.AdviceKind
import com.example.stockmonitor.analysis.AdviceNotificationState
import com.example.stockmonitor.analysis.AdvicePlanState
import com.example.stockmonitor.analysis.DailyAdvicePlan
import org.json.JSONArray
import org.json.JSONObject

object DailyAdviceJson {
    fun encode(plans: List<DailyAdvicePlan>): String = JSONArray().also { array ->
        plans.forEach { plan ->
            array.put(
                JSONObject()
                    .put("date", plan.date)
                    .put("code", plan.code)
                    .put("direction", plan.direction.name)
                    .put("kind", plan.kind.name)
                    .put("target_quantity", plan.targetQuantity)
                    .put("executed_quantity", plan.executedQuantity)
                    .put("last_known_quantity", plan.lastKnownQuantity)
                    .put("state", plan.state.name)
                    .put("notification_state", plan.notificationState.name)
                    .put("review_reason", plan.reviewReason)
                    .put("risk_priority", plan.riskPriority)
                    .put("continuity_reason", plan.continuityReason)
                    .put("created_at_ms", plan.createdAtMs)
                    .put("updated_at_ms", plan.updatedAtMs),
            )
        }
    }.toString()

    fun decode(raw: String?): List<DailyAdvicePlan> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            decodePlan(item)
        }
    }

    private fun decodePlan(item: JSONObject): DailyAdvicePlan? = runCatching {
        val date = item.optString("date")
        val code = item.optString("code")
        val direction = AdviceDirection.valueOf(item.optString("direction"))
        val kind = AdviceKind.valueOf(item.optString("kind"))
        val state = AdvicePlanState.valueOf(item.optString("state"))
        val notificationState = AdviceNotificationState.valueOf(item.optString("notification_state"))
        val targetQuantity = item.optDouble("target_quantity", Double.NaN)
        val executedQuantity = item.optDouble("executed_quantity", Double.NaN)
        val lastKnownQuantity = item.optDouble("last_known_quantity", Double.NaN)
        require(date.isNotBlank() && code.isNotBlank())
        require(targetQuantity.isFinite() && targetQuantity > 0.0)
        require(executedQuantity.isFinite() && executedQuantity >= 0.0)
        require(lastKnownQuantity.isFinite() && lastKnownQuantity >= 0.0)
        DailyAdvicePlan(
            date = date,
            code = code,
            direction = direction,
            kind = kind,
            targetQuantity = targetQuantity,
            executedQuantity = executedQuantity,
            lastKnownQuantity = lastKnownQuantity,
            state = state,
            notificationState = notificationState,
            reviewReason = item.optString("review_reason").takeIf { it.isNotBlank() },
            createdAtMs = item.optLong("created_at_ms", 0L),
            updatedAtMs = item.optLong("updated_at_ms", 0L),
            riskPriority = item.optInt("risk_priority", 0).coerceIn(0, 2),
            continuityReason = item.optString("continuity_reason").takeIf { it.isNotBlank() },
        )
    }.getOrNull()
}
