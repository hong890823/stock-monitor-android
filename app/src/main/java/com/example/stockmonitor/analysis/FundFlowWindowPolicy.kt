package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import java.util.Locale

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
    val summary: String,
    val reason: String,
)

object FundFlowWindowPolicy {
    const val SHORT_WINDOW_DAYS = 3
    const val LONG_WINDOW_DAYS = 5
    const val PERSISTENT_FLOW_PCT = 3.0
    const val STRONG_FLOW_PCT = 10.0

    fun assess(flows: List<FundFlow>): FundFlowWindowAssessment {
        val valid = flows
            .filter { it.mainNetPct?.isFinite() == true }
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
                summary = "多日资金流暂无有效样本",
                reason = "缺少有效主力资金流样本，本项不参与评分，也不会触发买卖提醒。",
            )
        }

        val recent3 = valid.takeLast(SHORT_WINDOW_DAYS)
        val recent3Values = recent3.mapNotNull { it.mainNetPct }
        val recent5Values = valid.mapNotNull { it.mainNetPct }
        val recent3Average = recent3Values.average()
        val recent5Average = recent5Values.average()
        val recent3PositiveDays = recent3Values.count { it > 0.0 }
        val recent5PositiveDays = recent5Values.count { it > 0.0 }
        val recent3NegativeDays = recent3Values.size - recent3PositiveDays
        val lowConfidence = valid.size < SHORT_WINDOW_DAYS ||
            valid.any { flow ->
                val amount = flow.amountYuan
                amount == null || !amount.isFinite() || amount < FundFlowPolicy.MIN_RELIABLE_AMOUNT_YUAN
            }
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
        val averageText = formatPct(recent3Average)
        val summary = when (tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW ->
                "近3个交易日${recent3PositiveDays}/${recent3Values.size}天净流入｜平均主力净占比 $averageText"
            FundFlowWindowTone.PERSISTENT_OUTFLOW ->
                "近3个交易日${recent3NegativeDays}/${recent3Values.size}天净流出｜平均主力净占比 $averageText"
            FundFlowWindowTone.MIXED ->
                "近3个交易日${recent3PositiveDays}天净流入、${recent3NegativeDays}天净流出｜方向不一致｜平均主力净占比 $averageText"
            FundFlowWindowTone.ACCUMULATING ->
                "多日资金流仅${valid.size}/${SHORT_WINDOW_DAYS}个交易日样本｜暂不判断方向"
            FundFlowWindowTone.UNAVAILABLE -> "多日资金流暂无有效样本"
        }
        val directionDetail =
            "近3个交易日${recent3PositiveDays}天净流入、${recent3NegativeDays}天净流出，" +
                "平均主力净占比 $averageText；近5日${recent5PositiveDays}天净流入。"
        val reason = when (tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW ->
                "${directionDetail}结论：持续流入，可作为加分参考，不单独触发买卖提醒。"
            FundFlowWindowTone.PERSISTENT_OUTFLOW ->
                "${directionDetail}结论：持续流出，暂缓晋级，不单独触发买卖提醒。"
            FundFlowWindowTone.MIXED ->
                "${directionDetail}结论：方向不一致，保持中性，不单独触发买卖提醒。"
            FundFlowWindowTone.ACCUMULATING ->
                "已有${valid.size}/${SHORT_WINDOW_DAYS}个交易日样本，暂不判断持续流入或流出方向；" +
                    "样本齐全后参与评分，不触发买卖提醒。"
            FundFlowWindowTone.UNAVAILABLE -> ""
        }
        val confidenceText = if (lowConfidence && tone != FundFlowWindowTone.ACCUMULATING) {
            " 当前成交额样本偏小，结论可信度较低。"
        } else {
            ""
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
            summary = summary,
            reason = reason + confidenceText,
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

    private fun formatPct(value: Double): String =
        String.format(Locale.CHINA, "%+.2f%%", value)

    private fun abs(value: Double): Double = if (value < 0.0) -value else value
}
