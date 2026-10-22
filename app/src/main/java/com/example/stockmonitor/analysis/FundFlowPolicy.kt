package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import kotlin.math.abs

enum class FundFlowTone {
    SUPPORTIVE,
    DIVERGENT,
    RISK,
    ABSORPTION_WATCH,
    NEUTRAL,
    UNAVAILABLE,
}

data class FundFlowAssessment(
    val tone: FundFlowTone,
    val strong: Boolean,
    val lowConfidence: Boolean,
    val reason: String,
)

object FundFlowPolicy {
    const val MIN_CHANGE_PCT = 0.5
    const val MIN_MAIN_NET_PCT = 3.0
    const val STRONG_MAIN_NET_PCT = 10.0
    const val MIN_RELIABLE_AMOUNT_YUAN = 5_000_000.0

    fun assess(changePct: Double?, flow: FundFlow?): FundFlowAssessment {
        val netPct = flow?.mainNetPct
        if (changePct == null || netPct == null ||
            !changePct.isFinite() || !netPct.isFinite()
        ) {
            return FundFlowAssessment(
                tone = FundFlowTone.UNAVAILABLE,
                strong = false,
                lowConfidence = false,
                reason = "资金流数据缺失，仅按价格与量价条件判断；资金流按订单规模归类，不代表机构身份。",
            )
        }

        val amount = flow.amountYuan
        val lowConfidence = amount == null || !amount.isFinite() ||
            amount < MIN_RELIABLE_AMOUNT_YUAN
        val strong = abs(netPct) >= STRONG_MAIN_NET_PCT
        val tone = when {
            changePct > MIN_CHANGE_PCT && netPct >= MIN_MAIN_NET_PCT -> FundFlowTone.SUPPORTIVE
            changePct > MIN_CHANGE_PCT && netPct <= -MIN_MAIN_NET_PCT -> FundFlowTone.DIVERGENT
            changePct < -MIN_CHANGE_PCT && netPct <= -MIN_MAIN_NET_PCT -> FundFlowTone.RISK
            changePct < -MIN_CHANGE_PCT && netPct >= MIN_MAIN_NET_PCT -> FundFlowTone.ABSORPTION_WATCH
            else -> FundFlowTone.NEUTRAL
        }
        val reason = when (tone) {
            FundFlowTone.SUPPORTIVE ->
                "价格上涨且大单/超大单主动买卖净流向为正，方向一致，仅作辅助确认。"
            FundFlowTone.DIVERGENT ->
                "价格上涨但大单/超大单主动买卖净流向为负，存在量价资金背离，暂缓普通买入确认。"
            FundFlowTone.RISK ->
                "价格下跌且大单/超大单主动买卖净流向为负，风险方向一致，增强谨慎提示。"
            FundFlowTone.ABSORPTION_WATCH ->
                "价格下跌但大单/超大单主动买卖净流向为正，仅作潜在承接观察，等待价格确认。"
            FundFlowTone.NEUTRAL ->
                "价格与资金净流向未形成明显方向组合，保持中性。"
            FundFlowTone.UNAVAILABLE ->
                "资金流数据缺失，仅按价格与量价条件判断。"
        }
        return FundFlowAssessment(
            tone = tone,
            strong = strong,
            lowConfidence = lowConfidence,
            reason = "$reason 资金流按订单规模归类，不代表机构身份。" +
                if (lowConfidence) " 当前成交额样本偏小。" else "",
        )
    }

    fun scoreDelta(assessment: FundFlowAssessment): Int {
        if (assessment.lowConfidence) return 0
        return when (assessment.tone) {
            FundFlowTone.SUPPORTIVE -> 1
            FundFlowTone.DIVERGENT, FundFlowTone.RISK -> -1
            else -> 0
        }
    }
}
