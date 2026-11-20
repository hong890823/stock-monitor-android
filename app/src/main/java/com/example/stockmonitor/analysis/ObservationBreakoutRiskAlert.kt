package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate

object ObservationBreakoutRiskAlert {
    fun shouldNotify(previous: ObservationCandidate, current: ObservationCandidate): Boolean =
        previous.breakoutRisk?.risk != true && current.breakoutRisk?.risk == true

    fun title(candidate: ObservationCandidate, name: String = candidate.name): String =
        "⚠️ 突破延续下杀风险｜${displayName(candidate, name)}"

    fun body(candidate: ObservationCandidate, name: String = candidate.name): String {
        val reason = candidate.breakoutRisk?.reason ?: "突破延续后出现下杀风险"
        return "${displayName(candidate, name)}｜${candidate.strategy.label}｜现价${String.format(java.util.Locale.CHINA, "%.2f", candidate.lastPrice)}｜$reason｜停止追买，持仓优先风险处置"
    }

    /** 通知正文优先用股票名称；候选名缺失或被写成代码时才退回代码。 */
    private fun displayName(candidate: ObservationCandidate, name: String): String =
        name.takeIf { it.isNotBlank() && it != candidate.code }
            ?: candidate.name.takeIf { it.isNotBlank() && it != candidate.code }
            ?: candidate.code
}
