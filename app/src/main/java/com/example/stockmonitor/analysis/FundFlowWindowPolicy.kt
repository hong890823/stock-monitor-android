package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow

enum class FundFlowWindowTone {
    PERSISTENT_INFLOW,
    PERSISTENT_OUTFLOW,
    MIXED,
    ACCUMULATING,
    UNAVAILABLE,
}

data class FundFlowWindowAssessment(
    val tone: FundFlowWindowTone,
    val sampleCount: Int,
    val recent3PositiveDays: Int,
    val recent5PositiveDays: Int,
    val recent3AvgMainNetPct: Double?,
    val recent5AvgMainNetPct: Double?,
    val strong: Boolean,
    val lowConfidence: Boolean,
    val reason: String,
)

object FundFlowWindowPolicy {
    const val SHORT_WINDOW_DAYS = 3
    const val LONG_WINDOW_DAYS = 5
    const val PERSISTENT_FLOW_PCT = 3.0
    const val STRONG_FLOW_PCT = 10.0

    fun assess(flows: List<FundFlow>): FundFlowWindowAssessment {
        val valid = flows
            .filter { it.mainNetPct != null && it.mainNetPct.isFinite() }
            .sortedBy { it.asOfMs }
            .takeLast(LONG_WINDOW_DAYS)
        if (valid.isEmpty()) {
            return FundFlowWindowAssessment(
                tone = FundFlowWindowTone.UNAVAILABLE,
                sampleCount = 0,
                recent3PositiveDays = 0,
                recent5PositiveDays = 0,
                recent3AvgMainNetPct = null,
                recent5AvgMainNetPct = null,
                strong = false,
                lowConfidence = true,
                reason = "多日资金流样本缺失，不参与候选池晋级；资金流按订单规模归类，不影响盘中提醒。",
            )
        }

        val recent3 = valid.takeLast(SHORT_WINDOW_DAYS)
        val recent3Average = recent3.mapNotNull { it.mainNetPct }.average()
        val recent5Average = valid.mapNotNull { it.mainNetPct }.average()
        val recent3PositiveDays = recent3.count { (it.mainNetPct ?: 0.0) > 0.0 }
        val recent5PositiveDays = valid.count { (it.mainNetPct ?: 0.0) > 0.0 }
        val lowConfidence = valid.size < SHORT_WINDOW_DAYS ||
            valid.any { it.amountYuan == null || !it.amountYuan.isFinite() || it.amountYuan < FundFlowPolicy.MIN_RELIABLE_AMOUNT_YUAN }
        val accumulating = valid.size < SHORT_WINDOW_DAYS
        val tone = when {
            accumulating -> FundFlowWindowTone.ACCUMULATING
            recent3Average >= PERSISTENT_FLOW_PCT && recent3PositiveDays >= 2 ->
                FundFlowWindowTone.PERSISTENT_INFLOW
            recent3Average <= -PERSISTENT_FLOW_PCT && recent3PositiveDays <= 1 ->
                FundFlowWindowTone.PERSISTENT_OUTFLOW
            else -> FundFlowWindowTone.MIXED
        }
        val strong = abs(recent3Average) >= STRONG_FLOW_PCT
        val direction = String.format("%.2f%%", recent3Average)
        val reason = when (tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW ->
                "近3日主力净流向（按订单规模归类）连续偏正，平均$direction；仅影响候选池晋级，不影响盘中提醒。"
            FundFlowWindowTone.PERSISTENT_OUTFLOW ->
                "近3日主力净流向（按订单规模归类）连续偏负，平均$direction；仅影响候选池晋级，暂缓晋级，不影响盘中提醒。"
            FundFlowWindowTone.MIXED ->
                "近3日主力净流向（按订单规模归类）方向不一致，平均$direction；保持中性，不影响盘中提醒。"
            FundFlowWindowTone.ACCUMULATING ->
                "多日资金流样本积累中（${valid.size}/$SHORT_WINDOW_DAYS），暂不参与候选池晋级；不影响盘中提醒。"
            FundFlowWindowTone.UNAVAILABLE -> ""
        }
        return FundFlowWindowAssessment(
            tone = tone,
            sampleCount = valid.size,
            recent3PositiveDays = recent3PositiveDays,
            recent5PositiveDays = recent5PositiveDays,
            recent3AvgMainNetPct = recent3Average,
            recent5AvgMainNetPct = recent5Average,
            strong = strong,
            lowConfidence = lowConfidence,
            reason = reason + if (lowConfidence && tone != FundFlowWindowTone.ACCUMULATING) {
                " 当前成交额样本偏小。"
            } else {
                ""
            },
        )
    }

    fun scoreDelta(assessment: FundFlowWindowAssessment): Int {
        if (assessment.lowConfidence || assessment.tone == FundFlowWindowTone.ACCUMULATING) return 0
        return when (assessment.tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW -> 1
            FundFlowWindowTone.PERSISTENT_OUTFLOW -> -1
            else -> 0
        }
    }

    private fun abs(value: Double): Double = if (value < 0.0) -value else value
}
