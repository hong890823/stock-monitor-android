package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate

object ObservationBreakoutRiskAlert {
    fun shouldNotify(previous: ObservationCandidate, current: ObservationCandidate): Boolean =
        previous.breakoutRisk?.risk != true && current.breakoutRisk?.risk == true

    fun title(candidate: ObservationCandidate): String =
        "⚠️ 突破延续下杀风险｜${candidate.name}"

    fun body(candidate: ObservationCandidate): String {
        val reason = candidate.breakoutRisk?.reason ?: "突破延续后出现下杀风险"
        return "${candidate.code}｜${candidate.strategy.label}｜现价${String.format(java.util.Locale.CHINA, "%.2f", candidate.lastPrice)}｜$reason｜停止追买，持仓优先风险处置"
    }
}
